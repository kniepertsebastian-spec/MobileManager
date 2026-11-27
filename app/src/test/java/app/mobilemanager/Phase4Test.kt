package app.mobilemanager

import app.mobilemanager.domain.extras.DataLimitPolicy
import app.mobilemanager.domain.extras.FirewallPolicy
import app.mobilemanager.domain.extras.LockerCrypto
import app.mobilemanager.domain.extras.LockerPolicy
import app.mobilemanager.domain.extras.NetType
import app.mobilemanager.domain.extras.PermissionDiffPolicy
import app.mobilemanager.domain.extras.SecondFactor
import app.mobilemanager.domain.extras.ShellCommandPolicy
import app.mobilemanager.domain.extras.WifiSafetyPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4Test {

    /* ---------- Shell-Erlaubtliste ---------- */

    @Test
    fun shellPolicyAllowsOnlyKnownCommands() {
        assertTrue(ShellCommandPolicy.isAllowed(listOf("cmd", "connectivity", "set-package-networking-enabled", "false", "com.example.app")))
        assertTrue(ShellCommandPolicy.isAllowed(listOf("cmd", "connectivity", "set-chain3-enabled", "true")))
        assertTrue(ShellCommandPolicy.isAllowed(listOf("settings", "put", "global", "private_dns_mode", "hostname")))
        assertTrue(ShellCommandPolicy.isAllowed(listOf("settings", "put", "global", "private_dns_specifier", "dns.quad9.net")))
        assertTrue(ShellCommandPolicy.isAllowed(listOf("cmd", "appops", "set", "com.example.app", "RUN_ANY_IN_BACKGROUND", "ignore")))
        assertTrue(ShellCommandPolicy.isAllowed(listOf("pm", "disable-user", "--user", "0", "com.example.bloat")))
        assertTrue(ShellCommandPolicy.isAllowed(listOf("pm", "grant", "com.example.app", "android.permission.CAMERA")))
        assertTrue(ShellCommandPolicy.isAllowed(listOf("pm", "trim-caches", "999G")))
        assertTrue(ShellCommandPolicy.isAllowed(listOf("settings", "put", "global", "window_animation_scale", "0.5")))
    }

    @Test
    fun shellPolicyRejectsInjectionAndUnknownCommands() {
        assertFalse(ShellCommandPolicy.isAllowed(listOf("sh", "-c", "rm -rf /")))
        assertFalse(ShellCommandPolicy.isAllowed(listOf("pm", "uninstall", "com.example.app")))
        assertFalse(ShellCommandPolicy.isAllowed(listOf("cmd", "connectivity", "set-package-networking-enabled", "false", "com.x; reboot")))
        assertFalse(ShellCommandPolicy.isAllowed(listOf("settings", "put", "global", "adb_enabled", "0")))
        assertFalse(ShellCommandPolicy.isAllowed(listOf("settings", "put", "global", "private_dns_specifier", "evil host")))
        assertFalse(ShellCommandPolicy.isAllowed(listOf("pm", "grant", "com.example.app", "android.permission.READ_SMS")))
        assertFalse(ShellCommandPolicy.isAllowed(listOf("cmd", "appops", "set", "com.example.app", "CAMERA", "allow")))
        assertFalse(ShellCommandPolicy.isAllowed(listOf("pm", "disable-user", "--user", "10", "com.example.app")))
        assertFalse(ShellCommandPolicy.isAllowed(listOf("cmd", "uimode", "night", "yes\nreboot")))
        assertFalse(ShellCommandPolicy.isAllowed(emptyList()))
    }

    /* ---------- TOTP / Base32 / PBKDF2 ---------- */

    @Test
    fun totpMatchesRfc6238Vectors() {
        val secret = "12345678901234567890".toByteArray()
        assertEquals("287082", LockerCrypto.totp(secret, 59_000L))
        assertEquals("081804", LockerCrypto.totp(secret, 1_111_111_109_000L))
        assertEquals("005924", LockerCrypto.totp(secret, 1_234_567_890_000L))
        assertTrue(LockerCrypto.verifyTotp(secret, "287 082", 59_000L + 30_000L))
        assertFalse(LockerCrypto.verifyTotp(secret, "287082", 59_000L + 120_000L))
    }

    @Test
    fun base32RoundTrip() {
        assertEquals("MZXW6YTBOI", LockerCrypto.base32("foobar".toByteArray()))
        val secret = LockerCrypto.newTotpSecret()
        assertArrayEquals(secret, LockerCrypto.base32Decode(LockerCrypto.base32(secret)))
        assertNull(LockerCrypto.base32Decode("not!base32"))
    }

    @Test
    fun pinHashVerifiesOnlyCorrectPin() {
        val salt = LockerCrypto.newSalt()
        val hash = LockerCrypto.pbkdf2("4711".toCharArray(), salt, iterations = 1_000)
        assertArrayEquals(hash, LockerCrypto.pbkdf2("4711".toCharArray(), salt, iterations = 1_000))
        assertFalse(hash.contentEquals(LockerCrypto.pbkdf2("4712".toCharArray(), salt, iterations = 1_000)))
    }

    @Test
    fun recoveryCodesAreNormalized() {
        val codes = LockerCrypto.newRecoveryCodes()
        assertEquals(5, codes.size)
        assertEquals(5, codes.toSet().size)
        val code = codes.first()
        assertEquals(LockerCrypto.recoveryHash(code), LockerCrypto.recoveryHash(code.lowercase().replace("-", " ")))
    }

    /* ---------- App-Sperre ---------- */

    @Test
    fun lockerPolicy() {
        val locked = LockerPolicy.lockedPackages(setOf("com.bank"), lockSystemScreens = true)
        assertTrue("com.android.settings" in locked && "com.bank" in locked)
        assertTrue(LockerPolicy.needsLock("com.bank", locked, unlockedUntil = null, now = 1_000))
        assertFalse(LockerPolicy.needsLock("com.bank", locked, unlockedUntil = 2_000, now = 1_000))
        assertTrue(LockerPolicy.needsLock("com.bank", locked, unlockedUntil = 500, now = 1_000))
        assertFalse(LockerPolicy.needsLock("com.other", locked, unlockedUntil = null, now = 1_000))
    }

    @Test
    fun trustedDeviceSkipsSecondFactor() {
        assertTrue(LockerPolicy.needsSecondFactor(SecondFactor.PIN, setOf("AA"), emptySet()))
        assertFalse(LockerPolicy.needsSecondFactor(SecondFactor.PIN, setOf("AA"), setOf("AA", "BB")))
        assertFalse(LockerPolicy.needsSecondFactor(SecondFactor.NONE, emptySet(), emptySet()))
    }

    @Test
    fun lockoutGrowsAndCaps() {
        assertEquals(0L, LockerPolicy.lockoutMillis(2))
        assertEquals(30_000L, LockerPolicy.lockoutMillis(3))
        assertEquals(60_000L, LockerPolicy.lockoutMillis(4))
        assertEquals(15 * 60_000L, LockerPolicy.lockoutMillis(20))
    }

    @Test
    fun strictModeCooldown() {
        assertTrue(LockerPolicy.canDisable(0, 0, 1_000))
        assertFalse(LockerPolicy.canDisable(5, 0, 1_000))
        val requested = 1_000_000L
        assertFalse(LockerPolicy.canDisable(5, requested, requested + 60_000))
        assertTrue(LockerPolicy.canDisable(5, requested, requested + 5 * 60_000))
        assertFalse(LockerPolicy.canDisable(5, requested, requested + 16 * 60_000))
    }

    /* ---------- Netzwerk ---------- */

    @Test
    fun firewallPerNetworkAndKillSwitch() {
        val wifi = setOf("a", "b")
        val mobile = setOf("b", "c")
        val protected = setOf("self")
        assertEquals(setOf("a", "b"), FirewallPolicy.denied(NetType.WIFI, wifi, mobile, false, setOf("d"), protected))
        assertEquals(setOf("b", "c"), FirewallPolicy.denied(NetType.MOBILE, wifi, mobile, false, setOf("d"), protected))
        assertEquals(setOf("b", "c", "d"), FirewallPolicy.denied(NetType.MOBILE, wifi, mobile, true, setOf("d", "self"), protected))
        assertEquals(setOf("b"), FirewallPolicy.denied(NetType.NONE, wifi, mobile, false, emptySet(), protected))
        val (deny, release) = FirewallPolicy.diff(applied = setOf("a", "x"), target = setOf("a", "b"))
        assertEquals(setOf("b"), deny)
        assertEquals(setOf("x"), release)
    }

    @Test
    fun dataLimitWarnsOncePerDay() {
        val mb = 1024L * 1024
        assertFalse(DataLimitPolicy.shouldWarn(100 * mb, 0, 0, 20261127))
        assertFalse(DataLimitPolicy.shouldWarn(100 * mb, 200, 0, 20261127))
        assertTrue(DataLimitPolicy.shouldWarn(250 * mb, 200, 20261126, 20261127))
        assertFalse(DataLimitPolicy.shouldWarn(250 * mb, 200, 20261127, 20261127))
    }

    @Test
    fun wifiSafety() {
        assertNotNull(WifiSafetyPolicy.warning(WifiSafetyPolicy.SECURITY_TYPE_OPEN, false))
        assertNotNull(WifiSafetyPolicy.warning(WifiSafetyPolicy.SECURITY_TYPE_WEP, false))
        assertNull(WifiSafetyPolicy.warning(WifiSafetyPolicy.SECURITY_TYPE_OWE, false))
        assertNull(WifiSafetyPolicy.warning(2, false))
        assertNotNull(WifiSafetyPolicy.warning(2, true))
    }

    @Test
    fun permissionDiffIgnoresNewApps() {
        val before = mapOf("app.a" to setOf("CAMERA"), "app.b" to emptySet())
        val after = mapOf("app.a" to setOf("CAMERA", "BACKGROUND_LOCATION"), "app.b" to emptySet(), "app.new" to setOf("CAMERA"))
        assertEquals(mapOf("app.a" to setOf("BACKGROUND_LOCATION")), PermissionDiffPolicy.added(before, after))
    }
}
