package com.example.radioptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "BOOT_RECEIVER_ONRECEIVE action=$action")
        if (action !in BOOT_ACTIONS) return

        Log.i(TAG, "BOOT_RECEIVED")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "BOOT_T199_SERVICE_START")
            context.startService(Intent(context, RadioForegroundService::class.java))
        } else {
            RadioServiceStarter.startFromBoot(context)
        }

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "BOOT_T199_ACTIVITY_START")
        }
        Log.i(TAG, "MAIN_ACTIVITY_START_REQUESTED")
        context.startActivity(activityIntent)
    }

    companion object {
        private const val TAG = "RadioBoot"
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
