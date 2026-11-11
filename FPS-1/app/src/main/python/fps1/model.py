from __future__ import annotations
from dataclasses import dataclass
from collections import defaultdict, deque
from datetime import timezone
import math
import numpy as np
import pandas as pd

EPS = 1e-9

def poisson_pmf(k: int, lam: float) -> float:
    return math.exp(-lam) * lam**k / math.factorial(k)

def poisson_vector(lam: float, max_goals: int) -> np.ndarray:
    p = np.array([poisson_pmf(k, lam) for k in range(max_goals + 1)], dtype=float)
    # Put omitted upper-tail mass into the last bucket, preserving total probability.
    p[-1] += max(0.0, 1.0 - p.sum())
    return p / p.sum()

def score_matrix(lh: float, la: float, max_goals: int) -> np.ndarray:
    return np.outer(poisson_vector(lh, max_goals), poisson_vector(la, max_goals))

def dc_correct(matrix: np.ndarray, lh: float, la: float, rho: float = -0.08) -> np.ndarray:
    """Dixon-Coles low-score correction for 0-0, 0-1, 1-0, 1-1."""
    m = matrix.copy()
    corrections = {
        (0, 0): 1 - lh * la * rho,
        (0, 1): 1 + lh * rho,
        (1, 0): 1 + la * rho,
        (1, 1): 1 - rho,
    }
    for (i, j), tau in corrections.items():
        if tau > 0:
            m[i, j] *= tau
    return m / m.sum()

def _team_strengths(history: pd.DataFrame, as_of: pd.Timestamp, decay_days: float = 730.0):
    """Regularised log-goal model: league mean + home advantage + attack/defence effects."""
    h = history.dropna(subset=["home_goals", "away_goals"]).copy()
    if h.empty:
        return None
    age = (as_of - h["date"]).dt.total_seconds().clip(lower=0) / 86400
    h["w"] = np.exp(-np.log(2) * age / max(decay_days, 1))
    teams = sorted(set(h.home_team) | set(h.away_team))
    idx = {t:i for i,t in enumerate(teams)}
    n = len(teams)
    # parameters: intercept, home advantage, home attack[n], away attack[n]
    # Sum-to-zero constraints are approximated by ridge regularisation.
    Xh, ya, wa = [], [], []
    Xa, yh, ww = [], [], []
    for r in h.itertuples():
        rowh = np.zeros(2 + 2*n); rowh[0] = 1; rowh[1] = 1
        rowh[2 + idx[r.home_team]] = 1
        rowh[2 + n + idx[r.away_team]] = -1
        rowa = np.zeros(2 + 2*n); rowa[0] = 1
        rowa[2 + idx[r.away_team]] = 1
        rowa[2 + n + idx[r.home_team]] = -1
        Xh.append(rowh); ya.append(np.log(float(r.home_goals) + 0.15)); wa.append(r.w)
        Xa.append(rowa); yh.append(np.log(float(r.away_goals) + 0.15)); ww.append(r.w)
    X = np.vstack([Xh, Xa]); y = np.array(ya + yh)
    w = np.array(wa + ww)
    penalty = np.eye(X.shape[1]) * 0.8
    penalty[0,0] = 0.01; penalty[1,1] = 0.05
    beta = np.linalg.solve(X.T @ (w[:,None] * X) + penalty, X.T @ (w*y))
    return {"teams": idx, "beta": beta, "n": n}

def _predict_rates(fit, home: str, away: str):
    idx, b, n = fit["teams"], fit["beta"], fit["n"]
    if home not in idx or away not in idx:
        # unseen teams receive neutral attack/defence effects
        ah = da = aa = dh = 0.0
    else:
        ah = b[2 + idx[home]]; da = b[2+n+idx[away]]
        aa = b[2 + idx[away]]; dh = b[2+n+idx[home]]
    lh = float(np.exp(b[0] + b[1] + ah - da))
    la = float(np.exp(b[0] + aa - dh))
    return float(np.clip(lh, 0.15, 4.5)), float(np.clip(la, 0.15, 4.5))

def _elo_ratings(history: pd.DataFrame, k=20.0, home_adv=60.0):
    ratings = defaultdict(lambda: 1500.0)
    for r in history.sort_values("date").itertuples():
        if pd.isna(r.home_goals) or pd.isna(r.away_goals): continue
        rh, ra = ratings[r.home_team], ratings[r.away_team]
        exp_h = 1 / (1 + 10 ** (-((rh + home_adv) - ra) / 400))
        actual = 1.0 if r.home_goals > r.away_goals else (0.5 if r.home_goals == r.away_goals else 0.0)
        margin = abs(r.home_goals-r.away_goals)
        mult = math.log1p(margin) * 2.2 / (1 + 0.001 * abs(rh-ra)) if margin else 1.0
        change = k * mult * (actual-exp_h)
        ratings[r.home_team] += change
        ratings[r.away_team] -= change
    return ratings

