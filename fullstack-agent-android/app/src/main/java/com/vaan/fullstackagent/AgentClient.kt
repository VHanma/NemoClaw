package com.vaan.fullstackagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AgentClient {
    private val client = OkHttpClient()
    suspend fun chat(endpoint: String, apiKey: String, model: String, system: String, history: List<Pair<String,String>>, user: String): String = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) return@withContext "Brain endpoint is empty. Open Settings and add an OpenAI-compatible chat endpoint."
        val messages = JSONArray().put(JSONObject().put("role","system").put("content",system))
        history.forEach { (role, text) -> messages.put(JSONObject().put("role", role).put("content", text)) }
        messages.put(JSONObject().put("role","user").put("content",user))
        val json = JSONObject().put("model", model.ifBlank { "gpt-4.1-mini" }).put("messages", messages).put("temperature", 0.7)
        val reqBuilder = Request.Builder().url(endpoint).post(json.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
        client.newCall(reqBuilder.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) return@withContext "Brain error ${r.code}: ${body.take(400)}"
            val root = JSONObject(body)
            root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() } ?: body.take(1200)
        }
    }
}
