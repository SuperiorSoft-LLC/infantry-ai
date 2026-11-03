package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.protocol.TraitCheck
import com.superiorsoftllc.infantry.protocol.TraitView
import kotlinx.serialization.Serializable

/**
 * TRAITS-T4 (docs/INFANTRY-ROLE-TRAITS.md section 3): a frozen copy of one trait as it was when a session spawned. A copy,
 * not a reference: a later edit of the trait (its version moves) never changes what a started session runs under, and
 * [version] says which rule it ran.
 */
@Serializable
data class TraitSnapshot(
    val id: String,
    val version: Int,
    val name: String,
    val body: String,
    val check: TraitCheck? = null,
) {
    companion object {
        fun of(trait: TraitView): TraitSnapshot = TraitSnapshot(trait.id, trait.version, trait.name, trait.body, trait.check)
    }
}
