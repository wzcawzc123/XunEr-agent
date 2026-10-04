package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentScreenObservationContractTest {
    @Test
    fun emptyArgumentsPreferUiTreeWithoutScreenshot() {
        val options = AgentScreenObservationContract.resolve(JSONObject())

        assertFalse(options.includeScreenshot)
        assertTrue(options.includeUiTree)
        assertEquals(60, options.maxNodes)
    }

    @Test
    fun explicitScreenshotKeepsUiTreeEnabledByDefault() {
        val options = AgentScreenObservationContract.resolve(
            JSONObject().put("include_screenshot", true),
        )

        assertTrue(options.includeScreenshot)
        assertTrue(options.includeUiTree)
        assertEquals(60, options.maxNodes)
    }

    @Test
    fun explicitArgumentsOverrideEveryDefault() {
        val options = AgentScreenObservationContract.resolve(
            JSONObject()
                .put("include_screenshot", true)
                .put("include_ui_tree", false)
                .put("max_nodes", 120),
        )

        assertTrue(options.includeScreenshot)
        assertFalse(options.includeUiTree)
        assertEquals(120, options.maxNodes)
    }

    @Test
    fun sparseTreeNoteFiresOnlyWhenTreeIsIncludedAndSparse() {
        assertEquals("", AgentScreenObservationContract.sparseTreeNote(nodeCount = 60, treeIncluded = true))
        assertEquals("", AgentScreenObservationContract.sparseTreeNote(nodeCount = 6, treeIncluded = false))

        val note = AgentScreenObservationContract.sparseTreeNote(nodeCount = 6, treeIncluded = true)
        assertTrue(note.contains("无障碍树稀疏"))
        assertTrue(note.contains("不要用 tap_element"))
        assertTrue(note.contains("coordinate_contract"))
        assertTrue(note.contains("重新 observe_screen"))
    }
}
