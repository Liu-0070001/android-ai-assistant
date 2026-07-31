# 本地智伴（Android）

本地优先的 Android AI 助手：OpenAI 兼容 API 对话、图片/文件/文件夹导入、本地知识库、自动联网搜索、人设以及远程 MCP 配置。

## 在 Android Studio 中运行

1. 使用 Android Studio 打开此目录 `android-ai-assistant/`。
2. 安装 Android SDK 35，并使用 JDK 17。
3. 等待 Gradle 同步完成，连接 Android 8.0（API 26）及以上真机或模拟器。
4. 点击 **Run**。在“设置”填写自己的 OpenAI 兼容 API 地址、模型和 API Key。

API Key 仅保存于设备的加密偏好设置中。应用不提供后端代理；联网搜索、AI 调用和启用的远程 MCP 会直接与相应服务通信。

> 本地知识库首版保存的是授权文件的元数据与可提取文本。PDF/DOCX 的完整解析与向量嵌入计划在下一阶段接入；当前界面和文件授权流程已就绪。
