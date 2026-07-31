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
import java.net.URLEncoder
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val masterKey = MasterKey.Builder(application).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val securePrefs = EncryptedSharedPreferences.create(
        application,
        "local_ai_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val localPrefs = application.getSharedPreferences("local_ai_data", Context.MODE_PRIVATE)
    private val _messages = MutableStateFlow(loadMessages().ifEmpty { listOf(ChatMessage(sender = Sender.ASSISTANT, content = "你好，我是本地智伴。添加资料、拍照，或直接开始提问。")) })
    val messages = _messages.asStateFlow()
    private val _knowledge = MutableStateFlow(loadKnowledge())
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

    private fun loadMessages(): List<ChatMessage> = runCatching {
        val array = JSONArray(localPrefs.getString("messages", "[]"))
        List(array.length()) { index -> array.getJSONObject(index).let { item ->
            ChatMessage(id = item.getString("id"), sender = Sender.valueOf(item.getString("sender")), content = item.getString("content"), timestamp = item.getLong("timestamp"))
        } }
    }.getOrDefault(emptyList())

    private fun saveMessages() {
        val array = JSONArray()
        _messages.value.forEach { message -> array.put(JSONObject().put("id", message.id).put("sender", message.sender.name).put("content", message.content).put("timestamp", message.timestamp)) }
        localPrefs.edit().putString("messages", array.toString()).apply()
    }

    private fun loadKnowledge(): List<KnowledgeDocument> = runCatching {
        val array = JSONArray(localPrefs.getString("knowledge", "[]"))
        List(array.length()) { index -> array.getJSONObject(index).let { item -> KnowledgeDocument(item.getString("id"), item.getString("name"), item.getString("uri"), item.optString("text"), item.getLong("addedAt")) } }
    }.getOrDefault(emptyList())

    private fun saveKnowledge() {
        val array = JSONArray()
        _knowledge.value.forEach { document -> array.put(JSONObject().put("id", document.id).put("name", document.name).put("uri", document.uri).put("text", document.text).put("addedAt", document.addedAt)) }
        localPrefs.edit().putString("knowledge", array.toString()).apply()
    }

    fun addKnowledge(uri: Uri) {
        val context = getApplication<Application>()
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else uri.lastPathSegment
        } ?: uri.lastPathSegment ?: "未命名文件"
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { extractKnowledgeText(uri, name) }.getOrElse { "[无法解析：${it.message ?: "文件格式不受支持"}]" }
            _knowledge.value += KnowledgeDocument(name = name, uri = uri.toString(), text = text)
            saveKnowledge()
        }
    }

    private fun extractKnowledgeText(uri: Uri, name: String): String {
        val context = getApplication<Application>()
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(size < 20L * 1024 * 1024 || size == -1L) { "文件超过 20 MB 限制" }
        val type = context.contentResolver.getType(uri).orEmpty()
        return when {
            type.startsWith("text/") || name.endsWith(".md", true) || name.endsWith(".json", true) || name.endsWith(".csv", true) ->
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(500_000) }.orEmpty()
            name.endsWith(".pdf", true) -> {
                PDFBoxResourceLoader.init(context)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    PDDocument.load(input).use { document ->
                        require(document.numberOfPages <= 300) { "PDF 超过 300 页限制" }
                        PDFTextStripper().getText(document).take(500_000)
                    }
                }.orEmpty()
            }
            name.endsWith(".docx", true) -> extractDocxText(uri)
            else -> throw IllegalArgumentException("支持 TXT、Markdown、JSON、CSV、PDF 和 DOCX")
        }
    }

    private fun extractDocxText(uri: Uri): String {
        val context = getApplication<Application>()
        val result = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entries = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    require(++entries <= 2_000) { "DOCX 包含过多文件" }
                    if (entry.name == "word/document.xml") {
                        val parser = android.util.Xml.newPullParser()
                        parser.setInput(zip, "UTF-8")
                        while (parser.next() != XmlPullParser.END_DOCUMENT) {
                            if (parser.eventType == XmlPullParser.TEXT) result.append(parser.text)
                            if (result.length > 500_000) return result.take(500_000)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return result.toString()
    }
    fun removeKnowledge(id: String) { _knowledge.value = _knowledge.value.filterNot { it.id == id }; saveKnowledge() }
    fun addPersona(name: String, prompt: String) { if (name.isNotBlank() && prompt.isNotBlank()) _personas.value += Persona(name = name, prompt = prompt) }
    fun selectPersona(persona: Persona) { _activePersona.value = persona }
    fun addMcp(name: String, endpoint: String) { if (name.isNotBlank() && endpoint.startsWith("https://")) _mcps.value += McpServer(name = name, endpoint = endpoint) }
    fun toggleMcp(id: String) { _mcps.value = _mcps.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } }
    fun saveSettings(settings: ApiSettings) {
        _settings.value = settings
        securePrefs.edit().putString("base_url", settings.baseUrl.trimEnd('/')).putString("model", settings.model)
            .putString("api_key", settings.apiKey).putBoolean("auto_web_search", settings.autoWebSearch).apply()
    }
    fun deleteMessage(id: String) { _messages.value = _messages.value.filterNot { it.id == id }; saveMessages() }
    fun deleteFrom(id: String) { _messages.value = _messages.value.takeWhile { it.id != id }; saveMessages() }

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
        saveMessages()
        val configured = _settings.value
        if (configured.baseUrl.isBlank() || configured.model.isBlank() || configured.apiKey.isBlank()) {
            _messages.value += ChatMessage(sender = Sender.ASSISTANT, content = "请先在“设置”填写兼容 API 的地址、模型和 API Key。你的 Key 只会保存在此设备的加密存储中。")
            saveMessages()
            return
        }
        _loading.value = true
        viewModelScope.launch {
            val result = runCatching { requestCompletion(configured, user) }
            val completion = result.getOrElse { Completion("请求失败：${it.message ?: "请检查网络和 API 配置"}") }
            _messages.value += ChatMessage(sender = Sender.ASSISTANT, content = completion.content, sources = completion.sources)
            saveMessages()
            _loading.value = false
        }
    }

    private fun retrieveKnowledge(query: String): String {
        val tokens = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+|(?<=\\p{IsHan})(?=\\p{IsHan})")).filter { it.length > 1 }.toSet()
        return _knowledge.value.mapNotNull { document ->
            val text = document.text
            if (text.startsWith("[无法解析") || text.isBlank()) return@mapNotNull null
            val normalized = text.lowercase()
            val score = tokens.sumOf { token -> Regex(Regex.escape(token)).findAll(normalized).count() }
            if (score == 0) return@mapNotNull null
            val index = tokens.map { normalized.indexOf(it) }.firstOrNull { it >= 0 } ?: 0
            val start = (index - 400).coerceAtLeast(0)
            val end = (index + 1_600).coerceAtMost(text.length)
            Triple(score, document.name, text.substring(start, end))
        }.sortedByDescending { it.first }.take(4)
            .joinToString("\n\n") { (_, name, excerpt) -> "[$name]\n$excerpt" }
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

    private fun searchWeb(query: String): List<Pair<String, String>> {
        val url = URL("https://api.duckduckgo.com/?q=${URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1&skip_disambig=1")
        val response = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("User-Agent", "LocalAIAssistant/1.0")
            inputStream.bufferedReader().use { it.readText() }
        }
        val payload = JSONObject(response)
        val results = mutableListOf<Pair<String, String>>()
        payload.optString("AbstractURL").takeIf { it.isNotBlank() }?.let { results += it to payload.optString("AbstractText") }
        payload.optJSONArray("RelatedTopics")?.let { topics ->
            for (index in 0 until topics.length()) {
                val item = topics.optJSONObject(index) ?: continue
                item.optString("FirstURL").takeIf { it.isNotBlank() }?.let { results += it to item.optString("Text") }
                if (results.size >= 4) break
            }
        }
        return results.filter { it.second.isNotBlank() }.take(4)
    }

    private suspend fun requestCompletion(settings: ApiSettings, user: ChatMessage): Completion = withContext(Dispatchers.IO) {
        val url = URL("${settings.baseUrl.trimEnd('/')}/chat/completions")
        val requestMessages = JSONArray().put(JSONObject().put("role", "system").put("content", _activePersona.value.prompt))
        _messages.value.filter { it.id != user.id }.takeLast(12).forEach { message ->
            requestMessages.put(JSONObject().put("role", if (message.sender == Sender.USER) "user" else "assistant").put("content", message.content))
        }
        val knowledgeContext = retrieveKnowledge(user.content)
        val webResults = if (settings.autoWebSearch && knowledgeContext.isBlank()) runCatching { searchWeb(user.content) }.getOrDefault(emptyList()) else emptyList()
        val prompt = buildString {
            append(user.content)
            if (knowledgeContext.isNotBlank()) append("\n\n本地知识库检索结果（引用回答时标明文件名）：\n$knowledgeContext")
            if (webResults.isNotEmpty()) append("\n\n联网搜索结果（只可依据以下摘要回答，并引用网址）：\n" + webResults.joinToString("\n") { "${it.first}\n${it.second}" })
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
        Completion(
            content = payload.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "模型未返回文本"),
            sources = webResults.map { it.first }
        )
    }
}
