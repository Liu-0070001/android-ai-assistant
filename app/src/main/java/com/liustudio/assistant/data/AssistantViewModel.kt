package com.liustudio.assistant.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
    private val _skills = MutableStateFlow(loadSkills())
    val skills = _skills.asStateFlow()
    private val _skillSearchState = MutableStateFlow("搜索 GitHub 上包含 SKILL.md 的公开仓库")
    val skillSearchState = _skillSearchState.asStateFlow()
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
            val sources = item.optJSONArray("sources")?.let { sourceArray ->
                List(sourceArray.length()) { sourceIndex ->
                    sourceArray.getJSONObject(sourceIndex).let { source ->
                        MessageSource(
                            title = source.getString("title"),
                            detail = source.optString("detail"),
                            uri = source.optString("uri"),
                            kind = SourceKind.valueOf(source.optString("kind", SourceKind.WEB.name))
                        )
                    }
                }
            }.orEmpty()
            ChatMessage(id = item.getString("id"), sender = Sender.valueOf(item.getString("sender")), content = item.getString("content"), timestamp = item.getLong("timestamp"), sources = sources)
        } }
    }.getOrDefault(emptyList())

    private fun saveMessages() {
        val array = JSONArray()
        _messages.value.forEach { message ->
            val sources = JSONArray()
            message.sources.forEach { source ->
                sources.put(JSONObject().put("title", source.title).put("detail", source.detail).put("uri", source.uri).put("kind", source.kind.name))
            }
            array.put(JSONObject().put("id", message.id).put("sender", message.sender.name).put("content", message.content).put("timestamp", message.timestamp).put("sources", sources))
        }
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

    private fun loadSkills(): List<Skill> = runCatching {
        val array = JSONArray(localPrefs.getString("skills", "[]"))
        List(array.length()) { index -> array.getJSONObject(index).let { item -> Skill(item.getString("id"), item.getString("name"), item.getString("description"), item.getString("downloadUrl"), item.optString("sourceUrl"), item.optString("content"), item.optBoolean("installed"), item.optBoolean("enabled")) } }
    }.getOrDefault(emptyList())

    private fun saveSkills() {
        val array = JSONArray()
        _skills.value.forEach { skill -> array.put(JSONObject().put("id", skill.id).put("name", skill.name).put("description", skill.description).put("downloadUrl", skill.downloadUrl).put("sourceUrl", skill.sourceUrl).put("content", skill.content).put("installed", skill.installed).put("enabled", skill.enabled)) }
        localPrefs.edit().putString("skills", array.toString()).apply()
    }

    fun addKnowledge(uri: Uri) {
        val context = getApplication<Application>()
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = displayName(uri)
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { extractKnowledgeText(uri, name) }.getOrElse { "[无法解析：${it.message ?: "文件格式不受支持"}]" }
            _knowledge.value = (_knowledge.value + KnowledgeDocument(name = name, uri = uri.toString(), text = text)).distinctBy { it.uri }
            saveKnowledge()
        }
    }

    fun addKnowledgeFolder(uri: Uri) {
        val context = getApplication<Application>()
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, uri) ?: return@launch
            val files = collectSupportedFiles(root).take(100)
            val imported = files.map { file ->
                val text = runCatching { extractKnowledgeText(file.uri, file.name ?: "未命名文件") }
                    .getOrElse { "[无法解析：${it.message ?: "文件格式不受支持"}]" }
                KnowledgeDocument(name = file.name ?: "未命名文件", uri = file.uri.toString(), text = text)
            }
            _knowledge.value = (_knowledge.value + imported).distinctBy { it.uri }
            saveKnowledge()
        }
    }

    private fun collectSupportedFiles(directory: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        val pending = ArrayDeque<DocumentFile>().apply { add(directory) }
        var visited = 0
        while (pending.isNotEmpty() && result.size < 100 && visited < 500) {
            val current = pending.removeFirst()
            visited++
            current.listFiles().forEach { child ->
                if (child.isDirectory) pending.add(child)
                else if (child.isFile && isSupportedKnowledgeFile(child.name.orEmpty(), child.type.orEmpty())) result += child
            }
        }
        return result
    }

    private fun isSupportedKnowledgeFile(name: String, type: String): Boolean =
        type.startsWith("text/") || listOf(".md", ".json", ".csv", ".pdf", ".docx").any { name.endsWith(it, true) }

    private fun displayName(uri: Uri): String {
        val context = getApplication<Application>()
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else uri.lastPathSegment
        } ?: uri.lastPathSegment ?: "未命名文件"
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
                            if (result.length > 500_000) return result.take(500_000).toString()
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
    fun toggleSkill(id: String) { _skills.value = _skills.value.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }; saveSkills() }
    fun removeSkill(id: String) { _skills.value = _skills.value.filterNot { it.id == id }; saveSkills() }

    fun searchSkills(query: String) {
        if (query.isBlank()) return
        _skillSearchState.value = "正在搜索 GitHub…"
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val url = URL("https://api.github.com/search/repositories?q=${URLEncoder.encode("$query skill", "UTF-8")}&sort=stars&order=desc&per_page=12")
                val connection = (url.openConnection() as HttpURLConnection).apply { setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "LocalAIAssistant") }
                val items = JSONObject(connection.inputStream.readBytes().toString(Charsets.UTF_8)).getJSONArray("items")
                List(items.length()) { i ->
                    val item = items.getJSONObject(i)
                    val htmlUrl = item.getString("html_url")
                    val rawUrl = "https://raw.githubusercontent.com/${item.getString("full_name")}/${item.getString("default_branch")}/SKILL.md"
                    Skill(item.getString("full_name"), item.getString("name"), item.optString("description", "GitHub 社区 Skill"), rawUrl, htmlUrl)
                }
            }
            result.onSuccess { found ->
                _skills.value = (_skills.value.filter { it.installed } + found).distinctBy { it.id }
                _skillSearchState.value = "找到 ${found.size} 个候选。下载前会读取 SKILL.md。"
            }.onFailure { _skillSearchState.value = "搜索失败：${it.message ?: "请检查网络"}" }
        }
    }

    fun downloadSkill(skill: Skill) {
        _skillSearchState.value = "正在读取 ${skill.name} 的 SKILL.md…"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val connection = (URL(skill.downloadUrl).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "LocalAIAssistant"); connectTimeout = 15_000; readTimeout = 20_000 }
                require(connection.responseCode in 200..299) { "未找到根目录 SKILL.md" }
                connection.inputStream.readBytes().toString(Charsets.UTF_8).also { require(it.length <= 100_000) { "SKILL.md 过大" } }
            }.onSuccess { content ->
                val directory = java.io.File(getApplication<Application>().filesDir, "skills").apply { mkdirs() }
                java.io.File(directory, "${skill.id.replace('/', '_')}.md").writeText(content, Charsets.UTF_8)
                _skills.value = _skills.value.map { if (it.id == skill.id) it.copy(content = content, installed = true) else it }
                saveSkills(); _skillSearchState.value = "已下载到应用私有目录，可启用使用。"
            }.onFailure { _skillSearchState.value = "安装失败：${it.message ?: "不是兼容的 Skill"}" }
        }
    }
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

    private fun retrieveKnowledge(query: String): KnowledgeRetrieval =
        KnowledgeRetriever.retrieve(query, _knowledge.value)

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
        val skillInstructions = _skills.value.filter { it.installed && it.enabled && it.content.isNotBlank() }.joinToString("\n\n") { "已启用 Skill：${it.name}\n${it.content}" }
        val systemPrompt = listOf(_activePersona.value.prompt, skillInstructions).filter { it.isNotBlank() }.joinToString("\n\n")
        val requestMessages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        _messages.value.filter { it.id != user.id }.takeLast(12).forEach { message ->
            requestMessages.put(JSONObject().put("role", if (message.sender == Sender.USER) "user" else "assistant").put("content", message.content))
        }
        val knowledge = retrieveKnowledge(user.content)
        val webResults = if (settings.autoWebSearch && !knowledge.hasStrongMatch) runCatching { searchWeb(user.content) }.getOrDefault(emptyList()) else emptyList()
        val prompt = buildString {
            append(user.content)
            if (knowledge.context.isNotBlank()) {
                append("\n\n本地知识库检索结果：\n${knowledge.context}")
                append("\n\n回答要求：优先依据高相关本地片段；引用本地资料时标明【文件名 · 片段编号】；资料只部分相关时明确证据边界，不要把低相关片段当作确定答案。")
            }
            if (webResults.isNotEmpty()) append("\n\n本地资料不足，以下是联网搜索摘要。只可依据摘要回答，并标明网址：\n" + webResults.joinToString("\n") { "${it.first}\n${it.second}" })
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
        val response = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
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
            sources = knowledge.references + webResults.map { (url, summary) ->
                MessageSource(
                    title = runCatching { URL(url).host }.getOrDefault("联网来源"),
                    detail = summary.take(120),
                    uri = url,
                    kind = SourceKind.WEB
                )
            }
        )
    }
}
