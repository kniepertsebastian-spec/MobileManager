package app.mobilemanager.launcher.domain

/** App-Vorschläge im Drawer – wie beim Pixel aus der eigenen Nutzung, aber rein lokal berechnet. */
object AppSuggestions {

    private const val RECENCY_WEIGHT = 0.05

    /**
     * [history]: App-Schlüssel der letzten Starts, neueste zuerst. Gezählt wird die Häufigkeit,
     * neuere Starts wiegen etwas mehr. Nur Apps aus [available] werden vorgeschlagen.
     */
    fun rank(history: List<String>, available: Set<String>, count: Int): List<String> {
        val scores = LinkedHashMap<String, Double>()
        history.forEachIndexed { index, key ->
            if (key in available) scores[key] = (scores[key] ?: 0.0) + 1.0 / (1.0 + index * RECENCY_WEIGHT)
        }
        return scores.entries.sortedByDescending { it.value }.take(count).map { it.key }
    }
}
