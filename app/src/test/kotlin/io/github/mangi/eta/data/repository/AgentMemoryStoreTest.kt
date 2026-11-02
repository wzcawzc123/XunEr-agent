package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun utf8LimitIsMeasuredInBytesAndFailedWritePreservesOldFile() {
        val store = store()
        val original = store.replaceAll("安全内容")

        assertEquals(12, original.byteSize)
        assertThrows(AgentMemoryException::class.java) {
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES + 1))
        }

        assertEquals(original, store.snapshot())
        assertEquals(
            AgentMemoryStore.MAX_FILE_BYTES,
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES)).byteSize,
        )
    }

    @Test
    fun supportsPagingCaseInsensitiveSearchAndLineMetadata() {
        val store = store()
        store.replaceAll("# 核心记忆\n喜欢 Kotlin\n## 项目\nEta Agent\n其他")

        val page = store.read(startLine = 3, maxChars = 20)
        assertEquals(3, page.startLine)
        assertTrue(page.content.startsWith("3: ## 项目"))
        assertTrue(page.hasMore)

        val search = store.read(query = "eta agent", maxChars = 200)
        assertEquals(1, search.matchedLines)
        assertTrue(search.content.contains("4: Eta Agent"))
        assertTrue(search.content.contains("3: ## 项目"))
        assertTrue(search.content.contains("5: 其他"))
        assertFalse(search.hasMore)
    }

    @Test
    fun mutationsAreAtomicRevisionCheckedAndCanDeleteOrClear() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n## 项目\n旧项目")

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = initial.revision,
                startLine = 2,
                endLine = 2,
                content = "新偏好",
            ),
        ) as AgentMemoryWriteResult.Success
        assertEquals("# 核心记忆\n新偏好\n## 项目\n旧项目", replaced.snapshot.content)

        val conflict = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "## 冲突追加"),
        ) as AgentMemoryWriteResult.Conflict
        assertEquals(replaced.snapshot.revision, conflict.snapshot.revision)
        assertFalse(store.snapshot().content.contains("冲突追加"))

        val appended = store.mutate(
            AgentMemoryMutation.Append(replaced.snapshot.revision, "## 新章节\n内容"),
        ) as AgentMemoryWriteResult.Success
        val deleted = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = appended.snapshot.revision,
                startLine = 4,
                endLine = 4,
                content = "",
            ),
        ) as AgentMemoryWriteResult.Success
        assertFalse(deleted.snapshot.content.contains("旧项目"))

        val cleared = store.mutate(
            AgentMemoryMutation.Clear(deleted.snapshot.revision),
        ) as AgentMemoryWriteResult.Success
        assertEquals("", cleared.snapshot.content)
        assertEquals(0, cleared.snapshot.byteSize)
        assertEquals(0, cleared.snapshot.lineCount)
    }

    @Test
    fun replaceTextSwapsUniqueExcerptRegardlessOfLineNumbers() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n用户喜欢简洁回答\n## 项目\nEta Agent")
        // 先插入一行把后续行号全部挤偏：内容寻址不该被行号漂移影响。
        val shifted = (
            store.mutate(
                AgentMemoryMutation.ReplaceText(initial.revision, "## 项目", "## 项目\n新增一行"),
            ) as AgentMemoryWriteResult.Success
            ).snapshot

        val result = store.mutate(
            AgentMemoryMutation.ReplaceText(shifted.revision, "用户喜欢简洁回答", "用户要求：回答尽量简洁"),
        ) as AgentMemoryWriteResult.Success

        assertTrue(result.changed)
        assertTrue(result.snapshot.content.contains("用户要求：回答尽量简洁"))
        assertFalse(result.snapshot.content.contains("用户喜欢简洁回答"))
    }

    @Test
    fun replaceTextRejectsMissingAndAmbiguousExcerptWithoutTouchingFile() {
        val store = store()
        val snapshot = store.replaceAll("# 核心记忆\n重复内容\n## 项目\n重复内容")

        val missing = assertThrows(AgentMemoryException::class.java) {
            store.mutate(AgentMemoryMutation.ReplaceText(snapshot.revision, "不存在的原文", "x"))
        }
        assertEquals("MEMORY_TEXT_NOT_FOUND", missing.code)
        // 报错里要给出可用标题，模型才知道下一步怎么定位。
        assertTrue(missing.message.orEmpty().contains("## 项目"))

        val ambiguous = assertThrows(AgentMemoryException::class.java) {
            store.mutate(AgentMemoryMutation.ReplaceText(snapshot.revision, "重复内容", "x"))
        }
        assertEquals("MEMORY_TEXT_AMBIGUOUS", ambiguous.code)

        assertEquals(snapshot, store.snapshot())
    }

    @Test
    fun appendRejectsHeadingThatAlreadyExists() {
        val store = store()
        val snapshot = store.replaceAll("# 核心记忆\n旧\n## 项目\nA")

        val failure = assertThrows(AgentMemoryException::class.java) {
            store.mutate(AgentMemoryMutation.Append(snapshot.revision, "## 项目\nB"))
        }

        assertEquals("MEMORY_HEADING_DUPLICATE", failure.code)
        assertEquals(snapshot.content, store.snapshot().content)
    }

    @Test
    fun replaceSectionSwapsOrDeletesWholeSection() {
        val store = store()
        val snapshot = store.replaceAll("# 核心记忆\n旧偏好\n## 待办\n- 旧事项\n## 环境\nAndroid")

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceSection(snapshot.revision, "待办", "## 待办\n- 新事项"),
        ) as AgentMemoryWriteResult.Success
        assertTrue(replaced.snapshot.content.contains("## 待办\n- 新事项"))
        assertFalse(replaced.snapshot.content.contains("旧事项"))
        assertTrue(replaced.snapshot.content.contains("## 环境\nAndroid"))

        // 空内容 = 删除整节
        val deleted = store.mutate(
            AgentMemoryMutation.ReplaceSection(replaced.snapshot.revision, "## 待办", ""),
        ) as AgentMemoryWriteResult.Success
        assertFalse(deleted.snapshot.content.contains("## 待办"))
        assertTrue(deleted.snapshot.content.contains("## 环境"))
    }

    @Test
    fun readsWholeSectionByHeadingIncludingSubsections() {
        val store = store()
        store.replaceAll("# 核心记忆\n偏好\n## 待办\n- A\n### 子节\n- B\n## 环境\nAndroid")

        val section = store.readSection("待办")
        assertTrue(section.content.contains("## 待办"))
        // 三级标题属于二级节，遇到同级的下一个标题才结束。
        assertTrue(section.content.contains("### 子节"))
        assertFalse(section.content.contains("## 环境"))

        val missing = assertThrows(AgentMemoryException::class.java) { store.readSection("没有这一节") }
        assertEquals("MEMORY_SECTION_NOT_FOUND", missing.code)
    }

    @Test
    fun keepsWriteHistoryAndRestoresPreviousVersion() {
        val store = store()
        store.replaceAll("第一版")
        store.replaceAll("第二版")
        store.replaceAll("第三版")

        // 首次写入前文件为空，没有可留档的内容。
        val history = store.listHistory()
        assertEquals(2, history.size)
        assertEquals("第二版", store.restoreHistory(history.first().id).content)
        // 恢复也是一次写入：当前内容会被先留档，所以恢复可再回滚。
        assertTrue(store.listHistory().size >= 3)
    }

    @Test
    fun writingIdenticalContentReportsNoChange() {
        val store = store()
        val snapshot = store.replaceAll("不变的内容")

        val result = store.mutate(
            AgentMemoryMutation.ReplaceText(snapshot.revision, "不变的内容", "不变的内容"),
        ) as AgentMemoryWriteResult.Success

        assertFalse(result.changed)
        assertEquals(snapshot.revision, result.snapshot.revision)
    }

    private fun store(): AgentMemoryStore = AgentMemoryStore(temporaryFolder.newFolder())
}
