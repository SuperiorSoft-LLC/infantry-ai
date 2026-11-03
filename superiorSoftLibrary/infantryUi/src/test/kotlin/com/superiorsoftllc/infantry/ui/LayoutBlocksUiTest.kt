package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockRefusedException
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.ColumnBlock
import com.superiorsoftllc.infantry.ui.block.layout.GroupBlock
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RowBlock
import com.superiorsoftllc.infantry.ui.block.layout.Show
import com.superiorsoftllc.infantry.ui.block.layout.Size
import com.superiorsoftllc.infantry.ui.block.layout.SpacerBlock
import com.superiorsoftllc.infantry.ui.block.layout.StackBlock
import com.superiorsoftllc.infantry.ui.block.layout.flattened
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** BLOCKS-LAYOUT L1: each layout primitive's measurement/placement, and the child-geometry rule (parent applies `place`). */
@OptIn(ExperimentalTestApi::class)
class LayoutBlocksUiTest {
    /** A leaf of fixed content size ([w] x [h]) that applies the handed modifier exactly once, first. */
    private class Probe(id: String, val w: Dp, val h: Dp, place: Place = Place()) : Block("probe", id, place) {
        @Composable
        override fun Render(scope: BlockScope) {
            Box(scope.modifier.testTag(id).width(w).height(h))
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.bounds(tag: String) = onNodeWithTag(tag).getBoundsInRoot()

    private fun host(root: Block, w: Dp = 200.dp, h: Dp = 100.dp) = @Composable {
        Box(Modifier.testTag("host").width(w).height(h)) { root.Render(BlockScope(Modifier.fillMaxSize(), null)) }
    }

    @Test
    fun rowPlacesChildrenLeftToRightAndWeightSplitsTheRest() = runComposeUiTest {
        val row = RowBlock(
            "r",
            children = listOf(
                Probe("a", 40.dp, 10.dp),
                Probe("b", 10.dp, 10.dp, Place(weight = 1f)),
                Probe("c", 10.dp, 10.dp, Place(weight = 3f)),
            ),
        )
        setContent(host(row, 200.dp))
        assertEquals(0.dp, bounds("a").left)
        // fixed 40, remaining 160 split 1:3
        assertEquals(40.dp, bounds("b").left)
        assertEquals(40.dp, bounds("b").width)
        assertEquals(80.dp, bounds("c").left)
        assertEquals(120.dp, bounds("c").width)
    }

    @Test
    fun rowWeightWithoutFillKeepsContentWidth() = runComposeUiTest {
        val row = RowBlock("r", children = listOf(Probe("a", 30.dp, 10.dp, Place(weight = 1f, weightFill = false)), Probe("b", 10.dp, 10.dp)))
        setContent(host(row))
        assertEquals(30.dp, bounds("a").width)
        assertEquals(30.dp, bounds("b").left)
    }

    @Test
    fun rowVerticalAlignmentCentersChildren() = runComposeUiTest {
        val row = RowBlock("r", Alignment.CenterVertically, listOf(Probe("a", 10.dp, 20.dp)), Place(size = Size.FillHeight))
        setContent(host(row, h = 100.dp))
        assertEquals(40.dp, bounds("a").top)
    }

    @Test
    fun columnWeightIsHeightAndSizeFillsTheCrossAxis() = runComposeUiTest {
        val col = ColumnBlock(
            "c",
            children = listOf(
                Probe("a", 10.dp, 30.dp),
                Probe("b", 10.dp, 10.dp, Place(weight = 1f, size = Size.FillWidth)),
            ),
        )
        setContent(host(col, 200.dp, 100.dp))
        assertEquals(70.dp, bounds("b").height)
        assertEquals(30.dp, bounds("b").top)
        assertEquals(200.dp, bounds("b").width)
    }

    @Test
    fun stackAlignsChildrenByPlaceAndIgnoresWeight() = runComposeUiTest {
        val stack = StackBlock(
            "s",
            children = listOf(
                Probe("tl", 20.dp, 20.dp),
                Probe("br", 20.dp, 20.dp, Place(align = Alignment.BottomEnd, weight = 5f)),
            ),
        )
        setContent(host(stack, 200.dp, 100.dp))
        assertEquals(0.dp, bounds("tl").left)
        assertEquals(180.dp, bounds("br").left)
        assertEquals(80.dp, bounds("br").top)
        assertEquals(20.dp, bounds("br").width) // weight is a row/column field; a stack ignores it
    }

    @Test
    fun rowIgnoresAlignItDoesNotUnderstand() = runComposeUiTest {
        val row = RowBlock("r", children = listOf(Probe("a", 20.dp, 20.dp, Place(align = Alignment.BottomEnd))))
        setContent(host(row))
        assertEquals(0.dp, bounds("a").left)
        assertEquals(0.dp, bounds("a").top)
    }

    @Test
    fun stackMeasureProvidesItsSizeToShowFilters() = runComposeUiTest {
        val stack = StackBlock(
            "s",
            measure = true,
            children = listOf(
                Probe("wide", 10.dp, 10.dp, Place(show = Show.Wide)),
                Probe("compact", 10.dp, 10.dp, Place(show = Show.Compact)),
            ),
        )
        setContent(host(stack, 1000.dp))
        onNodeWithTag("wide").assertExists()
        onNodeWithTag("compact").assertDoesNotExist()
    }

    @Test
    fun narrowMeasuredStackShowsCompactOnly() = runComposeUiTest {
        val stack = StackBlock(
            "s",
            measure = true,
            children = listOf(
                Probe("wide", 10.dp, 10.dp, Place(show = Show.Wide)),
                Probe("compact", 10.dp, 10.dp, Place(show = Show.Compact)),
            ),
        )
        setContent(host(stack, 500.dp))
        onNodeWithTag("wide").assertDoesNotExist()
        onNodeWithTag("compact").assertExists()
    }

    @Test
    fun showWithoutAMeasuredAncestorCountsAsWide() = runComposeUiTest {
        val col = ColumnBlock("c", children = listOf(Probe("wide", 10.dp, 10.dp, Place(show = Show.Wide)), Probe("compact", 10.dp, 10.dp, Place(show = Show.Compact))))
        setContent(host(col))
        onNodeWithTag("wide").assertExists()
        onNodeWithTag("compact").assertDoesNotExist()
    }

    @Test
    fun spacerWithWeightIsTheFlexibleGap() = runComposeUiTest {
        val row = RowBlock(
            "r",
            children = listOf(Probe("a", 20.dp, 10.dp), SpacerBlock("gap", place = Place(weight = 1f)), Probe("b", 20.dp, 10.dp)),
        )
        setContent(host(row, 200.dp))
        assertEquals(180.dp, bounds("b").left)
    }

    @Test
    fun spacerFixedSizeTakesItsSpace() = runComposeUiTest {
        val col = ColumnBlock("c", children = listOf(SpacerBlock("s", height = 25.dp), Probe("a", 10.dp, 10.dp)))
        setContent(host(col))
        assertEquals(25.dp, bounds("a").top)
    }

    @Test
    fun groupChildrenAreFlattenedIntoTheContainer() = runComposeUiTest {
        val row = RowBlock(
            "r",
            children = listOf(
                Probe("a", 20.dp, 10.dp),
                GroupBlock("g", listOf(Probe("b", 20.dp, 10.dp), GroupBlock("g2", listOf(Probe("c", 20.dp, 10.dp, Place(weight = 1f)))))),
            ),
        )
        setContent(host(row, 200.dp))
        assertEquals(20.dp, bounds("b").left)
        assertEquals(40.dp, bounds("c").left)
        assertEquals(160.dp, bounds("c").width) // a flattened child keeps its own place in the parent's axis
    }

    @Test
    fun groupAtTheRootRendersItsChildrenInSequence() = runComposeUiTest {
        val g = GroupBlock("g", listOf(Probe("a", 20.dp, 10.dp), Probe("b", 20.dp, 10.dp)))
        setContent(host(g))
        onNodeWithTag("a").assertExists()
        onNodeWithTag("b").assertExists()
    }

    @Test
    fun placeOrderIsWeightThenAlignThenSize() = runComposeUiTest {
        // In a column: weight takes the height, size = FillWidth stretches the width; both land on one node.
        val col = ColumnBlock("c", children = listOf(Probe("a", 10.dp, 10.dp, Place(weight = 1f, size = Size.FillWidth))))
        setContent(host(col, 200.dp, 100.dp))
        assertEquals(100.dp, bounds("a").height)
        assertEquals(200.dp, bounds("a").width)
    }

    @Test
    fun sizeFillMaxFillsBothAxes() = runComposeUiTest {
        val stack = StackBlock("s", children = listOf(Probe("a", 10.dp, 10.dp, Place(size = Size.FillMax))))
        setContent(host(stack, 200.dp, 100.dp))
        assertEquals(200.dp, bounds("a").width)
        assertEquals(100.dp, bounds("a").height)
    }

    @Test
    fun withChildrenOnContainersReturnsACopyWithTheSameFrame() {
        val col = ColumnBlock("c", Alignment.CenterHorizontally, place = Place(weight = 2f))
        val copy = col.withChildren(listOf(SpacerBlock("s")))
        assertEquals("column", copy.blockType)
        assertEquals("c", copy.id)
        assertEquals(Place(weight = 2f), copy.place)
        assertEquals(0, col.children.size)
        assertEquals(listOf("s"), copy.children.map { it.id })
        assertEquals(listOf("x", "y"), listOf(GroupBlock("g", listOf(SpacerBlock("x"), GroupBlock("h", listOf(SpacerBlock("y")))))).flattened().map { it.id })
    }

    @Test
    fun aLeafRefusesChildrenVisibly() {
        val e = assertFailsWith<BlockRefusedException> { SpacerBlock("s").withChildren(listOf(SpacerBlock("x"))) }
        assertEquals("spacer takes no children yet", e.message)
    }
}
