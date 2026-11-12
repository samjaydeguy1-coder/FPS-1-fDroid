import json
import re
import difflib
import unicodedata
from pathlib import Path

import pandas as pd

from fps1.data import load_data
from fps1.standings import calculate_standings
from fps1.model import (
    predict_components,
    blend_components,
    market_probabilities,
)



def normalize_team_name(name):
    name = unicodedata.normalize("NFKD", str(name))
    name = "".join(
        c for c in name if not unicodedata.combining(c)
    )
    name = name.casefold().replace("&", " and ")
    name = re.sub(r"\b(fc|afc|cf|sc)\b", " ", name)
    name = re.sub(r"[^a-z0-9]+", " ", name)
    return " ".join(name.split())


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


def resolve_team_name(name, available_teams, label, league):
    exact_lookup = {
        str(team).strip().casefold(): team
        for team in available_teams
    }

    # Preserve exact official names, including names that normalize
    # to the same value as another club.
    exact_key = name.strip().casefold()
    if exact_key in exact_lookup:
        return exact_lookup[exact_key]

    normalized_lookup = {}
    for team in available_teams:
        key = normalize_team_name(team)
        normalized_lookup.setdefault(key, []).append(team)

    key = normalize_team_name(name)
    target = TEAM_ALIASES.get(key, key)
    matches = normalized_lookup.get(target, [])

    if len(matches) == 1:
        return matches[0]

    if len(matches) > 1:
        raise ValueError(
            f"{label} team name '{name}' is ambiguous in {league}: "
            + ", ".join(sorted(matches))
        )

    suggestions = difflib.get_close_matches(
        target,
        list(normalized_lookup.keys()),
        n=3,
        cutoff=0.55
    )

    if suggestions:
        possible = sorted({
            team
            for suggestion in suggestions
            for team in normalized_lookup[suggestion]
        })
        message = " Possible matches: " + ", ".join(possible)
    else:
        message = ""

    raise ValueError(
        f"{label} team '{name}' was not recognized in {league}."
        + message
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

    if league not in config["leagues"] and league not in config.get("international_competitions", {}):
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

    # Resolve user-entered names against actual league history.
    available_teams = (
        set(history["home_team"].dropna()) |
        set(history["away_team"].dropna())
    )

    home = resolve_team_name(
        home, available_teams, "Home", league
    )
    away = resolve_team_name(
        away, available_teams, "Away", league
    )

    if home == away:
        raise ValueError(
            "Home and away teams resolve to the same club."
        )

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


def get_fixtures(league):
    """Return upcoming scheduled fixtures for one supported league."""
    league = league.strip()

    config = json.loads(
        (ROOT / "config.json").read_text(encoding="utf-8")
    )

    if league not in config["leagues"] and league not in config.get("international_competitions", {}):
        raise ValueError("Invalid league selected.")

    df = load_data(ROOT / "data" / "matches.csv")

    df["date"] = pd.to_datetime(
        df["date"], utc=True, errors="coerce"
    )

    now = pd.Timestamp.now(tz="UTC")

    upcoming = df[
        (df["league"] == league) &
        (df["status"] == "scheduled") &
        (df["date"] >= now)
    ].sort_values("date")

    fixtures = []

    for _, row in upcoming.iterrows():
        fixtures.append({
            "date": row["date"].strftime("%Y-%m-%d"),
            "home": str(row["home_team"]),
            "away": str(row["away_team"]),
        })

    return json.dumps({
        "league": league,
        "count": len(fixtures),
        "fixtures": fixtures,
    })


def get_match_result(league, date, home, away):
    """Return a verified final result for an exact fixture, if available."""
    league = league.strip()
    date = date.strip()
    home = home.strip()
    away = away.strip()

    df = load_data(ROOT / "data" / "matches.csv")

    df["date"] = pd.to_datetime(
        df["date"], utc=True, errors="coerce"
    )

    matches = df[
        (df["league"] == league) &
        (df["date"].dt.strftime("%Y-%m-%d") == date) &
        (df["home_team"].astype(str) == home) &
        (df["away_team"].astype(str) == away) &
        (df["status"] == "finished")
    ]

    if matches.empty:
        return json.dumps({"found": False})

    row = matches.iloc[-1]

    if pd.isna(row["home_goals"]) or pd.isna(row["away_goals"]):
        return json.dumps({"found": False})

    home_goals = int(row["home_goals"])
    away_goals = int(row["away_goals"])

    if home_goals > away_goals:
        outcome = "HOME"
    elif home_goals < away_goals:
        outcome = "AWAY"
    else:
        outcome = "DRAW"

    return json.dumps({
        "found": True,
        "league": league,
        "date": date,
        "home": home,
        "away": away,
        "home_goals": home_goals,
        "away_goals": away_goals,
        "outcome": outcome
    })


def get_standings_seasons(league):
    """Return seasons available for a competition in the Android dataset."""
    league = league.strip()
    df = load_data(ROOT / "data" / "matches.csv")
    seasons = sorted(
        df.loc[df["league"].astype(str).str.strip() == league, "season"]
        .dropna()
        .astype(str)
        .unique()
    )
    return json.dumps(seasons, ensure_ascii=False)


def get_standings(league, season):
    """Calculate a competition table using the Android app's match dataset."""
    league = league.strip()
    season = season.strip()
    df = load_data(ROOT / "data" / "matches.csv")
    result = calculate_standings(df, league, season)
    return json.dumps(result, ensure_ascii=False)
