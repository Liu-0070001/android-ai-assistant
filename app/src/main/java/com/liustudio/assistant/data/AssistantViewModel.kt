package com.liustudio.assistant.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val masterKey = MasterKey.Builder(application).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val securePrefs = EncryptedSharedPreferences.create(
        application,
        "local_ai_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val _messages = MutableStateFlow(listOf(ChatMessage(sender = Sender.ASSISTANT, content = "你好，我是本地智伴。添加资料、拍照，或直接开始提问。")))
    val messages = _messages.asStateFlow()
    private val _knowledge = MutableStateFlow<List<KnowledgeDocument>>(emptyList())
    val knowledge = _knowledge.asStateFlow()
    private val _personas = MutableStateFlow(listOf(
        Persona(name = "通用助手", prompt = "你是可靠、简洁的中文助手。", icon = "✦", official = true),
        Persona(name = "学习教练", prompt = "你是鼓励式学习教练，给出明确的下一步。", icon = "◈", official = true),
        Persona(name = "代码伙伴", prompt = "你是严谨的软件工程助手。", icon = "⌘", official = true)
    ))
    val personas = _personas.asStateFlow()
    private val _mcps = MutableStateFlow<List<McpServer>>(emptyList())
    val mcps = _mcps.asStateFlow()
    private val _settings = MutableStateFlow(ApiSettings(
        baseUrl = securePrefs.getString("base_url", "") ?: "",
        model = securePrefs.getString("model", "") ?: "",
        apiKey = securePrefs.getString("api_key", "") ?: "",
        autoWebSearch = securePrefs.getBoolean("auto_web_search", true)
    ))
    val settings = _settings.asStateFlow()
    private val _activePersona = MutableStateFlow(_personas.value.first())
    val activePersona = _activePersona.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    fun addKnowledge(uri: Uri) {
        val context = getApplication<Application>()
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else uri.lastPathSegment
        } ?: uri.lastPathSegment ?: "未命名文件"
        val type = context.contentResolver.getType(uri).orEmpty()
        val text = if (type.startsWith("text/") || name.endsWith(".md", true) || name.endsWith(".json", true) || name.endsWith(".csv", true)) {
            runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(500_000) }.orEmpty() }.getOrDefault("")
        } else ""
        _knowledge.value += KnowledgeDocument(name = name, uri = uri.toString(), text = text)
    }
    fun removeKnowledge(id: String) { _knowledge.value = _knowledge.value.filterNot { it.id == id } }
    fun addPersona(name: String, prompt: String) { if (name.isNotBlank() && prompt.isNotBlank()) _personas.value += Persona(name = name, prompt = prompt) }
    fun selectPersona(persona: Persona) { _activePersona.value = persona }
    fun addMcp(name: String, endpoint: String) { if (name.isNotBlank() && endpoint.startsWith("https://")) _mcps.value += McpServer(name = name, endpoint = endpoint) }
    fun toggleMcp(id: String) { _mcps.value = _mcps.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } }
    fun saveSettings(settings: ApiSettings) {
        _settings.value = settings
        securePrefs.edit().putString("base_url", settings.baseUrl.trimEnd('/')).putString("model", settings.model)
            .putString("api_key", settings.apiKey).putBoolean("auto_web_search", settings.autoWebSearch).apply()
    }
    fun deleteMessage(id: String) { _messages.value = _messages.value.filterNot { it.id == id } }
    fun deleteFrom(id: String) { _messages.value = _messages.value.takeWhile { it.id != id } }

    fun attachmentForUri(uri: Uri): Attachment {
        val resolver = getApplication<Application>().contentResolver
        val type = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else uri.lastPathSegment
        } ?: uri.lastPathSegment ?: "未命名文件"
        return Attachment(name, uri.toString(), if (type.startsWith("image/")) AttachmentKind.IMAGE else AttachmentKind.FILE, type)
    }

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        val user = ChatMessage(sender = Sender.USER, content = text, attachments = attachments)
        _messages.value += user
        val configured = _settings.value
        if (configured.baseUrl.isBlank() || configured.model.isBlank() || configured.apiKey.isBlank()) {
            _messages.value += ChatMessage(sender = Sender.ASSISTANT, content = "请先在“设置”填写兼容 API 的地址、模型和 API Key。你的 Key 只会保存在此设备的加密存储中。")
            return
        }
        _loading.value = true
        viewModelScope.launch {
            val result = runCatching { requestCompletion(configured, user) }
            _messages.value += ChatMessage(sender = Sender.ASSISTANT, content = result.getOrElse { "请求失败：${it.message ?: "请检查网络和 API 配置"}" })
            _loading.value = false
        }
    }

    private fun buildMultimodalContent(prompt: String, attachments: List<Attachment>): Any {
        if (attachments.isEmpty()) return prompt
        val context = getApplication<Application>()
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        attachments.forEach { attachment ->
            when {
                attachment.kind == AttachmentKind.IMAGE -> {
                    val base64 = attachment.inlineData ?: context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { input ->
                        Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
                    } ?: throw IllegalStateException("无法读取图片：${attachment.name}")
                    content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${attachment.mimeType};base64,$base64")))
                }
                attachment.mimeType.startsWith("text/") || attachment.name.endsWith(".md", true) || attachment.name.endsWith(".json", true) || attachment.name.endsWith(".csv", true) -> {
                    val text = context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("无法读取文件：${attachment.name}")
                    if (text.length > 100_000) throw IllegalStateException("文件过大：${attachment.name}。请上传不超过 100,000 个字符的文本文件。")
                    content.put(JSONObject().put("type", "text").put("text", "\n\n附件 ${attachment.name} 的内容：\n$text"))
                }
                else -> throw IllegalStateException("暂不支持直接发送 ${attachment.name}。目前可发送图片、TXT、Markdown、JSON 和 CSV；PDF/DOCX 会在知识库导入后解析。")
            }
        }
        return content
    }

    private suspend fun requestCompletion(settings: ApiSettings, user: ChatMessage): String = withContext(Dispatchers.IO) {
        val url = URL("${settings.baseUrl.trimEnd('/')}/chat/completions")
        val requestMessages = JSONArray().put(JSONObject().put("role", "system").put("content", _activePersona.value.prompt))
        _messages.value.filter { it.id != user.id }.takeLast(12).forEach { message ->
            requestMessages.put(JSONObject().put("role", if (message.sender == Sender.USER) "user" else "assistant").put("content", message.content))
        }
        val context = _knowledge.value.take(5).joinToString("、") { it.name }
        val prompt = buildString {
            append(user.content)
            if (context.isNotBlank()) append("\n\n当前可用本地知识库文件：$context。需要引用时请说明文件名。")
            if (settings.autoWebSearch) append("\n\n若现有上下文无法可靠回答，请明确说明需要联网搜索；不要编造搜索结果。")
        }
        requestMessages.put(JSONObject().put("role", "user").put("content", buildMultimodalContent(prompt, user.attachments)))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 30_000; readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        val body = JSONObject().put("model", settings.model).put("messages", requestMessages).put("temperature", 0.5).toString()
        connection.outputStream.bufferedWriter().use { it.write(body) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val contentType = connection.contentType.orEmpty()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("服务返回 HTTP ${connection.responseCode}：${response.take(240)}")
        }
        if (response.trimStart().startsWith("<") || !contentType.contains("json", ignoreCase = true)) {
            throw IllegalStateException("服务返回了网页而非 OpenAI 兼容 JSON。请确认 API 地址是接口根路径（通常以 /v1 结尾），而不是网站首页或登录页。")
        }
        val payload = runCatching { JSONObject(response) }.getOrElse {
            throw IllegalStateException("服务返回的内容不是有效 JSON，请检查 API 地址、模型和服务商兼容性。")
        }
        payload.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "模型未返回文本")
    }
}
