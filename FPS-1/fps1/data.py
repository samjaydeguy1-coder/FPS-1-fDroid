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

def save_data(df: pd.DataFrame, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(path, index=False)

def load_data(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path, parse_dates=["date"])
    df["date"] = pd.to_datetime(df["date"], utc=True)
    for c in ("home_goals", "away_goals"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    return df
