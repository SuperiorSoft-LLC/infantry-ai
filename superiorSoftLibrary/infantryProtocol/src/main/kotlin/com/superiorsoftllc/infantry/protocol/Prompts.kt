// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/**
 * Contract 1.44.0 (P10-A): the payload of `prompt_save`. Saving a [name] that already exists UPDATES that prompt
 * (same id, new body / tags, `updatedAt` moves); otherwise it creates one. Limits are enforced by the daemon:
 * name <= 120 chars, body <= 64 KB, <= 16 tags of <= 32 chars each. Decoded strictly, like `role_save`.
 */
@Serializable
data class PromptSave(
    val name: String,
    val body: String,
    val tags: List<String> = emptyList(),
) {
    companion object {
        fun decode(text: String): PromptSave = StrictJson.decode(serializer(), text, "prompt_save payload")
    }
}

/** Contract 1.44.0 (P10-A): one saved prompt, as the `prompt` and `prompts` frames carry it. */
@Serializable
data class PromptView(
    val id: String,
    val name: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)
