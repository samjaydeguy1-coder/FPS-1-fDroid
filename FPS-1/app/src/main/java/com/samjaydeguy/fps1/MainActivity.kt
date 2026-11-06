package com.samjaydeguy.fps1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import org.json.JSONObject

data class Fixture(
    val date: String,
    val home: String,
    val away: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                FPS1Screen(this)
            }
        }
    }
}

@Composable
fun FPS1Screen(activity: ComponentActivity) {
    val leagues = listOf(
        "EPL", "LaLiga", "SerieA", "Bundesliga", "Ligue1"
    )

    var league by remember { mutableStateOf("EPL") }
    var expanded by remember { mutableStateOf(false) }

    var fixtures by remember { mutableStateOf(listOf<Fixture>()) }
    var selectedFixture by remember { mutableStateOf<Fixture?>(null) }
    var search by remember { mutableStateOf("") }

    var loadingFixtures by remember { mutableStateOf(false) }
    var loadingPrediction by remember { mutableStateOf(false) }

    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(league) {
        fixtures = emptyList()
        selectedFixture = null
        search = ""
        result = ""
        error = ""
        loadingFixtures = true

        val selectedLeague = league

        Thread {
            try {
                val module = Python.getInstance().getModule("bridge")
                val json = module.callAttr(
                    "get_fixtures",
                    selectedLeague
                ).toString()

                val data = JSONObject(json)
                val array = data.getJSONArray("fixtures")
                val loaded = mutableListOf<Fixture>()

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    loaded.add(
                        Fixture(
                            date = item.getString("date"),
                            home = item.getString("home"),
                            away = item.getString("away")
                        )
                    )
                }

                activity.runOnUiThread {
                    fixtures = loaded
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "FPS-1",
            style = MaterialTheme.typography.headlineLarge
        )

        Text("Football Prediction System")
        Text("1X2 Match Probability")

        Text(
            "SELECT LEAGUE",
            style = MaterialTheme.typography.titleMedium
        )

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("League: $league")
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

        HorizontalDivider()

        Text(
            "AVAILABLE UPCOMING MATCHES",
            style = MaterialTheme.typography.titleMedium
        )

        if (loadingFixtures) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp)
                )
                Text("Loading $league fixtures...")
            }
        } else {
            Text("${fixtures.size} upcoming fixtures available")

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Search team or date") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (filteredFixtures.isEmpty()) {
                Text("No matching fixtures found.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filteredFixtures.forEach { fixture ->
                        OutlinedButton(
                            onClick = {
                                selectedFixture = fixture
                                result = ""
                                error = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selectedFixture == fixture)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    fixture.date,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    "${fixture.home} vs ${fixture.away}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        Text(
            "SELECTED MATCH",
            style = MaterialTheme.typography.titleMedium
        )

        if (selectedFixture == null) {
            Text("Tap a fixture above to select a match.")
        } else {
            Text(
                "${selectedFixture!!.home} vs ${selectedFixture!!.away}",
                style = MaterialTheme.typography.titleMedium
            )
            Text("Date: ${selectedFixture!!.date}")
            Text("League: $league")
        }

        Button(
            onClick = {
                val fixture = selectedFixture ?: return@Button

                loadingPrediction = true
                result = ""
                error = ""

                val selectedLeague = league
                val homeTeam = fixture.home
                val awayTeam = fixture.away

                Thread {
                    try {
                        val module = Python.getInstance()
                            .getModule("bridge")

                        val json = module.callAttr(
                            "predict",
                            selectedLeague,
                            homeTeam,
                            awayTeam
                        ).toString()

                        activity.runOnUiThread {
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
            enabled = selectedFixture != null &&
                !loadingPrediction &&
                !loadingFixtures,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (loadingPrediction)
                    "Calculating..."
                else
                    "PREDICT SELECTED MATCH"
            )
        }

        if (loadingPrediction) {
            CircularProgressIndicator()
        }

        if (error.isNotEmpty()) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (result.isNotEmpty()) {
            val data = JSONObject(result)
            val probabilities = data.getJSONObject("probabilities")
            val goals = data.getJSONObject("expected_goals")

            HorizontalDivider()

            Text(
                "PREDICTION RESULTS",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                "${data.getString("home")} vs ${data.getString("away")}"
            )
            Text("League: ${data.getString("league")}")

            Text(
                "Home win: %.1f%%".format(
                    probabilities.getDouble("home") * 100
                )
            )
            Text(
                "Draw: %.1f%%".format(
                    probabilities.getDouble("draw") * 100
                )
            )
            Text(
                "Away win: %.1f%%".format(
                    probabilities.getDouble("away") * 100
                )
            )

            HorizontalDivider()

            Text("Expected goals")
            Text("Home: %.2f".format(goals.getDouble("home")))
            Text("Away: %.2f".format(goals.getDouble("away")))

            Text(
                "Most likely outcome: ${
                    data.getString("most_likely_result").uppercase()
                }",
                style = MaterialTheme.typography.titleMedium
            )

            Text("Top correct scores")

            val scores = data.getJSONArray("top_scores")

            for (i in 0 until scores.length()) {
                val score = scores.getJSONObject(i)

                Text(
                    "${score.getString("score")} — " +
                    "%.1f%%".format(
                        score.getDouble("probability") * 100
                    )
                )
            }

            Text(
                "Model estimates only. Not betting advice.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
