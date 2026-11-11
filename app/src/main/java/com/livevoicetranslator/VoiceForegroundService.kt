
package com.livevoicetranslator

import android.app.Service
import android.content.Intent
import android.os.IBinder

class VoiceForegroundService : Service() {

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
