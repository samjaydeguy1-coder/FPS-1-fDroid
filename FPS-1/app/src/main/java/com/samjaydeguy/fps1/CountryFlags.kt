package com.samjaydeguy.fps1

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

internal object CountryFlags {

    private val nations = mapOf(
        "albania" to "🇦🇱",
        "andorra" to "🇦🇩",
        "armenia" to "🇦🇲",
        "austria" to "🇦🇹",
        "azerbaijan" to "🇦🇿",
        "belarus" to "🇧🇾",
        "belgium" to "🇧🇪",
        "bosnia and herzegovina" to "🇧🇦",
        "bulgaria" to "🇧🇬",
        "croatia" to "🇭🇷",
        "cyprus" to "🇨🇾",
        "czech republic" to "🇨🇿",
        "denmark" to "🇩🇰",
        "england" to "🇬🇧",
        "estonia" to "🇪🇪",
        "faroe islands" to "🇫🇴",
        "finland" to "🇫🇮",
        "france" to "🇫🇷",
        "georgia" to "🇬🇪",
        "germany" to "🇩🇪",
        "gibraltar" to "🇬🇮",
        "greece" to "🇬🇷",
        "hungary" to "🇭🇺",
        "iceland" to "🇮🇸",
        "israel" to "🇮🇱",
        "italy" to "🇮🇹",
        "kazakhstan" to "🇰🇿",
        "kosovo" to "🇽🇰",
        "latvia" to "🇱🇻",
        "liechtenstein" to "🇱🇮",
        "lithuania" to "🇱🇹",
        "luxembourg" to "🇱🇺",
        "malta" to "🇲🇹",
        "moldova" to "🇲🇩",
        "montenegro" to "🇲🇪",
        "netherlands" to "🇳🇱",
        "north macedonia" to "🇲🇰",
        "northern ireland" to "🇬🇧",
        "norway" to "🇳🇴",
        "poland" to "🇵🇱",
        "portugal" to "🇵🇹",
        "republic of ireland" to "🇮🇪",
        "ireland" to "🇮🇪",
        "romania" to "🇷🇴",
        "russia" to "🇷🇺",
        "san marino" to "🇸🇲",
        "scotland" to "🇬🇧",
        "serbia" to "🇷🇸",
        "slovakia" to "🇸🇰",
        "slovenia" to "🇸🇮",
        "spain" to "🇪🇸",
        "sweden" to "🇸🇪",
        "switzerland" to "🇨🇭",
        "turkey" to "🇹🇷",
        "ukraine" to "🇺🇦",
        "wales" to "🇬🇧"
    )

    private fun normalize(team: String): String =
        team.trim()
            .lowercase()
            .replace("’", "'")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")

    fun forTeam(team: String, competition: String): String {
        return when (competition) {
            "EPL" -> "🇬🇧"
            "LaLiga" -> "🇪🇸"
            "SerieA" -> "🇮🇹"
            "Bundesliga" -> "🇩🇪"
            "Ligue1" -> "🇫🇷"
            "NationsLeague" -> {
                val name = normalize(team)
                val canonical = when (name) {
                    "czechia" -> "czech republic"
                    "türkiye", "turkiye" -> "turkey"
                    "bosnia-herzegovina" -> "bosnia and herzegovina"
                    "republic of north macedonia" -> "north macedonia"
                    else -> name
                }
                nations[canonical] ?: ""
            }
            else -> ""
        }
    }
}

@Composable
internal fun CountryFlag(team: String, competition: String) {
    val flag = CountryFlags.forTeam(team, competition)
    if (flag.isNotEmpty()) {
        Text(flag, fontSize = 20.sp)
    }
}
