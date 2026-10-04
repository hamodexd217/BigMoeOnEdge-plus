package com.bigmoe.onedge

import android.app.Application
import com.bigmoe.onedge.di.AppContainer

class OnEdgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.autoLoadLastModelIfEnabled()
    }
}
