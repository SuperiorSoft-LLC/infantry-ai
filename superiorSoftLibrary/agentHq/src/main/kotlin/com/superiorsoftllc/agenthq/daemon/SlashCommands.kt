package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.MessageOrigin
import com.superiorsoftllc.agenthq.protocol.SystemKind
import com.superiorsoftllc.agenthq.harness.CommandAction
import com.superiorsoftllc.agenthq.harness.CommandLines
import com.superiorsoftllc.agenthq.harness.HarnessCommand
import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.protocol.CommandCatalogAnswer
import com.superiorsoftllc.agenthq.protocol.CommandCatalogRequest
import com.superiorsoftllc.agenthq.protocol.CommandResultAnswer
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlinx.serialization.json.Json

/**
 * CP-4 slices (a)+(b) and the boundary rule: the daemon serves each harness's `/` command catalog, and an input that
 * starts with `/` is a command, never a prompt.
 *
 * The daemon names no vendor here: a catalog comes from the harness plugin registered under the provider's id, or,
 * for a provider that is not a plugin (the stub), from the provider itself.
 */
internal object SlashCommands {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The catalog for [providerId], or null when no provider or harness has that id. */
    fun commandsFor(manager: SessionManager, harnesses: HarnessRegistry, providerId: String): List<HarnessCommand>? =
        harnesses.resolve(providerId)?.commands
            ?: manager.providers.firstOrNull { it.id == providerId }?.commands

    /** Answers a `command_catalog` request; a refusal is an `error` frame naming what was wrong. */
    fun catalog(envelope: Envelope, manager: SessionManager, harnesses: HarnessRegistry): Envelope = try {
        val request = CommandCatalogRequest.decode(envelope.data)
        val sessionId = envelope.sessionId
        val session = sessionId?.let { manager.get(it) ?: return Envelope(Envelope.ERROR, "command_catalog refused: unknown session '$it'") }
        val providerId = request.provider?.trim()?.takeIf { it.isNotEmpty() } ?: session?.provider ?: manager.providerId
        val catalog = commandsFor(manager, harnesses, providerId)
            ?: return Envelope(Envelope.ERROR, "command_catalog refused: unknown provider '$providerId'")
        Envelope(
            Envelope.COMMAND_CATALOG,
            json.encodeToString(
                CommandCatalogAnswer.serializer(),
                CommandCatalogAnswer(providerId, request.prefix, CommandLines.complete(catalog, request.prefix)),
            ),
            sessionId,
        )
    } catch (e: IllegalArgumentException) {
        Envelope(Envelope.ERROR, "command_catalog refused: ${e.message ?: "invalid request"}")
    }

    /**
     * What a `prompt` whose text starts with `/` becomes (CP-4 slice d): a `command_result` frame when the command ran,
     * an `error` frame when it was refused, and NEVER a turn handed to the harness as a prompt. The daemon knows only
     * the three [CommandAction]s, not a vendor:
     *
     *  - [CommandAction.SET_MODEL]: the session's next run names the model.
     *  - [CommandAction.SET_EFFORT]: the session's next run carries the level, which must be one of the catalog's values.
     *  - [CommandAction.NATIVE]: the harness's OWN command text (Claude's `/compact`) becomes the session's next turn on
     *    the same provider conversation; the harness does the work, the daemon reimplements nothing.
     *
     * An unknown command lists the known ones; a command needs a session to act on and never starts one.
     */
    fun execute(envelope: Envelope, active: String?, manager: SessionManager, harnesses: HarnessRegistry): Envelope {
        val line = CommandLines.parse(envelope.data)
        val session = (envelope.sessionId ?: active)?.let { manager.get(it) }
        if (envelope.sessionId != null && session == null) {
            return Envelope(Envelope.ERROR, "unknown session '${envelope.sessionId}': /${line.name} was not run")
        }
        val providerId = session?.provider ?: manager.providerId
        val catalog = commandsFor(manager, harnesses, providerId).orEmpty()
        val command = catalog.firstOrNull { it.name.equals(line.name, ignoreCase = true) }
            ?: return Envelope(
                Envelope.ERROR,
                "unknown command '/${line.name}' for provider '$providerId'; known commands: " +
                    catalog.joinToString(", ") { "/${it.name}" }.ifEmpty { "(none)" },
            )
        if (session == null) {
            return Envelope(Envelope.ERROR, "/${command.name} needs a session to apply to; none is active, and a command never starts one")
        }
        return try {
            val message = when (command.action) {
                CommandAction.SET_MODEL -> {
                    require(line.args.isNotEmpty()) { "usage: /${command.name} ${command.argument ?: "<model>"}" }
                    manager.setModel(session.id, line.args)
                    "model set to ${line.args}; it applies from the next turn"
                }
                CommandAction.SET_EFFORT -> {
                    val levels = command.values
                    require(line.args.isNotEmpty()) { "usage: /${command.name} ${levels.joinToString("|").ifEmpty { command.argument ?: "<level>" }}" }
                    val level = levels.firstOrNull { it.equals(line.args, ignoreCase = true) }
                        ?: if (levels.isEmpty()) line.args else throw IllegalArgumentException(
                            "'${line.args.take(40)}' is not an effort level of provider '$providerId'; valid levels: ${levels.joinToString(", ")}",
                        )
                    manager.setEffort(session.id, level)
                    "effort set to $level; it applies from the next turn"
                }
                CommandAction.NATIVE -> {
                    require(session.providerSessionId != null) { "/${command.name} has nothing to act on: this session has not run a turn yet" }
                    manager.resume(session.id, command.native!!, MessageOrigin.System(SystemKind.SLASH_COMMAND))
                    "${command.name}: the harness's own ${command.native} was started on this session's conversation"
                }
            }
            Envelope(
                Envelope.COMMAND_RESULT,
                json.encodeToString(CommandResultAnswer.serializer(), CommandResultAnswer(true, command.name, message)),
                session.id,
            )
        } catch (e: IllegalArgumentException) {
            Envelope(Envelope.ERROR, "/${command.name} refused: ${e.message ?: "invalid"}")
        } catch (e: IllegalStateException) {
            Envelope(Envelope.ERROR, "/${command.name} refused: ${e.message ?: "the session cannot take it now"}")
        }
    }
}
