"""ESPN feed adapter: the single provider behind Fonote's football contract.

The contract keeps the shape football-data.org gave it — `matches`, `homeTeam`, `utcDate`,
`status` — because that shape belongs to the client, which stores it on the device and reads it
offline. Only the provider behind it changed. The identifiers changed with it: an id in this
contract is an ESPN id, and nothing here reconciles two providers any more.
"""
import copy
import json
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta, timezone
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

BASE = 'https://site.api.espn.com/apis/site/v2/sports/soccer/'
# A Fonote competition code and the ESPN league it names. The codes are the contract's own and
# outlived the provider that first supplied them: a device that stored 'FL1' still reads it.
# The list itself no longer is: it was once the free plan of a paid API, and is now a choice —
# ESPN serves far more than these, and a competition is added by writing its slug here.
# Domestic leagues come first, which is how a club met in both keeps its own championship.
if __package__:
    from .catalogue import COMPETITIONS
else:
    from catalogue import COMPETITIONS

LEAGUES = {code: item['slug'] for code, item in COMPETITIONS.items()}
CUPS = {code for code, item in COMPETITIONS.items() if item['type'] == 'CUP'}


# Lists stay cached for five minutes, even today's. Only a match sheet open live may be
# read again after thirty seconds.
CACHE = 300
LIVE_CACHE = 30
# What a season changes at most once a day: which competitions exist and who plays in them.
CATALOGUE = 86400
MEMO = 128
# How long a reading that has run out is still worth serving. Rafraîchir is the client's word,
# never a command reaching this far: a reading nobody could renew is the last thing the feed
# said, and the last thing said beats an error on a page that was drawn a minute ago.
STALE = 6 * 3600
# How long a failed reading is left in place before the feed is asked again. Without it, a feed
# that is down would be asked once per refresh by every client that pulls its page down.
RETRY = 300
# A composition is published about an hour before kickoff and stays for good afterwards, so it
# is worth announcing only around the match it belongs to. Reading a whole season to find out
# would cost one request per fixture; reading the ones being played tonight costs a handful —
# and the reading is not wasted, since opening one of them then finds the sheet already in hand.
ANNOUNCE_BEFORE = 2 * 3600
ANNOUNCE_AFTER = 4 * 3600
ANNOUNCE_MOST = 30


# Twelve leagues answer one calendar request. Asked one after another they would keep the client
# waiting on a page it has already drawn from its own copy; asked all at once they would arrive
# as a burst on a feed we are guests on. Four at a time is neither.
FETCHERS = 4


# The vocabulary the client reads, kept word for word: `state` covers every match, and the few
# names below are the cases where a state alone would say the wrong thing — a match at the break
# is not simply 'in play', and one abandoned in the second half is not simply 'finished'.
STATES = {'pre': 'TIMED', 'in': 'IN_PLAY', 'post': 'FINISHED'}
STATUSES = {'STATUS_HALFTIME': 'PAUSED', 'STATUS_END_PERIOD': 'PAUSED',
            'STATUS_POSTPONED': 'POSTPONED', 'STATUS_CANCELED': 'CANCELLED',
            'STATUS_ABANDONED': 'SUSPENDED', 'STATUS_SUSPENDED': 'SUSPENDED',
            'STATUS_DELAYED': 'SUSPENDED', 'STATUS_RAIN_DELAY': 'SUSPENDED'}