def _recent_form(history: pd.DataFrame, home: str, away: str, n=8):
    def stats(team):
        rows = history[((history.home_team == team) | (history.away_team == team))].tail(n)
        gf, ga, count = 0.0, 0.0, 0
        for r in rows.itertuples():
            if pd.isna(r.home_goals) or pd.isna(r.away_goals): continue
            if r.home_team == team: gf += r.home_goals; ga += r.away_goals
            else: gf += r.away_goals; ga += r.home_goals
            count += 1
        return (gf/max(count,1), ga/max(count,1), count)
    return stats(home), stats(away)

@dataclass
class MatchModel:
    poisson: np.ndarray
    dixon_coles: np.ndarray
    elo: np.ndarray
    form: np.ndarray
    lambda_home: float
    lambda_away: float
    elo_home: float
    elo_away: float

def _eligible_history(history: pd.DataFrame, as_of: pd.Timestamp):
    """Return completed, valid results strictly before the prediction cutoff."""
    h = history.copy()

    dates = pd.to_datetime(h["date"], utc=True, errors="coerce")
    home_goals = pd.to_numeric(h["home_goals"], errors="coerce")
    away_goals = pd.to_numeric(h["away_goals"], errors="coerce")

    valid = (
        dates.notna()
        & np.isfinite(home_goals)
        & np.isfinite(away_goals)
        & (home_goals >= 0)
        & (away_goals >= 0)
        & (dates < as_of)
    )

    if "status" in h.columns:
        valid &= h["status"].astype(str).str.casefold().eq("finished")

    h = h.loc[valid].copy()
    h["date"] = dates.loc[valid]
    h["home_goals"] = home_goals.loc[valid]
    h["away_goals"] = away_goals.loc[valid]

    return h.sort_values("date").copy()


def predict_components(history: pd.DataFrame, home: str, away: str, as_of=None,
                       max_goals=10, decay_days=730, recent_n=8, elo_k=20, elo_home_adv=60):
    if as_of is None:
        as_of = pd.Timestamp.now(tz="UTC")
    as_of = pd.to_datetime(as_of, utc=True)
    h = _eligible_history(history, as_of)
    fit = _team_strengths(h, as_of, decay_days)
    if fit is None: raise ValueError("Not enough completed history to fit model.")
    lh, la = _predict_rates(fit, home, away)
    base = score_matrix(lh, la, max_goals)
    dc = dc_correct(base, lh, la)
    ratings = _elo_ratings(h, elo_k, elo_home_adv)
    rh, ra = ratings[home], ratings[away]
    p_home = 1/(1+10**(-((rh+elo_home_adv)-ra)/400))
    # Map Elo edge to a modest goal-rate multiplier; not a direct win probability matrix.
    elo_factor = float(np.clip((p_home/0.5)**0.55, 0.72, 1.38))
    elo_mat = score_matrix(lh*elo_factor, la/elo_factor, max_goals)
    (hgf,hga,_), (agf,aga,_) = _recent_form(h, home, away, recent_n)
    league_h = float(h.home_goals.mean()); league_a = float(h.away_goals.mean())
    # Shrink short form strongly toward long-run model rates.
    form_lh = 0.70*lh + 0.30*np.clip(lh*(0.5 + 0.5*hgf/max(league_h,0.2))*(0.5 + 0.5*aga/max(league_a,0.2)),0.15,4.5)
    form_la = 0.70*la + 0.30*np.clip(la*(0.5 + 0.5*agf/max(league_a,0.2))*(0.5 + 0.5*hga/max(league_h,0.2)),0.15,4.5)
    form_mat = score_matrix(form_lh, form_la, max_goals)
    return MatchModel(base, dc, elo_mat, form_mat, lh, la, float(rh), float(ra))

def blend_components(comp: MatchModel, weights: dict) -> np.ndarray:
    mats = {"poisson":comp.poisson, "dixon_coles":comp.dixon_coles, "elo":comp.elo, "form":comp.form}
    total = sum(float(weights.get(k,0)) for k in mats)
    if total <= 0: raise ValueError("At least one ensemble weight must be positive.")
    out = sum((float(weights.get(k,0))/total)*mats[k] for k in mats)
    return out/out.sum()

