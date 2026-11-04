package com.anees.novamobile.data.sources.vegamovies

/*
 * Ported from Indflix (https://github.com/dipender98/Indflix), GPL-3.0.
 * Original: Vegamovies/src/main/kotlin/com/vegamovies/LinkNaming.kt
 * Adapted: display-name/cosmetic label helpers dropped (Nova labels sources, not individual
 * links); quality, season/episode and server-kind parsing kept as-is.
 */

internal object Servers {
    const val GDRIVE = "G-Drive"
    const val VCLOUD = "V-Cloud"
    const val ZIP = "Batch/Zip"
    const val GATE = "Server"

    fun kindOf(url: String): String = when {
        url.contains("fastdl") -> GDRIVE
        url.contains("vcloud") -> VCLOUD
        url.contains("hubcloud") || url.contains("nexdrive") || url.endsWith(".zip", ignoreCase = true) -> ZIP
        else -> GDRIVE
    }
}

internal object LinkNaming {

    /** "Download 1080p …" heading → 1080; "4K" → 2160; nothing → 0. */
    fun qualityInt(heading: String?): Int {
        if (heading.isNullOrBlank()) return 0
        Regex("""(?i)\b(\d{3,4})p\b""").findAll(heading)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()?.let { return it }
        if (Regex("""(?i)\b4K\b""").containsMatchIn(heading)) return 2160
        return 0
    }

    /** "S01E05", "1x05", "Season 1 Episode 5" → (1, 5); partial matches return nulls. */
    fun seasonEpisodeFrom(heading: String): Pair<Int?, Int?> {
        Regex("""(?i)\bS(\d{1,2})[\s.\-_]?E(?:P)?\s?0*(\d{1,3})\b""").find(heading)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        Regex("""\b(\d{1,2})x(\d{2})\b""").find(heading)?.let {
            return it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        val season = Regex("""(?i)season\s*(\d{1,2})""").find(heading)?.groupValues?.get(1)?.toIntOrNull()
        val ep = Regex("""(?i)(?:episode|ep\.?|eps)\s*0*(\d{1,3})""").find(heading)?.groupValues?.get(1)?.toIntOrNull()
        return season to ep
    }

    fun kindFromChip(chip: String): String = when {
        chip.contains("G-Direct", true) || chip.contains("G-Drive", true) || chip.contains("Direct", true) -> Servers.GDRIVE
        chip.contains("V-Cloud", true) -> Servers.VCLOUD
        chip.contains("Batch", true) || chip.contains("Zip", true) || chip.contains("ZiP", true) -> Servers.ZIP
        else -> ""
    }

    /** Whole-season archives aren't playable one episode at a time. */
    fun isPack(text: String?): Boolean =
        text != null && Regex("""(?i)\b(batch|pack|zip)\b""").containsMatchIn(text)
}
