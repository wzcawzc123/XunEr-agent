package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.agent.memory.MemoryText
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /** 按标题读取时回显命中的标题（含 `#` 前缀），便于调用方确认取到的是哪一节。 */
    val section: String? = null,
)

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    /** 按标题整节替换；content 为空表示删除该节。 */
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
     * changed=false 表示写入后内容与之前逐字节一致（例如 append 了空内容），
     * 让调用方能把"真的写了"与"什么都没发生"区分开，而不是都看到 ok=true。
     */
    data class Success(
        val snapshot: AgentMemorySnapshot,
        val changed: Boolean = true,
    ) : AgentMemoryWriteResult

    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

/** 写前历史快照条目（`memory/history/` 下的一份上一版内容）。 */
internal data class AgentMemoryHistoryEntry(
    /** 文件名，同时是 [MemoryRepository.restoreHistory] 的 id。 */
    val id: String,
    val savedAtMillis: Long,
    val byteSize: Int,
)

/** 长期/短期记忆仓库的统一读写接口（memory_get/write 的 target 路由用）。 */
internal interface MemoryRepository {
    fun snapshot(): AgentMemorySnapshot

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult

    fun readSection(
        section: String,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult

    fun replaceAll(content: String): AgentMemorySnapshot

    /** 整文件覆盖写，但仅当当前 revision 仍等于 [revision] 时执行（UI 编辑防吞掉 Agent 写入）。 */
    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult

    /** 写前历史快照列表，最新在前。 */
    fun listHistory(): List<AgentMemoryHistoryEntry>

    /** 按 [id] 恢复历史快照；恢复前会把当前内容再记入历史（回滚也可回滚）。 */
    fun restoreHistory(id: String): AgentMemorySnapshot
}

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
    private val historyDir = File(memoryDir, HISTORY_DIR_NAME)
    private val lock = Any()

