package com.liustudio.assistant.data

import java.util.UUID

enum class Sender { USER, ASSISTANT }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: Sender,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    val sources: List<String> = emptyList()
)

data class Attachment(
    val name: String,
    val uri: String,
    val kind: AttachmentKind,
    val mimeType: String = "application/octet-stream",
    val inlineData: String? = null
)
enum class AttachmentKind { IMAGE, FILE, FOLDER }

data class KnowledgeDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uri: String,
    val text: String = "",
    val addedAt: Long = System.currentTimeMillis()
)
data class Persona(val id: String = UUID.randomUUID().toString(), val name: String, val prompt: String, val icon: String = "✦", val official: Boolean = false)
data class McpServer(val id: String = UUID.randomUUID().toString(), val name: String, val endpoint: String, val enabled: Boolean = false)
data class Completion(val content: String, val sources: List<String> = emptyList())
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val downloadUrl: String,
    val sourceUrl: String = downloadUrl,
    val content: String = "",
    val installed: Boolean = false,
    val enabled: Boolean = false
)
data class ApiSettings(val baseUrl: String = "", val model: String = "", val apiKey: String = "", val autoWebSearch: Boolean = true)
