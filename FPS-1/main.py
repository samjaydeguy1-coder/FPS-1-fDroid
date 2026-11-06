from __future__ import annotations
import argparse, json
from pathlib import Path
import pandas as pd
from fps1.data import fetch_openfootball, save_data, load_data
from fps1.model import predict_components, blend_components, market_probabilities
from fps1.validation import evaluate_walk_forward, calibration_table

ROOT=Path(__file__).resolve().parent
def read_config():
    return json.loads((ROOT/"config.json").read_text(encoding="utf-8"))

def cmd_update(args):
    cfg=read_config()
    seasons=args.seasons.split(",") if args.seasons else cfg["seasons"]
    df=fetch_openfootball(cfg,seasons,ROOT/"data"/"raw")
    save_data(df,ROOT/"data"/"matches.csv")
    finished=int((df.status=="finished").sum())
    upcoming=int((df.status=="scheduled").sum())
    print(f"Saved {len(df)} fixtures: {finished} completed, {upcoming} scheduled.")
    print("By league:")
    print(df.groupby("league").status.value_counts().unstack(fill_value=0).to_string())

def cmd_predict(args):
    cfg=read_config(); df=load_data(ROOT/"data"/"matches.csv")
    league=args.league
    hist=df[(df.league==league)&(df.status=="finished")].copy()
    if hist.empty: raise SystemExit(f"No completed data for {league}. Run: python main.py update")
    comp=predict_components(hist,args.home,args.away,pd.Timestamp.now(tz="UTC"),
        cfg["max_goals"],cfg["time_decay_days"],cfg["recent_form_matches"],
        cfg["elo_k"],cfg["elo_home_advantage"])
    mat=blend_components(comp,cfg["ensemble_weights"])
    out=market_probabilities(mat)
    print(f"\nFPS-1 | {league} | {args.home} vs {args.away}")
    print(f"Expected goals: home {comp.lambda_home:.2f} | away {comp.lambda_away:.2f}")
    print(f"Ensemble weights: {cfg['ensemble_weights']}")
    print("\n1X2")
    for k,v in out["1X2"].items(): print(f"  {k}: {v:.1%}")
    print("\nDouble chance")
    for k,v in out["double_chance"].items(): print(f"  {k}: {v:.1%}")
    print("\nDraw no bet (conditional on no draw)")
    print(f"  Home: {out['draw_no_bet']['home_conditional']:.1%} | Away: {out['draw_no_bet']['away_conditional']:.1%} | Draw push: {out['draw_no_bet']['push_probability']:.1%}")
    print("\nBTTS")
    for k,v in out["btts"].items(): print(f"  {k}: {v:.1%}")
    print("\nGoal totals")
    for k,v in out["totals"].items(): print(f"  {k}: {v:.1%}")
    print("\nTop correct scores")
    for x in out["correct_score_top"]: print(f"  {x['score']}: {x['probability']:.1%}")
    print("\nNOT BETTING ADVICE. Probabilities are model estimates, not guarantees.")

def cmd_validate(args):
    cfg=read_config(); df=load_data(ROOT/"data"/"matches.csv")
    preds,metrics=evaluate_walk_forward(df,cfg["ensemble_weights"],cfg["max_goals"],
        args.min_train or cfg["min_train_matches"],cfg["time_decay_days"],
        cfg["recent_form_matches"],cfg["elo_k"],cfg["elo_home_advantage"],args.step)
    if preds.empty: raise SystemExit("No validation predictions. Need more historical completed matches or lower --min-train.")
    reports=ROOT/"reports"; reports.mkdir(exist_ok=True)
    preds.to_csv(reports/"walk_forward_predictions.csv",index=False)
    calibration_table(preds).to_csv(reports/"calibration.csv",index=False)
    (reports/"metrics.json").write_text(json.dumps(metrics,indent=2),encoding="utf-8")
    print("Walk-forward validation (chronological, no future matches used):")
    print(json.dumps(metrics,indent=2))
    print(f"Saved reports to {reports}")

def main():
    p=argparse.ArgumentParser(description="FPS-1 football prediction system")
    sub=p.add_subparsers(dest="command",required=True)
    u=sub.add_parser("update",help="download free match data")
    u.add_argument("--seasons",help="comma-separated seasons, e.g. 2022-23,2023-24")
    u.set_defaults(func=cmd_update)
    q=sub.add_parser("predict",help="predict a fixture")
    q.add_argument("--league",required=True,choices=["EPL","LaLiga","SerieA","Bundesliga","Ligue1"])
    q.add_argument("--home",required=True); q.add_argument("--away",required=True)
    q.set_defaults(func=cmd_predict)
    v=sub.add_parser("validate",help="run chronological walk-forward validation")
    v.add_argument("--min-train",type=int); v.add_argument("--step",type=int,default=1)
    v.set_defaults(func=cmd_validate)
    a=p.parse_args(); a.func(a)
if __name__=="__main__": main()
