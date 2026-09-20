package com.meetdheeran.prism

import android.app.Application
import com.meetdheeran.prism.core.AppGraph

class PrismApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.get(this)
    }
}
