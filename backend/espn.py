"""Optional public ESPN feed adapter for the personal Fonote server."""
import copy
import json
import re
import threading
import time
import unicodedata
from datetime import datetime, timedelta
from urllib.parse import urlencode
from urllib.request import urlopen

LEAGUES = {'FL1': 'fra.1', 'PL': 'eng.1', 'BL1': 'ger.1', 'PD': 'esp.1',
           'SA': 'ita.1', 'PPL': 'por.1', 'DED': 'ned.1', 'ELC': 'eng.2',
           'BSA': 'bra.1', 'CL': 'uefa.champions', 'WC': 'fifa.world', 'EC': 'uefa.euro'}
ALIASES = {'olympique lyonnais': 'lyon', 'olympique lyon': 'lyon',
           'racing club de lens': 'lens', 'paris saint germain': 'psg',
           'olympique de marseille': 'marseille', 'olympique marseille': 'marseille',
           'stade brestois 29': 'brest', 'stade rennais': 'rennes',
           'bayern munchen': 'bayern munich', 'internazionale milano': 'internazionale',
           'inter milan': 'internazionale', 'sporting clube de portugal': 'sporting cp'}


# ESPN declares its own feed stale after nine seconds, so a match in play is re-read often
# enough for a substitution to show up within the minute, and left alone the rest of the time.
# The live window must stay well under the client's polling interval: a cache as long as the
# poll would answer every other refresh with the copy the client already has.
CACHE = 300
LIVE_CACHE = 30
LIVE = {'IN_PLAY', 'PAUSED'}
MEMO = 128


# The pitch draws a dark shirt number on every marker, so a club colour is lightened until
# that number stays readable; match.json follows the same rule with its pastel `colour`.
INK = (15, 35, 33)
MIN_CONTRAST = 4.5
MIN_DISTANCE = 60
DEFAULT_COLOURS = ('#7D9CD9', '#F6DADB')


def parse_colour(value):
    text = str(value or '').lstrip('#')
    if not re.fullmatch(r'[0-9a-fA-F]{6}', text):
        return None
    return tuple(int(text[i:i + 2], 16) for i in (0, 2, 4))


def hex_colour(colour):
    return '#%02X%02X%02X' % colour


def luminance(colour):
    parts = []
    for value in colour:
        value /= 255
        parts.append(value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4)
    return 0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2]


def contrast(colour, other):
    low, high = sorted((luminance(colour), luminance(other)))
    return (high + 0.05) / (low + 0.05)


def readable(colour):
    """Mix towards white by the smallest amount that keeps the shirt number legible."""
    for step in range(21):
        mixed = tuple(round(c + (255 - c) * step / 20) for c in colour)
        if contrast(mixed, INK) >= MIN_CONTRAST:
            return mixed
    return (255, 255, 255)


def distance(left, right):
    return sum((a - b) ** 2 for a, b in zip(left, right)) ** 0.5


def team_colours(home, away):
    """Real club colours; the away side falls back to its alternate rather than look like the host."""
    first = readable(parse_colour(home.get('color')) or parse_colour(DEFAULT_COLOURS[0]))
    for candidate in (away.get('color'), away.get('alternateColor')):
        other = parse_colour(candidate)
        if other and distance(first, readable(other)) >= MIN_DISTANCE:
            return hex_colour(first), hex_colour(readable(other))
    apart = max((parse_colour(c) for c in DEFAULT_COLOURS), key=lambda c: distance(first, c))
    return hex_colour(first), hex_colour(apart)


def crest(team):
    """The badge ESPN publishes, preferring the one it drew for a dark background.

    The two are usually the same file — most clubs need no second version — but when they
    differ, the note is drawn on grass at night and the wrong one arrives as a dark shape on a
    dark chip.
    """
    logos = [logo for logo in team.get('logos') or [] if isinstance(logo, dict) and logo.get('href')]
    dark = [logo for logo in logos if 'dark' in (logo.get('rel') or [])]
    return ((dark or logos) + [{}])[0].get('href', '')


def name_key(name):
    value = unicodedata.normalize('NFKD', name or '')
    value = ''.join(c for c in value if not unicodedata.combining(c)).lower()
    value = re.sub(r'[^a-z0-9]+', ' ', value).strip()
    value = ' '.join(w for w in value.split() if w not in {'fc', 'afc', 'ac', 'as', 'rc', 'aj', 'ogc'})
    return ALIASES.get(value, value)


def same_team(left, right):
    a = {name_key(left.get(k)) for k in ('name', 'shortName', 'tla')} - {''}
    b = {name_key(right.get(k)) for k in ('displayName', 'shortDisplayName', 'name', 'abbreviation')} - {''}
    return bool(a & b)


