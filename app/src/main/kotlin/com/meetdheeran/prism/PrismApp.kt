package com.meetdheeran.prism

import android.app.Application
import com.meetdheeran.prism.core.AppGraph
import com.meetdheeran.prism.island.MediaWatcher
import com.meetdheeran.prism.shizuku.ShizukuBridge

class PrismApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.get(this)
        ShizukuBridge.init(this)
        MediaWatcher.start(this)
    }
}
