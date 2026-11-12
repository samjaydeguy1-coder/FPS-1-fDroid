from __future__ import annotations

import pandas as pd


REQUIRED_COLUMNS = {
    "date",
    "league",
    "season",
    "status",
    "home_team",
    "away_team",
    "home_goals",
    "away_goals",
}


def calculate_standings(
    df: pd.DataFrame,
    league: str,
    season: str,
    as_of=None,
) -> dict:
    """
    Calculate a league table from the available fixture dataset.

    If as_of is supplied, only completed matches dated strictly before
    that date contribute to the table. All fixtures on the cutoff date
    are excluded to prevent same-day result leakage.

    If as_of is omitted, all valid completed matches in the season are
    included.

    Team membership is taken from the season fixture schedule. Fixture
    membership does not contribute points or other performance statistics.
    """

    missing = sorted(REQUIRED_COLUMNS - set(df.columns))
    if missing:
        raise ValueError(f"Missing required columns: {missing}")

    if not isinstance(league, str) or not league.strip():
        raise ValueError("A valid league identifier is required.")

    if not isinstance(season, str) or not season.strip():
        raise ValueError("A valid season identifier is required.")

    data = df.loc[
        (df["league"].astype(str).str.strip() == league)
        & (df["season"].astype(str).str.strip() == season)
    ].copy()

    if data.empty:
        raise ValueError(
            f"No fixtures found for {league}, season {season}."
        )

    data["_date"] = pd.to_datetime(
        data["date"], utc=True, errors="coerce"
    ).dt.floor("D")

    if data["_date"].isna().any():
        invalid_dates = int(data["_date"].isna().sum())
    else:
        invalid_dates = 0

    # A season's participating clubs are obtained from its fixture list.
    teams = sorted(
        set(data["home_team"].dropna().astype(str).str.strip())
        | set(data["away_team"].dropna().astype(str).str.strip())
    )
    teams = [team for team in teams if team]

    if not teams:
        raise ValueError("No valid teams found in the season fixture list.")

    if as_of is None:
        cutoff = pd.Timestamp.now(tz="UTC").normalize()
        historical_mode = False
    else:
        cutoff = pd.Timestamp(as_of)
        if cutoff.tzinfo is None:
            cutoff = cutoff.tz_localize("UTC")
        else:
            cutoff = cutoff.tz_convert("UTC")
        cutoff = cutoff.normalize()
        historical_mode = True

    status = data["status"].astype(str).str.strip().str.casefold()
    finished_mask = status == "finished"

    data["_home_goals"] = pd.to_numeric(
        data["home_goals"], errors="coerce"
    )
    data["_away_goals"] = pd.to_numeric(
        data["away_goals"], errors="coerce"
    )

    score_valid = (
        data["_home_goals"].notna()
        & data["_away_goals"].notna()
        & (data["_home_goals"] >= 0)
        & (data["_away_goals"] >= 0)
        & (data["_home_goals"] % 1 == 0)
        & (data["_away_goals"] % 1 == 0)
    )

    valid_finished = finished_mask & score_valid & data["_date"].notna()

    if historical_mode:
        eligible = valid_finished & (data["_date"] < cutoff)
        same_day_finished = valid_finished & (data["_date"] == cutoff)
        future_finished = valid_finished & (data["_date"] > cutoff)
    else:
        eligible = valid_finished
        same_day_finished = pd.Series(False, index=data.index)
        future_finished = pd.Series(False, index=data.index)

    table = {
        team: {
            "team": team,
            "played": 0,
            "won": 0,
            "drawn": 0,
            "lost": 0,
            "goals_for": 0,
            "goals_against": 0,
            "goal_difference": 0,
            "points": 0,
        }
        for team in teams
    }

    match_columns = [
        "home_team", "away_team", "_home_goals", "_away_goals"
    ]

    for home, away, hg, ag in data.loc[
        eligible, match_columns
    ].itertuples(index=False, name=None):
        home = str(home).strip()
        away = str(away).strip()
        hg = int(hg)
        ag = int(ag)

        if home not in table or away not in table or home == away:
            continue

        h = table[home]
        a = table[away]

        h["played"] += 1
        a["played"] += 1

        h["goals_for"] += hg
        h["goals_against"] += ag
        a["goals_for"] += ag
        a["goals_against"] += hg

        if hg > ag:
            h["won"] += 1
            a["lost"] += 1
            h["points"] += 3
        elif hg < ag:
            a["won"] += 1
            h["lost"] += 1
            a["points"] += 3
        else:
            h["drawn"] += 1
            a["drawn"] += 1
            h["points"] += 1
            a["points"] += 1

    for item in table.values():
        item["goal_difference"] = (
            item["goals_for"] - item["goals_against"]
        )

    rows = sorted(
        table.values(),
        key=lambda x: (
            -x["points"],
            -x["goal_difference"],
            -x["goals_for"],
            x["team"].casefold(),
        ),
    )

    # Flag groups still tied after the generic points/GD/GF criteria.
    tie_groups = {}
    for item in rows:
        key = (
            item["points"],
            item["goal_difference"],
            item["goals_for"],
        )
        tie_groups.setdefault(key, []).append(item["team"])

    unresolved_ties = [
        sorted(group)
        for group in tie_groups.values()
        if len(group) > 1
    ]

    for position, item in enumerate(rows, start=1):
        item["position"] = position

    leader_points = rows[0]["points"] if rows else 0

    for index, item in enumerate(rows):
        item["points_behind_leader"] = leader_points - item["points"]
        if index == 0:
            item["points_to_team_above"] = 0
        else:
            item["points_to_team_above"] = (
                rows[index - 1]["points"] - item["points"]
            )

    scheduled = status == "scheduled"

    scheduled_invalid_dates = scheduled & data["_date"].isna()
    future_scheduled = (
        scheduled & data["_date"].notna() & data["_date"].ge(cutoff)
    )
    past_unfinished = (
        scheduled & data["_date"].notna() & data["_date"].lt(cutoff)
    )

    invalid_finished_dates = finished_mask & data["_date"].isna()
    invalid_finished_scores = (
        finished_mask & data["_date"].notna() & ~score_valid
    )

    future_scheduled_count = int(future_scheduled.sum())
    past_unfinished_count = int(past_unfinished.sum())
    scheduled_invalid_dates_count = int(scheduled_invalid_dates.sum())

    eligible_count = int(eligible.sum())
    total_fixtures = len(data)

    other_status_count = int((~finished_mask & ~scheduled).sum())

    accounted_fixtures = sum([
        eligible_count,
        int(same_day_finished.sum()),
        int(future_finished.sum()),
        int(invalid_finished_scores.sum()),
        int(invalid_finished_dates.sum()),
        future_scheduled_count,
        past_unfinished_count,
        scheduled_invalid_dates_count,
        other_status_count,
    ])

    return {
        "league": league,
        "season": season,
        "as_of": cutoff.strftime("%Y-%m-%d"),
        "historical_mode": historical_mode,
        "team_count": len(teams),
        "table": rows,
        "unresolved_ties": unresolved_ties,
        "audit": {
            "total_fixtures": total_fixtures,
            "valid_completed_matches_in_table": eligible_count,
            "future_scheduled_fixtures": future_scheduled_count,
            "past_unfinished_fixtures": past_unfinished_count,
            "scheduled_with_invalid_dates": scheduled_invalid_dates_count,
            "same_day_completed_excluded": int(same_day_finished.sum()),
            "future_dated_completed_excluded": int(future_finished.sum()),
            "finished_with_invalid_scores": int(
                invalid_finished_scores.sum()
            ),
            "finished_with_invalid_dates": int(
                invalid_finished_dates.sum()
            ),
            "all_rows_with_invalid_dates": invalid_dates,
            "other_status_rows": other_status_count,
            "season_fixtures_not_accounted_for": (
                total_fixtures - accounted_fixtures
            ),
        },
    }


