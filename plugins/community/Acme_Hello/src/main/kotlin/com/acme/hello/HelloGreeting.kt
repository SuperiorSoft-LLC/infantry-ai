package com.acme.hello

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import com.superiorsoftllc.agenthq.ui.plugin.TabContext

const val GREETING_TEXT_TAG = "acme-hello-greeting-text"

/** What the block prints: the picked greeting, then the custom message if any; upper-cased when [shout]. */
fun greetingText(greeting: String?, message: String?, shout: Boolean): String {
    val base = greeting ?: "Hello World"
    val text = message?.trim().orEmpty().let { if (it.isEmpty()) base else "$base, $it" }
    return if (shout) text.uppercase() else text
}

/** The block's own classes: `big` (size), `bold` (weight), `muted` (colour). Any other token is ignored. */
@Composable
fun greetingStyle(classes: List<String>): TextStyle = TextStyle(
    fontSize = if ("big" in classes) 18.sp else 12.sp,
    fontWeight = if ("bold" in classes) FontWeight.Bold else FontWeight.Normal,
    color = LocalContentColor.current.copy(alpha = if ("muted" in classes) 0.55f else 1f),
)

/** Block acme.hello.greeting: reads its node's config live, so a change in Settings redraws it with no restart. */
@Composable
fun GreetingBlock(context: TabContext) {
    val config = context.config(AcmeHelloConfig.GREETING_NODE)
    val classes = config.classes("text").orEmpty()
    Text(
        greetingText(config.string("greeting"), config.string("message"), config.boolean("shout") == true),
        Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag(GREETING_TEXT_TAG)
            .semantics { stateDescription = classes.joinToString(" ") },
        style = greetingStyle(classes),
    )
}