# Which round it is, said the way the client already names rounds. ESPN spells the phase in a
# season slug on a calendar and in a season name on a match sheet, so both are reduced to the
# same words before being looked up here. A domestic league has one phase and never asks.
PHASES = {'league phase': 'GROUP_STAGE', 'group stage': 'GROUP_STAGE',
          'knockout round playoffs': 'PLAYOFFS', 'playoffs': 'PLAYOFFS',
          'qualifying': 'PRELIMINARY_ROUND', 'qualifiers': 'PRELIMINARY_ROUND',
          'second round': 'LAST_16', 'round of 32': 'LAST_32', '3rd place match': 'THIRD_PLACE',
          'round of 16': 'LAST_16', 'quarterfinals': 'QUARTER_FINALS',
          'semifinals': 'SEMI_FINALS', 'third place': 'THIRD_PLACE', 'final': 'FINAL'}


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
    dark chip. A calendar carries a single `logo` instead of the list a match sheet carries.
    """
    logos = [logo for logo in team.get('logos') or [] if isinstance(logo, dict) and logo.get('href')]
    dark = [logo for logo in logos if 'dark' in (logo.get('rel') or [])]
    found = ((dark or logos) + [{}])[0].get('href', '')
    return found or (team.get('logo') or '')


def instant(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def moment(value):
    """ESPN publishes a kickoff to the minute; the contract has always carried the seconds."""
    return instant(value).astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def words(value):
    """A slug or a title reduced to the plain words a phase is looked up by."""
    return re.sub(r'[^a-z0-9]+', ' ', str(value or '').lower()).strip()


def status(holder):
    """What the match is doing, in the client's vocabulary."""
    kind = (holder.get('status') or {}).get('type') or {}
    return STATUSES.get(kind.get('name'), STATES.get(kind.get('state'), 'SCHEDULED'))


def stage(text, code):
    """Which round, from the phase ESPN happened to name it by.

    A league plays one competition all season and says so; a cup names its phase, and an
    unknown one travels as its own words rather than being flattened into a group stage.
    """
    if code not in CUPS:
        return 'REGULAR_SEASON'
    found = words(text)
    if found in PHASES:
        return PHASES[found]
    # 'Two thousand twenty-six-27 UEFA Champions League, League Phase' names the phase last.
    tail = words(str(text or '').split(',')[-1])
    return PHASES.get(tail, tail.replace(' ', '_').upper() or 'GROUP_STAGE')


def competition_entry(league, code):
    """A competition as the catalogue and every fixture carry it."""
    logos = [logo.get('href') for logo in league.get('logos') or [] if logo.get('href')]
    return {'id': int(league.get('id') or 0), 'name': league.get('name') or code, 'code': code,
            'type': 'CUP' if code in CUPS else 'LEAGUE', 'emblem': logos[0] if logos else ''}


def team_entry(team):
    """A club, named the three ways the client shows clubs: in full, in a card, on a chip."""
    return {'id': int(team.get('id') or 0), 'name': team.get('displayName') or '',
            'shortName': team.get('shortDisplayName') or team.get('displayName') or '',
            'tla': (team.get('abbreviation') or '').upper(), 'crest': crest(team)}


def goals(sides, played):
    """The score, and nothing at all before a ball is kicked."""
    def count(side):
        value = str((sides.get(side) or {}).get('score', ''))
        return value if played and value.isdigit() else ''
    return {'fullTime': {'home': count('home'), 'away': count('away')}}


def fixture(event, code, competition, contest=None):
    """One match as the contract carries it, from a calendar entry or from a match sheet."""
    contest = contest if contest is not None else (event.get('competitions') or [{}])[0]
    sides = {c.get('homeAway'): c for c in contest.get('competitors') or []}
    state = status(contest if (contest.get('status') or {}).get('type') else event)
    season = (event.get('season') or {})
    return {'id': int(event['id']),
            'utcDate': moment(contest.get('date') or event['date']),
            'status': state, 'competition': competition, 'season': season.get('year'),
            'stage': stage(season.get('slug') or season.get('name') or '', code),
            'homeTeam': team_entry((sides.get('home') or {}).get('team') or {}),
            'awayTeam': team_entry((sides.get('away') or {}).get('team') or {}),
            'score': goals(sides, state not in {'TIMED', 'SCHEDULED', 'POSTPONED', 'CANCELLED'}),
            'venue': ((contest.get('venue') or event.get('venue') or {}).get('fullName')
                      or (event.get('venue') or {}).get('displayName') or '')}


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


LIVE = {'IN_PLAY', 'PAUSED'}
SLUGS = {slug: code for code, slug in LEAGUES.items()}


# Beyond three months a span is read year by year rather than month by month: the calendars a
# club is followed over run a year, and thirteen readings for one are thirteen too many.
BY_YEAR = 3