def market_probabilities(m: np.ndarray) -> dict:
    n = m.shape[0]-1
    home = float(np.tril(m, -1).sum()) # row index > column index
    draw = float(np.trace(m))
    away = float(np.triu(m, 1).sum())
    btts_yes = float(m[1:,1:].sum())
    total_goals = np.add.outer(np.arange(n+1), np.arange(n+1))
    over_under = {}
    for line in (0.5,1.5,2.5,3.5,4.5,5.5):
        over_under[f"over_{line}"] = float(m[total_goals > line].sum())
        over_under[f"under_{line}"] = float(m[total_goals < line].sum())
    score_order = sorted(((float(m[i,j]),i,j) for i in range(n+1) for j in range(n+1)), reverse=True)
    top_scores = [{"score":f"{i}-{j}","probability":p} for p,i,j in score_order[:8]]
    return {
        "1X2": {"home":home,"draw":draw,"away":away},
        "double_chance": {"1X":home+draw,"12":home+away,"X2":draw+away},
        "draw_no_bet": {
            "home_conditional": home/(home+away) if home+away else 0.5,
            "away_conditional": away/(home+away) if home+away else 0.5,
            "push_probability": draw
        },
        "btts": {"yes":btts_yes,"no":1-btts_yes},
        "totals": over_under,
        "correct_score_top": top_scores,
        "most_likely_result": max([("home",home),("draw",draw),("away",away)], key=lambda x:x[1])[0],
        "matrix": m.tolist()
    }


def prepare_prediction_history(
    history,
    as_of,
    decay_days=730,
    recent_n=8,
    elo_k=20,
    elo_home_adv=60
):
    """
    Prepare historical calculations once for multiple fixtures
    sharing the same training history.
    """
    as_of = pd.to_datetime(as_of, utc=True)
    h = _eligible_history(history, as_of)

    fit = _team_strengths(h, as_of, decay_days)

    if fit is None:
        raise ValueError(
            "Not enough completed history to fit model."
        )

    ratings = _elo_ratings(h, elo_k, elo_home_adv)

    return {
        "history": h,
        "fit": fit,
        "ratings": ratings,
        "recent_n": recent_n,
        "elo_home_adv": elo_home_adv,
        "league_home": float(h.home_goals.mean()),
        "league_away": float(h.away_goals.mean()),
        "form_cache": {}
    }


def predict_components_prepared(
    prepared,
    home,
    away,
    max_goals=10
):
    """
    Generate prediction components using prepared history.
    Recent-form statistics are cached per team.
    """
    h = prepared["history"]
    fit = prepared["fit"]
    ratings = prepared["ratings"]
    recent_n = prepared["recent_n"]
    elo_home_adv = prepared["elo_home_adv"]

    lh, la = _predict_rates(fit, home, away)

    base = score_matrix(lh, la, max_goals)
    dc = dc_correct(base, lh, la)

    rh = ratings[home]
    ra = ratings[away]

    p_home = 1 / (
        1 + 10 ** (-((rh + elo_home_adv) - ra) / 400)
    )

    elo_factor = float(np.clip(
        (p_home / 0.5) ** 0.55,
        0.72,
        1.38
    ))

    elo_mat = score_matrix(
        lh * elo_factor,
        la / elo_factor,
        max_goals
    )

    cache = prepared["form_cache"]

    if home not in cache:
        cache[home] = _recent_form(
            h, home, home, recent_n
        )[0]

    if away not in cache:
        cache[away] = _recent_form(
            h, away, away, recent_n
        )[0]

    hgf, hga, _ = cache[home]
    agf, aga, _ = cache[away]

    league_h = prepared["league_home"]
    league_a = prepared["league_away"]

    form_lh = (
        0.70 * lh
        + 0.30 * np.clip(
            lh
            * (0.5 + 0.5 * hgf / max(league_h, 0.2))
            * (0.5 + 0.5 * aga / max(league_a, 0.2)),
            0.15,
            4.5
        )
    )

    form_la = (
        0.70 * la
        + 0.30 * np.clip(
            la
            * (0.5 + 0.5 * agf / max(league_a, 0.2))
            * (0.5 + 0.5 * hga / max(league_h, 0.2)),
            0.15,
            4.5
        )
    )

    form_mat = score_matrix(
        form_lh,
        form_la,
        max_goals
    )

    return MatchModel(
        base,
        dc,
        elo_mat,
        form_mat,
        lh,
        la,
        float(rh),
        float(ra)
    )
