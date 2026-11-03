package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.SearchText
import com.superiorsoftllc.infantry.ui.model.filterRanked

/** Suffixes appended to a [SearchableSelect]'s `searchTag` for its status lines. */
const val SELECT_EMPTY_SUFFIX = "-empty"
const val SELECT_NO_HIT_SUFFIX = "-nohit"
const val SELECT_COUNT_SUFFIX = "-count"

/**
 * ROLE-FIRST S7: a search box over an always-visible, height-bounded [LazyColumn] (docs/INFANTRY-ROLE-FIRST.md 7.1).
 * Inline rather than a popup, so it composes inside a dialog's own scrolling column without a second window.
 *
 * The CALLER owns [query]. When [text] is given the list is narrowed here with [filterRanked]; when it is null the
 * caller has already filtered [items] (the executor model does, so its ranking and its state stay in one place)
 * and [totalCount] says how many there were before filtering.
 *
 * Keyboard, on the search box: Up/Down move the highlight (it starts on the first row and resets when the query
 * changes), Enter picks the highlighted row, Esc clears the query, Backspace on an empty box calls [onBackOut]
 * (the previous step). Status lines are tagged `searchTag` + [SELECT_EMPTY_SUFFIX] / [SELECT_NO_HIT_SUFFIX] /
 * [SELECT_COUNT_SUFFIX], every row with [itemTag]; a highlighted row reports `selected` in semantics.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> SearchableSelect(
    items: List<T>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (T) -> Unit,
    key: (T) -> Any,
    label: (T) -> String,
    searchTag: String,
    itemTag: (T) -> String,
    modifier: Modifier = Modifier,
    text: ((T) -> SearchText)? = null,
    isSelected: (T) -> Boolean = { false },
    badge: (T) -> String? = { null },
    placeholder: String = "Search...",
    emptyText: String = "Nothing to choose from",
    totalCount: Int = items.size,
    maxListHeight: Dp = 150.dp,
    onBackOut: (() -> Unit)? = null,
    /** Unselected row text; the host passes its surface's content color (a light dialog needs a dark one). */
    textColor: Color = Palette.Text,
    /** Drawn between the search box and the options, e.g. the "Model: ..." label right above the choice. */
    aboveList: (@Composable () -> Unit)? = null,
) {
    val shown = if (text == null) items else remember(items, query) { filterRanked(items, query, text) }
    var highlight by remember(query, shown.size) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(highlight, shown.size) { if (highlight in shown.indices) listState.scrollToItem(highlight) }
    // The list sits below the box: inside a scrolling dialog the hits of a typed search can land under the fold, so
    // the search looked empty. Typing scrolls the host so the rows show.
    val rowsInView = remember { BringIntoViewRequester() }
    LaunchedEffect(query, shown.isEmpty()) { if (query.isNotEmpty() && shown.isNotEmpty()) rowsInView.bringIntoView() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (totalCount == 0 && query.isEmpty()) {
            aboveList?.invoke()
            Text(emptyText, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(searchTag + SELECT_EMPTY_SUFFIX))
            return@Column
        }
        OutlinedTextField(
            query, onQueryChange, placeholder = { Text(placeholder) }, singleLine = true, colors = visibleBorderFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag(searchTag).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { if (shown.isNotEmpty()) highlight = (highlight + 1).coerceAtMost(shown.lastIndex); true }
                    Key.DirectionUp -> { highlight = (highlight - 1).coerceAtLeast(0); true }
                    Key.Enter, Key.NumPadEnter -> { shown.getOrNull(highlight)?.let(onPick); true }
                    Key.Escape -> if (query.isNotEmpty()) { onQueryChange(""); true } else false
                    Key.Backspace -> if (query.isEmpty() && onBackOut != null) { onBackOut(); true } else false
                    else -> false
                }
            },
        )
        if (shown.isEmpty()) {
            Text("No match for \"$query\"", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(searchTag + SELECT_NO_HIT_SUFFIX))
            return@Column
        }
        Text(
            "${shown.size} of $totalCount", color = Palette.Muted, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.End).testTag(searchTag + SELECT_COUNT_SUFFIX),
        )
        aboveList?.invoke()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxListHeight).bringIntoViewRequester(rowsInView), state = listState, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(shown, key = { _, it -> key(it) }) { index, item ->
                val on = isSelected(item)
                val lit = index == highlight
                // PICKER-UX: a row reads as an option, not a banner: a bordered box with a radio mark, a hover state,
                // and the keyboard highlight as a faint tint only (a lone row is always highlighted).
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                val shape = RoundedCornerShape(4.dp)
                Row(
                    Modifier.fillMaxWidth().hoverable(hover).clip(shape)
                        .border(
                            if (on) 1.5.dp else 1.dp,
                            when {
                                on -> Palette.Accent
                                hovered -> Palette.Accent.copy(alpha = 0.7f)
                                else -> textColor.copy(alpha = 0.25f)
                            },
                            shape,
                        )
                        .background(
                            when {
                                on -> Palette.Accent.copy(alpha = 0.14f)
                                hovered -> Palette.Accent.copy(alpha = 0.10f)
                                lit -> Palette.Accent.copy(alpha = 0.05f)
                                else -> Color.Transparent
                            },
                        )
                        .semantics { selected = lit }
                        .clickable { onPick(item) }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag(itemTag(item)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioMark(on, if (on || hovered) Palette.Accent else textColor.copy(alpha = 0.6f))
                    Text(
                        label(item), fontFamily = Mono, fontSize = 12.sp,
                        color = if (on) Palette.Accent else textColor, modifier = Modifier.weight(1f),
                    )
                    badge(item)?.let { Text(it, fontSize = 10.sp, color = Palette.Muted) }
                }
            }
        }
    }
}

/** PICKER-UX: a drawn radio mark (no glyph, so no tofu on the web build); the picked one says "picked" to a reader. */
@Composable
private fun RadioMark(on: Boolean, color: Color) {
    Box(
        Modifier.size(12.dp).border(1.5.dp, color, CircleShape).semantics { if (on) contentDescription = RADIO_PICKED },
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(6.dp).background(color, CircleShape))
    }
}

/** The content description of a picked [SearchableSelect] row's radio mark. */
const val RADIO_PICKED = "picked"
