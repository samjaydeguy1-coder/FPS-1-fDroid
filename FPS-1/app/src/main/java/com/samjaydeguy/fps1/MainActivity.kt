package com.samjaydeguy.fps1

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject

private val Navy: Color
    @Composable get() = LocalFps1Palette.current.background

private val Panel: Color
    @Composable get() = LocalFps1Palette.current.panel

private val Blue: Color
    @Composable get() = LocalFps1Palette.current.accent

private val Muted: Color
    @Composable get() = LocalFps1Palette.current.muted

private val Green: Color
    @Composable get() = LocalFps1Palette.current.success

private val Orange: Color
    @Composable get() = LocalFps1Palette.current.warning

private val Red: Color
    @Composable get() = LocalFps1Palette.current.error

private val Purple: Color
    @Composable get() = LocalFps1Palette.current.purple

@Composable
private fun ClubBadge(team: String) {
    val resource = ClubBadgeResources.byTeam[team] ?: return

    Image(
        painter = painterResource(id = resource),
        contentDescription = "$team badge",
        modifier = Modifier.size(30.dp)
    )
}

data class Fixture(
    val date: String,
    val home: String,
    val away: String
)

data class SelectedMatch(
    val league: String,
    val date: String,
    val home: String,
    val away: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember {
                mutableStateOf(loadFps1ThemeMode(this))
            }

            Fps1Theme(mode = themeMode) {
                FPS1Screen(
                    activity = this,
                    themeMode = themeMode,
                    onThemeModeChange = { selectedMode ->
                        themeMode = selectedMode
                        saveFps1ThemeMode(this, selectedMode)
                    }
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    symbol: String,
    accent: Color = Blue
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(symbol, color = accent, style = MaterialTheme.typography.titleLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}


private const val HISTORY_PREFS = "fps1_prediction_history"
private const val HISTORY_KEY = "predictions"

private const val SELECTED_MATCHES_PREFS = "fps1_selected_matches"
private const val SELECTED_MATCHES_KEY = "selected_matches"

private fun sameSelectedMatch(a: SelectedMatch, b: SelectedMatch): Boolean =
    a.league == b.league &&
    a.date == b.date &&
    a.home == b.home &&
    a.away == b.away

private fun loadSelectedMatches(activity: ComponentActivity): List<SelectedMatch> {
    val stored = activity.getSharedPreferences(
        SELECTED_MATCHES_PREFS, Context.MODE_PRIVATE
    ).getString(SELECTED_MATCHES_KEY, "[]") ?: "[]"

    return try {
        val array = JSONArray(stored)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    SelectedMatch(
                        league = item.getString("league"),
                        date = item.getString("date"),
                        home = item.getString("home"),
                        away = item.getString("away")
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveSelectedMatches(
    activity: ComponentActivity,
    matches: List<SelectedMatch>
) {
    val array = JSONArray()
    matches.forEach { match ->
        array.put(
            JSONObject().apply {
                put("league", match.league)
                put("date", match.date)
                put("home", match.home)
                put("away", match.away)
            }
        )
    }

    activity.getSharedPreferences(
        SELECTED_MATCHES_PREFS, Context.MODE_PRIVATE
    ).edit().putString(SELECTED_MATCHES_KEY, array.toString()).apply()
}

private fun loadPredictionHistory(activity: ComponentActivity): JSONArray {
    val stored = activity.getSharedPreferences(
        HISTORY_PREFS, Context.MODE_PRIVATE
    ).getString(HISTORY_KEY, "[]") ?: "[]"

    return try {
        JSONArray(stored)
    } catch (_: Exception) {
        JSONArray()
    }
}

private fun savePrediction(
    activity: ComponentActivity,
    league: String,
    fixture: Fixture,
    prediction: JSONObject
): JSONArray {
    val history = loadPredictionHistory(activity)
    val record = JSONObject().apply {
        put("league", league)
        put("date", fixture.date)
        put("home", fixture.home)
        put("away", fixture.away)
        put("predicted_at", System.currentTimeMillis())
        put("most_likely_result", prediction.getString("most_likely_result"))
        put("probabilities", prediction.getJSONObject("probabilities"))
        put("status", "pending")
    }

    var existingIndex = -1

    for (i in 0 until history.length()) {
        val existing = history.getJSONObject(i)

        if (
            existing.optString("league") == league &&
            existing.optString("date") == fixture.date &&
            existing.optString("home") == fixture.home &&
            existing.optString("away") == fixture.away
        ) {
            existingIndex = i

            if (existing.has("actual_outcome")) {
                record.put("actual_outcome", existing.getString("actual_outcome"))
                record.put("home_goals", existing.optInt("home_goals"))
                record.put("away_goals", existing.optInt("away_goals"))
                record.put("status", existing.optString("status", "verified"))
            }
            break
        }
    }

    if (existingIndex >= 0) {
        history.put(existingIndex, record)
    } else {
        history.put(record)
    }

    activity.getSharedPreferences(
        HISTORY_PREFS, Context.MODE_PRIVATE
    ).edit().putString(HISTORY_KEY, history.toString()).apply()

    return JSONArray(history.toString())
}

private fun verifyPendingPredictions(
    activity: ComponentActivity
): JSONArray {
    val history = loadPredictionHistory(activity)
    val module = Python.getInstance().getModule("bridge")
    var changed = false

    for (i in 0 until history.length()) {
        val record = history.getJSONObject(i)
        if (record.optString("status") != "pending") continue

        val response = module.callAttr(
            "get_match_result",
            record.getString("league"),
            record.getString("date"),
            record.getString("home"),
            record.getString("away")
        ).toString()

        val actual = JSONObject(response)
        if (!actual.optBoolean("found")) continue

        record.put("actual_outcome", actual.getString("outcome"))
        record.put("home_goals", actual.getInt("home_goals"))
        record.put("away_goals", actual.getInt("away_goals"))
        record.put("status", "verified")
        changed = true
    }

    if (changed) {
        activity.getSharedPreferences(
            HISTORY_PREFS, Context.MODE_PRIVATE
        ).edit().putString(HISTORY_KEY, history.toString()).apply()
    }

    return history
}

private fun normalizedOutcome(value: String): String {
    val text = value.trim().uppercase()
    return when {
        text.contains("HOME") -> "HOME"
        text.contains("DRAW") -> "DRAW"
        text.contains("AWAY") -> "AWAY"
        else -> text
    }
}

@Composable
fun FPS1Screen(
    activity: ComponentActivity,
    themeMode: Fps1ThemeMode,
    onThemeModeChange: (Fps1ThemeMode) -> Unit
) {
    val leagues = listOf("EPL", "LaLiga", "SerieA", "Bundesliga", "Ligue1", "NationsLeague")

    var league by remember { mutableStateOf("EPL") }
    var expanded by remember { mutableStateOf(false) }
    var fixtures by remember { mutableStateOf(listOf<Fixture>()) }
    var selectedFixture by remember { mutableStateOf<Fixture?>(null) }
    var selectedFixtureLeague by remember { mutableStateOf("EPL") }
    var selectedMatches by remember {
        mutableStateOf(loadSelectedMatches(activity))
    }
    var search by remember { mutableStateOf("") }
    var loadingFixtures by remember { mutableStateOf(false) }
    var loadingPrediction by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var loadedLeague by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var savedPredictions by remember {
        mutableStateOf(loadPredictionHistory(activity))
    }
    var error by remember { mutableStateOf("") }
    var activePage by remember { mutableStateOf("Dashboard") }
    var menuExpanded by remember { mutableStateOf(false) }

    var standingsLeague by remember { mutableStateOf("EPL") }
    var standingsSeason by remember { mutableStateOf("") }
    var standingsSeasons by remember { mutableStateOf(listOf<String>()) }
    var standingsData by remember { mutableStateOf<JSONObject?>(null) }
    var standingsLoading by remember { mutableStateOf(false) }
    var standingsError by remember { mutableStateOf("") }
    var standingsLeagueExpanded by remember { mutableStateOf(false) }
    var standingsSeasonExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(league, refreshKey) {
        val leagueChanged = loadedLeague != league

        if (leagueChanged) {
            fixtures = emptyList()
            if (selectedFixtureLeague != league) {
                selectedFixture = null
                result = ""
            }
            search = ""
        }

        loadedLeague = league
        error = ""
        loadingFixtures = true
        val selectedLeague = league

        Thread {
            try {
                val module = Python.getInstance().getModule("bridge")
                val json = module.callAttr("get_fixtures", selectedLeague).toString()
                val array = JSONObject(json).getJSONArray("fixtures")
                val loaded = mutableListOf<Fixture>()

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    loaded.add(
                        Fixture(
                            item.getString("date"),
                            item.getString("home"),
                            item.getString("away")
                        )
                    )
                }

                val verifiedHistory = verifyPendingPredictions(activity)

                activity.runOnUiThread {
                    fixtures = loaded
                    savedPredictions = verifiedHistory
                    loadingFixtures = false
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    error = e.message ?: "Could not load fixtures."
                    loadingFixtures = false
                }
            }
        }.start()
    }

    val filteredFixtures = fixtures.filter {
        it.home.contains(search, ignoreCase = true) ||
        it.away.contains(search, ignoreCase = true) ||
        it.date.contains(search, ignoreCase = true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
        // Brand header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(Blue, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("SJ", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("SAMJAY1", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                Text("FPS-1  •  FOOTBALL PREDICTION SYSTEM", color = Muted, style = MaterialTheme.typography.labelSmall)
            }

            Text(
                "● SCHEDULED MATCHES",
                color = Green,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Text(
                        "⋮",
                        color = Blue,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    listOf("Dashboard", "Fixtures", "Analysis", "Selected Matches", "History", "Settings", "About").forEach { page ->
                        DropdownMenuItem(
                            text = { Text(page) },
                            onClick = {
                                activePage = page
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (activePage == "Dashboard") {
        // Welcome banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Panel)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text("WELCOME, SAMJAY1", color = Blue, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Understand the game.\nAnalyse the possibilities.",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Your football match analysis and prediction centre.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Dashboard
        SectionTitle("Dashboard", "Your current prediction environment")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                StatCard("Upcoming fixtures", if (loadingFixtures) "..." else fixtures.size.toString(), "◷", Blue)
            }
            Box(modifier = Modifier.weight(1f)) {
                StatCard("Supported competitions", "6", "⚽", Purple)
            }
        }

        // Prediction performance
        val verifiedRecords = (0 until savedPredictions.length())
            .map { savedPredictions.getJSONObject(it) }
            .filter { it.optString("status") == "verified" }
            .sortedByDescending { it.optLong("predicted_at") }

        val pendingCount = (0 until savedPredictions.length()).count {
            savedPredictions.getJSONObject(it).optString("status") == "pending"
        }

        val correctCount = verifiedRecords.count {
            normalizedOutcome(it.optString("most_likely_result")) ==
                normalizedOutcome(it.optString("actual_outcome"))
        }
        val incorrectCount = verifiedRecords.size - correctCount

        SectionTitle(
            "Prediction Performance",
            "Based on verified match results only"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                StatCard("Saved forecasts", savedPredictions.length().toString(), "▤", Blue)
            }
            Box(modifier = Modifier.weight(1f)) {
                StatCard("Pending results", pendingCount.toString(), "◷", Orange)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                StatCard("Correct", correctCount.toString(), "✓", Green)
            }
            Box(modifier = Modifier.weight(1f)) {
                StatCard("Incorrect", incorrectCount.toString(), "×", Red)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Panel)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "RECENT VERIFIED ACCURACY",
                    color = Blue,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )

                listOf(5, 10, 20).forEach { limit ->
                    val recent = verifiedRecords.take(limit)
                    val correct = recent.count {
                        normalizedOutcome(it.optString("most_likely_result")) ==
                            normalizedOutcome(it.optString("actual_outcome"))
                    }
                    val accuracy = if (recent.isEmpty()) 0.0
                        else correct * 100.0 / recent.size

                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Last $limit verified matches", color = Muted)
                            Text(
                                if (recent.isEmpty()) "—"
                                else "${"%.1f".format(java.util.Locale.US, accuracy)}%",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (accuracy / 100.0).toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = Green,
                            trackColor = Navy
                        )
                        Text(
                            if (recent.isEmpty()) "No verified predictions yet"
                            else "$correct correct out of ${recent.size}",
                            color = Muted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        if (activePage == "Dashboard") {
            SectionTitle(
                "League Standings",
                "Competition tables calculated from the match dataset"
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { standingsLeagueExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("LEAGUE  •  $standingsLeague")
                        }

                        DropdownMenu(
                            expanded = standingsLeagueExpanded,
                            onDismissRequest = { standingsLeagueExpanded = false }
                        ) {
                            leagues.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item) },
                                    onClick = {
                                        standingsLeague = item
                                        standingsSeason = ""
                                        standingsSeasons = emptyList()
                                        standingsData = null
                                        standingsError = ""
                                        standingsLeagueExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        OutlinedButton(
                            onClick = { standingsSeasonExpanded = true },
                            enabled = standingsSeasons.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (standingsSeason.isBlank()) {
                                    "SELECT SEASON"
                                } else {
                                    "SEASON  •  $standingsSeason"
                                }
                            )
                        }

                        DropdownMenu(
                            expanded = standingsSeasonExpanded,
                            onDismissRequest = { standingsSeasonExpanded = false }
                        ) {
                            standingsSeasons.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item) },
                                    onClick = {
                                        standingsSeason = item
                                        standingsSeasonExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !standingsLoading,
                        onClick = {
                            standingsLoading = true
                            standingsError = ""

                            val requestedLeague = standingsLeague
                            val requestedSeason = standingsSeason

                            Thread {
                                try {
                                    val module = Python.getInstance().getModule("bridge")

                                    val seasonsJson = module.callAttr(
                                        "get_standings_seasons",
                                        requestedLeague
                                    ).toString()

                                    val seasonsArray = JSONArray(seasonsJson)
                                    val availableSeasons = buildList {
                                        for (i in 0 until seasonsArray.length()) {
                                            add(seasonsArray.getString(i))
                                        }
                                    }

                                    if (availableSeasons.isEmpty()) {
                                        throw IllegalStateException(
                                            "No seasons found for $requestedLeague."
                                        )
                                    }

                                    val chosenSeason =
                                        if (requestedSeason in availableSeasons) {
                                            requestedSeason
                                        } else {
                                            availableSeasons.last()
                                        }

                                    val tableJson = module.callAttr(
                                        "get_standings",
                                        requestedLeague,
                                        chosenSeason
                                    ).toString()

                                    val parsedTable = JSONObject(tableJson)

                                    activity.runOnUiThread {
                                        standingsSeasons = availableSeasons
                                        standingsSeason = chosenSeason
                                        standingsData = parsedTable
                                        standingsLoading = false
                                    }
                                } catch (e: Exception) {
                                    activity.runOnUiThread {
                                        standingsError = e.message
                                            ?: "Could not load standings."
                                        standingsLoading = false
                                    }
                                }
                            }.start()
                        }
                    ) {
                        Text(
                            if (standingsLoading) "LOADING STANDINGS..."
                            else "LOAD / UPDATE TABLE"
                        )
                    }

                    if (standingsError.isNotBlank()) {
                        Text(
                            standingsError,
                            color = Red,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    standingsData?.let { data ->
                        Text(
                            "${data.optString("league")}  •  ${data.optString("season")}",
                            fontWeight = FontWeight.Bold,
                            color = Blue
                        )

                        Text(
                            "As of ${data.optString("as_of")}",
                            color = Muted,
                            style = MaterialTheme.typography.labelSmall
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("POS", modifier = Modifier.width(30.dp), color = Muted,
                                style = MaterialTheme.typography.labelSmall)
                            Text("TEAM", modifier = Modifier.weight(1f), color = Muted,
                                style = MaterialTheme.typography.labelSmall)
                            Text("P W D L", modifier = Modifier.width(66.dp), color = Muted,
                                style = MaterialTheme.typography.labelSmall)
                            Text("GD", modifier = Modifier.width(32.dp), color = Muted,
                                style = MaterialTheme.typography.labelSmall)
                            Text("PTS", modifier = Modifier.width(32.dp), color = Muted,
                                style = MaterialTheme.typography.labelSmall)
                        }

                        HorizontalDivider(color = Muted.copy(alpha = 0.35f))

                        val tableRows = data.optJSONArray("table")
                        if (tableRows != null) {
                            for (index in 0 until tableRows.length()) {
                                val item = tableRows.getJSONObject(index)
                                val team = item.optString("team")

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        item.optString("position"),
                                        modifier = Modifier.width(30.dp),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelSmall
                                    )

                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        ClubBadge(team)
                                        Text(
                                            team,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }

                                    Text(
                                        "${item.optInt("played")} ${item.optInt("won")} ${item.optInt("drawn")} ${item.optInt("lost")}",
                                        modifier = Modifier.width(66.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )

                                    Text(
                                        item.optString("goal_difference"),
                                        modifier = Modifier.width(32.dp),
                                        style = MaterialTheme.typography.labelSmall
                                    )

                                    Text(
                                        item.optString("points"),
                                        modifier = Modifier.width(32.dp),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                if (index < tableRows.length() - 1) {
                                    HorizontalDivider(
                                        color = Muted.copy(alpha = 0.12f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (activePage == "History") {
        SectionTitle("Prediction History", "Your saved forecasts, newest first")
        val historyRecords = (0 until savedPredictions.length())
            .map { savedPredictions.getJSONObject(it) }
            .sortedByDescending { it.optLong("predicted_at") }


        if (historyRecords.isEmpty()) {
            Text(
                "No saved predictions yet. Select a fixture and press PREDICT MATCH.",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            historyRecords.forEach { record ->
                val status = record.optString("status", "pending")
                val predicted = normalizedOutcome(
                    record.optString("most_likely_result")
                )
                val actual = normalizedOutcome(
                    record.optString("actual_outcome")
                )
                val isCorrect = status == "verified" && predicted == actual

                val statusLabel = when {
                    status != "verified" -> "PENDING"
                    isCorrect -> "CORRECT"
                    else -> "INCORRECT"
                }

                val statusColor = when {
                    status != "verified" -> Orange
                    isCorrect -> Green
                    else -> Red
                }

                val probabilities = record.optJSONObject("probabilities")

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                record.optString("league"),
                                color = Blue,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                statusLabel,
                                color = statusColor,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CountryFlag(
                                record.optString("home"),
                                record.optString("league")
                            )
                            Text(
                                record.optString("home"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text("vs", color = Blue)
                            CountryFlag(
                                record.optString("away"),
                                record.optString("league")
                            )
                            Text(
                                record.optString("away"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            record.optString("date"),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Prediction", color = Muted)
                            Text(
                                record.optString("most_likely_result"),
                                color = Blue,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (probabilities != null) {
                            HorizontalDivider(
                                color = Muted.copy(alpha = 0.25f)
                            )

                            Text(
                                "1X2 PROBABILITIES",
                                color = Muted,
                                style = MaterialTheme.typography.labelSmall
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "H %.1f%%".format(
                                        java.util.Locale.US,
                                        probabilities.optDouble("home") * 100.0
                                    ),
                                    color = Blue
                                )
                                Text(
                                    "D %.1f%%".format(
                                        java.util.Locale.US,
                                        probabilities.optDouble("draw") * 100.0
                                    ),
                                    color = Orange
                                )
                                Text(
                                    "A %.1f%%".format(
                                        java.util.Locale.US,
                                        probabilities.optDouble("away") * 100.0
                                    ),
                                    color = Green
                                )
                            }
                        }

                        if (status == "verified") {
                            HorizontalDivider(
                                color = Muted.copy(alpha = 0.25f)
                            )
                            Text(
                                "FINAL SCORE: ${record.optInt("home_goals")} - " +
                                    "${record.optInt("away_goals")}",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        }

        }

        if (activePage == "Fixtures") {
        // Match centre
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("Match Centre", "Choose a league and select an upcoming fixture")

            IconButton(
                onClick = { refreshKey++ },
                enabled = !loadingFixtures
            ) {
                Text(
                    "↻",
                    color = if (loadingFixtures) Muted else Blue,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("LEAGUE  •  $league")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                leagues.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item) },
                        onClick = {
                            league = item
                            expanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search team or date") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("UPCOMING FIXTURES", color = Muted, style = MaterialTheme.typography.labelMedium)
            Text("${filteredFixtures.size} matches", color = Blue, style = MaterialTheme.typography.labelMedium)
        }

        if (loadingFixtures) {
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Blue)
            }
        } else if (filteredFixtures.isEmpty()) {
            Text("No matching fixtures found.", color = Muted)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                filteredFixtures.forEach { fixture ->
                    val candidate = SelectedMatch(
                        league, fixture.date, fixture.home, fixture.away
                    )
                    val isAdded = selectedMatches.any {
                        sameSelectedMatch(it, candidate)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!isAdded) {
                                    selectedMatches = selectedMatches + candidate
                                    saveSelectedMatches(activity, selectedMatches)
                                }
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isAdded) LocalFps1Palette.current.elevated else Panel
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    fixture.date,
                                    color = Muted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CountryFlag(fixture.home, league)
                            ClubBadge(fixture.home)
                            Text(fixture.home, fontWeight = FontWeight.SemiBold)
                        }
                                Text("vs", color = Blue, style = MaterialTheme.typography.labelSmall)
                                Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CountryFlag(fixture.away, league)
                            ClubBadge(fixture.away)
                            Text(fixture.away, fontWeight = FontWeight.SemiBold)
                        }
                            }

                            Text(
                                if (isAdded) "ADDED" else "ADD +",
                                color = if (isAdded) Green else Blue,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        }

        if (activePage == "Selected Matches") {
            SectionTitle(
                "Selected Matches",
                "Your saved fixtures, available for analysis"
            )

            if (selectedMatches.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "No selected matches yet",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Open Fixtures and tap a match to add it here.",
                            color = Muted
                        )
                        OutlinedButton(
                            onClick = { activePage = "Fixtures" }
                        ) {
                            Text("BROWSE FIXTURES")
                        }
                    }
                }
            } else {
                Text(
                    "${selectedMatches.size} saved matches",
                    color = Blue,
                    style = MaterialTheme.typography.labelMedium
                )

                selectedMatches.forEach { match ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Panel)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "${match.league}  •  ${match.date}",
                                color = Muted,
                                style = MaterialTheme.typography.labelSmall
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        CountryFlag(match.home, match.league)
                                        ClubBadge(match.home)
                                        Text(
                                            match.home,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        CountryFlag(match.away, match.league)
                                        ClubBadge(match.away)
                                        Text(
                                            match.away,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                Text(
                                    "VS",
                                    color = Blue,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            HorizontalDivider(color = Muted.copy(alpha = 0.25f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedFixtureLeague = match.league
                                        selectedFixture = Fixture(
                                            match.date,
                                            match.home,
                                            match.away
                                        )
                                        league = match.league
                                        result = ""
                                        error = ""
                                        activePage = "Analysis"
                                    }
                                ) {
                                    Text("ANALYSE")
                                }

                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        selectedMatches = selectedMatches.filterNot {
                                            sameSelectedMatch(it, match)
                                        }
                                        saveSelectedMatches(activity, selectedMatches)
                                    }
                                ) {
                                    Text("REMOVE")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (activePage == "Analysis") {
        // Prediction panel
        SectionTitle("Prediction Centre", "Select a fixture to analyse")

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Panel)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (selectedFixture == null) {
                    Text("No match selected", color = Muted)
                    Text("Choose a fixture from the Match Centre above.")
                } else {
                    Text("SELECTED FIXTURE", color = Green, style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CountryFlag(selectedFixture!!.home, selectedFixtureLeague)
                            ClubBadge(selectedFixture!!.home)
                            Text(
                                selectedFixture!!.home,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text("VS", color = Blue, fontWeight = FontWeight.Bold)

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CountryFlag(selectedFixture!!.away, selectedFixtureLeague)
                            ClubBadge(selectedFixture!!.away)
                            Text(
                                selectedFixture!!.away,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text("${selectedFixture!!.date}  •  $selectedFixtureLeague", color = Muted, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        val fixture = selectedFixture ?: return@Button
                        loadingPrediction = true
                        result = ""
                        error = ""

                        val selectedLeague = selectedFixtureLeague
                        val homeTeam = fixture.home
                        val awayTeam = fixture.away

                        Thread {
                            try {
                                val module = Python.getInstance().getModule("bridge")
                                val json = module.callAttr(
                                    "predict",
                                    selectedLeague,
                                    homeTeam,
                                    awayTeam
                                ).toString()

                                val prediction = JSONObject(json)
                                val updatedHistory = savePrediction(
                                    activity,
                                    selectedLeague,
                                    fixture,
                                    prediction
                                )

                                activity.runOnUiThread {
                                    savedPredictions = updatedHistory
                                    result = json
                                    loadingPrediction = false
                                }
                            } catch (e: Exception) {
                                activity.runOnUiThread {
                                    error = e.message ?: "Prediction failed."
                                    loadingPrediction = false
                                }
                            }
                        }.start()
                    },
                    enabled = selectedFixture != null && !loadingPrediction && !loadingFixtures,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (loadingPrediction) "ANALYSING MATCH..." else "PREDICT MATCH")
                }

                if (loadingPrediction) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Blue)
                }

                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Results
        if (result.isNotEmpty()) {
            val data = JSONObject(result)
            val probabilities = data.getJSONObject("probabilities")
            val goals = data.getJSONObject("expected_goals")

            SectionTitle("Prediction Results", "Model-generated match estimates")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CountryFlag(data.getString("home"), league)
                            ClubBadge(data.getString("home"))
                            Text(
                                data.getString("home"),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        Text("VS", color = Blue, fontWeight = FontWeight.Bold)

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CountryFlag(data.getString("away"), league)
                            ClubBadge(data.getString("away"))
                            Text(
                                data.getString("away"),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }

                    HorizontalDivider(color = Muted.copy(alpha = 0.25f))

                    Text("1X2 PROBABILITIES", color = Blue, style = MaterialTheme.typography.labelMedium)

                    ProbabilityRow("Home win", probabilities.getDouble("home"), Blue)
                    ProbabilityRow("Draw", probabilities.getDouble("draw"), Orange)
                    ProbabilityRow("Away win", probabilities.getDouble("away"), Green)

                    HorizontalDivider(color = Muted.copy(alpha = 0.25f))

                    Text("EXPECTED GOALS", color = Blue, style = MaterialTheme.typography.labelMedium)
                    Text("Home: %.2f".format(goals.getDouble("home")))
                    Text("Away: %.2f".format(goals.getDouble("away")))

                    Text("MOST LIKELY OUTCOME", color = Blue, style = MaterialTheme.typography.labelMedium)
                    Text(
                        data.getString("most_likely_result").uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text("TOP CORRECT SCORES", color = Blue, style = MaterialTheme.typography.labelMedium)
                    val scores = data.getJSONArray("top_scores")

                    for (i in 0 until scores.length()) {
                        val score = scores.getJSONObject(i)
                        Text(
                            "${score.getString("score")}  •  " +
                            "%.1f%%".format(score.getDouble("probability") * 100)
                        )
                    }
                }
            }

            Text(
                "Model estimates only. Not betting advice.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider(color = Muted.copy(alpha = 0.25f))

        }

        if (activePage == "Settings") {
            SectionTitle(
                "Settings",
                "Personalise your FPS-1 experience"
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "APPEARANCE",
                        color = Blue,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )

                    Text(
                        "Choose how FPS-1 should appear.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Fps1ThemeMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onThemeModeChange(mode)
                                }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = {
                                    onThemeModeChange(mode)
                                }
                            )

                            Column(
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    mode.label,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Text(
                                    when (mode) {
                                        Fps1ThemeMode.SYSTEM ->
                                            "Follow your device appearance"
                                        Fps1ThemeMode.DARK ->
                                            "Dark charcoal interface"
                                        Fps1ThemeMode.LIGHT ->
                                            "Light interface"
                                    },
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        if (activePage == "About") {
            SectionTitle("About FPS-1", "Football Prediction System")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "WELCOME TO FPS-1",
                        color = Blue,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Understand the game. Analyse the possibilities.",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "FPS-1 analyses football fixtures and estimates Home, Draw and Away probabilities, expected goals and possible scorelines.",
                        color = Muted
                    )
                    Text(
                        "Predictions are statistical estimates, not guarantees or betting advice.",
                        color = Green
                    )
                }
            }
        }

        Text(
            "SAMJAY1  •  FPS-1 FOOTBALL ANALYTICS",
            modifier = Modifier.fillMaxWidth(),
            color = Muted,
            style = MaterialTheme.typography.labelSmall
        )
            }

            NavigationBar(
                containerColor = Panel,
                contentColor = Blue
            ) {
                val destinations = listOf(
                    Triple("Dashboard", "⌂", "Home"),
                    Triple("Fixtures", "▦", "Fixtures"),
                    Triple("Analysis", "◈", "Analysis"),
                    Triple("Selected Matches", "☑", "Selected"),
                    Triple("History", "◷", "History"),
                    Triple("Settings", "⚙", "Settings")
                )

                destinations.forEach { (page, symbol, label) ->
                    NavigationBarItem(
                        selected = activePage == page,
                        onClick = { activePage = page },
                        icon = {
                            Text(
                                symbol,
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbabilityRow(label: String, probability: Double, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label)
            Text(
                "%.1f%%".format(probability * 100),
                fontWeight = FontWeight.Bold
            )
        }

        LinearProgressIndicator(
            progress = { probability.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(7.dp),
            color = accent,
            trackColor = Navy
        )
    }
}
