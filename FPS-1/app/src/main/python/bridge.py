import json
from pathlib import Path

import pandas as pd

from fps1.data import load_data
from fps1.model import (
    predict_components,
    blend_components,
    market_probabilities,
)

ROOT = Path(__file__).resolve().parent


def predict(league, home, away):
    league = league.strip()
    home = home.strip()
    away = away.strip()

    if not home or not away:
        raise ValueError("Enter both team names.")

    if home.casefold() == away.casefold():
        raise ValueError("Home and away teams must be different.")

    config = json.loads(
        (ROOT / "config.json").read_text(encoding="utf-8")
    )

    if league not in config["leagues"]:
        raise ValueError("Invalid league selected.")

    df = load_data(ROOT / "data" / "matches.csv")

    history = df[
        (df["league"] == league) &
        (df["status"] == "finished")
    ].copy()

    if history.empty:
        raise ValueError(
            "No completed matches available for this league."
        )

    # Validate and resolve team names against actual league history.
    available_teams = set(history["home_team"].dropna()) | set(
        history["away_team"].dropna()
    )

    team_lookup = {
        str(team).strip().casefold(): team
        for team in available_teams
    }

    home_key = home.strip().casefold()
    away_key = away.strip().casefold()

    if home_key not in team_lookup:
        raise ValueError(
            f"Home team '{home}' was not found in {league}. "
            "Use the exact team name from the dataset."
        )

    if away_key not in team_lookup:
        raise ValueError(
            f"Away team '{away}' was not found in {league}. "
            "Use the exact team name from the dataset."
        )

    home = team_lookup[home_key]
    away = team_lookup[away_key]

    components = predict_components(
        history,
        home,
        away,
        pd.Timestamp.now(tz="UTC"),
        config["max_goals"],
        config["time_decay_days"],
        config["recent_form_matches"],
        config["elo_k"],
        config["elo_home_advantage"],
    )

    matrix = blend_components(
        components,
        config["ensemble_weights"]
    )

    result = market_probabilities(matrix)

    response = {
        "league": league,
        "home": home,
        "away": away,
        "expected_goals": {
            "home": components.lambda_home,
            "away": components.lambda_away,
        },
        "probabilities": result["1X2"],
        "most_likely_result": result["most_likely_result"],
        "top_scores": result["correct_score_top"],
    }

    return json.dumps(response)
