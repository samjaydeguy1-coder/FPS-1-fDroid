import json
import os
import re
import unicodedata
import difflib
from pathlib import Path

import pandas as pd
from flask import Flask, jsonify, request, send_from_directory

from fps1.data import load_data
from fps1.model import (
    predict_components,
    blend_components,
    market_probabilities,
)

ROOT = Path(__file__).resolve().parent.parent
WEBSITE = Path(__file__).resolve().parent
STATIC = WEBSITE / "static"

app = Flask(__name__, static_folder=str(STATIC), static_url_path="/static")

CONFIG = json.loads((ROOT / "config.json").read_text(encoding="utf-8"))
DATA_FILE = ROOT / "data" / "matches.csv"

TEAM_ALIASES = {
    "man utd": "manchester united",
    "man united": "manchester united",
    "man u": "manchester united",
    "man city": "manchester city",
    "spurs": "tottenham hotspur",
    "wolves": "wolverhampton wanderers",
    "brighton": "brighton and hove albion",
    "newcastle": "newcastle united",
    "west ham": "west ham united",
    "leicester": "leicester city",
    "ipswich": "ipswich town",
    "forest": "nottingham forest",
    "palace": "crystal palace",
}


def normalize_team_name(name):
    name = unicodedata.normalize("NFKD", str(name))
    name = "".join(c for c in name if not unicodedata.combining(c))
    name = name.casefold().replace("&", " and ")
    name = re.sub(r"\b(fc|afc|cf|sc)\b", " ", name)
    name = re.sub(r"[^a-z0-9]+", " ", name)
    return " ".join(name.split())


def get_data():
    if not DATA_FILE.exists():
        raise RuntimeError("The football dataset is missing.")
    return load_data(DATA_FILE)


def get_history(league):
    if league not in CONFIG["leagues"]:
        raise ValueError("Invalid league selected.")

    df = get_data()
    history = df[
        (df["league"] == league) &
        (df["status"] == "finished")
    ].copy()

    if history.empty:
        raise ValueError("No completed matches available for this league.")

    return df, history


def resolve_team_name(name, available_teams, label, league):
    exact = {
        str(team).strip().casefold(): team
        for team in available_teams
    }

    key = name.strip().casefold()
    if key in exact:
        return exact[key]

    normalized = {}
    for team in available_teams:
        normalized.setdefault(normalize_team_name(team), []).append(team)

    target = normalize_team_name(name)
    target = TEAM_ALIASES.get(target, target)
    matches = normalized.get(target, [])

    if len(matches) == 1:
        return matches[0]

    if len(matches) > 1:
        raise ValueError(
            f"{label} team name '{name}' is ambiguous in {league}: "
            + ", ".join(sorted(matches))
        )

    suggestions = difflib.get_close_matches(
        target, list(normalized.keys()), n=3, cutoff=0.55
    )

    possible = sorted({
        team
        for suggestion in suggestions
        for team in normalized[suggestion]
    })

    message = (
        " Possible matches: " + ", ".join(possible)
        if possible else ""
    )

    raise ValueError(
        f"{label} team '{name}' was not recognized in {league}."
        + message
    )


@app.get("/")
def home():
    return send_from_directory(STATIC, "index.html")


@app.get("/api/health")
def health():
    return jsonify({
        "status": "ok",
        "service": "FPS-1",
        "dataset_available": DATA_FILE.exists(),
    })


@app.get("/api/leagues")
def leagues():
    return jsonify({
        "leagues": [
            {"id": key, "name": value["name"]}
            for key, value in CONFIG["leagues"].items()
        ]
    })


@app.get("/api/teams")
def teams():
    league = request.args.get("league", "").strip()

    try:
        _, history = get_history(league)
        available = sorted(
            set(history["home_team"].dropna()) |
            set(history["away_team"].dropna())
        )
        return jsonify({"league": league, "teams": available})
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400


@app.get("/api/fixtures")
def fixtures():
    league = request.args.get("league", "").strip()

    try:
        if league not in CONFIG["leagues"]:
            raise ValueError("Invalid league selected.")

        df = get_data()
        df["date"] = pd.to_datetime(df["date"], utc=True, errors="coerce")
        now = pd.Timestamp.now(tz="UTC")

        upcoming = df[
            (df["league"] == league) &
            (df["status"] == "scheduled") &
            (df["date"] >= now)
        ].sort_values("date")

        results = [
            {
                "date": row["date"].strftime("%Y-%m-%d"),
                "home": str(row["home_team"]),
                "away": str(row["away_team"]),
            }
            for _, row in upcoming.iterrows()
        ]

        return jsonify({
            "league": league,
            "count": len(results),
            "fixtures": results,
        })
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400


@app.post("/api/predict")
def predict():
    payload = request.get_json(silent=True) or {}

    league = str(payload.get("league", "")).strip()
    home = str(payload.get("home", "")).strip()
    away = str(payload.get("away", "")).strip()

    if not home or not away:
        return jsonify({"error": "Enter both team names."}), 400

    if len(home) > 100 or len(away) > 100:
        return jsonify({"error": "Team name is too long."}), 400

    if home.casefold() == away.casefold():
        return jsonify({"error": "Home and away teams must be different."}), 400

    try:
        _, history = get_history(league)

        available = (
            set(history["home_team"].dropna()) |
            set(history["away_team"].dropna())
        )

        home = resolve_team_name(home, available, "Home", league)
        away = resolve_team_name(away, available, "Away", league)

        if home == away:
            raise ValueError("Home and away teams resolve to the same club.")

        components = predict_components(
            history,
            home,
            away,
            pd.Timestamp.now(tz="UTC"),
            CONFIG["max_goals"],
            CONFIG["time_decay_days"],
            CONFIG["recent_form_matches"],
            CONFIG["elo_k"],
            CONFIG["elo_home_advantage"],
        )

        matrix = blend_components(
            components,
            CONFIG["ensemble_weights"],
        )

        result = market_probabilities(matrix)

        response = {
            "league": league,
            "home": home,
            "away": away,
            "expected_goals": {
                "home": float(components.lambda_home),
                "away": float(components.lambda_away),
            },
            "probabilities": result["1X2"],
            "most_likely_result": result["most_likely_result"],
            "top_scores": result["correct_score_top"],
        }

        return jsonify(response)

    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400
    except Exception:
        app.logger.exception("Prediction failed")
        return jsonify({
            "error": "Prediction could not be completed. Please try again."
        }), 500


if __name__ == "__main__":
    app.run(
        host="0.0.0.0",
        port=int(os.environ.get("PORT", 5000)),
        debug=False,
    )
