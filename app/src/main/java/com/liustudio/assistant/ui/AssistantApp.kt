package com.liustudio.assistant.ui

import android.Manifest
import android.graphics.Bitmap
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liustudio.assistant.data.*

private val Ink = Color(0xFF172033)
val Blue = Color(0xFF356AE6)

@Composable fun AssistantApp(vm: AssistantViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf("对话", "知识库", "人设", "扩展", "设置")
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue, surface = Color.White, background = Color(0xFFF6F7FB), onSurface = Ink)) {
        Scaffold(
            bottomBar = { NavigationBar { labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(listOf(Icons.Default.Chat, Icons.Default.Folder, Icons.Default.Face, Icons.Default.Extension, Icons.Default.Settings)[index], null) }, label = { Text(label) }) } } }
        ) { padding -> Box(Modifier.padding(padding).fillMaxSize()) { when(tab) { 0 -> ChatScreen(vm, { tab = it }); 1 -> KnowledgeScreen(vm); 2 -> PersonaScreen(vm); 3 -> McpScreen(vm); else -> SettingsScreen(vm) } } }
    }
}

@Composable fun AppHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) = Row(Modifier.fillMaxWidth().padding(20.dp, 18.dp, 20.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); subtitle?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }; action?.invoke() }

@Composable fun ChatScreen(vm: AssistantViewModel, navigate: (Int) -> Unit) {
    val messages by vm.messages.collectAsState(); val loading by vm.loading.collectAsState(); val persona by vm.activePersona.collectAsState()
    var input by remember { mutableStateOf("") }; var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }; var menu by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { attachments = attachments + vm.attachmentForUri(it) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let {
            val stream = java.io.ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            attachments = attachments + Attachment(
                name = "刚拍摄的照片.jpg",
                uri = "",
                kind = AttachmentKind.IMAGE,
                mimeType = "image/jpeg",
                inlineData = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            )
        }
    }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) camera.launch(null) }
    Column(Modifier.fillMaxSize()) {
        AppHeader("本地智伴", "${persona.icon} ${persona.name}") { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "菜单") }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { DropdownMenuItem(text = { Text("新建对话") }, onClick = { menu = false }); DropdownMenuItem(text = { Text("前往设置 API") }, onClick = { navigate(4); menu = false }) } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), reverseLayout = false) {
            items(messages, key = { it.id }) { message -> MessageCard(message, { vm.deleteMessage(message.id) }, { vm.deleteFrom(message.id) }) }
            if (loading) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(" 正在思考…", Modifier.padding(start = 10.dp), color = Color.Gray) } }
        }
        if (attachments.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { attachments.forEach { AssistChip(onClick = {}, label = { Text(it.name) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.clickable { attachments = attachments - it }.size(16.dp)) }) } }
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { filePicker.launch(arrayOf("text/*", "application/pdf", "application/*", "image/*")) }) { Icon(Icons.Default.AttachFile, "添加文件") }
            IconButton(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }) { Icon(Icons.Default.PhotoCamera, "拍照") }
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("问点什么…") }, maxLines = 4)
            IconButton(enabled = !loading, onClick = { vm.send(input, attachments); input = ""; attachments = emptyList() }) { Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = Blue) }
        }
        Text("发送内容会直接传给你配置的 AI 服务。", Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable private fun MessageCard(message: ChatMessage, delete: () -> Unit, deleteAfter: () -> Unit) {
    var menu by remember { mutableStateOf(false) }; val mine = message.sender == Sender.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) { Surface(color = if (mine) Blue else Color.White, contentColor = if (mine) Color.White else Ink, shape = RoundedCornerShape(18.dp), shadowElevation = if (mine) 0.dp else 1.dp, modifier = Modifier.widthIn(max = 320.dp)) { Column(Modifier.padding(14.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (mine) "你" else "智伴", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)); Box { Icon(Icons.Default.MoreHoriz, "消息操作", Modifier.size(18.dp).clickable { menu = true }); DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("删除此条") }, { delete(); menu = false }); if (mine) DropdownMenuItem({ Text("删除此条及之后") }, { deleteAfter(); menu = false }) } } }; if (message.attachments.isNotEmpty()) Text("📎 ${message.attachments.joinToString { it.name }}", style = MaterialTheme.typography.labelSmall); Text(message.content) } } }
}
