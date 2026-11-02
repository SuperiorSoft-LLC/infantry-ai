package com.superiorsoftllc.agenthq.role

import com.superiorsoftllc.agenthq.daemon.api.roles.RoleRecord
import com.superiorsoftllc.agenthq.daemon.api.roles.Roles

/** AR-4b: core's local provider of the [Roles] port, over [RoleService]. The Infantry_Roles daemon half takes it over later. */
class LocalRoles(private val service: RoleService) : Roles {
    override fun all(): List<RoleRecord> = service.all().map(::record)
    override fun role(id: String): RoleRecord? = service.role(id)?.let(::record)

    companion object {
        fun record(r: Role) = RoleRecord(
            r.id, r.name, r.color, r.persona, r.source.name, r.icon, r.description,
            r.responseDetail, r.responseMaxChars, r.responseBodyChars,
        )
    }
}