def window(date_from, date_to):
    """The readings a span is made of, as ESPN spells a date.

    A scoreboard answers on a day, a month or a year — 20260917, 202609, 2026 — and since
    mid-September 2026 answers 400 Bad Request to the span it accepted until then,
    20260917-20260918. So a span is no longer one reading: it is the coarsest buckets that
    cover it, read one after another and cut back to its own days by the caller.
    """
    start, end = date.fromisoformat(date_from), date.fromisoformat(date_to)
    if end < start:
        raise ValueError('Période invalide')
    if start == end:
        return [start.strftime('%Y%m%d')]
    months = (end.year - start.year) * 12 + end.month - start.month + 1
    if months > BY_YEAR:
        return [str(year) for year in range(start.year, end.year + 1)]
    return months_between(start, end)


def months_between(start, end):
    """Every month the span touches, first to last."""
    found, year, month = [], start.year, start.month
    while (year, month) <= (end.year, end.month):
        found.append('%04d%02d' % (year, month))
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)
    return found


def days_of(year, month):
    """Every day of a month, as ESPN spells a day."""
    last = date(year + (month == 12), month % 12 + 1, 1) - timedelta(days=1)
    return ['%04d%02d%02d' % (year, month, day) for day in range(1, last.day + 1)]


def narrower(bucket, date_from, date_to):
    """The readings a bucket that answered short of everything is made of instead.

    A year holds twelve months, a month its days; a day holds nothing smaller, and a reading
    that fills up on one day is as complete as ESPN will ever be about it. Only the ones the
    span actually touches are kept: a fortnight cut short costs a fortnight of readings, not a
    year of them.
    """
    if len(bucket) == 4:
        smaller = months_between(date(int(bucket), 1, 1), date(int(bucket), 12, 31))
    elif len(bucket) == 6:
        smaller = days_of(int(bucket[:4]), int(bucket[4:]))
    else:
        return []
    return [item for item in smaller if touches(item, date_from, date_to)]


def bounds(bucket):
    """The first and last day a bucket answers for."""
    if len(bucket) == 4:
        return date(int(bucket), 1, 1), date(int(bucket), 12, 31)
    if len(bucket) == 6:
        year, month = int(bucket[:4]), int(bucket[4:])
        return date(year, month, 1), date(year + (month == 12), month % 12 + 1, 1) - timedelta(days=1)
    return date.fromisoformat(bucket[:4] + '-' + bucket[4:6] + '-' + bucket[6:]),\
        date.fromisoformat(bucket[:4] + '-' + bucket[4:6] + '-' + bucket[6:])


def touches(bucket, date_from, date_to):
    """Whether a bucket holds any day of the span."""
    start, end = bounds(bucket)
    return start.isoformat() <= date_to and end.isoformat() >= date_from


def current(date_from, date_to):
    """Whether the span covers today, and so may hold a match being played right now."""
    today = datetime.now(timezone.utc).date()
    return date.fromisoformat(date_from) <= today <= date.fromisoformat(date_to)


