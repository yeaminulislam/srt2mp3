package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopProcessingReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_PROCESSING = "com.example.action.STOP_PROCESSING"
        var onStopProcessing: (() -> Unit)? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_STOP_PROCESSING) {
            onStopProcessing?.invoke()
        }
    }
}
