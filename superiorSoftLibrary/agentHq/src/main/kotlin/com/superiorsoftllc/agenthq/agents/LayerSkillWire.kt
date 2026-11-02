package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.LayerSkillFrames
import com.superiorsoftllc.agenthq.protocol.LayerSkillRequest
import com.superiorsoftllc.agenthq.protocol.LayerSkillView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** SKILLS-GLOBAL-1b: the daemon handler of `layer_skill_save|list|delete`. [push] gets every changed-scope answer. */
class LayerSkillWire(private val store: LayeredSkillStore, private val push: (Envelope) -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(LayerSkillView.serializer())

    /** The reply envelope; null when the answer was pushed to every client instead. */
    fun handle(envelope: Envelope): Envelope? {
        val op = envelope.type.removePrefix("layer_skill_")
        return try {
            val req = json.decodeFromString(LayerSkillRequest.serializer(), envelope.data)
            val scope = scopeOf(req)
            when (op) {
                "list" -> answer(scope)
                "save" -> {
                    store.save(scope, LayerSkill(req.name.orEmpty(), req.description, req.body.orEmpty()))
                    push(answer(scope)); null
                }
                "delete" -> {
                    require(store.delete(scope, req.name.orEmpty())) { "unknown skill '${req.name.orEmpty().trim()}' in the ${scope.layer.label} layer" }
                    push(answer(scope)); null
                }
                else -> Envelope(Envelope.ERROR, "layer_skill_$op refused: unknown operation")
            }
        } catch (e: IllegalArgumentException) {
            Envelope(Envelope.ERROR, "layer_skill_$op refused: ${e.message ?: "invalid request"}")
        } catch (e: kotlinx.serialization.SerializationException) {
            Envelope(Envelope.ERROR, "layer_skill_$op refused: malformed request")
        }
    }

    private fun scopeOf(r: LayerSkillRequest): SkillScope {
        val layer = SkillLayer.entries.firstOrNull { it.label == r.scope.trim().lowercase() }
            ?: throw IllegalArgumentException("scope must be global, project or role")
        return SkillScope(layer, r.projectId?.trim(), r.role?.trim())
    }

    private fun answer(scope: SkillScope): Envelope {
        val views = store.list(scope).map {
            LayerSkillView(scope.layer.label, it.name, it.description, it.body, scope.projectId, scope.role)
        }
        return Envelope(Envelope.LAYER_SKILLS, json.encodeToString(ser, views))
    }

    companion object {
        /** The inline cutoff setting (chars) or the default. */
        fun inlineMax(store: AgentHqStore): Int =
            store.settings.get(LayerSkillFrames.INLINE_MAX_SETTING)?.value?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: 1_500
    }
}
