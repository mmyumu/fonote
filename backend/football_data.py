"""Catalogue persistant, imports reprenables et accès ESPN régulé."""
import json
import logging
import os
import threading
import time
import unicodedata
from datetime import date, timedelta
from email.utils import parsedate_to_datetime
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

if __package__:
    from .catalogue import COMPETITIONS, catalogue
    from .espn import Espn, fixture, window, LIVE
    from .football_store import FootballStore
else:
    from catalogue import COMPETITIONS, catalogue
    from espn import Espn, fixture, window, LIVE
    from football_store import FootballStore

log = logging.getLogger('fonote.football')
WEEK = 7 * 86400


class NetworkGate:
    """Une requête à la fois ; toutes les ressources partagent le même budget."""
    def __init__(self, store, interval=2, background=10, opener=urlopen):
        self.store, self.interval, self.background, self.opener = store, interval, background, opener
        self.condition = threading.Condition()
        self.waiters = []
        self.sequence = 0
        self.busy = False
        self.closed = False
        timing = store.meta('network_timing', {})
        self.last = time.monotonic() - max(0, time.time() - timing.get('last', 0))
        self.last_background = time.monotonic() - max(0, time.time() - timing.get('background', 0))
        self.failures = 0

    def close(self):
        with self.condition:
            self.closed = True
            self.condition.notify_all()

    def read(self, url, priority=0):
        with self.condition:
            self.sequence += 1
            ticket = (priority, self.sequence)
            self.waiters.append(ticket)
            try:
                while True:
                    pause = self.store.meta('network_pause', {})
                    if self.closed:
                        raise OSError('Serveur arrêté')
                    if pause.get('blocked'):
                        raise OSError('ESPN a refusé les appels ; reprise manuelle nécessaire')
                    if pause.get('until', 0) > time.time():
                        raise OSError('ESPN temporairement indisponible ; nouvelle tentative différée')
                    due = self.last + self.interval
                    if priority >= 10:
                        due = max(due, self.last_background + self.background)
                    if not self.busy and ticket == min(self.waiters) and time.monotonic() >= due:
                        self.busy = True
                        self.last = time.monotonic()
                        if priority >= 10:
                            self.last_background = self.last
                        self.store.put_meta('network_timing', {'last': time.time(),
                            'background': time.time() if priority >= 10 else
                            time.time() - (time.monotonic() - self.last_background)})
                        break
                    self.condition.wait(max(.01, min(.2, due - time.monotonic())))
            finally:
                self.waiters.remove(ticket)
        try:
            log.info('ESPN request priority=%s resource=%s', priority, url.split('?')[0])
            with self.opener(url, timeout=10) as response:
                data = json.load(response)
            self.failures = 0
            return data
        except HTTPError as error:
            if error.code == 403:
                self.store.put_meta('network_pause', {'blocked': True})
            elif error.code == 429 or error.code >= 500:
                self.pause(error.headers.get('Retry-After') if error.headers else None)
            raise
        except (OSError, ValueError):
            self.pause(None)
            raise
        finally:
            with self.condition:
                self.busy = False
                self.condition.notify_all()

    def pause(self, retry):
        self.failures += 1
        delay = min(3600, 30 * 2 ** min(self.failures - 1, 7))
        if retry:
            try:
                delay = max(delay, float(retry))
            except ValueError:
                try:
                    delay = max(delay, parsedate_to_datetime(retry).timestamp() - time.time())
                except (ValueError, TypeError):
                    pass
        self.store.put_meta('network_pause', {'until': time.time() + delay})


