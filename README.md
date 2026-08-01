# 本地智伴（Android）

本地优先的 Android AI 助手：OpenAI 兼容 API 对话、图片/文件/文件夹导入、本地知识库、自动联网搜索、人设以及远程 MCP 配置。

## 在 Android Studio 中运行

1. 使用 Android Studio 打开此目录 `android-ai-assistant/`。
2. 安装 Android SDK 35，并使用 JDK 17。
3. 等待 Gradle 同步完成，连接 Android 8.0（API 26）及以上真机或模拟器。
4. 点击 **Run**。在“设置”填写自己的 OpenAI 兼容 API 地址、模型和 API Key。

API Key 仅保存于设备的加密偏好设置中。应用不提供后端代理；联网搜索、AI 调用和启用的远程 MCP 会直接与相应服务通信。

## 本地知识库

- 支持导入 TXT、Markdown、JSON、CSV、PDF 和 DOCX，也可递归导入文件夹内最多 100 个受支持文件。
- 文本会在手机本地按标题、段落和自然边界分片。
- 检索融合短语与标题匹配、词频排名和字符二元组相似度，并通过 RRF 合并结果。
- 命中片段可补充相邻上下文，并限制单个文档占用过多结果。
- 回答下方显示本地文件和片段编号；本地证据不足时才调用 DuckDuckGo 摘要搜索。

扫描版 PDF 暂不支持 OCR；图片作为聊天附件发送给兼容视觉模型，不会作为知识库文本索引。
