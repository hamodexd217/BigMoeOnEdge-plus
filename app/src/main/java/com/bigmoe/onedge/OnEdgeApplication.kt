package com.bigmoe.onedge

import android.app.Application
import com.bigmoe.onedge.di.AppContainer
import com.bigmoe.onedge.core.HexagonRuntimeSetup

class OnEdgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Configure FastRPC search paths before EngineNativeBridge loads the native library.
        // The ordinary CPU build contains no DSP skeleton assets; setup then safely no-ops.
        HexagonRuntimeSetup.prepare(this)
        container = AppContainer(this)
        container.autoLoadLastModelIfEnabled()
        container.startApiServerIfEnabled()
    }
}
