package com.superiorsoftllc.agenthq.prompts

import com.superiorsoftllc.agenthq.protocol.SpawnOptions

/** ASK-TIMER: the session prompt-timer override this spawn asks for; null = none. Refused ([IllegalArgumentException]) when invalid. */
fun SpawnOptions.promptTimeoutOverride(): PromptTimeoutOverride? {
    promptTimeoutSec?.let { require(it in 0..86_400) { "promptTimeoutSec must be 0..86400 (0 = no timer), got $it" } }
    val action = promptOnTimeout?.let {
        TimeoutAction.parse(it)
            ?: throw IllegalArgumentException("promptOnTimeout must be one of ${TimeoutAction.WIRE.joinToString()}")
    }
    if (promptTimeoutSec == null && action == null) return null
    return PromptTimeoutOverride(promptTimeoutSec, action)
}
