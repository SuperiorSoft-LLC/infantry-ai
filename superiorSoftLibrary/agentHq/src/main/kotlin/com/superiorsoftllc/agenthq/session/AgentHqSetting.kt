package com.superiorsoftllc.agenthq.session

/**
 * PKG-1: one AgentHQ setting, read as a JVM system property FIRST and then as an environment variable of the
 * same name.
 *
 * Why a property at all: the installed app (`AppMainKt` under the jpackage launcher, packaged by Compose Desktop) cannot set its own
 * environment, so it pins its isolated state dir and port as system properties before the daemon is built. A
 * dev run never sets these properties, so for every dev entry this is exactly `InfantryEnv.get(name)`.
 *
 * Why the property wins: an environment variable a developer exported for the dev daemon (`AGENTHQ_STATE_DIR`,
 * `AGENTHQ_PORT`) must never pull the installed app onto the dev state or the dev port.
 */
fun agentHqSetting(
    name: String,
    getProperty: (String) -> String? = System::getProperty,
    getenv: (String) -> String? = InfantryEnv::get,
): String? = getProperty(name)?.takeIf { it.isNotBlank() } ?: getenv(name)
