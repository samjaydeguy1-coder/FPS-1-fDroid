package com.samjaydeguy.fps1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chaquo.python.Python
import org.json.JSONObject

private val Navy = Color(0xFF101B32)
private val Panel = Color(0xFF1B2942)
private val Blue = Color(0xFF3987F6)
private val Muted = Color(0xFF9EADC4)
private val Green = Color(0xFF36C994)

data class Fixture(
    val date: String,
    val home: String,
    val away: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue,
                    secondary = Green,
                    background = Navy,
                    surface = Panel,
                    onPrimary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                FPS1Screen(this)
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    symbol: String
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
            Text(symbol, color = Blue, style = MaterialTheme.typography.titleLarge)
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

@Composable
fun FPS1Screen(activity: ComponentActivity) {
    val leagues = listOf("EPL", "LaLiga", "SerieA", "Bundesliga", "Ligue1")

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
            .background(Navy)
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
                Text("F1", fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("FPS-1", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                Text("FOOTBALL PREDICTION SYSTEM", color = Muted, style = MaterialTheme.typography.labelSmall)
            }

            Text(
                "● SCHEDULED MATCHES",
                color = Green,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

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
                Text("WELCOME TO FPS-1", color = Blue, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
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
                StatCard("Upcoming fixtures", if (loadingFixtures) "..." else fixtures.size.toString(), "◷")
            }
            Box(modifier = Modifier.weight(1f)) {
                StatCard("Supported leagues", "5", "⚽")
            }
        }

        // Match centre
        SectionTitle("Match Centre", "Choose a league and select an upcoming fixture")

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
                    val selected = selectedFixture == fixture

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedFixture = fixture
                                result = ""
                                error = ""
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) Color(0xFF243F65) else Panel
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
                                Text(fixture.home, fontWeight = FontWeight.SemiBold)
                                Text("vs", color = Blue, style = MaterialTheme.typography.labelSmall)
                                Text(fixture.away, fontWeight = FontWeight.SemiBold)
                            }

                            if (selected) {
                                Text(
                                    "SELECTED",
                                    color = Green,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text("›", color = Blue, style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                    }
                }
            }
        }

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
                    Text(
                        "${selectedFixture!!.home} vs ${selectedFixture!!.away}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("${selectedFixture!!.date}  •  $league", color = Muted, style = MaterialTheme.typography.bodySmall)
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
                                val module = Python.getInstance().getModule("bridge")
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
                    Text(
                        "${data.getString("home")} vs ${data.getString("away")}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )

                    HorizontalDivider(color = Muted.copy(alpha = 0.25f))

                    Text("1X2 PROBABILITIES", color = Blue, style = MaterialTheme.typography.labelMedium)

                    ProbabilityRow("Home win", probabilities.getDouble("home"))
                    ProbabilityRow("Draw", probabilities.getDouble("draw"))
                    ProbabilityRow("Away win", probabilities.getDouble("away"))

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

        Text(
            "FPS-1  •  FOOTBALL ANALYTICS",
            modifier = Modifier.fillMaxWidth(),
            color = Muted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ProbabilityRow(label: String, probability: Double) {
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
            color = Blue,
            trackColor = Navy
        )
    }
}
