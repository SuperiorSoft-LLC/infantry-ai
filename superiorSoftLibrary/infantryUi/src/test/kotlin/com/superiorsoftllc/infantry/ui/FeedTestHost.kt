// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.superiorsoftllc.infantry.plugins.official.chatfeed.InfantryChatFeed
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.OutputLine
import com.superiorsoftllc.infantry.ui.model.TimelineItem
import com.superiorsoftllc.infantry.ui.plugin.TabFactory

/**
 * CHAT-FEED-SPLIT-1: the feed draws its rows by kind from the loaded modules' registry, so a test that draws the feed
 * directly loads Infantry_ChatFeed, the way the app does. These two shadow the feed's own functions of the same name
 * (this package wins over a star import), so the many tests that call them need no change.
 */
private val feedFactory by lazy { TabFactory(listOf(InfantryChatFeed)) }

@Composable
fun AgentChat(agent: AgentView, nowProvider: () -> Long = { System.currentTimeMillis() }, roleColor: String? = null, questionSlot: @Composable () -> Unit = {}, contributions: List<TimelineItem> = emptyList()) =
    CompositionLocalProvider(LocalTabFactory provides feedFactory) {
        com.superiorsoftllc.infantry.plugins.official.chatfeed.AgentChat(agent, nowProvider, roleColor, questionSlot, contributions)
    }

@Composable
fun ChatLine(line: OutputLine, agentId: String, agentName: String, agentRole: String? = null, agentRoleColor: String? = null, showAvatar: Boolean = true) =
    CompositionLocalProvider(LocalTabFactory provides feedFactory) {
        com.superiorsoftllc.infantry.plugins.official.chatfeed.ChatLine(line, agentId, agentName, agentRole, agentRoleColor, showAvatar)
    }
