"""Download the ESPN payloads used by the Android offline fixtures.

The output is the same enriched match shape returned by backend.espn.Espn.enrich,
but built from the public ESPN summary because no provider credential belongs in the APK.
"""
import json
import sys
from pathlib import Path
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from backend.espn import clock_marks, crest, ground, lineup, bench, team_colours, timeline

EVENTS = {"2026-09-04": "401876469", "2026-09-12": "401876458"}


def get(event_id):
    url = "https://site.api.espn.com/apis/site/v2/sports/soccer/fra.1/summary?event=" + event_id
    with urlopen(url, timeout=15) as response:
        return json.load(response)


def fixture(data, date):
    competition = data["header"]["competitions"][0]
    competitors = {c["homeAway"]: c for c in competition["competitors"]}
    teams = {side: dict(competitors[side]["team"]) for side in ("home", "away")}
    rosters = {r.get("homeAway"): r for r in data.get("rosters", [])}
    converted = {"id": int(competition["id"]), "utcDate": competition["date"],
                 "competition": {"name": "Ligue 1", "code": "FL1"},
                 "stage": "J3" if date == "2026-09-04" else "J4",
                 "status": "FINISHED" if competition.get("isFinal") else "SCHEDULED"}
    for side in ("home", "away"):
        team = teams[side]
        roster = rosters.get(side, {})
        players = lineup(roster)
        taken = {p["fonote_id"] for p in players}
        converted[side + "Team"] = {
            "name": team.get("displayName") or team.get("name"),
            "abbreviation": team.get("abbreviation", ""),
            "color": team.get("color", ""),
            "alternateColor": team.get("alternateColor", ""),
            "logos": team.get("logos", []), "lineup": players,
            "bench": bench(roster, taken), "formation": roster.get("formation", ""),
        }
    colours = team_colours(converted["homeTeam"], converted["awayTeam"])
    sides = {str(teams[s].get("id")): s for s in ("home", "away")}
    converted["homeTeam"]["colour"], converted["awayTeam"]["colour"] = colours
    for side in ("home", "away"):
        converted[side + "Team"]["logo"] = crest(converted[side + "Team"])
        logo = converted[side + "Team"]["logo"]
        if logo:
            filename = side + "-" + str(teams[side].get("id")) + ".png"
            with urlopen(logo, timeout=15) as response:
                (Path("android/app/src/main/assets/logos")).mkdir(parents=True, exist_ok=True)
                (Path("android/app/src/main/assets/logos") / filename).write_bytes(response.read())
            converted[side + "Team"]["logo"] = "asset://logos/" + filename
    converted["timeline"] = timeline(data, sides)
    converted["clock"] = clock_marks(converted["timeline"])
    converted["team_stats"] = {}
    converted["ground"] = ground(data)
    converted["lineup_status"] = "available" if all(len(converted[s+"Team"]["lineup"]) == 11 for s in ("home", "away")) else "unavailable"
    converted["lineup_source"] = "ESPN"
    converted["espn_event_id"] = str(competition["id"])
    converted["score"] = {"fullTime": {s: competitors[s].get("score", "") for s in ("home", "away")}}
    return converted


out = [fixture(get(event), date) for date, event in EVENTS.items()]
Path("android/app/src/main/assets/demo-matches.json").write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
print("wrote demo-matches.json")
