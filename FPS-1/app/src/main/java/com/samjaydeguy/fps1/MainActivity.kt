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
    var home by remember { mutableStateOf("") }
    var away by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

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
                            result = ""
                            error = ""
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = home,
            onValueChange = { home = it },
            label = { Text("Home team") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = away,
            onValueChange = { away = it },
            label = { Text("Away team") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = {
                loading = true
                result = ""
                error = ""

                val selectedLeague = league
                val homeTeam = home.trim()
                val awayTeam = away.trim()

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
                            loading = false
                        }
                    } catch (e: Exception) {
                        activity.runOnUiThread {
                            error = e.message ?: "Prediction failed."
                            loading = false
                        }
                    }
                }.start()
            },
            enabled = !loading &&
                home.isNotBlank() &&
                away.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (loading) "Calculating..." else "PREDICT MATCH"
            )
        }

        if (loading) {
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
