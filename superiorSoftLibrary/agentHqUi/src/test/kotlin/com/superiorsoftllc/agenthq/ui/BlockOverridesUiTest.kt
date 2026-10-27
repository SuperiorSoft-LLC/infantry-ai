package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockOverride
import com.superiorsoftllc.agenthq.ui.block.layout.BlockResolver
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.ColumnBlock
import com.superiorsoftllc.agenthq.ui.block.layout.NodeOverride
import com.superiorsoftllc.agenthq.ui.block.layout.OverrideModes
import kotlin.test.Test
import kotlin.test.assertEquals

/** BLOCKS-LAYOUT L5: what a replace and a wrap draw, in a real composition; a locked node draws as core. */
@OptIn(ExperimentalTestApi::class)
class BlockOverridesUiTest {
    private class Leaf(id: String) : Block("leaf", id) {
        @Composable
        override fun Render(scope: BlockScope) = Text(id, scope.modifier.testTag(id))
    }

    private val tree = ColumnBlock("root", children = listOf(Leaf("a"), Leaf("b"), Leaf("c")))

    @Test fun replaceDrawsInPlaceOfTheNodeAndWrapDrawsAroundIt() = runComposeUiTest {
        val overrides = listOf(
            NodeOverride("Acme_X", "a", OverrideModes.REPLACE, BlockOverride { scope, inner -> Text(if (inner == null) "new" else "bad", scope.modifier.testTag("a-new")) }),
            NodeOverride("Acme_X", "b", OverrideModes.WRAP, BlockOverride { scope, inner -> Box(Modifier.testTag("b-frame")) { inner!!.Render(scope) } }),
            NodeOverride("Acme_X", "root", OverrideModes.REPLACE, BlockOverride { _, _ -> Text("drawn", Modifier.testTag("root-new")) }),
        )
        val resolved = BlockResolver.resolve(tree, overrides, locked = setOf("root"))
        assertEquals(listOf("Acme_X: shell override (replace) of root refused: root is locked"), resolved.problems)
        setContent { resolved.root.Render(BlockScope(Modifier, null)) }
        onNodeWithTag("a").assertDoesNotExist()
        onNodeWithTag("a-new").assertExists()
        onNodeWithTag("b-frame").assertExists()
        onNodeWithTag("b").assertExists()
        onNodeWithTag("c").assertExists()
        onNodeWithTag("root-new").assertDoesNotExist()
    }
}
