package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockOverride
import com.superiorsoftllc.infantry.ui.block.layout.BlockResolver
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.ColumnBlock
import com.superiorsoftllc.infantry.ui.block.layout.NodeOverride
import com.superiorsoftllc.infantry.ui.block.layout.OverrideHealth
import com.superiorsoftllc.infantry.ui.block.layout.OverrideModes
import com.superiorsoftllc.infantry.ui.block.layout.probeComposition
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GAP-AUDIT-1 (a): an override or injection that throws while it composes is never drawn; the window falls back to the
 * original (replace, wrap) or to nothing (before, after) and keeps drawing; a healthy one is drawn as before.
 */
@OptIn(ExperimentalTestApi::class)
class OverrideGuardUiTest {
    private class Leaf(id: String) : Block("leaf", id) {
        @Composable
        override fun Render(scope: BlockScope) = Text(id, scope.modifier.testTag(id))
    }

    /** A leaf that counts how often its state is created: a wrap's switch from the fallback must not recreate it. */
    private class Counted(id: String, val created: IntArray) : Block("leaf", id) {
        @Composable
        override fun Render(scope: BlockScope) {
            remember { created[0]++ }
            Text(id, scope.modifier.testTag(id))
        }
    }

    private val tree = ColumnBlock("root", children = listOf(Leaf("a"), Leaf("b"), Leaf("c"), Leaf("d")))

    private val boom = BlockOverride { _, _ -> throw IllegalStateException("plugin bug") }

    @BeforeTest fun clean() = OverrideHealth.clear()
    @AfterTest fun after() = OverrideHealth.clear()

    @Test fun throwingReplaceWrapBeforeAfterFallBackAndTheWindowKeepsDrawing() = runComposeUiTest {
        val overrides = listOf(
            NodeOverride("Acme_Bad", "a", OverrideModes.REPLACE, boom),
            NodeOverride("Acme_Bad", "b", OverrideModes.WRAP, BlockOverride { _, _ -> error("wrap bug") }),
            NodeOverride("Acme_Bad", "c", OverrideModes.BEFORE, boom),
            NodeOverride("Acme_Bad", "d", OverrideModes.AFTER, boom),
        )
        val resolved = BlockResolver.resolve(tree, overrides, locked = emptySet())
        assertEquals(emptyList(), resolved.problems)
        setContent { resolved.root.Render(BlockScope(Modifier, null)) }
        waitForIdle()
        listOf("a", "b", "c", "d").forEach { onNodeWithTag(it).assertExists() }
        val faults = OverrideHealth.faults
        assertEquals(setOf("Acme_Bad/replace/a", "Acme_Bad/wrap/b", "Acme_Bad/inject/Acme_Bad.before-c", "Acme_Bad/inject/Acme_Bad.after-d"), faults.keys)
        assertTrue(faults.getValue("Acme_Bad/replace/a").contains("plugin bug"))
    }

    @Test fun healthyOverridesAreDrawnAfterTheProbe() = runComposeUiTest {
        val overrides = listOf(
            NodeOverride("Acme_Ok", "a", OverrideModes.REPLACE, BlockOverride { s, _ -> Text("new", s.modifier.testTag("a-new")) }),
            NodeOverride("Acme_Ok", "b", OverrideModes.WRAP, BlockOverride { s, inner -> Box(Modifier.testTag("b-frame")) { inner!!.Render(s) } }),
            NodeOverride("Acme_Ok", "c", OverrideModes.BEFORE, BlockOverride { s, _ -> Text("pre", s.modifier.testTag("c-before")) }),
        )
        val resolved = BlockResolver.resolve(tree, overrides, locked = emptySet())
        setContent { resolved.root.Render(BlockScope(Modifier, null)) }
        waitForIdle()
        onNodeWithTag("a").assertDoesNotExist()
        onNodeWithTag("a-new").assertExists()
        onNodeWithTag("b-frame").assertExists()
        onNodeWithTag("b").assertExists()
        onNodeWithTag("c-before").assertExists()
        assertEquals(emptyMap(), OverrideHealth.faults)
    }

    @Test fun aWrapKeepsTheWrappedNodesStateWhenItSwitchesFromTheFallback() = runComposeUiTest {
        val created = IntArray(1)
        val root = ColumnBlock("root", children = listOf(Counted("x", created)))
        val wrap = NodeOverride("Acme_Ok", "x", OverrideModes.WRAP, BlockOverride { s, inner -> Box(Modifier.testTag("x-frame")) { inner!!.Render(s) } })
        val resolved = BlockResolver.resolve(root, listOf(wrap), locked = emptySet())
        setContent { resolved.root.Render(BlockScope(Modifier, null)) }
        waitForIdle()
        onNodeWithTag("x-frame").assertExists()
        assertEquals(1, created[0], "the wrapped node's state is created once, not again when the wrap takes over")
    }

    @Test fun theProbeRunsNoEffectAndReportsTheThrow() {
        val ran = mutableIntStateOf(0)
        assertNull(probeComposition { LaunchedEffect(Unit) { ran.intValue++ } })
        assertEquals(0, ran.intValue, "a probed block's LaunchedEffect never starts")
        assertNotNull(probeComposition { error("x") })
    }
}