def format_standings(result: dict) -> str:
    """Format a standings result as a readable terminal table."""

    headers = [
        "POS", "TEAM", "P", "W", "D", "L",
        "GF", "GA", "GD", "PTS",
    ]

    records = []
    for item in result["table"]:
        records.append([
            str(item["position"]),
            item["team"],
            str(item["played"]),
            str(item["won"]),
            str(item["drawn"]),
            str(item["lost"]),
            str(item["goals_for"]),
            str(item["goals_against"]),
            f'{item["goal_difference"]:+d}',
            str(item["points"]),
        ])

    widths = [
        max(len(headers[i]), *(len(row[i]) for row in records))
        for i in range(len(headers))
    ]

    def line(row):
        return "  ".join(
            str(value).ljust(widths[i])
            for i, value in enumerate(row)
        )

    output = [
        f'FPS-1 STANDINGS | {result["league"]} | {result["season"]}',
        f'As of: {result["as_of"]} '
        f'({"historical cutoff" if result["historical_mode"] else "current data"})',
        "",
        line(headers),
        "  ".join("-" * width for width in widths),
    ]

    output.extend(line(row) for row in records)

    audit = result["audit"]
    output.extend([
        "",
        "DATA AUDIT",
        f'Completed matches used: {audit["valid_completed_matches_in_table"]}',
        f'Future scheduled fixtures: {audit["future_scheduled_fixtures"]}',
        f'Past unfinished fixtures: {audit["past_unfinished_fixtures"]}',
        f'Same-day completed matches excluded: {audit["same_day_completed_excluded"]}',
        f'Invalid finished scores: {audit["finished_with_invalid_scores"]}',
        f'Invalid finished dates: {audit["finished_with_invalid_dates"]}',
    ])

    if result["unresolved_ties"]:
        output.append(
            "Unresolved ties after points/GD/GF: "
            + "; ".join(", ".join(group) for group in result["unresolved_ties"])
        )
        output.append(
            "Positions within these groups are provisional; "
            "official head-to-head rules are not applied."
        )

    if audit["past_unfinished_fixtures"]:
        output.append(
            "WARNING: Past fixtures remain unfinished in the dataset; "
            "the table may be incomplete."
        )

    return "\n".join(output)
