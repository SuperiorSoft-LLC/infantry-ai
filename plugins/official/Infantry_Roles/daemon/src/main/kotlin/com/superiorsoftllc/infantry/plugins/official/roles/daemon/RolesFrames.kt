package com.superiorsoftllc.infantry.plugins.official.roles.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.roles.RoleBindings
import com.superiorsoftllc.infantry.daemon.api.roles.RoleEnvironment
import com.superiorsoftllc.infantry.daemon.api.roles.RoleTraitLists
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.RoleBinding
import com.superiorsoftllc.infantry.protocol.RoleBindingSave
import com.superiorsoftllc.infantry.protocol.RoleSave
import com.superiorsoftllc.infantry.protocol.RoleTraits
import com.superiorsoftllc.infantry.protocol.RoleTraitsSave
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * AR-4b-ii-b: the role frames, answered here instead of by the daemon core: the catalog (`role_list`, `role_save`, `role_delete`),
 * the bindings (`role_binding_*`) and the trait lists (`role_traits_*`). Same types, payloads, pushes and refusals as when core
 * answered them; a daemon without this plugin answers `<type> refused: this daemon has no roles`.
 */
object RolesFrames {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** A plugin tool (or a loopback caller) waits for its own request id; the push to every client carries none. */
    private fun direct(frame: Frame): Boolean = frame.requestId?.let { it.startsWith("plugin:") || it.startsWith("loopback:") } == true

    private fun error(text: String) = Frame(Envelope.ERROR, text)

    /**
     * Where custom roles are written: `INFANTRY_ROLES_DIR`, else `roles/` in the daemon's state directory (the plugin's own folder is
     * `<state>/plugin-data/<id>/`, so the state directory is two levels up; custom roles stay where they always were).
     */
    fun rolesDir(api: DaemonApi): File =
        api.env(RoleService.ENV)?.takeIf { it.isNotBlank() }?.let(::File) ?: File(api.stateDir.parentFile.parentFile, "roles")

    fun register(api: DaemonApi, service: RoleService) {
        val push = { frame: Frame -> api.boardRuntime.push(listOf(frame)) }
        val environment = { api.services.find(RoleEnvironment::class) }
        val bindings = { api.services.find(RoleBindings::class) }
        val traits = { api.services.find(RoleTraitLists::class) }

        api.frames.handle(Envelope.ROLE_LIST) { _, _ ->
            Frame(Envelope.ROLES, json.encodeToString(ListSerializer(Role.serializer()), service.all()))
        }
        api.frames.handle(Envelope.ROLE_SAVE) { frame, _ ->
            try {
                val saved = service.save(RoleSave.decode(frame.data))
                val out = Frame(Envelope.ROLE, json.encodeToString(Role.serializer(), saved))
                push(out)
                if (direct(frame)) out else null
            } catch (e: IllegalArgumentException) {
                error("role_save refused: ${e.message ?: "invalid role"}")
            } catch (e: Exception) {
                error("role_save failed: ${e.message ?: e::class.simpleName}")
            }
        }
        // Contract 1.108.0 (ROLE-EDIT-1): a reset override is pushed as `role`, a removed custom role as `role_removed`.
        api.frames.handle(Envelope.ROLE_DELETE) { frame, _ ->
            try {
                val id = frame.data.trim()
                val remaining = service.delete(id, environment()?.liveRoleIds().orEmpty())
                val out = if (remaining != null) Frame(Envelope.ROLE, json.encodeToString(Role.serializer(), remaining)) else Frame(Envelope.ROLE_REMOVED, id)
                push(out)
                if (direct(frame)) out else null
            } catch (e: IllegalArgumentException) {
                error("role_delete refused: ${e.message ?: "invalid role"}")
            } catch (e: Exception) {
                error("role_delete failed: ${e.message ?: e::class.simpleName}")
            }
        }

        fun bindingHandler(): RoleBindingHandler? {
            val store = bindings() ?: return null
            val env = environment() ?: return null
            return RoleBindingHandler(store, service, env)
        }
        api.frames.handle(Envelope.ROLE_BINDING_LIST) { _, _ ->
            val h = bindingHandler() ?: return@handle error("role_binding_list refused: this daemon has no role bindings")
            Frame(Envelope.ROLE_BINDINGS, json.encodeToString(ListSerializer(RoleBinding.serializer()), h.all()))
        }
        api.frames.handle(Envelope.ROLE_BINDING_GET) { frame, _ ->
            val h = bindingHandler() ?: return@handle error("role_binding_get refused: this daemon has no role bindings")
            Frame(Envelope.ROLE_BINDING, json.encodeToString(RoleBinding.serializer(), h.get(frame.data.trim())))
        }
        // Contract 1.40.0 (ROLE-1c): a set is answered by the push of the stored `role_binding` to EVERY client; a refusal is an `error`.
        api.frames.handle(Envelope.ROLE_BINDING_SET) { frame, _ ->
            try {
                val h = bindingHandler() ?: return@handle error("role_binding_set refused: this daemon has no role bindings")
                val stored = h.set(RoleBindingSave.decode(frame.data))
                push(Frame(Envelope.ROLE_BINDING, json.encodeToString(RoleBinding.serializer(), stored)))
                null
            } catch (e: IllegalArgumentException) {
                error("role_binding_set refused: ${e.message ?: "invalid binding"}")
            } catch (e: Exception) {
                error("role_binding_set failed: ${e.message ?: e::class.simpleName}")
            }
        }

        fun traitHandler(): RoleTraitHandler? {
            val store = traits() ?: return null
            val env = environment() ?: return null
            return RoleTraitHandler(store, env, roleExists = { service.role(it) != null })
        }
        api.frames.handle(Envelope.ROLE_TRAITS_LIST) { _, _ ->
            val h = traitHandler() ?: return@handle error("role_traits_list refused: this daemon has no role traits")
            Frame(Envelope.ROLE_TRAITS_ALL, json.encodeToString(ListSerializer(RoleTraits.serializer()), h.all()))
        }
        api.frames.handle(Envelope.ROLE_TRAITS_GET) { frame, _ ->
            val h = traitHandler() ?: return@handle error("role_traits_get refused: this daemon has no role traits")
            Frame(Envelope.ROLE_TRAITS, json.encodeToString(RoleTraits.serializer(), h.get(frame.data.trim())))
        }
        // Contract 1.49.0 (TRAITS-T2): a set is answered by the push of the stored `role_traits` to EVERY client.
        api.frames.handle(Envelope.ROLE_TRAITS_SET) { frame, _ ->
            try {
                val h = traitHandler() ?: return@handle error("role_traits_set refused: this daemon has no role traits")
                val stored = h.set(RoleTraitsSave.decode(frame.data))
                push(Frame(Envelope.ROLE_TRAITS, json.encodeToString(RoleTraits.serializer(), stored)))
                null
            } catch (e: IllegalArgumentException) {
                error("role_traits_set refused: ${e.message ?: "invalid trait list"}")
            } catch (e: Exception) {
                error("role_traits_set failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }
}
