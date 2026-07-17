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
        id = "qwen2.5-0.5b-abliterated-sft",
        displayName = "Aethena Verified Local Brain",
        repository = "mradermacher/Qwen2.5-0.5B-Instruct-abliterated-SFT-i1-GGUF",
        purpose = "Uncensored local conversation, philosophy, automation, and lightweight coding"
    )

    val allowed = listOf(verified)
    private val allowedIds = allowed.map { it.id }.toSet()

    fun isAllowed(modelId: String): Boolean = modelId in allowedIds

    fun find(modelId: String): UncensoredModelProfile? = allowed.firstOrNull { it.id == modelId }
}
