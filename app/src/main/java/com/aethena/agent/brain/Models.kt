package com.aethena.agent.brain

data class ChatMessage(
    val role: String,
    val content: String
)

data class AgentAction(
    val type: String,
    val arg: String = ""
)

data class AgentReply(
    val reply: String,
    val actions: List<AgentAction> = emptyList()
)
