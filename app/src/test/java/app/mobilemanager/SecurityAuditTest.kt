package app.mobilemanager

import app.mobilemanager.data.backup.BackupCrypto
import app.mobilemanager.data.backup.EncryptedBackup
import app.mobilemanager.domain.extras.LockerPolicy
import app.mobilemanager.domain.extras.ShareInputPolicy
import app.mobilemanager.domain.extras.ShellCommandPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roadmap v2, Phase A: Shizuku-Grenze, Share-Eingang, Backup-Krypto und Sperrzeiten. */
class SecurityAuditTest {

    private val own = "app.mobilemanager"

    /* ---------- Shizuku ---------- */

    @Test
    fun shellCallerMustBeMobileManagerItself() {
        val table = mapOf(
            10_123 to arrayOf(own),
            1_010_123 to arrayOf(own), // Arbeitsprofil / zweiter Nutzer
            10_200 to arrayOf("com.evil"),
            10_300 to arrayOf(own, "com.shared.uid"),
        )
        val lookup: (Int) -> Array<String>? = { table[it] }
        assertTrue(ShellCommandPolicy.isTrustedCaller(10_123, own, lookup))
        assertTrue(ShellCommandPolicy.isTrustedCaller(1_010_123, own, lookup))
        assertFalse(ShellCommandPolicy.isTrustedCaller(10_200, own, lookup))
        assertFalse(ShellCommandPolicy.isTrustedCaller(10_300, own, lookup))
        assertFalse(ShellCommandPolicy.isTrustedCaller(10_999, own, lookup))
        assertFalse(ShellCommandPolicy.isTrustedCaller(0, own) { arrayOf(own) })
        assertFalse(ShellCommandPolicy.isTrustedCaller(2000, own) { arrayOf(own) })
        assertFalse(ShellCommandPolicy.isTrustedCaller(-1, own) { arrayOf(own) })
        assertFalse(ShellCommandPolicy.isTrustedCaller(10_123, own) { throw SecurityException() })
    }

    @Test
    fun shellPolicyRejectsInjectionAndOddArguments() {
        val bad = listOf(
            listOf("pm", "disable-user", "--user", "0", "com.example.app;reboot"),
            listOf("pm", "disable-user", "--user", "0", "com.example.app", "extra"),
            listOf("pm", "disable-user", "--user", "0", "../../system"),
            listOf("pm", "disable-user", "--user", "0", "com.example.\$(id)"),
            listOf("pm", "disable-user", "--user", "0", "com.exämple.app"),
            listOf("pm", "disable-user", "--user", "0", "nodots"),
            listOf("pm", "disable-user", "--user", "0", ".leading.dot"),
            listOf("pm", "disable-user", "--user", "0", "com.example.app\u0000"),
            listOf("pm", "disable-user", "--user", "0", "a." + "b".repeat(300)),
            listOf("pm", "list", "packages"),
            listOf("pm", "trim-caches", "1T"),
            listOf("pm", "trim-caches", "-1"),
            listOf("PM", "list", "packages", "-d"),
            listOf("/system/bin/pm", "list", "packages", "-d"),
            listOf("cmd", "connectivity", "set-chain3-enabled", "TRUE"),
            listOf("cmd", "connectivity", "set-chain3-enabled", "true", "&&", "reboot"),
            listOf("cmd", "appops", "set", "com.example.app", "RUN_IN_BACKGROUND", "allow ; id"),
            listOf("cmd", "uimode", "night", " yes"),
            listOf("settings", "put", "secure", "window_animation_scale", "0"),
            listOf("settings", "put", "global", "window_animation_scale", "10"),
            listOf("settings", "put", "global", "private_dns_specifier", "dns.example.com/evil"),
            listOf("settings", "put", "global", "private_dns_specifier", "localhost"),
            listOf("settings", "get", "global", "adb_enabled"),
            listOf("settings", "delete", "global", "private_dns_mode"),
            listOf(""),
        )
        bad.forEach { assertFalse("sollte abgelehnt werden: $it", ShellCommandPolicy.isAllowed(it)) }
    }

    @Test
    fun packageNameValidation() {
        assertTrue(ShellCommandPolicy.isPackage("com.example.app"))
        assertTrue(ShellCommandPolicy.isPackage("de.bank_app.v2"))
        listOf("", "com", "com.", "1com.example", "com..example", "com.example app", "com/example", "com.example\n").forEach {
            assertFalse("ungültig: '$it'", ShellCommandPolicy.isPackage(it))
        }
    }

    /* ---------- Teilen-Eingang (EXIF) ---------- */

    @Test
    fun shareInputOnlyAcceptsForeignContentUris() {
        assertTrue(ShareInputPolicy.isAcceptable("content", "media", own))
        assertTrue(ShareInputPolicy.isAcceptable("content", "com.google.android.apps.photos.contentprovider", own))
        assertFalse(ShareInputPolicy.isAcceptable("file", "", own))
        assertFalse(ShareInputPolicy.isAcceptable("file", null, own))
        assertFalse(ShareInputPolicy.isAcceptable("content", "app.mobilemanager.files", own))
        assertFalse(ShareInputPolicy.isAcceptable("content", "APP.MobileManager.files", own))
        assertFalse(ShareInputPolicy.isAcceptable("content", "0@app.mobilemanager.files", own))
        assertFalse(ShareInputPolicy.isAcceptable("content", own, own))
        assertFalse(ShareInputPolicy.isAcceptable("content", "", own))
        assertFalse(ShareInputPolicy.isAcceptable("http", "example.com", own))
        assertFalse(ShareInputPolicy.isAcceptable(null, "media", own))
    }

