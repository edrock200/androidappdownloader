package io.github.edrock200.appdownloader.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.edrock200.appdownloader.app

/** Keeps daily/weekly scheduled updates at their local time after a time-zone or clock change. */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> context.app().refreshSchedule(keepExisting = false)
        }
    }
}
