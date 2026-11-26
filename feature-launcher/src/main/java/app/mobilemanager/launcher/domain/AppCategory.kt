package app.mobilemanager.launcher.domain

/** Thematische Kategorie für die „Ordnen“-Funktion. Reihenfolge = Reihenfolge der Ordner. */
enum class AppCategory(val label: String) {
    COMMUNICATION("Kommunikation"),
    SOCIAL("Soziale Netzwerke"),
    PRODUCTIVITY("Produktivität"),
    FINANCE("Finanzen"),
    NAVIGATION("Navigation"),
    MUSIC("Musik & Audio"),
    VIDEO("Video"),
    PHOTOS("Fotos"),
    NEWS("Nachrichten"),
    SHOPPING("Shopping"),
    GAMES("Spiele"),
    TOOLS("Tools"),
    ;

    companion object {
        fun parseSet(csv: String): Set<AppCategory> =
            csv.split(',').mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }.toSet()

        fun toCsv(set: Set<AppCategory>): String = set.sortedBy { it.ordinal }.joinToString(",") { it.name }
    }
}

/**
 * Ordnet Apps einer Kategorie zu.
 * 1. Finanz-Apps über eine lokale Liste (Banking-Apps melden fast nie eine Kategorie).
 * 2. `ApplicationInfo.category`, wenn die App eine angibt.
 * 3. Lokale Stichwortliste (Paketname/Label).
 * 4. Rest → Tools.
 */
object AppCategorizer {

    // Werte aus android.content.pm.ApplicationInfo (stabile API-Konstanten), hier gespiegelt,
    // damit die Logik ohne Android-Framework testbar bleibt.
    const val CATEGORY_UNDEFINED = -1
    const val CATEGORY_GAME = 0
    const val CATEGORY_AUDIO = 1
    const val CATEGORY_VIDEO = 2
    const val CATEGORY_IMAGE = 3
    const val CATEGORY_SOCIAL = 4
    const val CATEGORY_NEWS = 5
    const val CATEGORY_MAPS = 6
    const val CATEGORY_PRODUCTIVITY = 7

    private val FINANCE_PACKAGES = listOf(
        "com.paypal", "de.number26", "com.starfinanz", "de.sparkasse", "de.fiducia", "de.dkb", "de.comdirect",
        "de.ingdiba", "com.ing.", "de.consorsbank", "com.revolut", "de.traderepublic", "de.postbank",
        "com.db.", "de.commerzbank", "com.commerzbank", "de.santander", "de.hypovereinsbank", "com.unicredit",
        "com.klarna", "de.c24", "com.vivid", "com.bunq", "de.tomorrow", "com.scalable", "com.coinbase",
        "com.binance", "de.smava", "com.google.android.apps.walletnfcrel", "de.finanzguru",
    )
    private val FINANCE_WORDS = listOf("bank", "banking", "sparkasse", "finanz", "wallet", "broker", "depot", "börse")

    private val KEYWORDS: List<Pair<AppCategory, List<String>>> = listOf(
        AppCategory.COMMUNICATION to listOf(
            "mail", "browser", "chrome", "firefox", "brave", "opera", "duckduckgo", "vivaldi", "messag", "dialer",
            "telephony", "contacts", "whatsapp", "signal", "telegram", "threema", "sms", "teams", "zoom", "skype",
        ),
        AppCategory.NAVIGATION to listOf("maps", "navi", "waze", "osmand", "bahn", "db.navigator", "transit", "uber", "bolt"),
        AppCategory.MUSIC to listOf("music", "spotify", "podcast", "radio", "audible", "deezer", "soundcloud", "tidal"),
        AppCategory.VIDEO to listOf("youtube", "netflix", "video", "prime", "disney", "twitch", "mediathek", "vlc"),
        AppCategory.PHOTOS to listOf("photo", "camera", "gallery", "foto", "snapseed", "lightroom"),
        AppCategory.SHOPPING to listOf("amazon.mshop", "shop", "ebay", "zalando", "otto", "aliexpress", "temu", "kleinanzeigen", "vinted"),
        AppCategory.NEWS to listOf("news", "nachrichten", "tagesschau", "spiegel", "zeit.", "faz", "sueddeutsche", "reddit", "feedly"),
        AppCategory.SOCIAL to listOf("instagram", "facebook", "tiktok", "musically", "twitter", "snapchat", "pinterest", "linkedin", "mastodon", "bluesky", "threads"),
        AppCategory.PRODUCTIVITY to listOf(
            "docs", "sheets", "slides", "office", "word", "excel", "notes", "keep", "calendar", "kalender", "todo",
            "tasks", "drive", "dropbox", "onedrive", "notion", "evernote", "pdf", "scanner", "authenticator",
        ),
    )

    fun categorize(androidCategory: Int, packageName: String, label: String): AppCategory {
        val pkg = packageName.lowercase()
        val name = label.lowercase()
        if (FINANCE_PACKAGES.any { pkg.startsWith(it) } || FINANCE_WORDS.any { it in name }) return AppCategory.FINANCE
        when (androidCategory) {
            CATEGORY_GAME -> return AppCategory.GAMES
            CATEGORY_AUDIO -> return AppCategory.MUSIC
            CATEGORY_VIDEO -> return AppCategory.VIDEO
            CATEGORY_IMAGE -> return AppCategory.PHOTOS
            CATEGORY_SOCIAL -> return AppCategory.SOCIAL
            CATEGORY_NEWS -> return AppCategory.NEWS
            CATEGORY_MAPS -> return AppCategory.NAVIGATION
            CATEGORY_PRODUCTIVITY -> return AppCategory.PRODUCTIVITY
        }
        KEYWORDS.forEach { (category, words) ->
            if (words.any { it in pkg || it in name }) return category
        }
        return AppCategory.TOOLS
    }
}
