package com.k2fsa.sherpa.onnx.tts.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class VoiceProfile(
    val id: String,
    val name: String,
    val wavPath: String,
    val createdAt: Long,
)

class VoiceProfileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("voice_vault_profiles", Context.MODE_PRIVATE)

    fun all(): List<VoiceProfile> {
        val raw = prefs.getString("profiles", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val p = VoiceProfile(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        wavPath = o.getString("wavPath"),
                        createdAt = o.optLong("createdAt", 0L),
                    )
                    if (File(p.wavPath).exists()) add(p)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun selectedId(): String? = prefs.getString("selected", null)

    fun selected(): VoiceProfile? {
        val id = selectedId()
        return all().firstOrNull { it.id == id } ?: all().firstOrNull()
    }

    fun select(id: String) {
        prefs.edit().putString("selected", id).apply()
    }

    fun add(name: String, wavFile: File): VoiceProfile {
        val profile = VoiceProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Voice ${all().size + 1}" },
            wavPath = wavFile.absolutePath,
            createdAt = System.currentTimeMillis(),
        )
        save(all() + profile)
        select(profile.id)
        return profile
    }

    fun rename(id: String, newName: String) {
        save(all().map { if (it.id == id) it.copy(name = newName.trim().ifBlank { it.name }) else it })
    }

    fun delete(id: String) {
        val current = all()
        current.firstOrNull { it.id == id }?.let { runCatching { File(it.wavPath).delete() } }
        val next = current.filterNot { it.id == id }
        save(next)
        if (selectedId() == id) {
            prefs.edit().putString("selected", next.firstOrNull()?.id).apply()
        }
    }

    private fun save(items: List<VoiceProfile>) {
        val arr = JSONArray()
        items.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("wavPath", p.wavPath)
                put("createdAt", p.createdAt)
            })
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }
}
