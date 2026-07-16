package com.aethena.agent.automation

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

class AethenaNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val packageName = sbn.packageName.orEmpty()
        val line = listOf(packageName, title, text)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
        if (line.isBlank()) return

        recent.add(0, line)
        while (recent.size > 60) recent.removeAt(recent.lastIndex)
    }

    companion object {
        private val recent = CopyOnWriteArrayList<String>()

        fun snapshot(): String = recent
            .take(40)
            .joinToString("\n")
            .ifBlank { "No captured notifications yet." }
    }
}
