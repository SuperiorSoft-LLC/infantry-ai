package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.images.ChatImageRetention
import java.io.File

/** Chat-image wiring (CORE-SPLIT-4a1). IMG-5: deletes chat uploads unused for `images.chatImageRetentionDays`. */
object ImagesWiring {
    fun buildRetention(
        projectRoots: () -> List<File>,
        retentionDays: () -> Int,
        clock: () -> Long,
        ticker: (Long, () -> Unit) -> AutoCloseable,
    ): ChatImageRetention = ChatImageRetention(
        projectRoots = projectRoots,
        retentionDays = retentionDays,
        clock = clock,
        ticker = ticker,
    )
}
