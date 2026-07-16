package com.aethena.agent.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.aethena.agent.brain.AgentAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ActionExecutor(private val context: Context) {
    suspend fun execute(action: AgentAction): String = withContext(Dispatchers.Main) {
        runCatching {
            when (action.type.lowercase()) {
                "open_app" -> openApp(action.arg)
                "open_uri" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(action.arg)))
                "back" -> service()?.goBack()?.result("Back")
                "home" -> service()?.goHome()?.result("Home")
                "recents" -> service()?.showRecents()?.result("Recents")
                "tap_text" -> service()?.tapText(action.arg)?.result("Tapped '${action.arg}'")
                "type_text" -> service()?.typeText(action.arg)?.result("Typed text")
                "scroll_down" -> service()?.scroll(true)?.result("Scrolled down")
                "scroll_up" -> service()?.scroll(false)?.result("Scrolled up")
                "read_screen" -> service()?.readScreen() ?: "Accessibility control is not enabled."
                "read_notifications" -> AethenaNotificationService.snapshot()
                "share_text" -> shareText(action.arg)
                "open_accessibility_settings" -> launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                "open_notification_settings" -> launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "open_overlay_settings" -> launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
                "start_orb" -> startOrb()
                "stop_orb" -> stopOrb()
                "termux" -> runTermux(action.arg)
                else -> "Unknown action: ${action.type}"
            }
        }.getOrElse { "Action failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun service(): AethenaAccessibilityService? = AethenaAccessibilityService.instance

    private fun openApp(name: String): String {
        if (name.isBlank()) return "Tell me which app to open."
        val pm = context.packageManager
        val target = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { info -> info to pm.getApplicationLabel(info).toString() }
            .sortedBy { it.second.length }
            .firstOrNull { (_, label) ->
                label.equals(name, ignoreCase = true) || label.contains(name, ignoreCase = true)
            }
            ?: return "I could not find an installed app named '$name'."

        val intent = pm.getLaunchIntentForPackage(target.first.packageName)
            ?: return "${target.second} does not expose a launch screen."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened ${target.second}."
    }

    private fun launch(intent: Intent): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened."
    }

    private fun shareText(text: String): String {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened the Android share sheet."
    }

    private fun startOrb(): String {
        if (!Settings.canDrawOverlays(context)) {
            launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            return "Allow display over other apps, then press Start Orb again."
        }
        ContextCompat.startForegroundService(context, Intent(context, AethenaOverlayService::class.java))
        return "Aethena orb started."
    }

    private fun stopOrb(): String {
        context.stopService(Intent(context, AethenaOverlayService::class.java))
        return "Aethena orb stopped."
    }

    private fun runTermux(command: String): String {
        if (command.isBlank()) return "No Termux command was supplied."
        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        }
        context.startService(intent)
        return "Sent the command to Termux. Termux must allow external apps."
    }

    private fun Boolean?.result(success: String): String = when (this) {
        true -> success
        false -> "The phone control action could not find a usable target."
        null -> "Accessibility control is not enabled."
    }
}
