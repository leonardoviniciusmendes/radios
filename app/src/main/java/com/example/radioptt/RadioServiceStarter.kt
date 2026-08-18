package com.example.radioptt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object RadioServiceStarter {
    fun startFromActivity(context: Context) {
        Log.i(ACTIVITY_TAG, "ACTIVITY_SERVICE_START_REQUESTED")
        start(context)
    }

    fun startFromBoot(context: Context) {
        Log.i(BOOT_TAG, "SERVICE_START_REQUESTED")
        start(context)
    }

    private fun start(context: Context) {
        val serviceIntent = Intent(context, RadioForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private const val ACTIVITY_TAG = "RadioActivity"
    private const val BOOT_TAG = "RadioBoot"
}