    /** 历史文件名内嵌的进程内单调序号：同秒多次写入时既保证不互相覆盖，也保证字典序=时间序。 */
    private var historySeq = 0L

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
     * 按标题读取整节。标题匹配忽略大小写与 `#` 前缀；匹配不到或匹配到多节时抛出带可用标题清单的错误，
     * 避免调用方拿着"空结果"却不知道为什么。
     */
    fun readSection(
        section: String,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        val lines = snapshot.content.memoryLines()
        val match = resolveSection(lines, section)
        renderRange(snapshot, match.heading.startIndex, match.heading.endIndex, boundedChars, match.heading.raw)
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@synchronized AgentMemoryWriteResult.Conflict(current)
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.ReplaceSection -> replaceSection(current, mutation)
            is AgentMemoryMutation.Append -> append(current, mutation.content)
            is AgentMemoryMutation.Clear -> ""
        }
        recordHistoryLocked(current.content)
        writeLocked(updated)
        AgentMemoryWriteResult.Success(snapshotOf(updated), changed = updated != current.content)
    }

    fun replaceAll(content: String): AgentMemorySnapshot = synchronized(lock) {
        recordHistoryLocked(snapshotLocked().content)
        writeLocked(content)
        snapshotOf(content)
    }

    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        recordHistoryLocked(current.content)
        writeLocked(content)
        AgentMemoryWriteResult.Success(snapshotOf(content), changed = content != current.content)
    }

    fun listHistory(): List<AgentMemoryHistoryEntry> = synchronized(lock) {
        historyFiles()
            .sortedWith(HISTORY_ORDER)
            .map { file ->
                AgentMemoryHistoryEntry(
                    id = file.name,
                    savedAtMillis = file.lastModified(),
                    byteSize = file.length().toInt(),
                )
            }
    }

    fun restoreHistory(id: String): AgentMemorySnapshot = synchronized(lock) {
        if (!HISTORY_ID.matches(id)) {
            throw AgentMemoryException(
                code = "MEMORY_HISTORY_INVALID",
                message = "非法的历史快照 id：$id",
            )
        }
        val file = File(historyDir, id)
        val bytes = try {
            file.readBytes()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_HISTORY_NOT_FOUND",
                message = "历史快照不存在或不可读：$id",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "历史快照超过 1 MiB 安全上限",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        // 恢复本身也留快照：回滚错了还能再回滚。
        recordHistoryLocked(snapshotLocked().content)
        writeLocked(content)
        snapshotOf(content)
    }

    /**
     * 落盘前把上一版内容写进 `history/`（FIFO 上限 [MAX_HISTORY_ENTRIES]）。
     * 历史是尽力而为的安全网：写不进去不能阻断真正的记忆保存。
     */
    private fun recordHistoryLocked(previousContent: String) {
        if (previousContent.isEmpty()) return
        try {
            if (!historyDir.exists() && !historyDir.mkdirs() && !historyDir.isDirectory) return
            // SimpleDateFormat 非线程安全，且长期/短期两个 store 各持自己的 lock，必须每次新建。
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val rev8 = sha256(previousContent.toByteArray(Charsets.UTF_8)).take(8)
            // 同一秒内可能连续写多次：内嵌单调序号保证文件名唯一（同内容也不会互相覆盖），
            // 且保持字典序即时间序（此前只用秒级时间戳+rev8，同秒快照的先后是哈希序=随机序）。
            val name = "$stamp-${historySeq.toString().padStart(6, '0')}-$rev8.md"
            historySeq++
            File(historyDir, name).writeBytes(previousContent.toByteArray(Charsets.UTF_8))
            historyFiles()
                .sortedWith(HISTORY_ORDER)
                .drop(MAX_HISTORY_ENTRIES)
                .forEach { it.delete() }
        } catch (_: IOException) {
            // 忽略：历史写失败不影响主写入。
        } catch (_: SecurityException) {
            // 同上。
        }
    }

    private fun historyFiles(): List<File> =
        historyDir.listFiles { file -> file.isFile && HISTORY_ID.matches(file.name) }.orEmpty().toList()

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
                message = "替换行范围无效（start/end 需 1 ≤ start ≤ end ≤ ${lines.size}）。" +
                    "请先用 memory_get(section=…) 或 memory_get(start_line=1, max_chars=32000) 预览实际行号后再试；" +
                    "若只想改整块内容，改用 mode=\"replace_section\" + section 更稳，不必手算行号。",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun replaceSection(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceSection,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        val resolved = resolveSection(lines, mutation.section)
        val heading = resolved.heading
        val replacement = mutation.content.memoryLines()
        // 空 content = 删除整节（文档化的用法），所以护栏只在"有内容却没带一级标题"时生效。
        if (heading.level == 1 && replacement.isNotEmpty() && replacement.none { headingLevel(it) == 1 }) {
            throw AgentMemoryException(
                code = "MEMORY_SECTION_HEADING_REQUIRED",
                message = "替换一级章节时，content 必须以同行的一级标题开头（例如 \"${heading.raw}\"）；" +
                    "只替换正文请改用 mode=\"replace_range\"，删除整节请传空 content。",
            )
        }
        lines.subList(heading.startIndex, heading.endIndex + 1).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(heading.startIndex, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        val incomingLines = content.memoryLines()
        val incomingLevelOne = incomingLines.filter { headingLevel(it) == 1 }.map { it.trimEnd() }
        if (incomingLevelOne.isNotEmpty()) {
            val existing = snapshot.content.memoryLines()
                .filter { headingLevel(it) == 1 }
                .map { it.trimEnd() }
                .toSet()
            val duplicated = incomingLevelOne.filter { it in existing }
            if (duplicated.isNotEmpty()) {
                throw AgentMemoryException(
                    code = "MEMORY_DUPLICATE_HEADING",
                    message = "追加内容包含已存在的一级标题：${duplicated.joinToString("、")}。" +
                        "请改用 mode=\"replace_section\" 替换该章节，或换一个未使用过的标题。" +
                        "（重复的 \"${MemoryText.CORE_HEADING}\" 会让新增内容被排除在自动注入的核心记忆之外。）",
                )
            }
        }
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
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
        val startIndex = (requestedStartLine - 1).coerceIn(0, lines.size)
        if (startIndex >= lines.size) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var endIndex = startIndex
        while (endIndex < lines.size) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(MemoryText.safeTake(rendered, maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < lines.size,
            matchedLines = endIndex - startIndex,
        )
    }

    private fun renderRange(
        snapshot: AgentMemorySnapshot,
        fromIndex: Int,
        toIndex: Int,
        maxChars: Int,
        section: String?,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (fromIndex > toIndex || fromIndex !in lines.indices || toIndex !in lines.indices) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0, section)
        }
        val output = StringBuilder()
        var endIndex = fromIndex
        while (endIndex <= toIndex) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(MemoryText.safeTake(rendered, maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = fromIndex + 1,
            endLine = endIndex,
            hasMore = endIndex <= toIndex,
            matchedLines = toIndex - fromIndex + 1,
            section = section,
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
        val all = headings(lines)
        val included = linkedSetOf<Int>()
        matched.forEach { index ->
            val owner = all.lastOrNull { index >= it.startIndex && index <= it.endIndex }
            val expandable = owner != null && (owner.endIndex - owner.startIndex + 1) <= MAX_SECTION_EXPANSION_LINES
            val range = if (owner != null && expandable) {
                owner.startIndex..owner.endIndex
            } else {
                (index - SEARCH_WINDOW_LINES).coerceAtLeast(0)..(index + SEARCH_WINDOW_LINES).coerceAtMost(lines.lastIndex)
            }
            range.forEach { candidate ->
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
            output.append(gap).append(MemoryText.safeTake(rendered, maxChars - output.length - gap.length))
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

    private data class MemoryHeading(
        val startIndex: Int,
        val endIndex: Int,
        val level: Int,
        val raw: String,
    ) {
        val title: String get() = headingTitle(raw)
    }

    private data class ResolvedSection(val heading: MemoryHeading)

    /** 按标题层级切出所有章节（`#` 的区间到下一个同级或更高级标题为止）。 */
    private fun headings(lines: List<String>): List<MemoryHeading> {
        val result = mutableListOf<MemoryHeading>()
        var index = 0
        while (index < lines.size) {
            val level = headingLevel(lines[index])
            if (level == 0) {
                index++
                continue
            }
            var end = index + 1
            while (end < lines.size) {
                val next = headingLevel(lines[end])
                if (next in 1..level) break
                end++
            }
            result += MemoryHeading(index, end - 1, level, lines[index])
            index++
        }
        return result
    }

    private fun resolveSection(lines: List<String>, section: String): ResolvedSection {
        val target = headingTitle(section.trim())
        if (target.isEmpty()) {
            throw AgentMemoryException(
                code = "MEMORY_SECTION_INVALID",
                message = "section 不能为空；可用标题：\n${availableHeadings(lines)}",
            )
        }
        val all = headings(lines)
        if (all.isEmpty()) {
            throw AgentMemoryException(
                code = "MEMORY_SECTION_NOT_FOUND",
                message = "记忆文件里没有任何 Markdown 标题。",
            )
        }
        val exact = all.filter { it.title.equals(target, ignoreCase = true) }
        val candidates = exact.ifEmpty { all.filter { it.title.contains(target, ignoreCase = true) } }
        return when (candidates.size) {
            0 -> throw AgentMemoryException(
                code = "MEMORY_SECTION_NOT_FOUND",
                message = "未找到标题包含「$section」的章节；可用标题：\n${availableHeadings(lines)}",
            )
            1 -> ResolvedSection(candidates.first())
            else -> throw AgentMemoryException(
                code = "MEMORY_SECTION_AMBIGUOUS",
                message = "「$section」匹配到 ${candidates.size} 个章节，请用更完整的标题：\n" +
                    candidates.joinToString("\n") { "  ${it.raw.trimEnd()}（第 ${it.startIndex + 1} 行）" },
            )
        }
    }

    private fun availableHeadings(lines: List<String>): String =
        lines.withIndex()
            .filter { headingLevel(it.value) > 0 }
            .take(MAX_HEADING_LIST)
            .joinToString("\n") { "  第 ${it.index + 1} 行  ${it.value.trimEnd()}" }
            .ifEmpty { "  （无标题）" }

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

        /** `mode="clear"` 必须回传此串，避免一次误调用清空全部记忆。 */
        const val CLEAR_CONFIRMATION = "DELETE_ALL"

        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        private const val HISTORY_DIR_NAME = "history"
        private const val DEFAULT_START_LINE = 1
        private const val SEARCH_WINDOW_LINES = 6
        private const val MAX_SECTION_EXPANSION_LINES = 24
        private const val MAX_HEADING_LIST = 40

        /** 写前历史快照保留份数（FIFO 滚动删除）。 */
        const val MAX_HISTORY_ENTRIES = 10

        /** 历史快照文件名：`yyyyMMdd-HHmmss-<seq6>-<rev8>.md`；mtime 优先、文件名兜底的倒序=最新在前。 */
        private val HISTORY_ID = Regex("[0-9]{8}-[0-9]{6}-[0-9]{6}-[0-9a-f]{8}\\.md")

        private val HISTORY_ORDER = compareByDescending<File> { it.lastModified() }
            .thenByDescending { it.name }
    }
}

internal object AgentMemoryRepository : MemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    /** [isEnabledCached] 的缓存值；由 [setEnabled] 与 [warmEnabledCache] 唯二更新。 */
    @Volatile
    private var cachedEnabled: Boolean = true

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(context.applicationContext.filesDir)
        }
    }

    /**
     * 仅供测试：把单例重新绑到当前 Application 的目录。
     *
     * [init] 只在首次调用时生效，而 Robolectric 每个测试类都会新建 Application
     * （`filesDir` 不同）。不重绑的话，后续测试类的读写全部落到上一个类的目录，
     * 表现为「写入成功但读出来是空的」这类顺序依赖——只在跑全量时复现，单跑必过。
     */
    @androidx.annotation.VisibleForTesting
    internal fun rebindForTests(context: Context) {
        store = AgentMemoryStore(context.applicationContext.filesDir)
    }

    override fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    override fun read(
        query: String?,
        startLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars)
    }

    override fun readSection(
        section: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.readSection(section, maxChars)
    }

    override fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    override fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    override fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult {
        ensureInitialized()
        return store.replaceAllIfRevision(content, revision)
    }

    override fun listHistory(): List<AgentMemoryHistoryEntry> {
        ensureInitialized()
        return store.listHistory()
    }

    override fun restoreHistory(id: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.restoreHistory(id)
    }

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().memoryEnabled

    /**
     * 非挂起读取（工具派发路径用）：读缓存值，消除该路径上的 `runBlocking { DataStore }` 阻塞。
     * 开关只可能从 UI 的 [setEnabled] 改，缓存不会漂移。
     */
    fun isEnabledCached(): Boolean = cachedEnabled

    suspend fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        SettingsDataStore.setMemoryEnabled(enabled)
    }

    /** 启动时把开关值读进缓存（此后工具派发路径不再碰 DataStore）。 */
    internal suspend fun warmEnabledCache() {
        cachedEnabled = isEnabled()
    }

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }
}

/** 标题层级：返回 `#` 的个数（1..6），不是标题或 `#` 后无空格时返回 0。 */
private fun headingLevel(line: String): Int {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("#")) return 0
    val hashes = trimmed.takeWhile { it == '#' }.length
    if (hashes !in 1..6) return 0
    if (trimmed.length <= hashes || trimmed[hashes] != ' ') return 0
    return hashes
}

/** 去掉 `#` 前缀与首尾空白后的标题文本。 */
private fun headingTitle(line: String): String =
    line.trimStart().trimStart('#').trim()