    @Test
    fun shareInputTypesSizesAndNames() {
        assertTrue(ShareInputPolicy.isImageType("image/heic"))
        assertFalse(ShareInputPolicy.isImageType("application/octet-stream"))
        assertFalse(ShareInputPolicy.isImageType(null))
        assertTrue(ShareInputPolicy.isLossless("IMAGE/JPEG"))
        assertFalse(ShareInputPolicy.isLossless("image/heic"))
        assertTrue(ShareInputPolicy.withinSizeLimit(null))
        assertFalse(ShareInputPolicy.withinSizeLimit(ShareInputPolicy.MAX_BYTES + 1))
        assertFalse(ShareInputPolicy.withinSizeLimit(-5))
        assertEquals(1, ShareInputPolicy.sampleSize(4000, 3000))
        assertEquals(2, ShareInputPolicy.sampleSize(12_000, 9_000))
        assertEquals("bild_3", ShareInputPolicy.safeBaseName(null, 3))
        assertEquals("______etc_passwd", ShareInputPolicy.safeBaseName("../../etc/passwd.jpg", 0))
        assertEquals("bild_1", ShareInputPolicy.safeBaseName(".jpg", 1))
    }

    /* ---------- Verschlüsseltes Backup ---------- */

    private val fast = 100_000

    @Test
    fun backupRoundTripUsesFreshSaltAndIv() {
        val plain = "PK\u0003\u0004 Inhalt".toByteArray()
        val a = BackupCrypto.seal(plain, "richtig123".toCharArray(), fast)
        val b = BackupCrypto.seal(plain, "richtig123".toCharArray(), fast)
        assertFalse("Salt/IV müssen pro Export neu sein", a.contentEquals(b))
        assertArrayEquals(plain, BackupCrypto.open(a, "richtig123".toCharArray()).getOrThrow())
    }

    @Test
    fun backupRejectsWrongPasswordTamperingAndForeignFiles() {
        val sealed = BackupCrypto.seal(ByteArray(1024) { it.toByte() }, "richtig123".toCharArray(), fast)
        assertEquals(
            "Falsches Passwort oder beschädigte Datei",
            BackupCrypto.open(sealed, "falsch1234".toCharArray()).exceptionOrNull()?.message,
        )
        val flipped = sealed.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        assertTrue(BackupCrypto.open(flipped, "richtig123".toCharArray()).isFailure)
        // Kopf (Iterationen) ist authentifiziert – Ändern fällt auf.
        val header = sealed.copyOf().also { it[8] = (it[8].toInt() xor 1).toByte() }
        assertTrue(BackupCrypto.open(header, "richtig123".toCharArray()).isFailure)
        assertEquals("Sicherung ist beschädigt", BackupCrypto.open(sealed.copyOf(30), "richtig123".toCharArray()).exceptionOrNull()?.message)
        assertEquals("Keine MobileManager-Sicherung", BackupCrypto.open("PK zip".toByteArray(), "x".toCharArray()).exceptionOrNull()?.message)
        assertTrue(BackupCrypto.open(ByteArray(0), "x".toCharArray()).isFailure)
        // Unsinnige Iterationszahl im Kopf (DoS) wird vor dem KDF abgelehnt.
        val dos = sealed.copyOf().also { it[5] = 0x7f }
        assertEquals("Sicherung ist beschädigt", BackupCrypto.open(dos, "richtig123".toCharArray()).exceptionOrNull()?.message)
    }

    @Test
    fun backupEntryAllowlist() {
        assertTrue(EncryptedBackup.isAllowed("datastore/settings.preferences_pb"))
        assertTrue(EncryptedBackup.isAllowed("databases/launcher.db"))
        assertTrue(EncryptedBackup.isAllowed("files/blocklists/ads.txt"))
        assertFalse(EncryptedBackup.isAllowed("datastore/locker_settings.preferences_pb"))
        assertFalse(EncryptedBackup.isAllowed("datastore/../shared_prefs/x.preferences_pb"))
        assertFalse(EncryptedBackup.isAllowed("/data/data/app.mobilemanager/x"))
        assertFalse(EncryptedBackup.isAllowed("files/blocklists/sub/ads.txt"))
        assertFalse(EncryptedBackup.isAllowed("files\\blocklists\\ads.txt"))
        assertFalse(EncryptedBackup.isAllowed("databases/other.db"))
        assertFalse(EncryptedBackup.isAllowed("shared_prefs/x.xml"))
    }

    /* ---------- App-Sperre ---------- */

    @Test
    fun savedLockoutIsClampedAfterRestart() {
        val now = 1_000_000_000L
        assertEquals(0L, LockerPolicy.clampLockout(now - 1, now))
        assertEquals(now + 60_000, LockerPolicy.clampLockout(now + 60_000, now))
        assertEquals(now + LockerPolicy.MAX_LOCKOUT_MILLIS, LockerPolicy.clampLockout(now + 365L * 86_400_000, now))
    }
}