class Espn:
    def __init__(self):
        self.cache = {}
        self.clubs = {}
        self.readings = {}
        self.failures = {}
        self.fetched_at = {}
        self.lock = threading.Lock()
        self.pool = ThreadPoolExecutor(max_workers=FETCHERS)

    def url_for(self, league, resource, params):
        return BASE + league + '/' + resource + '?' + urlencode(params)

    def fetch(self, league, resource, params, ttl=CACHE):
        """One reading of the feed, remembered for as long as it is worth reading again.

        Nothing a client asks reaches ESPN directly. A reader who pulls a page down ten times is
        answered ten times, from here, and the feed hears about it once at most: the only thing
        that sends this to the network is a reading that has run out.

        Two threads wanting the same page wait on one another rather than both going, so a burst
        of refreshes costs one request and not one each. Pages never queue behind each other —
        the twelve leagues of a calendar are twelve locks — which is why the network stays
        outside the shared lock, as it always did.
        """
        url = self.url_for(league, resource, params)
        held = self.held(url)
        if held is not None:
            return held
        with self.reading(url):
            # Whoever held the lock may have been reading this very page: ask again before going.
            held = self.held(url)
            if held is not None:
                return held
            with self.lock:
                failure = self.failures.get(url)
                if failure and failure[0] > time.monotonic():
                    raise failure[1]
            try:
                with urlopen(url, timeout=10) as response:
                    data = json.load(response)
            except (OSError, ValueError) as error:
                stale = self.stale(url)
                if stale is None:
                    with self.lock:
                        now = time.monotonic()
                        self.failures = {k: v for k, v in self.failures.items() if v[0] > now}
                        if len(self.failures) >= MEMO:
                            self.failures.pop(next(iter(self.failures)))
                        self.failures[url] = (now + RETRY, error)
                    raise
                return stale
            self.keep(url, data, ttl)
            return copy.deepcopy(data)

    def snapshot(self, league, resource, params):
        """Home and calendar never read again a match sheet less than five minutes old.

        A sheet open live may shorten its expiry; its read date stays separate so that
        lists do not trigger that fast tracking.
        """
        url = self.url_for(league, resource, params)
        with self.lock:
            cached = self.cache.get(url)
            if cached and time.monotonic() - self.fetched_at.get(url, float('-inf')) < CACHE:
                return copy.deepcopy(cached[1])
        return self.fetch(league, resource, params, CACHE)

    def held(self, url):
        """The reading on file, while it is still worth serving rather than reading again."""
        with self.lock:
            cached = self.cache.get(url)
            if not cached or cached[0] <= time.monotonic():
                return None
            return copy.deepcopy(cached[1])

    def stale(self, url):
        """The reading that has run out, served because the feed would not renew it.

        Held five minutes longer on the way out: a feed that is not answering is not answering the
        next refresh either, and the client is no worse off reading the same copy twice.
        """
        with self.lock:
            cached = self.cache.get(url)
            if not cached:
                return None
            self.cache[url] = (time.monotonic() + RETRY, cached[1])
            return copy.deepcopy(cached[1])

    def keep(self, url, data, ttl):
        """File a fresh reading, and let go of the ones nothing could be served from any more."""
        with self.lock:
            now = time.monotonic()
            # A reading that has run out is not dropped with it: until STALE it is still the
            # answer of last resort, the one thing left to say when the feed says nothing.
            self.cache = {k: v for k, v in self.cache.items() if v[0] + STALE > now}
            if len(self.cache) >= MEMO:
                self.cache.pop(next(iter(self.cache)))
            self.cache[url] = (now + ttl, data)
            self.fetched_at = {k: v for k, v in self.fetched_at.items() if k in self.cache}
            self.fetched_at[url] = now

    def reading(self, url):
        """The lock that stands for one page being read, so two refreshes make one request."""
        with self.lock:
            lock = self.readings.get(url)
            if lock is None:
                # A lock nobody holds guards nothing: they go the way the readings themselves do.
                if len(self.readings) >= MEMO:
                    self.readings = {k: v for k, v in self.readings.items() if v.locked()}
                lock = self.readings[url] = threading.Lock()
            return lock

    def shorten(self, league, resource, params, ttl):
        """Hold a reading for less time than it was filed under, once it turns out to be live."""
        url = self.url_for(league, resource, params)
        with self.lock:
            cached = self.cache.get(url)
            if cached:
                self.cache[url] = (min(cached[0], time.monotonic() + ttl), cached[1])

    def spread(self, work, codes):
        """Ask several leagues at once, and let a league that will not answer stay silent."""
        def guarded(code):
            try:
                return code, work(code)
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                return code, None
        return [(code, value) for code, value in self.pool.map(guarded, codes) if value is not None]

    def board(self, code, dates=None, ttl=CACHE, limit=500):
        params = {'limit': limit}
        if dates:
            params['dates'] = dates
        return self.fetch(LEAGUES[code], 'scoreboard', params, ttl)

    def calendar(self, code, date_from, date_to, ttl=CACHE, limit=500):
        """A span of one competition's calendar, in the shape a scoreboard answers.

        Several readings, since ESPN no longer takes a span in one — see `window` — merged into
        the one answer the callers have always read. A bucket is coarser than the span it serves
        and is cut back to it here; a bucket that came back full was truncated by ESPN, so it is
        read again in narrower ones rather than quietly losing the matches beyond the limit.
        """
        leagues, events, seen, truncated = None, [], set(), False
        buckets = list(window(date_from, date_to))
        while buckets:
            bucket = buckets.pop(0)
            data = self.board(code, dates=bucket, ttl=ttl, limit=limit)
            if leagues is None:
                leagues = data.get('leagues')
            found = data.get('events')
            if not isinstance(found, list):
                raise ValueError('Calendrier invalide')
            if len(found) >= limit:
                smaller = narrower(bucket, date_from, date_to)
                if smaller:
                    buckets = smaller + buckets
                    continue
                # A single day ESPN cuts short: nothing narrower to ask, so what is kept is
                # said to be incomplete rather than passed off as the whole day.
                truncated = True
            for item in found:
                identifier = str(item.get('id') or '')
                day = str(item.get('date') or '')[:10]
                if identifier in seen or not date_from <= day <= date_to:
                    continue
                seen.add(identifier)
                events.append(item)
        events.sort(key=lambda item: (str(item.get('date') or ''), str(item.get('id') or '')))
        return {'leagues': leagues or [], 'events': events, 'truncated': truncated}

    def competitions(self):
        """Which competitions this server knows, named and badged by the feed itself."""
        def read(code):
            league = (self.board(code, ttl=CATALOGUE, limit=1).get('leagues') or [{}])[0]
            return competition_entry(league, code)
        found = dict(self.spread(read, list(LEAGUES)))
        items = [found[code] for code in LEAGUES if code in found]
        return {'count': len(items), 'competitions': items}

    def lineup_ready(self, identifier):
        """Whether both elevens are out, read from the very sheet the match itself is read from."""
        try:
            data = self.snapshot('all', 'summary', {'event': str(identifier)})
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            return 'unknown'
        rosters = {r.get('homeAway'): r for r in data.get('rosters') or []}
        both = all(lineup(rosters.get(side) or {}) for side in ('home', 'away'))
        return 'available' if both else 'unavailable'

    def announce(self, matches):
        """Say which of these have their composition already published.

        Only around kickoff, and never by guessing. Anything outside that window is left
        'unknown', which claims nothing: a calendar says a composition is there or says
        nothing at all, because a badge promising a sheet that is not there would cost more
        than the badge is worth.
        """
        now = datetime.now(timezone.utc)
        close = []
        for match in matches:
            match['lineup_status'] = 'unknown'
            delta = (instant(match['utcDate']) - now).total_seconds()
            if -ANNOUNCE_AFTER <= delta <= ANNOUNCE_BEFORE:
                close.append(match)
        close = close[:ANNOUNCE_MOST]
        for match, found in zip(close, self.pool.map(self.lineup_ready, [m['id'] for m in close])):
            match['lineup_status'] = found
        return matches

    def fixtures(self, date_from, date_to, codes=None, lineups=False):
        """Every match of a span, in the order they are played."""
        # An impossible span is refused once, here, rather than by each league's own reading.
        window(date_from, date_to)
        ttl = CACHE
        wanted = [code for code in (codes or LEAGUES) if code in LEAGUES]

        def read(code):
            data = self.calendar(code, date_from, date_to, ttl)
            league = (data.get('leagues') or [{}])[0]
            competition = competition_entry(league, code)
            return [fixture(event, code, competition) for event in data.get('events') or []]

        matches = [m for _, found in self.spread(read, wanted) for m in found]
        matches.sort(key=lambda m: (m['utcDate'], m['id']))
        if lineups:
            self.announce(matches)
        return {'count': len(matches), 'matches': matches}

    def teams(self, code):
        """The clubs of one competition."""
        if code not in LEAGUES:
            return None
        data = self.fetch(LEAGUES[code], 'teams', {}, CATALOGUE)
        entries = (data.get('sports') or [{}])[0].get('leagues') or [{}]
        found = [team_entry(item.get('team') or {}) for item in entries[0].get('teams') or []]
        return {'count': len(found), 'teams': found}

    def league_of(self, team_id):
        """Which competition a club plays in, resolved once for the season.

        ESPN keeps a club's own calendar to matches already played, so the next fixture of a
        followed club is read off its league's calendar instead — which first means knowing
        the league.
        """
        with self.lock:
            known = self.clubs.get(team_id)
        if known:
            return known
        def read(code):
            data = self.fetch(LEAGUES[code], 'teams', {}, CATALOGUE)
            entries = (data.get('sports') or [{}])[0].get('leagues') or [{}]
            return [str((item.get('team') or {}).get('id') or '')
                    for item in entries[0].get('teams') or []]
        index = {}
        # A club met in a cup keeps the domestic league that lists it: that calendar is the one
        # where its next match is certain to appear, week after week. The leagues are read in
        # the order they are declared, domestic ones first, and the first to name a club wins.
        for code, ids in self.spread(read, list(LEAGUES)):
            for identifier in ids:
                if identifier:
                    index.setdefault(identifier, code)
        with self.lock:
            self.clubs.update(index)
        return index.get(team_id)

    def team_fixtures(self, team_id, date_from, date_to, limit=100):
        """A club's matches over a span, drawn from the calendars it appears in."""
        code = self.league_of(str(team_id))
        codes = [code] if code else []
        # A club is followed for its domestic season, but its European nights count too.
        codes += [cup for cup in CUPS if cup != code]
        # As above: the span is checked before the calendars are read, not once per calendar.
        window(date_from, date_to)
        ttl = CACHE

        def read(where):
            data = self.calendar(where, date_from, date_to, ttl)
            league = (data.get('leagues') or [{}])[0]
            competition = competition_entry(league, where)
            found = []
            for event in data.get('events') or []:
                contest = (event.get('competitions') or [{}])[0]
                sides = [str(((c.get('team') or {}).get('id') or ''))
                         for c in contest.get('competitors') or []]
                if str(team_id) in sides:
                    found.append(fixture(event, where, competition))
            return found

        matches = [m for _, found in self.spread(read, codes) for m in found]
        matches.sort(key=lambda m: (m['utcDate'], m['id']))
        del matches[limit:]
        return {'count': len(matches), 'matches': matches}

    def match(self, identifier, live=True):
        """One match in full: the fixture, the two elevens, the run of play and the counts.

        The match sheet is read without naming a league — ESPN answers on any of them — so a
        stored match opens from its identifier alone, with nothing to look up first.
        """
        params = {'event': str(identifier)}
        try:
            data = (self.fetch('all', 'summary', params, CACHE) if live
                    else self.snapshot('all', 'summary', params))
        except HTTPError as refused:
            # An identifier the feed does not hold is a match that does not exist, not an
            # outage: the caller says 'unknown' rather than 'come back later'.
            if refused.code in (400, 404):
                return None
            raise
        header = data.get('header') or {}
        league = header.get('league') or {}
        code = SLUGS.get(league.get('slug') or '')
        contest = (header.get('competitions') or [{}])[0]
        if not header.get('id') or not contest.get('date'):
            return None
        result = fixture({'id': header['id'], 'date': contest['date'],
                          'season': header.get('season') or {}},
                         code, competition_entry(league, code or ''), contest)
        if live and result['status'] in LIVE:
            self.shorten('all', 'summary', params, LIVE_CACHE)
        result['lineup_status'] = 'unavailable'
        rosters = {r.get('homeAway'): r for r in data.get('rosters') or []}
        converted = {}
        for side in ('home', 'away'):
            roster = rosters.get(side) or {}
            players = lineup(roster)
            if not players:
                return result
            converted[side] = (players, roster, roster.get('formation') or '', roster.get('team') or {})
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
            # The match sheet knows the badge and the three letters that the calendar left out.
            badge = crest(team)
            if badge:
                result[side + 'Team']['crest'] = badge
                result[side + 'Team']['logo'] = badge
            abbreviation = (team.get('abbreviation') or '').strip()
            if abbreviation:
                result[side + 'Team']['abbreviation'] = abbreviation
                result[side + 'Team'].setdefault('tla', abbreviation.upper())
            sides[str(team.get('id') or '')] = side
        # The run of play is a bonus on top of the composition: it is read after the eleven
        # are secured, so a surprise in it can never cost the pitch its players.
        result['timeline'] = timeline(data, sides)
        result['clock'] = clock_marks(result['timeline'])
        result['team_stats'] = team_stats(data, sides)
        result['ground'] = ground(data)
        if not result['venue']:
            result['venue'] = result['ground']['venue']
        result.update(lineup_status='available', lineup_source='ESPN', espn_event_id=str(identifier))
        return result
