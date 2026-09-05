#!/usr/bin/env python3
"""Build assets/match.json from StatsBomb Open Data. Standard library only.

Run offline once; the app never calls an API at runtime.
    python3 scripts/fetch-match.py 8658 > android/app/src/main/assets/match.json
"""
import argparse, colorsys, json, sys, urllib.parse, urllib.request

SB = 'https://raw.githubusercontent.com/statsbomb/open-data/master/data'
SPORTSDB = 'https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t='
PITCH = (23, 56, 41)  # mid-tone of the pitch gradient drawn by PitchView
FRENCH = {'Croatia': 'Croatie', 'England': 'Angleterre', 'Belgium': 'Belgique',
          'Spain': 'Espagne', 'Germany': 'Allemagne', 'Brazil': 'Brésil',
          'Argentina': 'Argentine', 'Netherlands': 'Pays-Bas', 'Morocco': 'Maroc'}

# Normalised spots for a side attacking downwards; PitchView mirrors the away team.
SPOT = {
     1: (0.50, 0.055),  2: (0.13, 0.170),  3: (0.30, 0.160),  4: (0.50, 0.160),
     5: (0.70, 0.160),  6: (0.87, 0.170),  7: (0.11, 0.240),  8: (0.89, 0.240),
     9: (0.32, 0.250), 10: (0.50, 0.240), 11: (0.68, 0.250), 12: (0.15, 0.310),
    13: (0.32, 0.300), 14: (0.50, 0.300), 15: (0.68, 0.300), 16: (0.85, 0.310),
    17: (0.17, 0.410), 18: (0.32, 0.360), 19: (0.50, 0.360), 20: (0.68, 0.360),
    21: (0.83, 0.410), 22: (0.38, 0.430), 23: (0.50, 0.430), 24: (0.62, 0.430),
    25: (0.50, 0.375),
}


def get(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def _linear(channel):
    channel /= 255
    return channel/12.92 if channel <= 0.04045 else ((channel+0.055)/1.055)**2.4


def _luminance(rgb):
    return 0.2126*_linear(rgb[0]) + 0.7152*_linear(rgb[1]) + 0.0722*_linear(rgb[2])


def contrast(first, second):
    a, b = _luminance(first), _luminance(second)
    return (max(a, b)+0.05) / (min(a, b)+0.05)


def readable(official, target, saturation):
    """Official hue, lifted until the marker clears `target` against the pitch.

    France's navy sits at 1.02:1 on the pitch and simply disappears. Lifting both
    sides to the same target is no better: equal lightness leaves hue as the only
    difference, which fails at a glance -- so callers pass distinct targets.
    """
    raw = official.lstrip('#')
    rgb = [int(raw[i:i+2], 16)/255 for i in (0, 2, 4)]
    hue = colorsys.rgb_to_hls(*rgb)[0]
    for step in range(101):
        candidate = tuple(round(v*255) for v in colorsys.hls_to_rgb(hue, step/100, saturation))
        if contrast(candidate, PITCH) >= target:
            return '#%02X%02X%02X' % candidate
    return '#FFFFFF'


def team_colour(name):
    rows = get(SPORTSDB + urllib.parse.quote(name)).get('teams') or []
    if not rows:
        return None
    found = [rows[0][key] for key in ('strColour1', 'strColour2', 'strColour3') if rows[0].get(key)]
    return found[0] if found else None


def surname(full, nickname):
    """Surname only: nicknames such as "Samuel Umtiti" are still two words."""
    parts = (nickname or full).split()
    return parts[-1] if len(parts) > 1 else parts[0]


def build(match_id):
    lineups = {t['team_name']: t for t in get(f'{SB}/lineups/{match_id}.json')}
    starts = get(f'{SB}/events/{match_id}.json')[:2]
    if any(e['type']['name'] != 'Starting XI' for e in starts):
        sys.exit(f'Match {match_id}: aucun Starting XI en tête des évènements')

    teams, players = [], []
    for event, side in zip(starts, ('home', 'away')):
        name = event['team']['name']
        official = team_colour(name) or ('#21304D' if side == 'home' else '#ED1C24')
        target, saturation = (4.6, 0.55) if side == 'home' else (9.5, 0.62)
        teams.append({
            'key': side,
            'name': FRENCH.get(name, name),
            'formation': '-'.join(str(event['tactics']['formation'])),
            'colour': readable(official, target, saturation),
            'colourOfficial': official,
        })
        nicknames = {p['player_id']: p.get('player_nickname') for p in lineups[name]['lineup']}
        for slot in event['tactics']['lineup']:
            player_id = slot['player']['id']
            x, y = SPOT[slot['position']['id']]
            players.append({
                'id': f'sb-{player_id}',
                'name': surname(slot['player']['name'], nicknames.get(player_id)),
                'number': slot['jersey_number'],
                'team': side,
                'position': slot['position']['name'],
                'x': x, 'y': y,
            })
    return teams, players


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('match_id', type=int, help='identifiant StatsBomb, par exemple 8658')
    parser.add_argument('--id', default=None, help='identifiant du match dans Fonote')
    parser.add_argument('--competition', default='')
    parser.add_argument('--stage', default='')
    parser.add_argument('--date', default='')
    args = parser.parse_args()
    teams, players = build(args.match_id)
    json.dump({
        'id': args.id or f'sb-{args.match_id}',
        'title': f"{teams[0]['name']} · {teams[1]['name']}",
        'competition': args.competition,
        'stage': args.stage,
        'date': args.date,
        'source': f'StatsBomb Open Data (match {args.match_id})',
        'teams': teams,
        'players': players,
    }, sys.stdout, ensure_ascii=False, indent=2)
    print(file=sys.stdout)
