package com.liustudio.assistant.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.liustudio.assistant.data.ApiSettings
import com.liustudio.assistant.data.AssistantViewModel

private val supportedDocuments = arrayOf("text/*", "application/pdf", "application/*")

@Composable
fun KnowledgeScreen(vm: AssistantViewModel) {
    val documents by vm.knowledge.collectAsState()
    val addFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::addKnowledge)
    }
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::addKnowledgeFolder)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(
            title = "本地知识库",
            subtitle = "结构化分片与混合检索均在手机本地完成"
        ) {
            IconButton(onClick = { addFile.launch(supportedDocuments) }) {
                Icon(Icons.Default.Add, contentDescription = "添加文件")
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { addFile.launch(supportedDocuments) }) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Text("导入文件", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = { addFolder.launch(null) }) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Text("选择文件夹", modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (documents.isEmpty()) {
            EmptyState(
                icon = Icons.Default.MenuBook,
                title = "还没有资料",
                description = "导入 PDF、TXT、Markdown、JSON、CSV、DOCX，或选择包含这些文件的文件夹。",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                items(documents, key = { it.id }) { document ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = document.name,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                if (document.text.startsWith("[无法解析")) {
                                    document.text
                                } else {
                                    "已解析 ${document.text.length} 字符 · 本地混合检索"
                                }
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.removeKnowledge(document.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "删除")
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }

        Text(
            text = "检索会融合短语、词频、标题和字符相似度，并在命中时补充相邻片段；本地证据不足才自动联网。扫描版 PDF 暂不支持 OCR。",
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PersonaScreen(vm: AssistantViewModel) {
    val personas by vm.personas.collectAsState()
    val active by vm.activePersona.collectAsState()
    var dialogVisible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(
            title = "人设与 Skill",
            subtitle = "选择回答风格；官方目录将在联网版本中更新"
        ) {
            IconButton(onClick = { dialogVisible = true }) {
                Icon(Icons.Default.Add, contentDescription = "创建人设")
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            items(personas, key = { it.id }) { persona ->
                ListItem(
                    headlineContent = {
                        Text("${persona.icon}  ${persona.name}", fontWeight = FontWeight.SemiBold)
                    },
                    supportingContent = {
                        Text(persona.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        RadioButton(
                            selected = persona.id == active.id,
                            onClick = { vm.selectPersona(persona) }
                        )
                    }
                )
                HorizontalDivider()
            }
        }
    }

    if (dialogVisible) {
        PersonaDialog(
            onConfirm = { name, prompt ->
                vm.addPersona(name, prompt)
                dialogVisible = false
            },
            onDismiss = { dialogVisible = false }
        )
    }
}

@Composable
private fun PersonaDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建人设") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("系统提示词") },
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), prompt.trim()) },
                enabled = name.isNotBlank() && prompt.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun McpScreen(vm: AssistantViewModel) {
    val mcps by vm.mcps.collectAsState()
    val skills by vm.skills.collectAsState()
    val skillState by vm.skillSearchState.collectAsState()
    var dialogVisible by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(
            title = "MCP 与 Skill",
            subtitle = "搜索 GitHub 社区库；仅解析 SKILL.md 指令"
        ) {
            IconButton(onClick = { dialogVisible = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加 MCP")
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("搜索 GitHub Skill") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(
                onClick = { vm.searchSkills(search.trim()) },
                enabled = search.isNotBlank()
            ) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
        }
        Text(
            text = skillState,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            item { SectionTitle("Skill 搜索结果与已安装内容") }
            items(skills, key = { it.id }) { skill ->
                ListItem(
                    headlineContent = { Text(skill.name, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(skill.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        if (skill.installed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = skill.enabled,
                                    onCheckedChange = { vm.toggleSkill(skill.id) }
                                )
                                IconButton(onClick = { vm.removeSkill(skill.id) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "卸载")
                                }
                            }
                        } else {
                            TextButton(onClick = { vm.downloadSkill(skill) }) { Text("安装") }
                        }
                    }
                )
                HorizontalDivider()
            }

            item { SectionTitle("已配置 MCP", topPadding = 20.dp) }
            if (mcps.isEmpty()) {
                item {
                    Text(
                        text = "暂无 MCP。点击右上角添加 HTTPS Server。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            items(mcps, key = { it.id }) { mcp ->
                ListItem(
                    headlineContent = { Text(mcp.name, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(mcp.endpoint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = mcp.enabled,
                            onCheckedChange = { vm.toggleMcp(mcp.id) }
                        )
                    }
                )
                HorizontalDivider()
            }
            item {
                Text(
                    text = "安全规则：MCP 默认无法读取附件和知识库。发送文件、写入数据、支付或外发消息等操作必须逐次确认。",
                    modifier = Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (dialogVisible) {
        McpDialog(
            onConfirm = { name, endpoint ->
                vm.addMcp(name, endpoint)
                dialogVisible = false
            },
            onDismiss = { dialogVisible = false }
        )
    }
}

@Composable
private fun SectionTitle(text: String, topPadding: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = topPadding, bottom = 8.dp)
    )
}

@Composable
private fun McpDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加远程 MCP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("HTTPS Endpoint") },
                    singleLine = true
                )
                Text(
                    text = "只接受 HTTPS 地址，避免应用将数据发送到不安全的服务。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim()) },
                enabled = name.isNotBlank() && url.trim().startsWith("https://")
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun SettingsScreen(vm: AssistantViewModel) {
    val saved by vm.settings.collectAsState()
    var baseUrl by remember(saved) { mutableStateOf(saved.baseUrl) }
    var model by remember(saved) { mutableStateOf(saved.model) }
    var key by remember(saved) { mutableStateOf(saved.apiKey) }
    var autoSearch by remember(saved) { mutableStateOf(saved.autoWebSearch) }
    var revealKey by remember { mutableStateOf(false) }
    var visionEnabled by remember(saved) { mutableStateOf(saved.visionEnabled) }
    var visionBaseUrl by remember(saved) { mutableStateOf(saved.visionBaseUrl) }
    var visionModel by remember(saved) { mutableStateOf(saved.visionModel) }
    var visionKey by remember(saved) { mutableStateOf(saved.visionApiKey) }
    var revealVisionKey by remember { mutableStateOf(false) }
    var discoveringText by remember { mutableStateOf(false) }
    var textModelOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showTextModelPicker by remember { mutableStateOf(false) }
    var discoveringVision by remember { mutableStateOf(false) }
    var visionModelOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showVisionModelPicker by remember { mutableStateOf(false) }
    var discoverError by remember { mutableStateOf("") }
    var testingText by remember { mutableStateOf(false) }
    var testingVision by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("") }
    var connectionPassed by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current

    fun probeTextModels() {
        discoverError = ""
        connectionStatus = ""
        discoveringText = true
        vm.discoverModels(baseUrl, key, onSuccess = { list ->
            textModelOptions = list
            discoveringText = false
            showTextModelPicker = true
        }, onFailure = { msg ->
            discoveringText = false
            discoverError = msg
        })
    }

    fun probeVisionModels() {
        discoverError = ""
        connectionStatus = ""
        discoveringVision = true
        vm.discoverModels(visionBaseUrl, visionKey, onSuccess = { list ->
            visionModelOptions = list
            discoveringVision = false
            showVisionModelPicker = true
        }, onFailure = { msg ->
            discoveringVision = false
            discoverError = msg
        })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader("设置", "配置仅保存在当前设备")
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("AI 服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    model = ""
                },
                label = { Text("兼容 API 地址") },
                placeholder = { Text("https://api.example.com/v1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (revealKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { revealKey = !revealKey }) {
                        Icon(
                            imageVector = if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealKey) "隐藏 API Key" else "显示 API Key"
                        )
                    }
                }
            )
            OutlinedButton(
                onClick = { probeTextModels() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !discoveringText && baseUrl.isNotBlank() && key.isNotBlank()
            ) {
                Icon(
                    imageVector = if (discoveringText) Icons.Default.HourglassTop else Icons.Default.Search,
                    contentDescription = null
                )
                Text(if (discoveringText) "正在探查可用模型…" else "探查并选择模型", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedTextField(
                value = model,
                onValueChange = {},
                readOnly = true,
                label = { Text("已选择的模型") },
                placeholder = { Text("请先探查并选择模型") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedButton(
                onClick = {
                    connectionStatus = ""
                    testingText = true
                    vm.testModel(baseUrl, key, model, onSuccess = {
                        testingText = false
                        connectionPassed = true
                        connectionStatus = it
                    }, onFailure = {
                        testingText = false
                        connectionPassed = false
                        connectionStatus = it
                    })
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !testingText && model.isNotBlank()
            ) {
                Text(if (testingText) "正在测试文本模型…" else "测试文本模型连接")
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动联网搜索", fontWeight = FontWeight.Medium)
                    Text(
                        text = "本地证据不足时获取公开网页摘要。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoSearch, onCheckedChange = { autoSearch = it })
            }
            HorizontalDivider()
            Text("识图模型（拍照识题）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("使用独立识图模型", fontWeight = FontWeight.Medium)
                    Text(
                        text = "拍照后先由识图模型提取题目，再由文本模型引导讲解。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = visionEnabled, onCheckedChange = { visionEnabled = it })
            }
            if (visionEnabled) {
                OutlinedTextField(
                    value = visionBaseUrl,
                    onValueChange = {
                        visionBaseUrl = it
                        visionModel = ""
                    },
                    label = { Text("识图 API 地址") },
                    placeholder = { Text("https://api.example.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = visionKey,
                    onValueChange = { visionKey = it },
                    label = { Text("识图 API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (revealVisionKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { revealVisionKey = !revealVisionKey }) {
                            Icon(
                                imageVector = if (revealVisionKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealVisionKey) "隐藏识图 Key" else "显示识图 Key"
                            )
                        }
                    }
                )
                OutlinedButton(
                    onClick = { probeVisionModels() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !discoveringVision && visionBaseUrl.isNotBlank() && visionKey.isNotBlank()
                ) {
                    Icon(
                        imageVector = if (discoveringVision) Icons.Default.HourglassTop else Icons.Default.Search,
                        contentDescription = null
                    )
                    Text(if (discoveringVision) "正在探查可用模型…" else "探查并选择识图模型", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = visionModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("已选择的识图模型") },
                    placeholder = { Text("请先探查并选择模型") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        connectionStatus = ""
                        testingVision = true
                        vm.testModel(visionBaseUrl, visionKey, visionModel, onSuccess = {
                            testingVision = false
                            connectionPassed = true
                            connectionStatus = it
                        }, onFailure = {
                            testingVision = false
                            connectionPassed = false
                            connectionStatus = it
                        })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !testingVision && visionModel.isNotBlank()
                ) {
                    Text(if (testingVision) "正在测试识图模型…" else "测试识图模型连接")
                }
            }
            Button(
                onClick = {
                    vm.saveSettings(
                        ApiSettings(
                            baseUrl = baseUrl,
                            model = model,
                            apiKey = key,
                            autoWebSearch = autoSearch,
                            visionEnabled = visionEnabled,
                            visionBaseUrl = visionBaseUrl,
                            visionModel = visionModel,
                            visionApiKey = visionKey
                        )
                    )
                    Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = baseUrl.isNotBlank() && model.isNotBlank() && key.isNotBlank() &&
                    (!visionEnabled || (visionBaseUrl.isNotBlank() && visionModel.isNotBlank() && visionKey.isNotBlank()))
            ) {
                Text("保存本机配置")
            }
            Text(
                text = "文本模型负责讲解，识图模型负责提取题目，可分别使用不同国产模型服务商。API Key 使用设备私有加密存储。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (discoverError.isNotBlank()) {
                Text(
                    text = "探查失败：$discoverError",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (connectionStatus.isNotBlank()) {
                Text(
                    text = connectionStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connectionPassed == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showTextModelPicker && textModelOptions.isNotEmpty()) {
        ModelPickerDialog(
            title = "选择文本模型",
            models = textModelOptions,
            onSelect = { selected ->
                model = selected
                showTextModelPicker = false
            },
            onDismiss = { showTextModelPicker = false }
        )
    }
    if (showVisionModelPicker && visionModelOptions.isNotEmpty()) {
        ModelPickerDialog(
            title = "选择识图模型",
            models = visionModelOptions,
            onSelect = { selected ->
                visionModel = selected
                showVisionModelPicker = false
            },
            onDismiss = { showVisionModelPicker = false }
        )
    }
}

@Composable
private fun ModelPickerDialog(
    title: String,
    models: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(models, key = { it }) { modelId ->
                    TextButton(
                        onClick = { onSelect(modelId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = modelId,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
