package com.liustudio.assistant.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.liustudio.assistant.data.*

@Composable fun KnowledgeScreen(vm: AssistantViewModel) {
    val documents by vm.knowledge.collectAsState()
    val addFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::addKnowledge) }
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.addKnowledge(it) } }
    Column(Modifier.fillMaxSize()) { AppHeader("本地知识库", "资料默认只保存在手机上") { IconButton(onClick = { addFile.launch(arrayOf("text/*", "application/pdf", "application/*", "image/*")) }) { Icon(Icons.Default.Add, "添加文件") } }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { addFile.launch(arrayOf("text/*", "application/pdf", "application/*", "image/*")) }) { Icon(Icons.Default.UploadFile, null); Text(" 导入文件") }; OutlinedButton(onClick = { addFolder.launch(null) }) { Icon(Icons.Default.FolderOpen, null); Text(" 选择文件夹") } }
        if (documents.isEmpty()) EmptyState(Icons.Default.MenuBook, "还没有资料", "导入 PDF、文本、图片或授权一个文件夹后，即可在对话中引用。") else LazyColumn(Modifier.padding(16.dp)) { items(documents, key = { it.id }) { document -> ListItem(headlineContent = { Text(document.name, fontWeight = FontWeight.Medium) }, supportingContent = { Text("本机授权文件 · 已加入检索范围") }, leadingContent = { Icon(Icons.Default.Description, null, tint = Blue) }, trailingContent = { IconButton({ vm.removeKnowledge(document.id) }) { Icon(Icons.Default.DeleteOutline, "删除") } }); HorizontalDivider() } }
        Text("提示：首版已提供文件授权和资料管理。完整文本提取、OCR 与离线语义索引将在后续版本补充。", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable fun PersonaScreen(vm: AssistantViewModel) {
    val personas by vm.personas.collectAsState(); val active by vm.activePersona.collectAsState(); var dialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) { AppHeader("人设与 Skill", "选择回答风格；官方目录将在联网版本中更新") { IconButton({ dialog = true }) { Icon(Icons.Default.Add, "创建人设") } }; LazyColumn(Modifier.padding(horizontal = 16.dp)) { items(personas, key = { it.id }) { persona -> ListItem(headlineContent = { Text("${persona.icon}  ${persona.name}", fontWeight = FontWeight.SemiBold) }, supportingContent = { Text(persona.prompt, maxLines = 2) }, trailingContent = { RadioButton(persona.id == active.id, { vm.selectPersona(persona) }) }); HorizontalDivider() } } }
    if (dialog) PersonaDialog({ name, prompt -> vm.addPersona(name, prompt); dialog = false }, { dialog = false })
}

@Composable private fun PersonaDialog(confirm: (String, String) -> Unit, dismiss: () -> Unit) { var name by remember { mutableStateOf("") }; var prompt by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = dismiss, title = { Text("新建人设") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("名称") }); OutlinedTextField(prompt, { prompt = it }, label = { Text("系统提示词") }, minLines = 3) } }, confirmButton = { TextButton({ confirm(name, prompt) }, enabled = name.isNotBlank() && prompt.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } }) }

@Composable fun McpScreen(vm: AssistantViewModel) {
    val mcps by vm.mcps.collectAsState(); var dialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) { AppHeader("MCP 与扩展", "仅支持远程 HTTPS MCP；调用前会要求确认") { IconButton({ dialog = true }) { Icon(Icons.Default.Add, "添加 MCP") } }; if (mcps.isEmpty()) EmptyState(Icons.Default.Extension, "暂未连接扩展", "你可以搜索官方开放目录，或添加自己的 HTTPS MCP Server。") else LazyColumn(Modifier.padding(horizontal = 16.dp)) { items(mcps, key = { it.id }) { mcp -> ListItem(headlineContent = { Text(mcp.name, fontWeight = FontWeight.Medium) }, supportingContent = { Text(mcp.endpoint) }, leadingContent = { Icon(Icons.Default.Hub, null, tint = Blue) }, trailingContent = { Switch(mcp.enabled, { vm.toggleMcp(mcp.id) }) }); HorizontalDivider() } }; Text("安全规则：MCP 默认无法读取附件和知识库。发送文件、写入数据、支付或外发消息等操作必须逐次确认。", Modifier.padding(20.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
    if (dialog) McpDialog({ name, endpoint -> vm.addMcp(name, endpoint); dialog = false }, { dialog = false })
}

@Composable private fun McpDialog(confirm: (String, String) -> Unit, dismiss: () -> Unit) { var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = dismiss, title = { Text("添加远程 MCP") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("名称") }); OutlinedTextField(url, { url = it }, label = { Text("HTTPS Endpoint") }, singleLine = true); Text("只接受 HTTPS 地址，避免应用将数据发送到不安全的服务。", style = MaterialTheme.typography.labelSmall) } }, confirmButton = { TextButton({ confirm(name, url) }, enabled = name.isNotBlank() && url.startsWith("https://")) { Text("添加") } }, dismissButton = { TextButton(dismiss) { Text("取消") } }) }

@Composable fun SettingsScreen(vm: AssistantViewModel) {
    val saved by vm.settings.collectAsState(); var baseUrl by remember(saved) { mutableStateOf(saved.baseUrl) }; var model by remember(saved) { mutableStateOf(saved.model) }; var key by remember(saved) { mutableStateOf(saved.apiKey) }; var autoSearch by remember(saved) { mutableStateOf(saved.autoWebSearch) }
    Column(Modifier.fillMaxSize()) { AppHeader("设置", "本地配置，不需要账号"); Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Text("AI API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("兼容 API 地址") }, placeholder = { Text("https://api.example.com/v1") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(key, { key = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()); HorizontalDivider(); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("自动联网搜索", fontWeight = FontWeight.Medium); Text("资料不足时让模型提示需要搜索。实际搜索依赖你的 AI 服务支持。", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }; Switch(autoSearch, { autoSearch = it }) }; Button(onClick = { vm.saveSettings(ApiSettings(baseUrl, model, key, autoSearch)) }, modifier = Modifier.fillMaxWidth()) { Text("保存本机配置") }; Text("隐私说明：API Key 使用设备私有存储保存。发送消息、附件说明和联网检索请求时，数据会直接交给你选择的 AI 或搜索服务。", style = MaterialTheme.typography.labelSmall, color = Color.Gray) } }
}

@Composable private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) = Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, Modifier.size(48.dp), tint = Blue); Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(description, color = Color.Gray, style = MaterialTheme.typography.bodyMedium) }
