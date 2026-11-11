package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.agent.memory.AgentMemoryHeadings
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

internal data class AgentMemorySnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val lineCount: Int,
)

internal data class AgentMemoryReadResult(
    val snapshot: AgentMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val hasMore: Boolean,
    val matchedLines: Int,
)

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    /**
     * 内容寻址替换：把 `oldText` 原地换成 `newText`。
     *
     * 相比 [ReplaceRange] 按行号定位，这种方式不依赖"现在的第几行"：
     * 分页读之后、上下文被压缩之后、别处插入过内容之后，行号都会漂移，
     * 而模型引用原文片段是天然稳定的。模型只需从最近一次读取结果里**复制**原文，
     * 不必计算行号，这是业界（Anthropic memory tool / Hermes）一致的选择。
     */
    data class ReplaceText(
        override val revision: String,
        val oldText: String,
        val newText: String,
    ) : AgentMemoryMutation

    /**
     * 按标题整节替换；`content` 为空表示删除该节。
     *
     * 适合"这一节整体重写"（典型场景：更新 `## 待办`、刷新 `## 环境`），
     * 比逐行改更贴近用户对"一节"的直觉。
     */
    data class ReplaceSection(
        override val revision: String,
        val section: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Append(
        override val revision: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Clear(
        override val revision: String,
    ) : AgentMemoryMutation
}

internal sealed interface AgentMemoryWriteResult {
    /**
     * @param changed false 表示写入后内容与之前逐字节一致（例如把一行换成同样的内容），
     * 让调用方能区分「真的写了」与「什么都没发生」。
     * @param warning 非阻断提醒（例如写入后仍存在重复标题），随结果一起回给模型。
     */
    data class Success(
        val snapshot: AgentMemorySnapshot,
        val changed: Boolean = true,
        val warning: String? = null,
    ) : AgentMemoryWriteResult

    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

/** 写前历史快照条目（`memory/history/` 下的一份上一版内容）。 */
data class AgentMemoryHistoryEntry(
    /** 文件名，同时是 [AgentMemoryStore.restoreHistory] 的 id。 */
    val id: String,
    /** 快照对应的版本前缀（前 8 位），便于和 revision 对照。 */
    val revisionPrefix: String,
    val byteSize: Int,
    /** 落盘时间（毫秒），用于展示。 */
    val savedAtMillis: Long,
)

internal class AgentMemoryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 单一 MEMORY.md 的有界、原子文件存储。 */
internal class AgentMemoryStore(
    rootDir: File,
) {
    private val memoryDir = File(rootDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))
    private val lock = Any()

    /** 进程内单调序号：同一秒内连写两次时，文件名不会互相覆盖。 */
    private var historySequence = 0

    fun snapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotLocked()
    }

    fun read(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        if (query.isNullOrBlank()) {
            page(snapshot, startLine, boundedChars)
        } else {
            search(snapshot, query.trim(), boundedChars)
        }
    }

    /**
     * 按标题读整节。
     *
     * 标题匹配忽略大小写与前导 `#`；命中多节时报错并给出**候选清单**而不是猜一个 ——
     * 猜错会让模型基于错误原文去改写，比读不到更危险。
     */
    fun readSection(
        section: String,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        val lines = snapshot.content.memoryLines()
        val headings = AgentMemoryHeadings.parse(lines)
        if (headings.isEmpty()) {
            throw AgentMemoryException(
                "MEMORY_SECTION_NOT_FOUND",
                "记忆里还没有任何标题，无法按节读取；请先 memory_get 查看全文或直接写入。",
            )
        }
        val matches = AgentMemoryHeadings.matchIndices(headings, section)
        when (matches.size) {
            0 -> throw AgentMemoryException(
                "MEMORY_SECTION_NOT_FOUND",
                "没有名为「${section.trim().trimStart('#').trim()}」的章节。可用标题：\n" +
                    AgentMemoryHeadings.catalogue(headings),
            )
            1 -> {
                val range = AgentMemoryHeadings.sectionRange(headings, matches.single(), lines.size)
                renderRange(snapshot, lines, range.first, range.last, boundedChars)
            }
            else -> throw AgentMemoryException(
                "MEMORY_SECTION_AMBIGUOUS",
                "标题「${section.trim().trimStart('#').trim()}」出现了 ${matches.size} 次" +
                    "（第 ${matches.map { headings[it].line }.joinToString("、")} 行），无法确定读哪一节。" +
                    "请先用 memory_get(start_line=…) 翻到对应行确认。",
            )
        }
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@synchronized AgentMemoryWriteResult.Conflict(current)
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.ReplaceText -> replaceText(current, mutation)
            is AgentMemoryMutation.ReplaceSection -> replaceSection(current, mutation)
            is AgentMemoryMutation.Append -> append(current, mutation.content)
            is AgentMemoryMutation.Clear -> ""
        }
        if (updated == current.content) {
            return@synchronized AgentMemoryWriteResult.Success(current, changed = false)
        }
        writeLocked(updated)
        AgentMemoryWriteResult.Success(snapshotOf(updated), warning = duplicateHeadingWarning(updated))
    }

    fun replaceAll(content: String): AgentMemorySnapshot = synchronized(lock) {
        writeLocked(content)
        snapshotOf(content)
    }

    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        writeLocked(content)
        AgentMemoryWriteResult.Success(snapshotOf(content))
    }

    private fun snapshotLocked(): AgentMemorySnapshot {
        val file = atomicFile.baseFile
        if (!file.exists()) return snapshotOf("")
        val bytes = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆文件",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件超过 1 MiB 安全上限",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        return snapshotOf(content, bytes)
    }

    private fun writeLocked(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件不能超过 1 MiB UTF-8 字节",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        // 写前留档：模型改坏记忆是高频事故，没有回滚就只能靠整机备份。
        // 尽力而为 —— 读不到旧内容（超大/读失败）也不能阻断真正的保存。
        runCatching { snapshotLocked().content }.getOrNull()?.let(::recordHistoryLocked)
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆文件",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆文件",
                cause = throwable,
            )
        }
    }

    /** 历史快照列表，最新在前。 */
    fun listHistory(): List<AgentMemoryHistoryEntry> = synchronized(lock) {
        historyFiles().mapNotNull { file ->
            val parsed = parseHistoryName(file) ?: return@mapNotNull null
            AgentMemoryHistoryEntry(
                id = file.name,
                revisionPrefix = parsed.second,
                byteSize = file.length().toInt(),
                savedAtMillis = file.lastModified(),
            )
        }
    }

    /**
     * 按 [id] 恢复历史快照。
     *
     * 恢复本身也是一次写入，因此当前内容会先被记入历史 —— 回滚同样可以再回滚。
     */
    fun restoreHistory(id: String): AgentMemorySnapshot = synchronized(lock) {
        val dir = File(memoryDir, HISTORY_DIR_NAME)
        val target = File(dir, id)
        // 只接受该目录下的直接子文件，避免 ../ 穿越到别处。
        if (target.parentFile?.canonicalPath != dir.canonicalPath || !target.isFile) {
            throw AgentMemoryException("MEMORY_HISTORY_MISSING", "历史快照不存在或已被清理")
        }
        val content = try {
            target.readText(Charsets.UTF_8)
        } catch (throwable: IOException) {
            throw AgentMemoryException("MEMORY_HISTORY_READ_FAILED", "无法读取历史快照", throwable)
        }
        writeLocked(content)
        snapshotOf(content)
    }

    /**
     * 落盘前把上一版内容写进 `history/`（FIFO 上限 [MAX_HISTORY_ENTRIES]）。
     *
     * 文件名形如 `20261102-103045-000007-a1b2c3d4.md`：时间戳保证可读排序，
     * 进程内单调序号解决"同一秒连写两次"的覆盖问题，尾部 revision 前缀便于和工具返回对照。
     */
    private fun recordHistoryLocked(previousContent: String) {
        if (previousContent.isEmpty()) return
        val bytes = previousContent.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) return
        runCatching {
            val dir = File(memoryDir, HISTORY_DIR_NAME)
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) return
            File(dir, newHistoryName(bytes)).writeText(previousContent, Charsets.UTF_8)
            val files = historyFiles()
            if (files.size > MAX_HISTORY_ENTRIES) {
                files.drop(MAX_HISTORY_ENTRIES).forEach { it.delete() }
            }
        }
    }

    private fun newHistoryName(bytes: ByteArray): String {
        historySequence += 1
        val stamp = HISTORY_STAMP.format(
            java.time.Instant.ofEpochMilli(System.currentTimeMillis())
                .atZone(java.time.ZoneId.systemDefault()),
        )
        return "$stamp-${historySequence.toString().padStart(6, '0')}-${sha256(bytes).take(8)}.md"
    }

    /** 历史文件按文件名倒序（= 时间倒序），越界与临时文件直接忽略。 */
    private fun historyFiles(): List<File> {
        val dir = File(memoryDir, HISTORY_DIR_NAME)
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".md") } ?: return emptyList()
        return files.sortedByDescending { it.name }
    }

    private fun parseHistoryName(file: File): Pair<Long, String>? {
        val name = file.name.removeSuffix(".md")
        val parts = name.split('-')
        if (parts.size < 4) return null
        return file.lastModified() to parts.last()
    }

    private fun replaceRange(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceRange,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        if (
            mutation.startLine < 1 ||
            mutation.endLine < mutation.startLine ||
            mutation.endLine > lines.size
        ) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "替换行范围无效；请重新读取记忆后再试",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    /** 按标题整节替换；空 content 删除该节。标题必须唯一命中。 */
    private fun replaceSection(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceSection,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        val headings = AgentMemoryHeadings.parse(lines)
        if (headings.isEmpty()) {
            throw AgentMemoryException(
                "MEMORY_SECTION_NOT_FOUND",
                "记忆里还没有任何标题，无法按节替换；请先用 memory_get 查看全文。",
            )
        }
        val matches = AgentMemoryHeadings.matchIndices(headings, mutation.section)
        when (matches.size) {
            0 -> throw AgentMemoryException(
                "MEMORY_SECTION_NOT_FOUND",
                "没有名为「${mutation.section.trim().trimStart('#').trim()}」的章节。可用标题：\n" +
                    AgentMemoryHeadings.catalogue(headings),
            )
            1 -> Unit
            else -> throw AgentMemoryException(
                "MEMORY_SECTION_AMBIGUOUS",
                "标题「${mutation.section.trim().trimStart('#').trim()}」出现了 ${matches.size} 次" +
                    "（第 ${matches.map { headings[it].line }.joinToString("、")} 行），无法确定替换哪一节。" +
                    "请先用 mode=replace_text 逐处改写，或把重复小节的标题区分开。",
            )
        }
        val range = AgentMemoryHeadings.sectionRange(headings, matches.single(), lines.size)
        val replacement = mutation.content.memoryLines()
        val startIndex = range.first - 1
        repeat(range.last - range.first + 1) { lines.removeAt(startIndex) }
        if (replacement.isNotEmpty()) lines.addAll(startIndex, replacement)
        return lines.joinToString("\n")
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        rejectDuplicateHeadings(snapshot.content, content)
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
    }

    /**
     * 内容寻址替换：`oldText` 必须在全文**恰好出现一次**。
     *
     * 0 次 → 告诉模型"找不到"，并附可用标题清单（多半是它凭记忆写的原文，或已被别处改过）；
     * 多次 → 列出行号要求加长 oldText。两种失败都**不改文件**，
     * 宁可让模型重读一次，也不猜"它大概想改哪一处"。
     */
    private fun replaceText(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceText,
    ): String {
        val oldText = mutation.oldText
        if (oldText.isEmpty()) {
            throw AgentMemoryException("MEMORY_TEXT_EMPTY", "old_text 不能为空；删除整节请用 mode=replace_section")
        }
        val content = snapshot.content
        val positions = mutableListOf<Int>()
        var cursor = content.indexOf(oldText)
        while (cursor >= 0) {
            positions += cursor
            cursor = content.indexOf(oldText, cursor + 1)
        }
        if (positions.isEmpty()) {
            val headings = AgentMemoryHeadings.parse(content.memoryLines())
            throw AgentMemoryException(
                code = "MEMORY_TEXT_NOT_FOUND",
                message = "记忆里找不到 old_text 这段原文（可能已被改写，或与原文有细微差异）。" +
                    "请先用 memory_get 复制当前原文再重试，不要凭记忆构造。\n" +
                    "可用标题（可用 mode=replace_section 按标题整节替换）：\n" +
                    AgentMemoryHeadings.catalogue(headings),
            )
        }
        if (positions.size > 1) {
            val lines = positions.map { offset ->
                content.take(offset).count { it == '\n' } + 1
            }
            throw AgentMemoryException(
                code = "MEMORY_TEXT_AMBIGUOUS",
                message = "old_text 在记忆里出现了 ${positions.size} 次（第 ${lines.joinToString("、")} 行），" +
                    "无法确定改哪一处。请把 old_text 加长到能唯一定位（连同前后各一两行一起复制）。",
            )
        }
        val start = positions.single()
        return content.replaceRange(start, start + oldText.length, mutation.newText)
    }

    /**
     * 拒绝「追加出同名标题」。
     *
     * 这不是洁癖：注入侧只取**第一个** `# 核心记忆`，第二个同名标题下的内容会被静默丢弃；
     * `## 待办` 之类的重名则让「按标题定位」变成不确定行为。
     * 想更新已有小节时正确做法是 replace_text / replace_section，所以这里直接拒绝并给出指引。
     */
    private fun rejectDuplicateHeadings(existing: String, incoming: String) {
        val existingTitles = AgentMemoryHeadings.parse(existing.memoryLines())
        if (existingTitles.isEmpty()) return
        val incomingHeadings = AgentMemoryHeadings.parse(incoming.memoryLines())
        if (incomingHeadings.isEmpty()) return
        val clashes = incomingHeadings
            .filter { candidate -> existingTitles.any { it.title.equals(candidate.title, ignoreCase = true) } }
        if (clashes.isEmpty()) return
        throw AgentMemoryException(
            code = "MEMORY_HEADING_DUPLICATE",
            message = "追加内容里的标题与已有小节重名：${clashes.joinToString("、") { it.rendered }}。" +
                "同名小节会让按标题定位变得不确定，而重复的「# 核心记忆」会让后一份内容静默丢失。" +
                "要更新已有小节请用 mode=replace_text（引用原文片段）或 mode=replace_section；" +
                "确实是新内容请换一个标题。\n现有标题：\n" +
                AgentMemoryHeadings.catalogue(existingTitles),
        )
    }

    /** 写入后仍有重复标题时给一句提醒（不阻断：可能正在修历史遗留的重复）。 */
    private fun duplicateHeadingWarning(content: String): String? {
        val headings = AgentMemoryHeadings.parse(content.memoryLines())
        if (headings.isEmpty()) return null
        val duplicates = headings.groupBy { it.title.lowercase() }
            .filterValues { it.size > 1 }
            .values
            .map { group -> group.first().rendered }
        if (duplicates.isEmpty()) return null
        return "记忆里仍有重复标题：${duplicates.joinToString("、")}。" +
            "重复的「# 核心记忆」只有第一份会被注入，建议合并或改名。"
    }

    private fun page(
        snapshot: AgentMemorySnapshot,
        requestedStartLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        return renderRange(snapshot, lines, requestedStartLine, lines.size, maxChars)
    }

    /** 把 [startLine]..[endLine]（1 起、闭区间）渲染成带行号的文本，按 [maxChars] 截断。 */
    private fun renderRange(
        snapshot: AgentMemorySnapshot,
        lines: List<String>,
        startLine: Int,
        endLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val startIndex = (startLine - 1).coerceIn(0, lines.size)
        val hardEnd = endLine.coerceAtMost(lines.size)
        if (startIndex >= hardEnd) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var cursor = startIndex
        while (cursor < hardEnd) {
            val rendered = "${cursor + 1}: ${lines[cursor]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
            cursor++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = cursor,
            hasMore = cursor < hardEnd,
            matchedLines = cursor - startIndex,
        )
    }

    private fun search(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        val matched = lines.indices.filter { index ->
            lines[index].contains(query, ignoreCase = true)
        }
        val included = linkedSetOf<Int>()
        matched.forEach { index ->
            for (candidate in (index - SEARCH_CONTEXT_LINES)..(index + SEARCH_CONTEXT_LINES)) {
                if (candidate in lines.indices) included += candidate
            }
        }
        val output = StringBuilder()
        var lastIncluded: Int? = null
        var renderedCount = 0
        for (index in included) {
            val rendered = "${index + 1}: ${lines[index]}"
            val gap = when {
                output.isEmpty() -> ""
                lastIncluded != null && index > lastIncluded + 1 -> "\n…\n"
                else -> "\n"
            }
            if (output.isNotEmpty() && output.length + gap.length + rendered.length > maxChars) break
            output.append(gap).append(rendered.take(maxChars - output.length - gap.length))
            lastIncluded = index
            renderedCount++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = included.firstOrNull()?.plus(1),
            endLine = lastIncluded?.plus(1),
            hasMore = renderedCount < included.size,
            matchedLines = matched.size,
        )
    }

    private fun snapshotOf(
        content: String,
        bytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(bytes),
        byteSize = bytes.size,
        lineCount = content.memoryLines().size,
    )

    private fun String.memoryLines(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500
        /** 写前历史快照保留份数（FIFO 滚动删除）。 */
        const val MAX_HISTORY_ENTRIES = 10
        /** `mode="clear"` 只接受这个确认串，避免一次误调用清空全部记忆。 */
        const val CLEAR_CONFIRMATION = "DELETE_ALL"
        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        private const val HISTORY_DIR_NAME = "history"
        private const val DEFAULT_START_LINE = 1
        private const val SEARCH_CONTEXT_LINES = 1
        private val HISTORY_STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

internal object AgentMemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(context.applicationContext.filesDir)
        }
    }

    fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars)
    }

    fun readSection(
        section: String,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.readSection(section, maxChars)
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    /**
     * 整文件覆盖，但仅在 revision 未变时执行。
     *
     * 设置页的手动保存走这里：编辑期间模型也可能写入记忆，无条件覆盖会把它**静默吞掉**，
     * 用户还会以为"我保存的是最新版"。
     */
    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult {
        ensureInitialized()
        return store.replaceAllIfRevision(content, revision)
    }

    fun listHistory(): List<AgentMemoryHistoryEntry> {
        ensureInitialized()
        return store.listHistory()
    }

    fun restoreHistory(id: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.restoreHistory(id)
    }

    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.memoryEnabledFlow()

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().memoryEnabled

    suspend fun setEnabled(enabled: Boolean) = SettingsDataStore.setMemoryEnabled(enabled)

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }
}
