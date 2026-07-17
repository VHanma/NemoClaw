package com.aethena.agent.brain

data class UncensoredModelProfile(
    val id: String,
    val displayName: String,
    val repository: String,
    val purpose: String
)

object UncensoredModelCatalog {
    const val LOCAL_BASE_URL = "http://127.0.0.1:8080/v1"

    val verified = UncensoredModelProfile(
        id = "huihui-qwen3.5-4b-abliterated-q3ks",
        displayName = "Aethena Qwen3.5 4B Uncensored",
        repository = "mradermacher/Huihui-Qwen3.5-4B-abliterated-i1-GGUF",
        purpose = "Local uncensored conversation, philosophy, reasoning, automation, and coding"
    )

    val allowed = listOf(verified)
    private val allowedIds = allowed.map { it.id }.toSet()

    fun isAllowed(modelId: String): Boolean = modelId in allowedIds

    fun find(modelId: String): UncensoredModelProfile? = allowed.firstOrNull { it.id == modelId }
}
