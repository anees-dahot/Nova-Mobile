package com.anees.novamobile.data

/**
 * Family-safe filter. TMDB's `adult` flag misses most Indian erotic web series (Ullu, Kooku…),
 * which are badly tagged but have almost no votes. So, on top of include_adult=false:
 *  - known erotic apps/networks and title terms are always blocked
 *  - Indian-language titles need ≥25 votes, everything else ≥5 (search only; lists filter server-side)
 */
object SafeFilter {
    private val INDIC = setOf("hi", "ur", "ta", "te", "bn", "mr", "kn", "ml", "pa", "gu", "or", "bh")

    private val BLOCKED_NETWORKS = listOf(
        "ullu", "kooku", "hotshots", "primeshots", "prime shots", "rabbit movies", "hunters", "atrangii",
        "fliz", "nuefliks", "besharams", "woow", "hotx", "cineprime", "digimovieplex", "feneo", "tadka",
        "boom movies", "chikooflix", "yessma", "bigshots", "moodx", "hothit", "primeflix", "gupchup",
        "voovi", "kangan", "triflicks", "neonx", "jalva", "dreams films", "xprime", "uncut adda",
        "showhit", "hulchul", "fugi", "lookent", "aahaflix", "mojflix", "kotha app"
    )

    private val BLOCKED_TITLE = Regex(
        "charmsukh|palang ?tod|mastram|gandii? baat|kavita bhabhi|riti riwaj|jane anjane mein|" +
        "\\bxxx\\b|\\buncut\\b|\\berotic|\\bsoftcore|\\bhentai|\\b18\\+|\\bporn",
        RegexOption.IGNORE_CASE
    )

    fun titleOk(title: String?) = title == null || !BLOCKED_TITLE.containsMatchIn(title)

    fun networksOk(names: List<String>) = names.none { n -> BLOCKED_NETWORKS.any { n.lowercase().contains(it) } }

    /** For search results, which have no server-side vote floor. */
    fun searchOk(title: String?, lang: String?, votes: Int, adult: Boolean): Boolean =
        !adult && titleOk(title) && votes >= (if (lang in INDIC) 25 else 5)
}
