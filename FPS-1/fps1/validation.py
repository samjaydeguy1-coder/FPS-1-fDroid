from __future__ import annotations

import numpy as np
import pandas as pd

from .model import (
    prepare_prediction_history,
    predict_components_prepared,
    blend_components,
    market_probabilities
)


def multiclass_logloss(y, p):
    y = np.asarray(y, dtype=int)
    p = np.asarray(p, dtype=float)

    if len(y) == 0:
        return float("nan")

    p = np.clip(p, 1e-12, 1.0)
    p = p / p.sum(axis=1, keepdims=True)

    return float(-np.mean([
        np.log(p[i, yi])
        for i, yi in enumerate(y)
    ]))


def multiclass_brier(y, p, classes=3):
    y = np.asarray(y, dtype=int)
    p = np.asarray(p, dtype=float)

    if len(y) == 0:
        return float("nan")

    oh = np.eye(classes)[y]

    return float(np.mean(
        np.sum((p - oh) ** 2, axis=1)
    ))


def evaluate_walk_forward(
    df,
    weights,
    max_goals=10,
    min_train=80,
    decay_days=730,
    recent_n=8,
    elo_k=20,
    elo_home_adv=60,
    step=1
):
    """
    Date-grouped chronological expanding-window evaluation.

    Every fixture on a given date is evaluated using only
    finished matches from strictly earlier dates.

    This prevents same-day temporal leakage.

    The historical-frequency baseline uses Laplace smoothing.
    Prediction failures are counted and reported rather than
    silently discarded.
    """

    all_rows = []
    errors = {}

    if df.empty:
        return pd.DataFrame(), {
            "matches": 0,
            "skipped_predictions": 0,
            "error_reasons": {}
        }

    required = [
        "date", "league", "status",
        "home_team", "away_team",
        "home_goals", "away_goals"
    ]

    missing = [c for c in required if c not in df.columns]

    if missing:
        raise ValueError(
            f"Missing required columns: {missing}"
        )

    finished = df[
        df["status"].astype(str).str.lower() == "finished"
    ].copy()

    finished["_validation_date"] = pd.to_datetime(
        finished["date"],
        utc=True,
        errors="coerce"
    ).dt.floor("D")

    finished["home_goals"] = pd.to_numeric(
        finished["home_goals"],
        errors="coerce"
    )

    finished["away_goals"] = pd.to_numeric(
        finished["away_goals"],
        errors="coerce"
    )

    finished = finished.dropna(
        subset=[
            "_validation_date",
            "league",
            "home_team",
            "away_team",
            "home_goals",
            "away_goals"
        ]
    )

    finished = finished.sort_values(
        ["league", "_validation_date"]
    ).reset_index(drop=True)

    eligible_dates = 0
    evaluated_dates = 0

    for league, group in finished.groupby("league"):

        g = group.sort_values(
            "_validation_date"
        ).reset_index(drop=True)

        dates = sorted(
            g["_validation_date"].unique()
        )

        # Each test date is evaluated as one group.
        # All matches on that date share the same history.
        for date_index, test_date in enumerate(dates):

            if date_index % max(int(step), 1) != 0:
                continue

            hist = g[
                g["_validation_date"] < test_date
            ].copy()

            test_group = g[
                g["_validation_date"] == test_date
            ]

            if len(hist) < min_train:
                continue

            eligible_dates += 1
            evaluated_dates += 1

            # Historical outcomes:
            # 0 = Home, 1 = Draw, 2 = Away
            hist_actual = np.where(
                hist["home_goals"] > hist["away_goals"],
                0,
                np.where(
                    hist["home_goals"] == hist["away_goals"],
                    1,
                    2
                )
            )

            counts = np.bincount(
                hist_actual,
                minlength=3
            )

            baseline_p = (
                counts + 1.0
            ) / (
                counts.sum() + 3.0
            )

            try:
                prepared = prepare_prediction_history(
                    hist,
                    test_date,
                    decay_days,
                    recent_n,
                    elo_k,
                    elo_home_adv
                )
            except Exception as exc:
                reason = f"{type(exc).__name__}: {str(exc)}"
                errors[reason] = (
                    errors.get(reason, 0) + len(test_group)
                )
                continue

            for _, row in test_group.iterrows():

                try:
                    comp = predict_components_prepared(
                        prepared,
                        row["home_team"],
                        row["away_team"],
                        max_goals
                    )

                    mat = blend_components(
                        comp,
                        weights
                    )

                    probs = market_probabilities(
                        mat
                    )["1X2"]

                    p = np.asarray([
                        probs["home"],
                        probs["draw"],
                        probs["away"]
                    ], dtype=float)

                    if (
                        not np.all(np.isfinite(p))
                        or np.any(p < 0)
                        or p.sum() <= 0
                    ):
                        raise ValueError(
                            "Invalid 1X2 probability values"
                        )

                    p = p / p.sum()

                    actual = (
                        0 if row["home_goals"] > row["away_goals"]
                        else 1 if row["home_goals"] == row["away_goals"]
                        else 2
                    )

                    one_hot = np.eye(3)[actual]

                    all_rows.append({
                        "league": league,
                        "date": row["date"],
                        "home_team": row["home_team"],
                        "away_team": row["away_team"],
                        "actual": actual,

                        "p_home": p[0],
                        "p_draw": p[1],
                        "p_away": p[2],

                        "baseline_p_home": baseline_p[0],
                        "baseline_p_draw": baseline_p[1],
                        "baseline_p_away": baseline_p[2],

                        "logloss": -np.log(
                            max(p[actual], 1e-12)
                        ),

                        "brier": float(
                            np.sum((one_hot - p) ** 2)
                        ),

                        "baseline_logloss": -np.log(
                            max(baseline_p[actual], 1e-12)
                        ),

                        "baseline_brier": float(
                            np.sum(
                                (one_hot - baseline_p) ** 2
                            )
                        )
                    })

                except Exception as exc:
                    reason = (
                        f"{type(exc).__name__}: {str(exc)}"
                    )

                    errors[reason] = (
                        errors.get(reason, 0) + 1
                    )

    out = pd.DataFrame(all_rows)

    diagnostics = {
        "skipped_predictions": int(sum(errors.values())),
        "error_reasons": errors,
        "eligible_dates": int(eligible_dates),
        "evaluated_dates": int(evaluated_dates)
    }

    if out.empty:
        return out, {
            "matches": 0,
            **diagnostics
        }

    y = out["actual"].to_numpy(dtype=int)

    p = out[
        ["p_home", "p_draw", "p_away"]
    ].to_numpy(dtype=float)

    bp = out[
        [
            "baseline_p_home",
            "baseline_p_draw",
            "baseline_p_away"
        ]
    ].to_numpy(dtype=float)

    metrics = {
        "matches": int(len(out)),

        "log_loss": multiclass_logloss(y, p),
        "brier_score": multiclass_brier(y, p),

        "accuracy": float(
            (p.argmax(axis=1) == y).mean()
        ),

        "baseline_log_loss": multiclass_logloss(y, bp),
        "baseline_brier_score": multiclass_brier(y, bp),

        "baseline_accuracy": float(
            (bp.argmax(axis=1) == y).mean()
        ),

        **diagnostics
    }

    return out, metrics


def calibration_table(predictions, bins=10):
    if predictions.empty:
        return pd.DataFrame()

    rows = []

    for label, col, actual_code in [
        ("Home", "p_home", 0),
        ("Draw", "p_draw", 1),
        ("Away", "p_away", 2)
    ]:
        d = predictions[[col, "actual"]].copy()

        d["bin"] = pd.cut(
            d[col],
            bins=np.linspace(0, 1, bins + 1),
            include_lowest=True
        )

        for interval, g in d.groupby(
            "bin",
            observed=True
        ):
            if len(g):
                rows.append({
                    "outcome": label,
                    "probability_bin": str(interval),
                    "mean_predicted": g[col].mean(),
                    "observed_frequency": (
                        g.actual == actual_code
                    ).mean(),
                    "count": len(g)
                })

    return pd.DataFrame(rows)
