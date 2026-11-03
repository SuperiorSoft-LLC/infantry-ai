package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.daemon.api.roles.RoleBindingRecord
import com.superiorsoftllc.infantry.daemon.api.roles.RoleBindings
import com.superiorsoftllc.infantry.daemon.api.roles.RoleRecord
import com.superiorsoftllc.infantry.daemon.api.roles.RoleTraitLists
import com.superiorsoftllc.infantry.daemon.api.roles.RoleTraitsRecord
import com.superiorsoftllc.infantry.daemon.api.roles.Roles
import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.protocol.RoleIds
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** AR-4b-ii-b: the [Roles] port over the plugin's own [RoleService]. */
class ServiceRoles(private val service: RoleService) : Roles {
    override fun all(): List<RoleRecord> = service.all().map(::record)
    override fun role(id: String): RoleRecord? = service.role(id)?.let(::record)

    companion object {
        fun record(r: Role) = RoleRecord(
            r.id, r.name, r.color, r.persona, r.source.name, r.icon, r.description,
            r.responseDetail, r.responseMaxChars, r.responseBodyChars,
        )
    }
}

/** AR-4b-ii-b: the role bindings in the adopted `role_bindings` table (module.json `daemon.storage.adopt`; core creates it). */
class StorageRoleBindings(private val db: PluginStorage) : RoleBindings {
    private fun Map<String, Any?>.toRecord() = RoleBindingRecord(
        this["role_id"] as String, this["provider"] as String?, this["model"] as String?, this["effort"] as String?, (this["updated_at"] as Number).toLong(),
    )

    // ORIGIN-SCRUB-1a: a retired role id (an older client) reads and writes the row of the id that replaced it.
    override fun get(roleId: String): RoleBindingRecord? =
        db.query("SELECT * FROM role_bindings WHERE role_id = ?", listOf(RoleIds.normalize(roleId))).firstOrNull()?.toRecord()

    override fun all(): List<RoleBindingRecord> = db.query("SELECT * FROM role_bindings ORDER BY role_id ASC").map { it.toRecord() }

    override fun set(binding: RoleBindingRecord, at: Long) {
        val id = RoleIds.normalize(binding.roleId)
        db.transaction {
            db.execute("INSERT OR IGNORE INTO role_bindings(role_id, provider, model, effort, updated_at) VALUES (?, ?, ?, ?, ?)", listOf(id, binding.provider, binding.model, binding.effort, at))
            db.execute("UPDATE role_bindings SET provider = ?, model = ?, effort = ?, updated_at = ? WHERE role_id = ?", listOf(binding.provider, binding.model, binding.effort, at, id))
        }
    }
}

/** AR-4b-ii-b: the role trait lists in the adopted `role_traits` table. */
class StorageRoleTraitLists(private val db: PluginStorage) : RoleTraitLists {
    private val ids = ListSerializer(String.serializer())

    private fun Map<String, Any?>.toRecord() =
        RoleTraitsRecord(this["role_id"] as String, Json.decodeFromString(ids, this["trait_ids"] as String), (this["updated_at"] as Number).toLong())

    override fun get(roleId: String): RoleTraitsRecord? =
        db.query("SELECT * FROM role_traits WHERE role_id = ?", listOf(RoleIds.normalize(roleId))).firstOrNull()?.toRecord()

    override fun all(): List<RoleTraitsRecord> = db.query("SELECT * FROM role_traits ORDER BY role_id ASC").map { it.toRecord() }

    override fun set(roleId: String, traitIds: List<String>, at: Long, beforeWrite: () -> Unit): RoleTraitsRecord = db.transaction {
        beforeWrite()
        val id = RoleIds.normalize(roleId)
        val encoded = Json.encodeToString(ids, traitIds)
        db.execute("INSERT OR IGNORE INTO role_traits(role_id, trait_ids, updated_at) VALUES (?, ?, ?)", listOf(id, encoded, at))
        db.execute("UPDATE role_traits SET trait_ids = ?, updated_at = ? WHERE role_id = ?", listOf(encoded, at, id))
        RoleTraitsRecord(id, traitIds, at)
    }
}
