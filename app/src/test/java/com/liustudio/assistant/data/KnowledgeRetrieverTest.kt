package com.liustudio.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRetrieverTest {
    @Test
    fun `ranks the document containing the exact topic first`() {
        val documents = listOf(
            document("study", "学习计划.md", "# 每日学习计划\n先创建任务，再开始计时。暂停期间记录为休息，结束后进行自我评价。"),
            document("recipe", "菜谱.md", "# 家常菜\n西红柿炒鸡蛋需要准备鸡蛋和番茄。")
        )

        val result = KnowledgeRetriever.retrieve("学习暂停期间如何记录", documents)

        assertTrue(result.matches.isNotEmpty())
        assertEquals("study", result.matches.first().chunk.documentId)
        assertEquals(SourceKind.KNOWLEDGE, result.references.first().kind)
        assertTrue(result.context.contains("学习计划.md"))
    }

    @Test
    fun `uses headings as a retrieval signal`() {
        val documents = listOf(
            document("auth", "系统设计.md", "# 登录认证流程\n用户提交账号密码。服务端验证身份后签发访问令牌。"),
            document("other", "介绍.md", "系统提供多个普通功能和页面。")
        )

        val result = KnowledgeRetriever.retrieve("登录认证流程", documents)

        assertEquals("auth", result.matches.first().chunk.documentId)
        assertTrue(result.references.first().detail.contains("登录认证流程"))
        assertTrue(result.hasStrongMatch)
    }

    @Test
    fun `expands an adjacent chunk when the anchor is short`() {
        val longPrefix = "背景说明。".repeat(120)
        val text = "$longPrefix\n# 数据同步\n同步规则。\n${"搭档状态通过云端实时同步，网络恢复后重新订阅。".repeat(80)}"
        val document = document("sync", "同步说明.md", text)

        val result = KnowledgeRetriever.retrieve("数据同步规则", listOf(document))

        assertTrue(result.matches.first().expandedIndices.size > 1)
        assertTrue(result.matches.first().content.contains("网络恢复后重新订阅"))
    }

    @Test
    fun `marks unrelated evidence as insufficient for suppressing web search`() {
        val result = KnowledgeRetriever.retrieve(
            "量子计算最新进展",
            listOf(document("local", "旅行.txt", "周末去公园散步，并准备午餐。"))
        )

        assertFalse(result.hasStrongMatch)
        assertTrue(result.matches.isEmpty())
        assertTrue(result.references.isEmpty())
    }

    @Test
    fun `limits results from a single document`() {
        val repeated = (1..20).joinToString("\n\n") { "知识库检索步骤 $it：先分析问题，再查找相关资料。" }
        val first = document("one", "检索指南.md", repeated)
        val second = document("two", "补充说明.md", "知识库检索还应展示引用来源。")

        val result = KnowledgeRetriever.retrieve("知识库检索", listOf(first, second), topK = 4)

        assertTrue(result.matches.count { it.chunk.documentId == "one" } <= 2)
        assertTrue(result.matches.any { it.chunk.documentId == "two" })
    }

    private fun document(id: String, name: String, text: String) = KnowledgeDocument(
        id = id,
        name = name,
        uri = "content://knowledge/$id",
        text = text,
        addedAt = 1L
    )
}
