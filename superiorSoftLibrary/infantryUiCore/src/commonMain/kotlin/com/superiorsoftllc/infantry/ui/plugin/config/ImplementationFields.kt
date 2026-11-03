package com.superiorsoftllc.infantry.ui.plugin.config

import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigSetBy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s D): a newly registered implementation becomes a field's selection only
 * if the field is `exposed`, allows it (`autoSelectNew`) and the user never picked one (`set_by` is not `user`). "New"
 * is against the keys seen at the previous load, stored beside the field as `<field>@known` (own-only, never shown).
 * The very first load records what is there and selects nothing.
 */
object ImplementationFields {
    fun knownKey(field: String) = "$field@known"

    suspend fun reconcile(state: NodeConfigState, tree: ConfigTree, implementations: Implementations) {
        for (f in tree.fields.values.flatten()) {
            val d = f.field
            val registry = d.registry ?: continue
            if (d.type != ConfigFieldTypes.IMPLEMENTATION || !d.exposed || !d.autoSelectNew) continue
            val registered = implementations.keys(registry)
            val known = (state.own(f.node, knownKey(f.key))?.value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            if (known != null) {
                val fresh = registered - known.toSet()
                if (fresh.isNotEmpty() && state.own(f.node, f.key)?.setBy != ConfigSetBy.USER) {
                    state.set(f.node, f.key, JsonPrimitive(fresh.last()), ConfigSetBy.SEED)
                }
            }
            if (known != registered) state.set(f.node, knownKey(f.key), JsonArray(registered.map(::JsonPrimitive)), ConfigSetBy.SEED)
        }
    }
}
