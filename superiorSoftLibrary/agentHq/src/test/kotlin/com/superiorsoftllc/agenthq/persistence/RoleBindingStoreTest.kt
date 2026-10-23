package com.superiorsoftllc.agenthq.persistence

import com.superiorsoftllc.agenthq.role.RoleBinding
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ROLE-1a: a role owns its provider / model / effort; rebinding overwrites in place. */
class RoleBindingStoreTest {

    private fun open(): SqlAgentHqStore {
        val f = Files.createTempFile("agenthq-rolebinding-", ".db").toFile().apply { delete() }
        return SqlAgentHqStore.open(f.absolutePath)
    }

    @Test
    fun `bind a role to model A reads A and rebinding to B reads B`() {
        open().use { store ->
            assertNull(store.roleBindings.get("backend"), "an unbound role has no binding")

            store.roleBindings.set(RoleBinding("backend", "claude", "model-A", "high"), at = 10)
            val a = store.roleBindings.get("backend")!!
            assertEquals("model-A", a.model)
            assertEquals(RoleBinding("backend", "claude", "model-A", "high", 10), a)

            store.roleBindings.set(RoleBinding("backend", "claude", "model-B", null), at = 20)
            val b = store.roleBindings.get("backend")!!
            assertEquals("model-B", b.model, "rebind must replace A")
            assertNull(b.effort, "a null field clears the pin")
            assertEquals(20L, b.updatedAt)
            assertEquals(1, store.roleBindings.all().size, "rebinding never adds a second row")
        }
    }

    @Test
    fun `bindings are per role and listed by role id`() {
        open().use { store ->
            store.roleBindings.set(RoleBinding("qa", "codex", "m1"), 1)
            store.roleBindings.set(RoleBinding("backend", "claude", "m2"), 2)
            assertEquals(listOf("backend", "qa"), store.roleBindings.all().map { it.roleId })
        }
    }
}
