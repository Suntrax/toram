package com.blissless.toram

import android.app.Application

class ToramApplication : Application() {

    lateinit var engine: TorrentEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = TorrentEngine(this)
    }
}
