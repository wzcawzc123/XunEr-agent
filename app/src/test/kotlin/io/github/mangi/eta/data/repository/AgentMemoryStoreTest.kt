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
    fun readsAndReplacesWholeSectionsByHeading() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n偏好\n# 振动模块\n旧内容\n细节\n# 触控\n保留")

        val section = store.readSection("振动模块")
        assertEquals("# 振动模块", section.section?.trim())
        assertTrue(section.content.contains("3: # 振动模块"))
        assertTrue(section.content.contains("4: 旧内容"))
        assertTrue(section.content.contains("5: 细节"))
        // 章节边界在下一个同级标题处停住，不越界。
        assertFalse(section.content.contains("触控"))

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceSection(
                revision = initial.revision,
                section = "振动模块",
                content = "# 振动模块\n新内容",
            ),
        ) as AgentMemoryWriteResult.Success
        assertEquals(
            "# 核心记忆\n偏好\n# 振动模块\n新内容\n# 触控\n保留",
            replaced.snapshot.content,
        )
        assertTrue(replaced.changed)

        val missing = assertThrows(AgentMemoryException::class.java) {
            store.readSection("不存在的章节")
        }
        assertEquals("MEMORY_SECTION_NOT_FOUND", missing.code)
        assertTrue(missing.message!!.contains("# 振动模块"))
    }

    @Test
    fun appendingADuplicateTopLevelHeadingIsRejected() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n偏好")

        val failure = assertThrows(AgentMemoryException::class.java) {
            store.mutate(AgentMemoryMutation.Append(initial.revision, "# 核心记忆\n又一段"))
        }

        assertEquals("MEMORY_DUPLICATE_HEADING", failure.code)
        // 拒绝后文件必须原封不动
        assertEquals("# 核心记忆\n偏好", store.snapshot().content)
        assertEquals(initial.revision, store.snapshot().revision)
    }

    @Test
    fun unchangedWritesAreReportedAsNotChanged() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n偏好")

        val empty = store.mutate(
            AgentMemoryMutation.Append(initial.revision, ""),
        ) as AgentMemoryWriteResult.Success
        assertFalse(empty.changed)
        assertEquals(initial.revision, empty.snapshot.revision)

        val identical = store.mutate(
            AgentMemoryMutation.ReplaceRange(initial.revision, 2, 2, "偏好"),
        ) as AgentMemoryWriteResult.Success
        assertFalse(identical.changed)
    }

    @Test
    fun searchExpandsSmallSectionsSoMatchesArriveWithContext() {
        val store = store()
        store.replaceAll("# A\n第一行\n目标关键字\n第三行\n# B\n无关内容")

        val result = store.read(query = "目标关键字", maxChars = 500)

        assertEquals(1, result.matchedLines)
        assertTrue(result.content.contains("第一行"))
        assertTrue(result.content.contains("第三行"))
        assertFalse(result.content.contains("无关内容"))
    }

    @Test
    fun pagingNeverSplitsSurrogatePairs() {
        val store = store()
        store.replaceAll("😀".repeat(40))

        val page = store.read(maxChars = 6)

        assertTrue(page.content.startsWith("1: "))
        assertFalse(hasLoneSurrogate(page.content))
    }

    private fun hasLoneSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
                    index += 2
                }
                Character.isLowSurrogate(char) -> return true
                else -> index++
            }
        }
        return false
    }

    private fun store(): AgentMemoryStore = AgentMemoryStore(temporaryFolder.newFolder())
}
