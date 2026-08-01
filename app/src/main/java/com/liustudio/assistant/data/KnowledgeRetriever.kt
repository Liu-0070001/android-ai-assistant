package com.liustudio.assistant.data

import kotlin.math.ln

internal data class KnowledgeChunk(
    val documentId: String,
    val documentName: String,
    val documentUri: String,
    val index: Int,
    val section: String,
    val content: String
)

internal data class KnowledgeMatch(
    val chunk: KnowledgeChunk,
    val content: String,
    val score: Double,
    val expandedIndices: List<Int>
)

internal data class KnowledgeRetrieval(
    val matches: List<KnowledgeMatch>,
    val references: List<MessageSource>,
    val context: String,
    val hasStrongMatch: Boolean
)

internal object KnowledgeRetriever {
    private const val targetChunkSize = 900
    private const val overlapSize = 140
    private const val rrfK = 60.0

    fun chunkDocument(document: KnowledgeDocument): List<KnowledgeChunk> {
        val text = document.text.trim()
        if (text.isBlank() || text.startsWith("[无法解析")) return emptyList()

        val chunks = mutableListOf<KnowledgeChunk>()
        var section = ""
        val buffer = StringBuilder()

        fun flush() {
            val value = buffer.toString().trim()
            if (value.isBlank()) return
            var start = 0
            while (start < value.length) {
                val preferredEnd = (start + targetChunkSize).coerceAtMost(value.length)
                val end = if (preferredEnd == value.length) {
                    preferredEnd
                } else {
                    findBoundary(value, start, preferredEnd)
                }
                val content = value.substring(start, end).trim()
                if (content.isNotBlank()) {
                    chunks += KnowledgeChunk(
                        documentId = document.id,
                        documentName = document.name,
                        documentUri = document.uri,
                        index = chunks.size,
                        section = section,
                        content = content
                    )
                }
                if (end >= value.length) break
                start = (end - overlapSize).coerceAtLeast(start + 1)
            }
            buffer.clear()
        }

        text.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) {
                if (buffer.length >= targetChunkSize / 2) flush()
            } else if (isHeading(line)) {
                flush()
                section = line.trimStart('#').trim()
            } else {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(line)
                if (buffer.length >= targetChunkSize * 2) flush()
            }
        }
        flush()
        return chunks
    }

    fun retrieve(query: String, documents: List<KnowledgeDocument>, topK: Int = 4): KnowledgeRetrieval {
        val chunks = documents.flatMap(::chunkDocument)
        val queryTerms = terms(query)
        if (chunks.isEmpty() || queryTerms.isEmpty()) return emptyRetrieval()

        val phraseRanking = rank(chunks, minimumScore = 1.0) { chunk -> phraseScore(query, queryTerms, chunk) }
        val termRanking = rank(chunks, minimumScore = 0.35) { chunk -> termScore(queryTerms, chunk) }
        val bigramRanking = rank(chunks, minimumScore = 0.12) { chunk -> bigramScore(query, chunk) }
        val rankings = listOf(phraseRanking, termRanking, bigramRanking)

        val fused = mutableMapOf<KnowledgeChunk, Double>()
        rankings.forEachIndexed { routeIndex, ranking ->
            val weight = listOf(1.25, 1.0, 0.8)[routeIndex]
            ranking.take(30).forEachIndexed { index, scored ->
                fused[scored.first] = fused.getOrDefault(scored.first, 0.0) + weight / (rrfK + index + 1)
            }
        }

        val selected = mutableListOf<Pair<KnowledgeChunk, Double>>()
        val documentCounts = mutableMapOf<String, Int>()
        fused.entries.sortedByDescending { it.value }.forEach { entry ->
            if (selected.size >= topK) return@forEach
            val count = documentCounts.getOrDefault(entry.key.documentId, 0)
            if (count >= 2) return@forEach
            selected += entry.key to entry.value
            documentCounts[entry.key.documentId] = count + 1
        }

        val matches = selected.map { (chunk, score) -> expand(chunk, chunks, queryTerms, score) }
        val references = matches.map { match ->
            MessageSource(
                title = match.chunk.documentName,
                detail = buildString {
                    append("片段 ${match.chunk.index + 1}")
                    if (match.chunk.section.isNotBlank()) append(" · ${match.chunk.section}")
                },
                uri = match.chunk.documentUri,
                kind = SourceKind.KNOWLEDGE
            )
        }.distinctBy { "${it.title}|${it.detail}" }
        val strongestSignals = listOf(
            phraseRanking.firstOrNull()?.second ?: 0.0,
            termRanking.firstOrNull()?.second ?: 0.0,
            bigramRanking.firstOrNull()?.second ?: 0.0
        )
        val hasStrongMatch = strongestSignals[0] >= 2.5 || strongestSignals[1] >= 2.0 || strongestSignals[2] >= 0.34
        val context = matches.joinToString("\n\n") { match ->
            buildString {
                append("【${match.chunk.documentName} · 片段 ${match.chunk.index + 1}")
                if (match.chunk.section.isNotBlank()) append(" · ${match.chunk.section}")
                append("】\n${match.content}")
            }
        }
        return KnowledgeRetrieval(matches, references, context, hasStrongMatch)
    }

    private fun rank(
        chunks: List<KnowledgeChunk>,
        minimumScore: Double,
        scorer: (KnowledgeChunk) -> Double
    ): List<Pair<KnowledgeChunk, Double>> =
        chunks.map { it to scorer(it) }.filter { it.second >= minimumScore }.sortedByDescending { it.second }

    private fun phraseScore(query: String, queryTerms: Set<String>, chunk: KnowledgeChunk): Double {
        val content = chunk.content.lowercase()
        val normalizedQuery = query.lowercase().trim()
        var score = if (normalizedQuery.length >= 4 && content.contains(normalizedQuery)) 8.0 else 0.0
        queryTerms.forEach { term ->
            if (chunk.section.lowercase().contains(term)) score += 3.0
            if (content.contains(term)) score += 1.0
        }
        return score
    }

    private fun termScore(queryTerms: Set<String>, chunk: KnowledgeChunk): Double {
        val content = chunk.content.lowercase()
        val lengthPenalty = 1.0 + ln((content.length + 50).toDouble())
        return queryTerms.sumOf { term ->
            val count = Regex(Regex.escape(term)).findAll(content).count().coerceAtMost(6)
            count * (1.0 + ln(term.length.toDouble() + 1.0))
        } / lengthPenalty
    }

    private fun bigramScore(query: String, chunk: KnowledgeChunk): Double {
        val queryBigrams = bigrams(query)
        if (queryBigrams.isEmpty()) return 0.0
        val chunkBigrams = bigrams(chunk.section + " " + chunk.content)
        return queryBigrams.count { it in chunkBigrams }.toDouble() / queryBigrams.size
    }

    private fun expand(
        chunk: KnowledgeChunk,
        allChunks: List<KnowledgeChunk>,
        queryTerms: Set<String>,
        score: Double
    ): KnowledgeMatch {
        val sameDocument = allChunks.filter { it.documentId == chunk.documentId }.associateBy { it.index }
        val indices = mutableListOf(chunk.index)
        listOf(chunk.index - 1, chunk.index + 1).forEach { index ->
            val neighbor = sameDocument[index] ?: return@forEach
            val relevant = queryTerms.any { term ->
                neighbor.content.contains(term, ignoreCase = true) || neighbor.section.contains(term, ignoreCase = true)
            }
            if (relevant || chunk.content.length < 500) indices += index
        }
        val ordered = indices.distinct().sorted()
        val content = ordered.mapNotNull(sameDocument::get).joinToString("\n") { it.content }.take(2_600)
        return KnowledgeMatch(chunk, content, score, ordered)
    }

    private fun terms(value: String): Set<String> {
        val normalized = value.lowercase()
        val words = Regex("[\\p{L}\\p{N}]{2,}").findAll(normalized).map { it.value }.toMutableSet()
        val hanRuns = Regex("[\\p{IsHan}]{2,}").findAll(normalized).map { it.value }
        hanRuns.forEach { run ->
            if (run.length <= 6) words += run
            for (size in 2..minOf(4, run.length)) {
                for (start in 0..run.length - size) words += run.substring(start, start + size)
            }
        }
        return words.filterNot { it in stopWords }.toSet()
    }

    private fun bigrams(value: String): Set<String> {
        val compact = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        if (compact.length < 2) return emptySet()
        return (0 until compact.length - 1).map { compact.substring(it, it + 2) }.toSet()
    }

    private fun isHeading(line: String): Boolean =
        line.startsWith("#") ||
            (line.length <= 48 && (line.endsWith("：") || Regex("^第[一二三四五六七八九十0-9]+[章节部分]").containsMatchIn(line)))

    private fun findBoundary(text: String, start: Int, preferredEnd: Int): Int {
        val minimum = (start + targetChunkSize / 2).coerceAtMost(preferredEnd)
        for (index in preferredEnd downTo minimum) {
            if (text[index - 1] in setOf('\n', '。', '！', '？', '；')) return index
        }
        return preferredEnd
    }

    private fun emptyRetrieval() = KnowledgeRetrieval(emptyList(), emptyList(), "", false)

    private val stopWords = setOf(
        "这个", "那个", "什么", "怎么", "如何", "为什么", "可以", "是否", "以及", "一个", "我们", "你们", "他们", "进行", "相关", "请问"
    )
}
