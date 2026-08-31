package com.vaan.fullstackagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AgentMemory(private val context: Context) {
    private val file = File(context.filesDir, "memory.json")

    fun append(role: String, text: String) {
        val arr = loadArray()
        arr.put(JSONObject().put("role", role).put("text", text).put("time", System.currentTimeMillis()))
        file.writeText(arr.toString())
    }

    fun recent(limit: Int = 24): List<Pair<String, String>> {
        val arr = loadArray()
        val start = maxOf(0, arr.length() - limit)
        return (start until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.optString("role") to o.optString("text")
        }
    }

    fun clear() { if (file.exists()) file.delete() }

    private fun loadArray(): JSONArray = try {
        if (!file.exists()) JSONArray() else JSONArray(file.readText())
    } catch (_: Exception) { JSONArray() }
}
