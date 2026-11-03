package com.superiorsoftllc.infantry.db

import com.superiorsoftllc.infantry.persistence.SettingRow
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore

/** Second-JVM helper for [LockAttributionTest]: takes the write lock, says so, and never lets go. */
object LockHolderChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = SqlInfantryStore.open(args[0], args[1])
        store.transaction {
            store.settings.put(SettingRow("held-by-child", "v", 1))
            println("HOLDING"); System.out.flush()
            Thread.sleep(120_000)
        }
    }
}
