package com.bigmoe.onedge.di

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import com.bigmoe.onedge.attachments.AttachmentManager
import com.bigmoe.onedge.core.AgentController
import com.bigmoe.onedge.core.EngineController
import com.bigmoe.onedge.core.EngineNativeBridge
import com.bigmoe.onedge.core.ModelManager
import com.bigmoe.onedge.core.NativeEngineBackend
import com.bigmoe.onedge.data.local.AppDatabase
import com.bigmoe.onedge.data.local.datastore.EngineSettingsDataStore
import com.bigmoe.onedge.data.local.datastore.ChatComposerDataStore
import com.bigmoe.onedge.data.repository.ArtifactRepository
import com.bigmoe.onedge.data.repository.ChatRepository
import com.bigmoe.onedge.tools.AndroidDeviceInfo
import com.bigmoe.onedge.tools.ToolManager
import com.bigmoe.onedge.tools.WebViewJavaScriptRunner
import com.bigmoe.onedge.tools.registerCodeTools
import com.bigmoe.onedge.tools.registerFileTools
import com.bigmoe.onedge.tools.registerUtilityTools
import com.bigmoe.onedge.tools.registerWebPageTools
import com.bigmoe.onedge.tools.WebSearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.bigmoe.onedge.workspace.Workspace
import java.io.File

class AppContainer(private val context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True once when an old v1 database is detected before Room's intentional destructive upgrade. */
    private var roomUpgradeNoticePending: Boolean = detectRoomV1Upgrade()

    fun consumeRoomUpgradeNotice(): Boolean {
        if (!roomUpgradeNoticePending) return false
        roomUpgradeNoticePending = false
        return true
    }

    private fun detectRoomV1Upgrade(): Boolean {
        val dbFile = context.getDatabasePath("onedge_database.db")
        if (!dbFile.isFile) return false
        return runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { it.version == 1 }
        }.getOrDefault(false)
    }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "onedge_database.db")
            // v1 -> v2 only ADDED tables, but a hand-written migration could not be tested before release,
            // and a wrong one crashes at start-up. The v1 build was a development build, so its chats are
            // cleared once on upgrade. From v2 on, add real migrations (docs/STATUS.md).
            .fallbackToDestructiveMigration()
            .build()
    }

    val settingsDataStore: EngineSettingsDataStore by lazy {
        EngineSettingsDataStore(context.applicationContext)
    }

    val chatComposerDataStore: ChatComposerDataStore by lazy {
        ChatComposerDataStore(context.applicationContext)
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(
            sessionDao = database.chatSessionDao(),
            messageDao = database.chatMessageDao(),
            attachmentDao = database.attachmentDao()
        )
    }

    /** Loads libonedge-engine.so on first use (lazily, so a missing library cannot crash app start). */
    val engineController: EngineController by lazy {
        EngineController(
            backendProvider = { NativeEngineBackend(EngineNativeBridge()) },
            csvPathProvider = { newMetricsCsvPath() }
        )
    }

    val modelManager: ModelManager by lazy {
        ModelManager(File(context.applicationContext.filesDir, "models"))
    }

    /** The sandbox every file tool, attachment and artifact lives in: `<app files>/workspace`. */
    val workspace: Workspace by lazy { Workspace(File(context.applicationContext.filesDir, "workspace")) }

    val artifactRepository: ArtifactRepository by lazy { ArtifactRepository(database.artifactDao()) }

    val attachmentManager: AttachmentManager by lazy { AttachmentManager(workspace) }

    /**
     * To add a tool: implement com.bigmoe.onedge.tools.Tool and register it here. The prompt, the call parsing,
     * the approval dialog and the "running" label need no other change (pick its category and, if it should be
     * switchable, add its name to the matching *_TOOL_NAMES set).
     */
    val toolManager: ToolManager by lazy {
        ToolManager().apply {
            registerTool(WebSearchTool())
            registerWebPageTools(this)
            registerFileTools(this, workspace, artifactRepository)
            registerCodeTools(this, workspace, WebViewJavaScriptRunner(context.applicationContext))
            registerUtilityTools(this, AndroidDeviceInfo(context.applicationContext))
        }
    }

    val agentController: AgentController by lazy {
        AgentController(engineController = engineController, toolManager = toolManager)
    }

    /** `<external files>/metrics/session-<timestamp>.csv` (shared through FileProvider, see res/xml/file_paths.xml). */
    private fun newMetricsCsvPath(): String? {
        val base = context.applicationContext.getExternalFilesDir(null) ?: return null
        val dir = File(base, "metrics")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        return File(dir, "session-$stamp.csv").absolutePath
    }

    fun totalRamBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /** Called once from Application.onCreate: honours "load the last model on app start". */
    fun autoLoadLastModelIfEnabled() {
        appScope.launch {
            val s = settingsDataStore.settingsFlow.first()
            if (s.autoLoadLastModel && s.lastModelPath.isNotBlank() && File(s.lastModelPath).isFile) {
                val projector = s.lastMmprojPath.takeIf { it.isNotBlank() && File(it).isFile }
                engineController.loadModel(s.lastModelPath, s.toLoadConfig(), projector)
            }
        }
    }
}