def instant(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def select_event(match, events):
    """Never guess from kickoff alone or accept an ambiguous pair of teams."""
    candidates = []
    for event in events:
        for contest in event.get('competitions', []):
            sides = {c.get('homeAway'): c.get('team', {}) for c in contest.get('competitors', [])}
            if (same_team(match['homeTeam'], sides.get('home', {}))
                    and same_team(match['awayTeam'], sides.get('away', {}))
                    and abs((instant(event['date']) - instant(match['utcDate'])).total_seconds()) <= 7200):
                candidates.append(event)
    return candidates[0] if len(candidates) == 1 else None


def lineup(roster):
    players = []
    for player in roster.get('roster', []):
        if player.get('starter') is not True:
            continue
        athlete = player.get('athlete', {})
        identifier = str(athlete.get('id', ''))
        if not identifier.isdigit() or not athlete.get('displayName'):
            return []
        players.append({'id': int(identifier), 'fonote_id': 'espn-' + identifier,
                        'name': athlete['displayName'], 'shirtNumber': player.get('jersey'),
                        'position': player.get('position', {}).get('displayName', ''),
                        'formationPlace': player.get('formationPlace'),
                        'stats': counted(player)})
    if len(players) != 11 or len({p['id'] for p in players}) != 11:
        return []
    # ESPN's formation place is the classic shirt convention (2 right back, 9 striker, 11 winger),
    # not a back-to-front order, so it settles the order of this list and nothing else: the pitch
    # places each player from his `position` text. A partial field leaves the roster order alone.
    places = [str(p['formationPlace']) for p in players if p['formationPlace'] is not None]
    if len(places) == 11 and {p for p in places if p.isdigit()} == {str(n) for n in range(1, 12)}:
        players.sort(key=lambda p: int(p['formationPlace']))
    return players


MINUTE = re.compile(r"(\d+)'(?:\s*\+\s*(\d+)')?")
# Which run-of-play marks the app needs to stop guessing the clock from the scheduled kickoff.
MARKS = {'kickoff': 'kickoff', 'halftime': 'halftime',
         'start-2nd-half': 'second_half', 'end-regular-time': 'end'}


def minute(event):
    """The minute as shown, added time included: ESPN clamps `clock.value` at 45 and 90."""
    shown = (event.get('clock') or {}).get('displayValue') or ''
    found = MINUTE.search(shown)
    if found:
        return int(found.group(1)) + int(found.group(2) or 0)
    value = (event.get('clock') or {}).get('value')
    return int(value // 60) if isinstance(value, (int, float)) else None


def counted(entry):
    """What the provider counted, by its own key. Names and order are the client's business."""
    return {stat['name']: stat.get('displayValue', '')
            for stat in entry.get('stats') or [] if stat.get('name')}


def team_stats(data, sides):
    """The provider's own count of the match, per side."""
    counts = {}
    for team in (data.get('boxscore') or {}).get('teams', []):
        side = sides.get(str((team.get('team') or {}).get('id') or ''))
        if side:
            counts[side] = {stat['name']: stat.get('displayValue', '')
                            for stat in team.get('statistics') or [] if stat.get('name')}
    return counts


def ground(data):
    """Where it was played, before whom, and who refereed it."""
    info = data.get('gameInfo') or {}
    named = [o.get('displayName') for o in info.get('officials') or [] if o.get('displayName')]
    return {'venue': (info.get('venue') or {}).get('fullName') or '',
            'attendance': info.get('attendance'),
            'referee': named[0] if named else ''}


def bench(roster, taken):
    """Named substitutes, so a player coming on already has a name and a number to draw.

    Unlike the eleven, a broken entry here is skipped rather than fatal: the pitch is drawn
    from the starters, and a missing substitute costs one late marker, not the composition.
    """
    players = []
    for player in roster.get('roster', []):
        if player.get('starter') is True:
            continue
        athlete = player.get('athlete', {})
        identifier = str(athlete.get('id', ''))
        if not identifier.isdigit() or not athlete.get('displayName'):
            continue
        fonote_id = 'espn-' + identifier
        if fonote_id in taken:
            continue
        taken.add(fonote_id)
        players.append({'id': int(identifier), 'fonote_id': fonote_id,
                        'name': athlete['displayName'], 'shirtNumber': player.get('jersey'),
                        'position': player.get('position', {}).get('displayName', ''),
                        'stats': counted(player)})
    return players


def timeline(data, sides):
    """What happened, in order, with players named by their Fonote id.

    ESPN lists both actors of an event in the same order whatever the kind: the scorer then
    who assisted, the player coming on then the one going off. That order is the whole meaning
    of `players`, so it is kept as published rather than sorted.
    """
    events = []
    for event in data.get('keyEvents', []):
        actors = []
        for participant in event.get('participants') or []:
            identifier = str(((participant or {}).get('athlete') or {}).get('id', ''))
            if identifier.isdigit():
                actors.append('espn-' + identifier)
        events.append({'kind': (event.get('type') or {}).get('type') or '',
                       'label': (event.get('type') or {}).get('text') or '',
                       'minute': minute(event),
                       'period': (event.get('period') or {}).get('number'),
                       'team': sides.get(str((event.get('team') or {}).get('id') or '')),
                       'players': actors,
                       # A goal is flagged, never spelled: `goal`, `goal---header` and
                       # `penalty---scored` are all one, and only this field says so.
                       'scoring': event.get('scoringPlay') is True,
                       'text': event.get('text') or '', 'at': event.get('wallclock')})
    return events


def clock_marks(events):
    """When the match really kicked off, broke and resumed, rather than when it was scheduled."""
    marks = {}
    for event in events:
        name = MARKS.get(event['kind'])
        if name and event['at'] and name not in marks:
            marks[name] = event['at']
            # The final whistle also carries its own minute, added time included. A stopped
            # clock is read, not recomputed: 90+6 is what was whistled, not 96 minutes of wall.
            if name == 'end' and event['minute'] is not None:
                marks['end_minute'] = event['minute']
    return marks


class Espn:
    def __init__(self):
        self.cache = {}
        self.events = {}
        self.lock = threading.Lock()

    def fetch(self, league, resource, params, ttl=CACHE):
        url = 'https://site.api.espn.com/apis/site/v2/sports/soccer/' + league + '/' + resource + '?' + urlencode(params)
        with self.lock:
            now = time.monotonic()
            cached = self.cache.get(url)
            if cached and cached[0] > now:
                return copy.deepcopy(cached[1])
            with urlopen(url, timeout=5) as response:
                data = json.load(response)
            # Short cache also allows late lineup publication to become visible.
            self.cache = {k: v for k, v in self.cache.items() if v[0] > now}
            if len(self.cache) >= MEMO:
                self.cache.pop(next(iter(self.cache)))
            self.cache[url] = (now + ttl, data)
            return copy.deepcopy(data)

    def event_id(self, match, league, ttl):
        """Which ESPN event this fixture is, resolved once and then remembered.

        The scoreboard exists only to answer that question, and the answer cannot change:
        refreshing a match in play therefore costs one request, not two.
        """
        key = str(match.get('id'))
        with self.lock:
            known = self.events.get(key)
        if known:
            return known
        date = instant(match['utcDate'])
        dates = (date - timedelta(days=1)).strftime('%Y%m%d') + '-' + (date + timedelta(days=1)).strftime('%Y%m%d')
        events = self.fetch(league, 'scoreboard', {'dates': dates, 'limit': 100}, ttl).get('events', [])
        event = select_event(match, events)
        if event is None:
            return None
        with self.lock:
            if len(self.events) >= MEMO:
                self.events.pop(next(iter(self.events)))
            self.events[key] = event['id']
        return event['id']

    def enrich(self, match):
        result = copy.deepcopy(match)
        league = LEAGUES.get(match.get('competition', {}).get('code'))
        result['lineup_status'] = 'unsupported' if not league else 'unavailable'
        if not league:
            return result
        try:
            ttl = LIVE_CACHE if match.get('status') in LIVE else CACHE
            identifier = self.event_id(match, league, ttl)
            if identifier is None:
                result['lineup_status'] = 'unmatched'
                return result
            data = self.fetch(league, 'summary', {'event': identifier}, ttl)
            rosters = {r.get('homeAway'): r for r in data.get('rosters', [])}
            converted = {}
            for side in ('home', 'away'):
                roster = rosters.get(side, {})
                if not same_team(match[side + 'Team'], roster.get('team', {})):
                    return result
                players = lineup(roster)
                if not players:
                    return result
                converted[side] = (players, roster, roster.get('formation') or '', roster.get('team', {}))
            ids = [p['fonote_id'] for players, _, _, _ in converted.values() for p in players]
            if len(set(ids)) != 22:
                return result
            taken = set(ids)
            colours = team_colours(converted['home'][3], converted['away'][3])
            sides = {}
            for side, colour in zip(('home', 'away'), colours):
                players, roster, formation, team = converted[side]
                result[side + 'Team']['lineup'] = players
                result[side + 'Team']['bench'] = bench(roster, taken)
                result[side + 'Team']['formation'] = formation
                result[side + 'Team']['colour'] = colour
                # Kept, where the abbreviation used to be read for the match and dropped: three
                # letters and a badge name a club in the width of a button, which its name does
                # not. Both are ESPN's own — its `TRY` for Troyes, against football-data's `ETR`
                # — and they land beside `tla` and `crest` rather than over them, a client
                # choosing which of the two providers it would rather show.
                abbreviation = (team.get('abbreviation') or '').strip()
                if abbreviation:
                    result[side + 'Team']['abbreviation'] = abbreviation
                badge = crest(team)
                if badge:
                    result[side + 'Team']['logo'] = badge
                sides[str(team.get('id') or '')] = side
            # The run of play is a bonus on top of the composition: it is read after the eleven
            # are secured, so a surprise in it can never cost the pitch its players.
            result['timeline'] = timeline(data, sides)
            result['clock'] = clock_marks(result['timeline'])
            result['team_stats'] = team_stats(data, sides)
            result['ground'] = ground(data)
            result.update(lineup_status='available', lineup_source='ESPN', espn_event_id=identifier)
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            result['lineup_status'] = 'provider_error'
        return result
