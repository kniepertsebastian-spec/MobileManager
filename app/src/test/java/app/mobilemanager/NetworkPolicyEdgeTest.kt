package app.mobilemanager

import app.mobilemanager.domain.extras.DataLimitPolicy
import app.mobilemanager.domain.extras.FirewallPolicy
import app.mobilemanager.domain.extras.NetType
import app.mobilemanager.domain.extras.WifiSafetyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roadmap v2 D.1: Grenz- und Negativfälle der Netzwerk-Regeln. */
class NetworkPolicyEdgeTest {

    @Test
    fun protectedAppsAreNeverBlocked() {
        val all = setOf("self", "dialer", "a")
        NetType.entries.forEach { net ->
            listOf(true, false).forEach { kill ->
                val denied = FirewallPolicy.denied(net, all, all, kill, all, protected = setOf("self", "dialer"))
                assertFalse(denied.contains("self"))
                assertFalse(denied.contains("dialer"))
            }
        }
    }

    @Test
    fun emptyAndDisjointRules() {
        assertTrue(FirewallPolicy.denied(NetType.WIFI, emptySet(), emptySet(), false, emptySet(), emptySet()).isEmpty())
        // Ohne Netz bleibt nur gesperrt, was auf beiden Netzarten gesperrt ist.
        assertTrue(FirewallPolicy.denied(NetType.NONE, setOf("a"), setOf("b"), false, emptySet(), emptySet()).isEmpty())
        // Kill-Switch inaktiv → seine Liste zählt nicht.
        assertTrue(FirewallPolicy.denied(NetType.WIFI, emptySet(), emptySet(), false, setOf("x"), emptySet()).isEmpty())
        // Kill-Switch wirkt auch ohne Netz.
        assertEquals(setOf("x"), FirewallPolicy.denied(NetType.NONE, emptySet(), emptySet(), true, setOf("x"), emptySet()))
    }

    @Test
    fun diffIsIdempotentAndHandlesEmpty() {
        assertEquals(emptySet<String>() to emptySet<String>(), FirewallPolicy.diff(setOf("a"), setOf("a")))
        assertEquals(setOf("a") to emptySet<String>(), FirewallPolicy.diff(emptySet(), setOf("a")))
        assertEquals(emptySet<String>() to setOf("a"), FirewallPolicy.diff(setOf("a"), emptySet()))
    }

    @Test
    fun dataLimitBoundaries() {
        val mb = 1024L * 1024
        assertTrue("genau am Limit warnt", DataLimitPolicy.shouldWarn(200 * mb, 200, 0, 1))
        assertFalse(DataLimitPolicy.shouldWarn(200 * mb - 1, 200, 0, 1))
        assertFalse("negatives Limit = aus", DataLimitPolicy.shouldWarn(Long.MAX_VALUE, -1, 0, 1))
        assertFalse("unbekannte Nutzung", DataLimitPolicy.shouldWarn(-1, 1, 0, 1))
        // Kein Int-Überlauf bei großen Limits.
        assertFalse(DataLimitPolicy.shouldWarn(3L * 1024 * mb, Int.MAX_VALUE, 0, 1))
        assertTrue(DataLimitPolicy.shouldWarn(Long.MAX_VALUE, Int.MAX_VALUE, 0, 1))
    }

    @Test
    fun wifiSafetyIgnoresUnknownTypes() {
        listOf(-1, 2, 3, 4, 5, 7, 8, 9, 11, 99).forEach { assertNull("Typ $it", WifiSafetyPolicy.warning(it, false)) }
    }
}
