package app.mobilemanager.domain.guard

/**
 * Apps, deren Benachrichtigungen standardmäßig nicht ins Archiv wandern: Banking, Bezahl-Apps,
 * Authenticator/2FA und Passwortmanager. Bekannte Pakete plus Stichworte im Paketnamen –
 * lieber ein paar zu viel ausschließen als TANs und Einmal-Codes 7 Tage lang aufzuheben.
 */
object SensitiveApps {

    private val KNOWN = setOf(
        // Authenticator / 2FA
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
        "org.fedorahosted.freeotp", "org.liberty.android.freeotpplus", "com.beemdevelopment.aegis",
        "com.yubico.yubioath", "com.twofasapp", "com.duosecurity.duomobile", "me.mattartist.authenticator",
        // Passwortmanager
        "com.x8bit.bitwarden", "com.bitwarden.authenticator", "com.agilebits.onepassword", "com.lastpass.lpandroid",
        "com.dashlane", "keepass2android.keepass2android", "keepass2android.keepass2android_nonet",
        "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre", "com.callpod.android_apps.keeper",
        "proton.android.pass", "com.enpass.android", "com.nordpass.android.app.password.manager",
        "com.google.android.apps.authenticator",
        // Banking / Bezahlen (Auswahl DACH + international)
        "com.starfinanz.smob.android.sfinanzstatus", "com.starfinanz.mobile.android.pushtan",
        "de.fiducia.smartphone.android.banking.vr", "de.fiduciagad.securego.vr", "de.dkb.portalapp",
        "com.db.pwcc.dbmobile", "de.commerzbanking.mobil", "de.comdirect.android", "de.consorsbank",
        "de.ingdiba.bankingapp", "de.number26.android", "com.revolut.revolut", "de.traderepublic.app",
        "com.paypal.android.p2pmobile", "com.google.android.apps.walletnfcrel", "de.postbank.finanzassistent",
        "com.c24.bankapp", "com.bunq.android", "com.vivid.money", "de.santander.presentation",
        "at.bawag.mbanking", "at.erstebank.george", "ch.ubs.mobilebanking", "com.kontist",
        "de.tomorrow.banking", "com.wise.android", "de.hypovereinsbank.banking",
    )

    private val KEYWORDS = listOf(
        "bank", "banking", "authenticator", "authy", "otp", "2fa", "totp", "pushtan", "phototan", "securego",
        "password", "passwort", "keepass", "vault", "wallet", "sparkasse", "volksbank", "raiffeisen", "finanz",
    )

    fun isSensitive(packageName: String): Boolean {
        if (packageName in KNOWN) return true
        val lower = packageName.lowercase()
        return KEYWORDS.any { it in lower }
    }

    /** Wird diese App vom Archiv ausgeschlossen? Eigene Ausnahmen gelten immer, die Liste nur, wenn aktiv. */
    fun excluded(packageName: String, excludeSensitive: Boolean, customExcluded: Set<String>): Boolean =
        packageName in customExcluded || (excludeSensitive && isSensitive(packageName))
}