class FootballData(Espn):
    def __init__(self, path, start=True, opener=urlopen, interval=None, background=None):
        super().__init__()
        self.store = FootballStore(path)
        self.gate = NetworkGate(self.store,
                                float(os.getenv('FONOTE_ESPN_INTERVAL', '2')) if interval is None else interval,
                                float(os.getenv('FONOTE_ESPN_BACKGROUND_INTERVAL', '10')) if background is None else background,
                                opener)
        if os.getenv('FONOTE_ESPN_RESUME') == '1':
            self.store.put_meta('network_pause', {})
        self.context = threading.local()
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.thread = None
        if start:
            self.thread = threading.Thread(target=self.run, name='football-imports', daemon=True)
            self.thread.start()

    def close(self):
        self.stop.set(); self.wake.set(); self.gate.close()
        if self.thread:
            self.thread.join(timeout=12)
        self.pool.shutdown(wait=False, cancel_futures=True)

    def request(self, url, ttl, stale=False):
        with self.reading(url):
            held = self.store.response(url)
            if held and held['expires'] > time.time():
                return json.loads(held['payload'])
            try:
                data = self.gate.read(url, getattr(self.context, 'priority', 0))
                self.store.keep_response(url, data, ttl)
                return data
            except (OSError, ValueError):
                if stale and held:
                    return json.loads(held['payload'])
                raise

    def snapshot(self, league, resource, params):
        return self.fetch(league, resource, params, 300)

    def competitions(self):
        return catalogue()

    def enqueue(self, key, kind, payload, priority=0, ttl=None):
        self.store.enqueue(key, kind, payload, priority, ttl)
        self.wake.set()
        return key

    def validate_code(self, code):
        if code not in COMPETITIONS:
            raise LookupError('Compétition inconnue')
        return code

    def seasons(self, code):
        self.validate_code(code)
        key = self.enqueue('seasons:' + code, 'seasons', {'code': code}, ttl=WEEK)
        return {'seasons': self.store.meta(key, []), **self.state([key])}

    def teams(self, code):
        self.validate_code(code)
        key = self.enqueue('teams:' + code, 'teams', {'code': code}, ttl=WEEK)
        items = self.store.meta(key, [])
        return {'count': len(items), 'teams': items, **self.state([key])}

    def search(self, code, season, team='', phase='', start=None, end=None, page=0, query=''):
        self.validate_code(code)
        year = int(season)
        if not 1800 <= year <= date.today().year + 2 or page < 0:
            raise ValueError('Saison ou page invalide')
        if start:
            date.fromisoformat(start)
        if end:
            date.fromisoformat(end)
        if start and end and start > end:
            raise ValueError('Période invalide')
        key = self.enqueue(f'season:{code}:{year}', 'season', {'code': code, 'season': year},
                           ttl=86400 if year >= date.today().year - 1 else None)
        items = self.store.known([code], year, start, end)
        teams = {}
        phases = set()
        for item in items:
            phases.add(item.get('stage', ''))
            for side in ('homeTeam', 'awayTeam'):
                t = item.get(side, {})
                teams[str(t.get('id'))] = t
        if team:
            items = [m for m in items if any(str(m.get(s, {}).get('id')) == str(team)
                                           for s in ('homeTeam', 'awayTeam'))]
        if phase:
            items = [m for m in items if m.get('stage') == phase]
        if query:
            needle = normalized(query)
            items = [m for m in items if needle in normalized(' '.join(
                m.get(s, {}).get('name', '') for s in ('homeTeam', 'awayTeam')))]
        return {'count': len(items), 'matches': items[page * 50:(page + 1) * 50],
                'page': page, 'hasMore': len(items) > (page + 1) * 50,
                'teams': list(teams.values()), 'phases': sorted(phases - {''}), **self.state([key])}

    def fixtures(self, date_from, date_to, codes=None, lineups=False):
        window(date_from, date_to)
        if (date.fromisoformat(date_to) - date.fromisoformat(date_from)).days > 370:
            raise ValueError('Période trop longue')
        wanted = codes or list(COMPETITIONS)
        keys = []
        for code in wanted:
            self.validate_code(code)
            keys.append(self.enqueue(f'range:{code}:{date_from}:{date_to}', 'range',
                        {'code': code, 'start': date_from, 'end': date_to}, priority=5,
                        ttl=300 if date_from <= date.today().isoformat() <= date_to else 21600))
        items = self.store.known(wanted, start=date_from, end=date_to)
        return {'count': len(items), 'matches': items, **self.state(keys)}

    def team_fixtures(self, team_id, date_from, date_to, limit=100):
        # Les calendriers partagent les tâches avec l'accueil ; aucune fiche n'est préchargée.
        known = self.store.meta('team_codes:' + str(team_id), [])
        codes = known or list(COMPETITIONS)
        data = self.fixtures(date_from, date_to, codes)
        items = [m for m in data['matches'] if any(str(m.get(s, {}).get('id')) == str(team_id)
                                                 for s in ('homeTeam', 'awayTeam'))][:limit]
        return dict(data, count=len(items), matches=items)

    def match(self, identifier, live=True, refresh=False):
        known, fetched = self.store.detail(identifier)
        ttl = 30 if live and known and known.get('status') in LIVE else 300
        if known and not refresh and (known.get('status') == 'FINISHED' or time.time() - fetched < ttl):
            return known
        if refresh:
            with self.store.connect() as db:
                db.execute('UPDATE football_responses SET expires=0 WHERE url=?',
                           (self.url_for('all', 'summary', {'event': str(identifier)}),))
        try:
            # Le cache réseau suit le même délai que la fiche, sans le cache mémoire historique.
            self.context.detail_ttl = ttl
            data = super().match(identifier, live=live)
            return self.store.keep_detail(data) if data else None
        except (OSError, ValueError):
            if known:
                return dict(known, stale=True)
            raise
        finally:
            self.context.detail_ttl = None

    def fetch(self, league, resource, params, ttl=300):
        if resource == 'summary':
            ttl = getattr(self.context, 'detail_ttl', None) or ttl
        return self.request(self.url_for(league, resource, params), ttl)

    def refresh_archive(self, code, year):
        self.validate_code(code)
        year = int(year)
        if not 1800 <= year <= date.today().year + 2:
            raise ValueError('Saison invalide')
        key = f'season:{code}:{year}'
        with self.store.connect() as db:
            keys, seen = [key], set()
            while keys:
                current = keys.pop()
                if current in seen:
                    continue
                seen.add(current)
                keys.extend(self.store.meta(current + ':children', []))
                db.execute("UPDATE football_jobs SET state='pending',due=0,priority=0,error='' WHERE key=? AND state!='running'", (current,))
            db.execute('UPDATE football_responses SET expires=0 WHERE url LIKE ?',
                       ('%' + COMPETITIONS[code]['slug'] + '/scoreboard%',))
        self.enqueue(key, 'season', {'code': code, 'season': year}, 0, 0)
        return self.state([key])

    def set_follows(self, device, data):
        if not isinstance(data, dict):
            raise ValueError('Suivis invalides')
        comps, teams = data.get('competitions', []), data.get('teams', [])
        if not isinstance(comps, list) or not isinstance(teams, list) or len(comps) > 23 or len(teams) > 500:
            raise ValueError('Suivis invalides')
        by_id = {str(c['id']): code for code, c in COMPETITIONS.items()}
        codes = []
        for value in comps:
            code = by_id.get(str(value), str(value))
            self.validate_code(code); codes.append(code)
        if any(not str(t).isdigit() for t in teams):
            raise ValueError('Équipe invalide')
        with self.store.connect() as db:
            db.execute('INSERT OR REPLACE INTO football_follows VALUES (?,?)',
                       (device, json.dumps({'competitions': codes, 'teams': [str(t) for t in teams]})))
        self.prepare()
        return {'saved': True}

    def prepare(self):
        with self.store.connect() as db:
            follows = [json.loads(r[0]) for r in db.execute('SELECT payload FROM football_follows')]
        codes = set()
        for follow in follows:
            codes.update(follow['competitions'])
            for team in follow['teams']:
                codes.update(self.store.meta('team_codes:' + team, []))
        # Les tâches de fond devenues inutiles ne seront plus reprises ; les archives restent.
        with self.store.connect() as db:
            for row in db.execute("SELECT key,payload FROM football_jobs WHERE priority>=10 AND state IN ('pending','error')").fetchall():
                if json.loads(row['payload']).get('code') not in codes:
                    db.execute("DELETE FROM football_jobs WHERE key=?", (row['key'],))
        for code in sorted(codes):
            self.enqueue('seasons:' + code, 'seasons', {'code': code}, 10, WEEK)
            self.enqueue('current:' + code, 'current', {'code': code}, 10, 86400)

    def run(self):
        next_prepare = 0
        while not self.stop.is_set():
            try:
                if time.monotonic() >= next_prepare:
                    self.prepare(); next_prepare = time.monotonic() + 60
                job = self.store.claim()
                if job:
                    self.execute(job)
                    continue
            except Exception:
                log.exception('Erreur du travailleur football')
            self.wake.wait(1); self.wake.clear()

    def execute(self, job):
        self.context.priority = job['priority']
        try:
            partial = self.process(job)
            self.store.finish(job, partial=bool(partial))
        except (OSError, ValueError, KeyError, TypeError, AttributeError, LookupError) as error:
            log.warning('Import %s différé : %s', job['key'], type(error).__name__)
            self.store.finish(job, error=error)
        finally:
            self.context.priority = 0

    def process(self, job):
        payload = json.loads(job['payload']); code = payload['code']; item = COMPETITIONS[code]
        kind = job['kind']
        if kind == 'seasons':
            url = f"https://sports.core.api.espn.com/v2/sports/soccer/leagues/{item['slug']}/seasons?limit=1000"
            data = self.request(url, WEEK)
            if data.get('pageCount', 1) > 1:
                raise ValueError('Catalogue des saisons tronqué')
            years = sorted({int(x['$ref'].split('/seasons/')[1].split('?')[0]) for x in data['items']}, reverse=True)
            self.store.put_meta(job['key'], [{'year': y, 'label': f'{y}-{y + 1}' if item['seasonFormat'] == 'split' else str(y)} for y in years])
        elif kind == 'teams':
            data = super().teams(code)
            teams = data['teams']
            for team in teams:
                team.update(gender=item['gender'], participants=item['participants'], country=item['country'])
                key = 'team_codes:' + str(team['id'])
                self.store.put_meta(key, sorted(set(self.store.meta(key, []) + [code])))
            self.store.put_meta(job['key'], teams)
        elif kind == 'current':
            data = self.fetch(item['slug'], 'scoreboard', {'limit': 1}, 86400)
            season = (data.get('leagues') or [{}])[0].get('season') or {}
            year = season.get('year')
            end = season.get('endDate', '')[:10]
            # Une dernière vérification après clôture, puis l'archive cesse d'être actualisée.
            finalized = self.store.meta(f'finalized:{code}:{year}', False)
            if year and not finalized:
                key = self.enqueue(f'season:{code}:{year}', 'season', {'code': code, 'season': int(year)}, 10, 86400)
                if end and end < (date.today() - timedelta(days=2)).isoformat():
                    existing = self.store.job(key)
                    # La fin est validée uniquement après une récupération réussie postérieure à la clôture.
                    if existing and existing['state'] == 'done' and self.state([key])['state'] == 'ready' and date.fromtimestamp(existing['updated']).isoformat() > end:
                        self.store.put_meta(f'finalized:{code}:{year}', True)
        elif kind == 'season':
            year = payload['season']
            # Les bornes viennent de la saison choisie, pas du header qui peut nommer l'année actuelle.
            url = f"https://sports.core.api.espn.com/v2/sports/soccer/leagues/{item['slug']}/seasons/{year}?lang=en&region=us"
            data = self.request(url, WEEK)
            start, end = data.get('startDate', '')[:10], data.get('endDate', '')[:10]
            if not start or not end:
                raise ValueError('Bornes de saison indisponibles')
            children = self.store.meta(job['key'] + ':children', [])
            if not children:
                child = f"range:{code}:{start}:{end}:season:{year}"
                children = [child]
                self.store.put_meta(job['key'] + ':children', children)
            for child in children:
                self.enqueue(child, 'range', {'code': code, 'start': start, 'end': end, 'season': year},
                             job['priority'], 86400 if year >= date.today().year - 1 else None)
            # Le parent est prêt après ses enfants ; state() agrège leurs états.
        elif kind == 'range':
            start, end = payload['start'], payload['end']
            data = self.board(code, dates=window(start, end), ttl=300 if start <= date.today().isoformat() <= end else 21600)
            events = data.get('events')
            if not isinstance(events, list):
                raise ValueError('Calendrier invalide')
            matches = [fixture(event, code, item) for event in events]
            if 'season' in payload:
                matches = [m for m in matches if m.get('season') in (None, payload['season'])]
                for m in matches:
                    m['season'] = payload['season']
            self.store.matches(matches)
            for match in matches:
                for side in ('homeTeam', 'awayTeam'):
                    team = match.get(side, {})
                    if team.get('id'):
                        key = 'team_codes:' + str(team['id'])
                        known = self.store.meta(key, [])
                        if code not in known:
                            self.store.put_meta(key, sorted(known + [code]))
            if len(events) >= 500 or data.get('count', len(events)) > len(events):
                first, last = date.fromisoformat(start), date.fromisoformat(end)
                if first >= last:
                    return True
                mid = first + (last - first) // 2
                children = []
                for a, b in ((first, mid), (mid + timedelta(days=1), last)):
                    child = job['key'] + ':' + a.isoformat() + ':' + b.isoformat()
                    children.append(self.enqueue(child, 'range', dict(payload, start=a.isoformat(), end=b.isoformat()), job['priority']))
                self.store.put_meta(job['key'] + ':children', children)
        else:
            raise ValueError('Tâche inconnue')

    def state(self, keys):
        expanded, seen = list(keys), set()
        states = set()
        while expanded:
            key = expanded.pop()
            if key in seen:
                continue
            seen.add(key)
            job = self.store.job(key)
            if job:
                states.add(job['state'])
            expanded.extend(self.store.meta(key + ':children', []))
        state = ('error' if 'error' in states else 'loading' if states & {'pending', 'running'}
                 else 'partial' if 'partial' in states else 'ready')
        return {'state': state, 'coverage': 'partial' if state != 'ready' else 'processed',
                'retryAfter': 2 if state == 'loading' else 30,
                'providerBlocked': bool(self.store.meta('network_pause', {}).get('blocked'))}


def normalized(text):
    return ''.join(c for c in unicodedata.normalize('NFD', text.casefold()) if not unicodedata.combining(c))
