package com.bigmoe.onedge.core

/** A LoadConfig with the original app's defaults; tests override what they care about via copy(). */
fun testLoadConfig(): LoadConfig = com.bigmoe.onedge.data.local.datastore.EngineSettings().toLoadConfig()
