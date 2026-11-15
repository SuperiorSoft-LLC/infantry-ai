// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.update.BuildEnvironment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ENDPOINT-ENV-1: the build environment and a module's `daemon.endpoints` resolution. */
class ServiceEndpointsTest {
    private val table = mapOf(
        "api" to mapOf("prod" to "https://api.example.com/v1/", "dev" to "http://127.0.0.1:18953/v1", "staging" to "https://staging.example.com/v1"),
        "site" to mapOf("prod" to "https://example.com"),
    )
    private val noEnv: (String) -> String? = { null }

    @Test fun `the exact environment wins, then prod, and an undeclared name is null`() {
        assertEquals("http://127.0.0.1:18953/v1", ServiceEndpoints.resolve(table, "Acme_Host", "api", "dev", noEnv))
        assertEquals("https://staging.example.com/v1", ServiceEndpoints.resolve(table, "Acme_Host", "api", "staging", noEnv))
        assertEquals("https://api.example.com/v1", ServiceEndpoints.resolve(table, "Acme_Host", "api", "prod", noEnv), "trailing slash trimmed")
        assertEquals("https://example.com", ServiceEndpoints.resolve(table, "Acme_Host", "site", "dev", noEnv), "no dev entry: prod")
        assertEquals("https://api.example.com/v1", ServiceEndpoints.resolve(table, "Acme_Host", "api", "qa", noEnv), "an unknown environment: prod")
        assertNull(ServiceEndpoints.resolve(table, "Acme_Host", "billing", "prod", noEnv))
        assertNull(ServiceEndpoints.resolve(mapOf("x" to mapOf("dev" to "http://h")), "Acme_Host", "x", "staging", noEnv), "no exact and no prod")
    }

    @Test fun `the developer override variable wins over the table, and a non-http override is ignored`() {
        assertEquals("INFANTRY_ENDPOINT_ACME_HOST_API", ServiceEndpoints.overrideVariable("Acme_Host", "api"))
        assertEquals("INFANTRY_ENDPOINT_INFANTRY_SITES_BILLING_V2", ServiceEndpoints.overrideVariable("Infantry_Sites", "billing.v2"))
        val env = mapOf("INFANTRY_ENDPOINT_ACME_HOST_API" to "http://localhost:9000/x/")
        assertEquals("http://localhost:9000/x", ServiceEndpoints.resolve(table, "Acme_Host", "api", "prod", env::get))
        assertEquals("http://localhost:9000/x", ServiceEndpoints.resolve(emptyMap(), "Acme_Host", "api", "prod", env::get), "override works without a table entry")
        val bad = mapOf("INFANTRY_ENDPOINT_ACME_HOST_API" to "file:///etc/passwd")
        assertEquals("https://api.example.com/v1", ServiceEndpoints.resolve(table, "Acme_Host", "api", "prod", bad::get))
    }

    @Test fun `load check - prod must be https, every address http(s)`() {
        assertNull(ServiceEndpoints.refusal(table))
        assertNull(ServiceEndpoints.refusal(emptyMap()))
        val plainProd = ServiceEndpoints.refusal(mapOf("api" to mapOf("prod" to "http://api.example.com")))
        assertNotNull(plainProd); assertTrue("prod must be https" in plainProd, plainProd)
        assertNotNull(ServiceEndpoints.refusal(mapOf("api" to mapOf("dev" to "ftp://h"))))
        assertNotNull(ServiceEndpoints.refusal(mapOf("api" to mapOf("dev" to "not a url"))))
        assertNotNull(ServiceEndpoints.refusal(mapOf("api" to emptyMap())))
    }

    @Test fun `the environment - run time variable, then the baked property, then the file, then prod`() {
        val home = Files.createTempDirectory("env-home").toFile()
        try {
            assertEquals("prod", BuildEnvironment.resolve(null, null, null))
            assertEquals("prod", BuildEnvironment.resolve(null, null, home), "no ENVIRONMENT file")
            java.io.File(home, BuildEnvironment.FILE).writeText("staging\n")
            assertEquals("staging", BuildEnvironment.resolve(null, null, home))
            assertEquals("dev", BuildEnvironment.resolve(null, "dev", home))
            assertEquals("qa", BuildEnvironment.resolve(" QA ", "dev", home), "the variable wins, trimmed and lower case")
            assertEquals("dev", BuildEnvironment.resolve("bad name!", "dev", home), "an invalid name is skipped")
        } finally { home.deleteRecursively() }
    }
}
