from __future__ import annotations
import json, time
from pathlib import Path
from datetime import datetime, timezone
import requests
import pandas as pd

BASE = "https://raw.githubusercontent.com/openfootball/football.json/master"

def fetch_openfootball(config: dict, seasons: list[str], raw_dir: Path, timeout=25) -> pd.DataFrame:
    """Fetch public-domain fixtures/results. Unplayed fixtures are retained separately."""
    raw_dir.mkdir(parents=True, exist_ok=True)
    rows, errors = [], []
    for season in seasons:
        for league, meta in config["leagues"].items():
            url = f"{BASE}/{season}/{meta['data_file']}"
            path = raw_dir / f"{season}_{league}.json"
            try:
                if not path.exists() or path.stat().st_size == 0:
                    r = requests.get(url, timeout=timeout, headers={"User-Agent": "FPS-1/1.0"})
                    if r.status_code == 404:
                        continue
                    r.raise_for_status()
                    path.write_text(r.text, encoding="utf-8")
                payload = json.loads(path.read_text(encoding="utf-8"))
                matches = payload.get("matches", []) if isinstance(payload, dict) else payload
                for m in matches:
                    score = m.get("score") or {}
                    ft = score.get("ft") if isinstance(score, dict) else score

                    if not isinstance(ft, list) or len(ft) != 2:
                        ft = None

                    home, away = m.get("team1"), m.get("team2")

                    if not home or not away or not m.get("date"):
                        continue

                    hg = ag = None

                    if ft is not None:
                        hg, ag = ft

                    rows.append({
                        "date": pd.to_datetime(m["date"], errors="coerce", utc=True),
                        "league": league, "season": season,
                        "home_team": str(home).strip(), "away_team": str(away).strip(),
                        "home_goals": hg, "away_goals": ag,
                        "status": "finished" if hg is not None and ag is not None else "scheduled",
                        "source": "openfootball"
                    })
            except Exception as e:
                errors.append(f"{season}/{league}: {e}")
    if errors:
        print("Data warnings:")
        for e in errors: print(" -", e)
    if not rows:
        raise RuntimeError("No data downloaded. Check internet access, league codes, or season folders.")
    df = pd.DataFrame(rows).dropna(subset=["date"])
    df = df.drop_duplicates(subset=["league", "date", "home_team", "away_team"])
    df = df.sort_values(["league", "date"]).reset_index(drop=True)
    return df


NATIONS_LEAGUE_BASE = (
    "https://raw.githubusercontent.com/openfootball/internationals/"
    "master/uefa_nations_league"
)

NATIONS_LEAGUE_SEASONS = {
    2018: "2018-19",
    2019: "2018-19",
    2020: "2020-21",
    2021: "2020-21",
    2022: "2022-23",
    2023: "2022-23",
    2024: "2024-25",
    2025: "2024-25",
}

NATIONS_LEAGUE_EXPECTED = {
    2018: 138,
    2019: 4,
    2020: 162,
    2021: 4,
    2022: 160,
    2023: 4,
    2024: 158,
    2025: 28,
}


def fetch_nations_league(raw_dir: Path, timeout=25) -> pd.DataFrame:
    """Fetch and parse historical UEFA Nations League results."""
    import re

    raw_dir.mkdir(parents=True, exist_ok=True)

    date_pattern = re.compile(
        r"^(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+"
        r"(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+"
        r"\d{1,2}$"
    )

    match_pattern = re.compile(
        r"^(.+?)\s+(\d+)-(\d+)\s+(.+?)\s+@\s*.+$"
    )

    rows = []

    for year, season in NATIONS_LEAGUE_SEASONS.items():
        path = raw_dir / f"{year}_uefa_nations_league.txt"

        if not path.exists() or path.stat().st_size == 0:
            url = f"{NATIONS_LEAGUE_BASE}/{year}_uefa_nations_league.txt"
            response = requests.get(
                url,
                timeout=timeout,
                headers={"User-Agent": "FPS-1/1.0"},
            )
            response.raise_for_status()
            path.write_text(response.text, encoding="utf-8")

        current_date = None
        year_rows = []

        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()

            if not line or line.startswith("#") or line.startswith("="):
                continue

            if date_pattern.fullmatch(line):
                current_date = datetime.strptime(
                    f"{year} {line}", "%Y %a %b %d"
                )
                continue

            match = match_pattern.match(line)

            if not match:
                continue

            if current_date is None:
                raise ValueError(
                    f"Match without date heading in {path.name}: {line}"
                )

            home, home_goals, away_goals, away = match.groups()

            year_rows.append({
                "date": pd.to_datetime(current_date, utc=True),
                "league": "NationsLeague",
                "season": season,
                "home_team": home.strip(),
                "away_team": away.strip(),
                "home_goals": int(home_goals),
                "away_goals": int(away_goals),
                "status": "finished",
                "source": "openfootball-internationals",
            })

        expected = NATIONS_LEAGUE_EXPECTED[year]

        if len(year_rows) != expected:
            raise ValueError(
                f"{year} Nations League count mismatch: "
                f"expected {expected}, parsed {len(year_rows)}"
            )

        rows.extend(year_rows)
        print(f"Nations League {year}: verified {len(year_rows)} matches")

    df = pd.DataFrame(rows)

    df = df.drop_duplicates(
        subset=["league", "date", "home_team", "away_team"]
    )

    df = df.sort_values("date").reset_index(drop=True)

    if len(df) != 658:
        raise ValueError(
            f"Historical Nations League total should be 658, got {len(df)}"
        )

    return df

def save_data(df: pd.DataFrame, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(path, index=False)

def load_data(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    df["date"] = pd.to_datetime(df["date"], format="mixed", utc=True)
    for c in ("home_goals", "away_goals"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    return df
