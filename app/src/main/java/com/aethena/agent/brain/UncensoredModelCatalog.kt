package com.aethena.agent.brain

data class UncensoredModelProfile(
    val id: String,
    val displayName: String,
    val repository: String,
    val purpose: String
)

object UncensoredModelCatalog {
    const val LOCAL_BASE_URL = "http://127.0.0.1:8080/v1"

    val general = UncensoredModelProfile(
        id = "qwen2.5-0.5b-abliterated-sft",
        displayName = "Aethena Freeform",
        repository = "mradermacher/Qwen2.5-0.5B-Instruct-abliterated-SFT-i1-GGUF",
        purpose = "General uncensored conversation"
    )

    val thinker = UncensoredModelProfile(
        id = "atomight-v2.2-ultrathink-0.5b-abliterated",
        displayName = "Aethena Deep Thinker",
        repository = "mradermacher/Atomight-V2.2-UltraThink-0.5B-abliterated-i1-GGUF",
        purpose = "Philosophy and complex reasoning"
    )

    val coder = UncensoredModelProfile(
        id = "qwen2.5-coder-0.5b-abliterated",
        displayName = "Aethena Architect",
        repository = "bartowski/Qwen2.5-Coder-0.5B-Instruct-abliterated-GGUF",
        purpose = "Coding and project creation"
    )

    val allowed = listOf(general, thinker, coder)
    private val allowedIds = allowed.map { it.id }.toSet()

    fun isAllowed(modelId: String): Boolean = modelId in allowedIds

    fun find(modelId: String): UncensoredModelProfile? = allowed.firstOrNull { it.id == modelId }
}
