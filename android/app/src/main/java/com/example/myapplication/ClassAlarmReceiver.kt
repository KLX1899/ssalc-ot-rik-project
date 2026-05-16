package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ClassAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Handle alarm notification here
        if (context != null && intent != null) {
            // Start or trigger the AutoJoinService or perform necessary actions
            val serviceIntent = Intent(context, AutoJoinService::class.java)
            context.startService(serviceIntent)
        }
    }
}
