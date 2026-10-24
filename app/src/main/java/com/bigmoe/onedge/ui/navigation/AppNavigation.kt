package com.bigmoe.onedge.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.bigmoe.onedge.ui.chat.ChatScreen
import com.bigmoe.onedge.ui.chat.ChatViewModel
import com.bigmoe.onedge.ui.history.HistoryViewModel
import com.bigmoe.onedge.ui.files.FileViewModel
import com.bigmoe.onedge.ui.files.FileViewerScreen
import com.bigmoe.onedge.ui.files.WorkspaceScreen
import com.bigmoe.onedge.ui.home.HomeScreen
import com.bigmoe.onedge.ui.home.ChatHistoryPanel
import com.bigmoe.onedge.ui.models.ModelScreen
import com.bigmoe.onedge.ui.models.ModelViewModel
import com.bigmoe.onedge.ui.settings.AutoSettingsScreen
import com.bigmoe.onedge.ui.settings.AutoTuneViewModel
import com.bigmoe.onedge.ui.settings.SettingsScreen
import com.bigmoe.onedge.ui.settings.SettingsViewModel

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Models : Screen("models")
    data object Settings : Screen("settings")
    data object Workspace : Screen("workspace")
    data object AutoSettings : Screen("autosettings")
    data object File : Screen("file/{path}") {
        fun createRoute(path: String): String = "file/${Uri.encode(path)}"
    }
    data object Chat : Screen("chat?sessionId={sessionId}") {
        fun createRoute(sessionId: String? = null): String =
            if (sessionId != null) "chat?sessionId=$sessionId" else "chat"
    }
}

/**
 * Three top-level pages (Models, Chats = Home, Settings) behind a floating bar, and the Chat page on top
 * of them. The app always starts on Home; it never jumps straight into the last chat.
 */
@Composable
fun AppNavigation(
    chatViewModel: ChatViewModel,
    historyViewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel,
    modelViewModel: ModelViewModel,
    autoTuneViewModel: AutoTuneViewModel,
    fileViewModel: FileViewModel,
    initialSessionId: String? = null,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val backStack by navController.currentBackStackEntryAsState()
    var chatHistoryPaneVisible by rememberSaveable { mutableStateOf(true) }
    val route = backStack?.destination?.route
    val tab = when (route) {
        Screen.Home.route -> TopTab.CHATS
        Screen.Models.route -> TopTab.MODELS
        Screen.Settings.route -> TopTab.SETTINGS
        else -> null
    }

    fun goTo(target: TopTab) {
        val dest = when (target) {
            TopTab.CHATS -> Screen.Home.route
            TopTab.MODELS -> Screen.Models.route
            TopTab.SETTINGS -> Screen.Settings.route
        }
        if (dest == route) return
        navController.navigate(dest) {
            popUpTo(Screen.Home.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    fun openChat(sessionId: String?) {
        if (sessionId == null) chatViewModel.newChat() else chatViewModel.loadSession(sessionId)
        navController.navigate(Screen.Chat.createRoute(sessionId)) { launchSingleTop = true }
    }

    LaunchedEffect(initialSessionId) {
        if (!initialSessionId.isNullOrBlank()) openChat(initialSessionId)
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.fillMaxSize(),
            // Switching between Chats / Models / Settings / a chat is instant: no fade, no slide.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable(route = Screen.Home.route) {
                HomeScreen(viewModel = historyViewModel, onOpenChat = { id -> openChat(id) })
            }
            composable(route = Screen.Models.route) {
                ModelScreen(viewModel = modelViewModel)
            }
            composable(route = Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onOpenModels = { goTo(TopTab.MODELS) },
                    onOpenWorkspace = { navController.navigate(Screen.Workspace.route) },
                    onOpenAutoSettings = { navController.navigate(Screen.AutoSettings.route) { launchSingleTop = true } }
                )
            }
            composable(route = Screen.AutoSettings.route) {
                AutoSettingsScreen(
                    viewModel = autoTuneViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(route = Screen.Workspace.route) {
                WorkspaceScreen(
                    viewModel = fileViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFile = { path -> navController.navigate(Screen.File.createRoute(path)) }
                )
            }
            composable(
                route = Screen.File.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { entry ->
                val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
                FileViewerScreen(
                    viewModel = fileViewModel,
                    path = path,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Screen.Chat.route,
                arguments = listOf(
                    navArgument("sessionId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                val sessionIdArg = entry.arguments?.getString("sessionId")
                // Rotation / process recreation: re-open the route's original chat only when the VM has
                // no already-selected session. Wide-screen chat switching deliberately keeps the VM
                // session independent from this route argument.
                LaunchedEffect(sessionIdArg) {
                    if (sessionIdArg != null && chatViewModel.uiState.value.currentSession == null) {
                        chatViewModel.loadSession(sessionIdArg)
                    }
                }
                val chatUiState by chatViewModel.uiState.collectAsState()
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 840.dp
                    if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            AnimatedVisibility(
                                visible = chatHistoryPaneVisible,
                                enter = slideInHorizontally(animationSpec = tween(240), initialOffsetX = { -it }) + expandHorizontally(animationSpec = tween(240), expandFrom = Alignment.Start) + fadeIn(animationSpec = tween(180)),
                                exit = slideOutHorizontally(animationSpec = tween(220), targetOffsetX = { -it }) + shrinkHorizontally(animationSpec = tween(220), shrinkTowards = Alignment.Start) + fadeOut(animationSpec = tween(160))
                            ) {
                                ChatHistoryPanel(
                                    viewModel = historyViewModel,
                                    selectedSessionId = chatUiState.currentSession?.id,
                                    onNewChat = { chatViewModel.newChat() },
                                    onOpenChat = { chatViewModel.loadSession(it) }
                                )
                            }
                            ChatScreen(
                                viewModel = chatViewModel,
                                historyViewModel = historyViewModel,
                                onNewChat = { openChat(null) },
                                onOpenChat = { id -> openChat(id) },
                                onNavigateHome = { goTo(TopTab.CHATS) },
                                onNavigateToModels = { goTo(TopTab.MODELS) },
                                onOpenFile = { path -> navController.navigate(Screen.File.createRoute(path)) },
                                modelViewModel = modelViewModel,
                                wideMode = true,
                                historyPaneVisible = chatHistoryPaneVisible,
                                onToggleHistoryPane = { chatHistoryPaneVisible = !chatHistoryPaneVisible },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        ChatScreen(
                            viewModel = chatViewModel,
                            historyViewModel = historyViewModel,
                            onNewChat = { openChat(null) },
                            onOpenChat = { id -> openChat(id) },
                            onNavigateHome = { goTo(TopTab.CHATS) },
                            onNavigateToModels = { goTo(TopTab.MODELS) },
                            onOpenFile = { path -> navController.navigate(Screen.File.createRoute(path)) },
                            modelViewModel = modelViewModel
                        )
                    }
                }
            }
        }

        if (tab != null) {
            FloatingNavBar(
                selected = tab,
                onSelect = { goTo(it) },
                onNewChat = { openChat(null) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
