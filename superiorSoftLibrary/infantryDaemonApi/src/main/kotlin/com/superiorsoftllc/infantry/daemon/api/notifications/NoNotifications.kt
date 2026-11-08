// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.notifications

import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.Notifications

/**
 * NOTIFY-CORE-1 (1.53.0): the notification bus of a daemon that has none (an older host, a test double): every post is dropped
 * and answers its own id; presenters and action listeners are never called.
 */
object NoNotifications : Notifications {
    override fun notify(title: String, body: String) {}
    override fun notify(notification: Notification): String = notification.id
}
