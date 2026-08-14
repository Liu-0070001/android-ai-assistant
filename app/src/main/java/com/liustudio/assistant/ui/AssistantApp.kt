package com.liustudio.assistant.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.text.method.LinkMovementMethod
import android.util.Base64
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liustudio.assistant.data.AssistantViewModel
import com.liustudio.assistant.data.Attachment
import com.liustudio.assistant.data.AttachmentKind
import com.liustudio.assistant.data.ChatMessage
import com.liustudio.assistant.data.Sender
import com.liustudio.assistant.data.SourceKind
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.io.ByteArrayOutputStream

val Blue = Color(0xFF275DAD)

private data class AppDestination(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val destinations = listOf(
    AppDestination("对话", Icons.Default.ChatBubbleOutline),
    AppDestination("知识库", Icons.Default.Folder),
    AppDestination("人设", Icons.Default.Face),
    AppDestination("扩展", Icons.Default.Extension),
    AppDestination("设置", Icons.Default.Settings)
)

@Composable
fun AssistantApp(vm: AssistantViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }

    LocalAssistantTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    destinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label
                                )
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                when (selectedTab) {
                    0 -> ChatScreen(vm, onNavigate = { selectedTab = it })
                    1 -> KnowledgeScreen(vm)
                    2 -> PersonaScreen(vm)
                    3 -> McpScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        action?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AssistantViewModel, onNavigate: (Int) -> Unit) {
    val messages by vm.messages.collectAsState()
    val loading by vm.loading.collectAsState()
    val persona by vm.activePersona.collectAsState()
    var input by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var menuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { attachments = attachments + vm.attachmentForUri(it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let {
            val stream = ByteArrayOutputStream()
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
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(null)
    }

    LaunchedEffect(messages.size, loading) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex + if (loading) 1 else 0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(
            title = "本地智伴",
            subtitle = "${persona.icon} ${persona.name}"
        ) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "对话菜单")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("新建对话") },
                        onClick = {
                            vm.clearConversation()
                            menuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("配置 AI 服务") },
                        onClick = {
                            onNavigate(4)
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    onDelete = { vm.deleteMessage(message.id) },
                    onDeleteFrom = { vm.deleteFrom(message.id) }
                )
            }
            if (messages.size == 1 && !loading) {
                item {
                    SuggestionRow(onSelect = { input = it })
                }
            }
            if (loading) {
                item { ThinkingIndicator() }
            }
        }

        Composer(
            input = input,
            attachments = attachments,
            loading = loading,
            onInputChange = { input = it },
            onRemoveAttachment = { attachment -> attachments = attachments - attachment },
            onPickFile = {
                filePicker.launch(arrayOf("text/*", "application/pdf", "application/*", "image/*"))
            },
            onTakePhoto = { requestCamera.launch(Manifest.permission.CAMERA) },
            onSend = {
                vm.send(input.trim(), attachments)
                input = ""
                attachments = emptyList()
            }
        )
        if (attachments.any { it.kind == AttachmentKind.IMAGE }) {
            Text(
                text = "发送后将先由识图模型提取题目，再由讲题人引导讲解。",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SuggestionRow(onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(listOf("出一道高数题让我练手", "讲讲等价无穷小的适用条件", "总结一道考研真题的思维点")) { suggestion ->
            AssistChip(
                onClick = { onSelect(suggestion) },
                label = { Text(suggestion) }
            )
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = "正在识题与讲解…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun Composer(
    input: String,
    attachments: List<Attachment>,
    loading: Boolean,
    onInputChange: (String) -> Unit,
    onRemoveAttachment: (Attachment) -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachments, key = { "${it.uri}:${it.name}" }) { attachment ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = attachment.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp)
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "移除 ${attachment.name}",
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { onRemoveAttachment(attachment) }
                                )
                            }
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onPickFile) {
                    Icon(Icons.Default.AttachFile, contentDescription = "添加文件")
                }
                IconButton(onClick = onTakePhoto) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "拍照")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息") },
                    maxLines = 5,
                    shape = RoundedCornerShape(8.dp)
                )
                IconButton(
                    enabled = !loading && (input.isNotBlank() || attachments.isNotEmpty()),
                    onClick = onSend
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (!loading && (input.isNotBlank() || attachments.isNotEmpty())) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onDelete: () -> Unit,
    onDeleteFrom: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isMine = message.sender == Sender.USER
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(8.dp),
            tonalElevation = if (isMine) 0.dp else 1.dp,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isMine) "你" else "智伴",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("message", message.content))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制消息",
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "消息操作",
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("删除此条") },
                                onClick = {
                                    onDelete()
                                    menuExpanded = false
                                }
                            )
                            if (isMine) {
                                DropdownMenuItem(
                                    text = { Text("删除此条及之后") },
                                    onClick = {
                                        onDeleteFrom()
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (message.attachments.isNotEmpty()) {
                    Text(
                        text = message.attachments.joinToString(prefix = "附件：") { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMine) {
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                RichMessageText(message.content)

                if (message.sources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "参考来源",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    message.sources.forEach { source ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .then(
                                    if (source.kind == SourceKind.WEB && source.uri.isNotBlank()) {
                                        Modifier.clickable { uriHandler.openUri(source.uri) }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            Text(
                                text = source.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (source.detail.isNotBlank()) {
                                Text(
                                    text = source.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichMessageText(content: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textColor = LocalContentColor.current.toArgb()
    val inlineFormulaSize = with(density) { 16.sp.toPx() }
    val blockFormulaSize = with(density) { 18.sp.toPx() }
    val markwon = remember(context, inlineFormulaSize, blockFormulaSize, textColor) {
        Markwon.builder(context)
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(
                JLatexMathPlugin.create(inlineFormulaSize, blockFormulaSize) { builder ->
                    builder.inlinesEnabled(true)
                    builder.theme().blockFitCanvas(false)
                    builder.theme().textColor(textColor)
                }
            )
            .build()
    }
    val blocks = remember(content) { parseMessageBlocks(content) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            if (block.isFormula) {
                FormulaLine(
                    latex = block.content,
                    markwon = markwon,
                    textColor = textColor
                )
            } else {
                MarkdownText(
                    markdown = normalizeLatexDelimiters(block.content),
                    markwon = markwon,
                    textColor = textColor
                )
            }
        }
    }
}

private data class MessageRenderBlock(val content: String, val isFormula: Boolean)

private val blockFormulaPattern = Regex(
    """\\\[(.*?)\\\]|\$\$(.*?)\$\$""",
    RegexOption.DOT_MATCHES_ALL
)

private fun parseMessageBlocks(content: String): List<MessageRenderBlock> {
    val blocks = mutableListOf<MessageRenderBlock>()
    var start = 0
    blockFormulaPattern.findAll(content).forEach { match ->
        if (match.range.first > start) {
            content.substring(start, match.range.first)
                .takeIf { it.isNotBlank() }
                ?.let { blocks += MessageRenderBlock(it.trim('\n'), false) }
        }
        val latex = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
        if (latex.isNotBlank()) blocks += MessageRenderBlock(latex, true)
        start = match.range.last + 1
    }
    if (start < content.length) {
        content.substring(start)
            .takeIf { it.isNotBlank() }
            ?.let { blocks += MessageRenderBlock(it.trim('\n'), false) }
    }
    return blocks.ifEmpty { listOf(MessageRenderBlock(content, false)) }
}

@Composable
private fun MarkdownText(markdown: String, markwon: Markwon, textColor: Int) {
    AndroidView(
        factory = { viewContext ->
            TextView(viewContext).apply {
                textSize = 16f
                includeFontPadding = false
                setTextColor(textColor)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            markwon.setMarkdown(textView, markdown)
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun FormulaLine(latex: String, markwon: Markwon, textColor: Int) {
    AndroidView(
        factory = { viewContext ->
            HorizontalScrollView(viewContext).apply {
                isFillViewport = false
                isHorizontalScrollBarEnabled = true
                addView(
                    TextView(viewContext).apply {
                        textSize = 18f
                        includeFontPadding = false
                        setTextColor(textColor)
                        setHorizontallyScrolling(true)
                    },
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        },
        update = { scrollView ->
            val textView = scrollView.getChildAt(0) as TextView
            textView.setTextColor(textColor)
            markwon.setMarkdown(textView, "\$\$\n$latex\n\$\$")
        },
        modifier = Modifier.fillMaxWidth()
    )
}

private fun normalizeLatexDelimiters(content: String): String = content
    .replace(Regex("""\\\[\s*(.*?)\s*\\]""", RegexOption.DOT_MATCHES_ALL)) { match ->
        "\$\$\n${match.groupValues[1]}\n\$\$"
    }
    .replace(Regex("""\\\((.*?)\\\)""")) { match ->
        "\$\$${match.groupValues[1]}\$\$"
    }
    .replace(Regex("""(?<!\$)\$(?!\$)([^$\n]+?)\$(?!\$)""")) { match ->
        "\$\$${match.groupValues[1]}\$\$"
    }
