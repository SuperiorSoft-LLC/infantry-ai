package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.LimitInfo
import com.superiorsoftllc.agenthq.ui.model.LimitsSetting

const val LIMITS_PENDING_TAG = "limits-pending"
const val LIMIT_FIELD_TAG_PREFIX = "limit-field-"
const val LIMIT_SET_TAG_PREFIX = "limit-set-"
const val LIMIT_VALUE_TAG_PREFIX = "limit-value-"

/**
 * No-hard-coded-limits (contract 1.133.0): every daemon limit, grouped (`concurrency`, `watchdog`, `build`, ...). Each row
 * shows the value in force, a field and Set; under it the description and the accepted range, what 0 means, the default
 * and the env name. The value shown is the DAEMON's answer: the field is only what will be sent.
 */
@Composable
fun LimitsSection(limits: List<LimitInfo>?, onSet: (key: String, value: Long) -> Unit) {
    if (limits == null) {
        Text("Not read from the daemon (needs contract 1.133.0)", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.testTag(LIMITS_PENDING_TAG))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        limits.groupBy { it.group }.forEach { (group, items) ->
            Text(group.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Palette.Accent)
            items.forEach { info -> LimitRow(info, onSet) }
        }
    }
}

@Composable
private fun LimitRow(info: LimitInfo, onSet: (String, Long) -> Unit) {
    // Keyed on the daemon's value, so an accepted change (or another window's) resets the field to it.
    var text by remember(info.key, info.value) { mutableStateOf(info.value.toString()) }
    val parsed = LimitsSetting.parse(info, text)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(info.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                if (info.value == 0L && info.zeroMeans != null) "0 (${info.zeroMeans})" else "${info.value} ${info.unit}",
                fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.testTag(LIMIT_VALUE_TAG_PREFIX + info.key),
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, isError = parsed == null,
                modifier = Modifier.width(110.dp).testTag(LIMIT_FIELD_TAG_PREFIX + info.key),
            )
            OutlinedButton(
                onClick = { parsed?.let { onSet(info.key, it) } },
                enabled = parsed != null && parsed != info.value,
                modifier = Modifier.testTag(LIMIT_SET_TAG_PREFIX + info.key),
            ) { Text("Set") }
        }
        Text(info.description, fontSize = 11.sp, color = Palette.Text)
        Text("${info.key}: ${info.rangeText()}", fontSize = 10.sp, color = Palette.Muted, modifier = Modifier.fillMaxWidth())
    }
}
