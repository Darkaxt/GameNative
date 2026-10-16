package com.winlator.core.envvars

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvVarRedactionTest {
    @Test
    fun processEnvironmentMasksEverySupportedCredentialAndAccountField() {
        val names = listOf(
            "STEAMHOST_TOKEN", "STEAMHOST_ACCOUNT", "STEAMHOST_STEAMID64",
            "SteamUser", "SteamAppUser", "STEAMID", "AMAZON_GAMES_FUEL_ENTITLEMENT_ID",
        )
        val environment = names.map { "$it=synthetic=value" }.toTypedArray()
        val original = environment.clone()

        assertEquals(names.joinToString(", ", "[", "]") { "$it=<redacted>" }, EnvVarRedaction.redact(environment))
        assertArrayEquals(original, environment)
    }

    @Test
    fun processEnvironmentPreservesNonSensitiveJavaOptionsAndLaunchSelection() {
        val environment = arrayOf("_JAVA_OPTIONS=-XX:-UseZGC", "STEAMHOST_LAUNCH_OPTION=7")

        assertEquals("[_JAVA_OPTIONS=-XX:-UseZGC, STEAMHOST_LAUNCH_OPTION=7]", EnvVarRedaction.redact(environment))
    }

    @Test
    fun processEnvironmentHandlesNullAndMalformedEntriesWithoutCrashing() {
        assertEquals("null", EnvVarRedaction.redact(null as Array<String>?))
        assertEquals("[null, , NO_EQUALS, STEAMHOST_TOKEN=<redacted>]", EnvVarRedaction.redact(arrayOf(null, "", "NO_EQUALS", "STEAMHOST_TOKEN=")))
    }
}
