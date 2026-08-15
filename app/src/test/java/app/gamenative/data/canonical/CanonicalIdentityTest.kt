package app.gamenative.data.canonical

import app.gamenative.data.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalIdentityTest {
    @Test
    fun `canonical IDs are opaque UUIDs`() {
        val id = CanonicalGameId.parse("3f84adbe-7c61-4a4a-83cf-6f2e4ec2eb55")

        assertEquals("3f84adbe-7c61-4a4a-83cf-6f2e4ec2eb55", id.value)
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalGameId.parse("STEAM_620")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalGameId.parse("3F84ADBE-7C61-4A4A-83CF-6F2E4EC2EB55")
        }
    }

    @Test
    fun `random canonical IDs are valid and distinct`() {
        val first = CanonicalGameId.random()
        val second = CanonicalGameId.random()

        assertEquals(first, CanonicalGameId.parse(first.value))
        assertNotEquals(first, second)
    }

    @Test
    fun `canonical ID generator supports deterministic IDs`() {
        val expected = CanonicalGameId.parse("3f84adbe-7c61-4a4a-83cf-6f2e4ec2eb55")
        val generator = CanonicalIdGenerator { expected }

        assertEquals(expected, generator.generate())
    }

    @Test
    fun `account scopes accept only lowercase SHA-256 hex`() {
        val value = "a".repeat(64)

        assertEquals(value, AccountScope.parse(value).value)
        listOf(
            "76561198000000000",
            "account-id",
            "A".repeat(64),
            "a".repeat(63),
            "a".repeat(65),
            "g".repeat(64),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                AccountScope.parse(invalid)
            }
        }
    }

    @Test
    fun `owned copy key keeps all fields structured`() {
        val scope = AccountScope.parse("a".repeat(64))
        val stableSourceId = EpicStableSourceId.encode("ns:one", "商品/id")
        val key = OwnedCopyKey(scope, GameSource.EPIC, stableSourceId)

        assertEquals(scope, key.accountScope)
        assertEquals(GameSource.EPIC, key.source)
        assertEquals(stableSourceId, key.stableSourceId)
        assertEquals("ns:one" to "商品/id", EpicStableSourceId.decode(key.stableSourceId))
        assertEquals(key, OwnedCopyKey(scope, GameSource.EPIC, stableSourceId))
    }

    @Test
    fun `owned copy keys keep sources separate`() {
        val scope = AccountScope.parse("b".repeat(64))

        assertNotEquals(
            OwnedCopyKey(scope, GameSource.STEAM, "620"),
            OwnedCopyKey(scope, GameSource.GOG, "620"),
        )
    }

    @Test
    fun `owned copy keys reject blank stable source IDs`() {
        val scope = AccountScope.parse("c".repeat(64))

        assertThrows(IllegalArgumentException::class.java) {
            OwnedCopyKey(scope, GameSource.STEAM, "  ")
        }
    }

    @Test
    fun `owned copy keys enforce provider stable ID formats`() {
        val scope = AccountScope.parse("d".repeat(64))

        listOf("01", "gog-1").forEach { stableSourceId ->
            assertThrows(IllegalArgumentException::class.java) {
                OwnedCopyKey(scope, GameSource.GOG, stableSourceId)
            }
        }
        listOf(
            "11111111-1111-1111-1111-111111111111",
            "amzn1.adg.product.11111111-1111-1111-1111-11111111111A",
        ).forEach { stableSourceId ->
            assertThrows(IllegalArgumentException::class.java) {
                OwnedCopyKey(scope, GameSource.AMAZON, stableSourceId)
            }
        }

        assertEquals("1", OwnedCopyKey(scope, GameSource.GOG, "1").stableSourceId)
        assertEquals(
            "amzn1.adg.product.11111111-1111-1111-1111-111111111111",
            OwnedCopyKey(
                scope,
                GameSource.AMAZON,
                "amzn1.adg.product.11111111-1111-1111-1111-111111111111",
            ).stableSourceId,
        )
    }

    @Test
    fun `Epic stable IDs round trip delimiters and Unicode`() {
        val namespace = "namespace:with.dots/and spaces"
        val catalogId = "商品/id:ß.δ"
        val encoded = EpicStableSourceId.encode(namespace, catalogId)

        assertEquals(namespace to catalogId, EpicStableSourceId.decode(encoded))
        assertEquals(2, encoded.split('.').size)
        assertTrue(encoded.none(Char::isWhitespace))
    }

    @Test
    fun `Epic stable IDs reject incomplete values`() {
        listOf("" to "catalog", "namespace" to " ").forEach { (namespace, catalogId) ->
            assertThrows(IllegalArgumentException::class.java) {
                EpicStableSourceId.encode(namespace, catalogId)
            }
        }
        listOf("one-part", "one.two.three", "Zg==.YQ==", "Zh.YQ").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                EpicStableSourceId.decode(value)
            }
        }
    }
}
