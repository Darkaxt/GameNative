package app.gamenative

import app.gamenative.enums.OS
import java.util.EnumSet
import junit.framework.TestCase.assertEquals
import org.junit.Test

class EnumOSTest {

    @Test
    fun testOSFromBitmask() {
        for (code in 1..7) {
            val expected = OS.entries.filter { it != OS.none && code and it.code == it.code }
                .toCollection(EnumSet.noneOf(OS::class.java))
            assertEquals(expected, OS.from(code))
            assertEquals(code, OS.code(OS.from(code)))
        }
        assertEquals(EnumSet.of(OS.none), OS.from(0))
        assertEquals(EnumSet.of(OS.none), OS.from(8))
        assertEquals(EnumSet.of(OS.windows), OS.from(9))
    }

    @Test
    fun testOSFromString() {
        assertEquals(EnumSet.of(OS.windows, OS.macos, OS.linux), OS.from("windows,macos,linux"))
        assertEquals(EnumSet.of(OS.windows, OS.macos), OS.from("windows,macos"))
        assertEquals(EnumSet.of(OS.windows), OS.from("windows"))
        assertEquals(EnumSet.of(OS.none), OS.from(null))
        assertEquals(EnumSet.of(OS.none), OS.from(""))
        assertEquals(EnumSet.of(OS.none), OS.from("invalid"))
    }
}
