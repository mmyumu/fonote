"""Fonote personal server. Python 3.12+, no external dependencies."""
import argparse
import hmac
import json
import logging
import os
import re
import sqlite3
import subprocess
import sys
import time
import urllib.parse
from contextlib import contextmanager
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import UUID

if __package__:
    from .espn import Espn
    from .football_data import FootballData
    from .release import Release, described
else:
    from espn import Espn
    from football_data import FootballData
    from release import Release, described

DEMO = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/match.json'
# Each action carries its own polarity; the client derives colour and balance from it,
# so 'positive' and 'negative' are the catch-all members of each side, not the axis itself.
ACTIONS = {'positive', 'goal', 'assist', 'pass', 'dribble', 'shot_on', 'header_on', 'duel_won',
           'defense', 'tackle', 'interception', 'save', 'keeper_exit', 'negative', 'own_goal',
           'lost_ball', 'pass_missed', 'dribble_lost', 'shot_off', 'header_off', 'duel_lost',
           'save_missed', 'keeper_exit_missed', 'yellow', 'red'}
# What a stroke on a tactical schema can mean. Meaning is carried by the shape of the line the
# client draws — solid, dashed, waved, doubled — never by a colour, which already names a team.
# 'carry' is no longer written: a run made by the player holding the ball is drawn waved, read
# off the ball itself. The journal being immutable, what was written before is still accepted.
# 'tackle' is the one stroke a player's key names itself: a run ending on the man it stops.
STROKES = {'pass', 'run', 'carry', 'shot', 'tackle'}
# Bounds a hand-drawn schema stays well inside; anything past them is a client gone wrong,
# not a moment of football. Twenty-two players is the whole pitch.
MAX_TOKENS, MAX_SHAPES, MAX_POINTS = 30, 40, 32
# The oldest Android version code this server still syncs with. Raise it along with a change to
# the operations an older app would get wrong: that app pauses its sync and asks for the update,
# its notes waiting on the device. Apps before 1.2.0 do not read it. 2 is Fonote 1.1.0.
MIN_APP = 2
# Silent until someone configures it: tests print nothing, while the server started from the
# command line says what it serves.
log = logging.getLogger('fonote')


def load_env(path=None):
    """Load literal KEY=value settings; existing environment variables take precedence."""
    path = Path(path) if path is not None else Path(__file__).resolve().parents[1] / '.env'
    if not path.exists():
        return
    for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:].strip()
        key, separator, value = line.partition('=')
        key, value = key.strip(), value.strip()
        if not separator or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key):
            raise ValueError(f'Configuration .env invalide à la ligne {number}')
        if value.startswith(('"', "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f'Configuration .env invalide à la ligne {number}')
            value = value[1:-1]
        os.environ.setdefault(key, value)


def one(query, key, default=None):
    values = query.get(key) or []
    return values[-1] if values else default


def football(espn, route, query):
    """Serve the narrow public Fonote contract from the ESPN feed.

    The routes and the shape of their answers are the client's, not a provider's: a device
    that stored `/v1/football/matches` under that name keeps reading it back. What changed
    underneath is who answers, and therefore which identifiers travel — they are ESPN's now.

    Returns None when the path is not a football route at all, so the caller can go on to the
    routes that need a token, and raises LookupError for a football route naming something the
    feed does not know.
    """
    parts = route.strip('/').split('/')
    if len(parts) < 3 or parts[:2] != ['v1', 'football']:
        return None
    rest = parts[2:]
    if rest == ['competitions']:
        return espn.competitions()
    if rest == ['search']:
        return espn.search(one(query, 'competition', ''), one(query, 'season', ''),
                           one(query, 'team', ''), one(query, 'phase', ''),
                           one(query, 'dateFrom'), one(query, 'dateTo'),
                           int(one(query, 'page', '0')), one(query, 'q', ''))
    if len(rest) == 3 and rest[0] == 'competitions' and rest[2] == 'seasons':
        return espn.seasons(rest[1])
    if rest == ['matches']:
        today = date.today().isoformat()
        codes = [c for c in (one(query, 'competitions', '') or '').split(',') if c]
        # The clubs travel beside the competitions so that the reading can be widened to the
        # ones they play in; without them a narrowed calendar would lose their cup nights.
        teams = [t for t in (one(query, 'teams', '') or '').split(',') if t]
        # Saying which compositions are out costs a reading per match, so it is asked for
        # rather than assumed: a client that will not show it should not pay for it.
        return espn.fixtures(one(query, 'dateFrom', today), one(query, 'dateTo', today),
                             espn.widen(codes, teams) or None, one(query, 'lineups', '') == '1')
    if len(rest) == 3 and rest[0] == 'competitions' and rest[2] == 'teams':
        found = espn.teams(rest[1])
        if found is None:
            raise LookupError('Compétition inconnue')
        return found
    if len(rest) == 3 and rest[0] == 'teams' and rest[1].isdigit() and rest[2] == 'matches':
        today = date.today()
        limit = one(query, 'limit', '100')
        return espn.team_fixtures(int(rest[1]),
                                  one(query, 'dateFrom', today.isoformat()),
                                  one(query, 'dateTo', (today + timedelta(days=365)).isoformat()),
                                  int(limit) if limit.isdigit() else 100)
    if len(rest) == 2 and rest[0] == 'matches' and rest[1].isdigit():
        found = (espn.match(rest[1], live=False) if one(query, 'overview', '') == '1'
                 else espn.match(rest[1]))
        if found is None:
            raise LookupError('Match inconnu')
        return found
    return None


def validate(op):
    if not isinstance(op, dict):
        raise ValueError('Objet attendu')
    UUID(op['id'])
    UUID(op['note_id'])
    kind = op['kind']
    # 'restore' undoes a 'delete'. Only an explicit restore does, so a deletion still wins
    # over a concurrent edit arriving from another device.
    # 'diagram' carries the schema of a tactical note. Written beside the note under the same
    # id, the way a comment is: a drawn note is a note that also happens to be drawn, and the
    # journal keeps one kind of note rather than two.
    if kind not in {'note', 'comment', 'delete', 'restore', 'diagram'}:
        raise ValueError('Type inconnu')
    allowed = {'id', 'note_id', 'kind'}
    if kind == 'note':
        match = json.loads(DEMO.read_text())
        # 'fd-' named a football-data fixture, which the server no longer serves. The journal
        # is immutable, so notes written then are still read back and still validate.
        remote_match = isinstance(op['match_id'], str) and re.fullmatch(r'(?:fd|espn)-[0-9]+', op['match_id'])
        if op['match_id'] != match['id'] and not remote_match:
            raise ValueError('Match inconnu')
        # A note on the match as a whole happens at no moment in particular: its minute is null.
        # An action happens at a minute, so this note credits nobody and the bilan never counts
        # it. A free note is the same written note pinned to a minute: both may be about one
        # club or name players — named, never credited.
        timeless = op['minute'] is None
        if not timeless and (type(op['minute']) is not int or not 0 <= op['minute'] <= 150):
            raise ValueError('Minute invalide')
        # A note names everyone involved in the same moment, each with their own action.
        # Notes written before that carried a single player at the top level; the log is
        # immutable, so both shapes stay valid and readers normalise them.
        legacy = 'entries' not in op
        allowed |= {'match_id', 'minute'} | ({'player_id', 'action'} if legacy else {'entries'})
        entries = [op] if legacy else op['entries']
        known = {p['id'] for p in match['players']}
        if not isinstance(entries, list) or len(entries) > len(known):
            raise ValueError('Participants invalides')
        if timeless and entries:
            raise ValueError('Minute requise pour une action')

        def player(identifier):
            # ESPN names no coach for football: the client seats one on each bench, known by the
            # ESPN id of his club, and he is noted like any player of that match.
            remote_player = isinstance(identifier, str) and re.fullmatch(r'(?:fd|espn)-[0-9]+|espn-coach-[0-9]+', identifier)
            if identifier not in known and not (remote_match and remote_player):
                raise ValueError('Joueur inconnu')

        for entry in entries:
            if not isinstance(entry, dict) or not {'player_id', 'action'} <= set(entry):
                raise ValueError('Participant invalide')
            # Where on the pitch the action took place, if the note says: a point on the board's
            # own frame, both coordinates or neither.
            placed = {'x', 'y'} <= set(entry)
            if not legacy and set(entry) != {'player_id', 'action'} | ({'x', 'y'} if placed else set()):
                raise ValueError('Participant invalide')
            if not legacy and placed:
                fraction(entry['x'])
                fraction(entry['y'])
            player(entry['player_id'])
            if entry['action'] not in ACTIONS:
                raise ValueError('Action inconnue')
        people = [entry['player_id'] for entry in entries]
        # Naming belongs to a note that credits nobody; a note of actions says who by its entries.
        if not entries:
            # Both may be left out: a note about neither club and nobody in particular.
            allowed |= {'team', 'players'} & set(op)
            if op.get('team') not in (None, 'home', 'away'):
                raise ValueError('Équipe inconnue')
            people = op.get('players', [])
            if not isinstance(people, list) or len(people) > len(known):
                raise ValueError('Joueurs invalides')
            # About one club, or about some players: a note naming both would say neither.
            if op.get('team') is not None and people:
                raise ValueError('Équipe ou joueurs, pas les deux')
            for identifier in people:
                player(identifier)
        if len(set(people)) != len(people):
            raise ValueError('Joueur en double')
    if kind == 'comment':
        allowed |= {'text'}
        if not isinstance(op['text'], str) or len(op['text']) > 2000:
            raise ValueError('Commentaire trop long ou invalide')
    if kind == 'diagram':
        allowed |= {'schema'}
        validate_schema(op['schema'])
    if set(op) != allowed:
        raise ValueError('Champs invalides')
    return op


def fraction(value):
    """A coordinate on the board: a fraction of the pitch, and nothing else."""
    if type(value) not in (int, float) or isinstance(value, bool) or not 0 <= value <= 1:
        raise ValueError('Coordonnée invalide')
    return value


def validate_keys(keys, ids, ball=False, version=2):
    if not isinstance(keys, list) or len(keys) > 120:
        raise ValueError('Positions clés invalides')
    previous = -1
    for key in keys:
        allowed = {'t', 'x', 'y', 'path', 'kind'} | ({'owner', 'flight'} if ball else set())
        if version == 3:
            allowed |= {'id', 'after', 'offset', 'baked'}
        if not isinstance(key, dict) or not {'t', 'x', 'y'} <= key.keys() or not key.keys() <= allowed:
            raise ValueError('Position clé invalide')
        if type(key['t']) is not int or not previous < key['t'] <= 1200:
            raise ValueError('Temps invalide')
        previous = key['t']
        if version == 3:
            if not isinstance(key.get('id'), str) or not 1 <= len(key['id']) <= 64:
                raise ValueError('Identifiant de position invalide')
            if ('after' in key) != ('offset' in key):
                raise ValueError('Lien temporel incomplet')
            if 'after' in key and (not isinstance(key['after'], str) or not 1 <= len(key['after']) <= 64
                                   or type(key['offset']) is not int or not -1200 <= key['offset'] <= 1200):
                raise ValueError('Lien temporel invalide')
            if 'baked' in key and type(key['baked']) is not bool:
                raise ValueError('Parcours invalide')
        fraction(key['x'])
        fraction(key['y'])
        if 'kind' in key and key['kind'] not in STROKES:
            raise ValueError('Trajet inconnu')
        if 'owner' in key and (not isinstance(key['owner'], str) or key['owner'] not in ids):
            raise ValueError('Porteur inconnu')
        if 'flight' in key and type(key['flight']) is not bool:
            raise ValueError('Trajet de ballon invalide')
        if 'path' in key:
            if not isinstance(key['path'], list) or not 2 <= len(key['path']) <= MAX_POINTS:
                raise ValueError('Trajet invalide')
            for point in key['path']:
                if not isinstance(point, list) or len(point) != 2:
                    raise ValueError('Point invalide')
                fraction(point[0])
                fraction(point[1])


def validate_schema(schema):
    """The geometry of a tactical note: who stands where, and the run of play between them.

    Deliberately geometry alone. A token names a player and stops there, so nothing here can
    disagree with the composition, the bilan or the journal about who he is or what he did.
    """
    version = schema.get('version') if isinstance(schema, dict) else None
    versioned = type(version) is int and version in (2, 3)
    expected = {'board', 'tokens', 'shapes', 'version', 'ball'} if versioned else {'board', 'tokens', 'shapes'}
    if version == 3:
        expected |= {'steps'}
    if not isinstance(schema, dict) or set(schema) != expected:
        raise ValueError('Schéma invalide')
    if schema['board'] not in {'blank', 'full'}:
        raise ValueError('Terrain inconnu')
    tokens, shapes = schema['tokens'], schema['shapes']
    if not isinstance(tokens, list) or len(tokens) > MAX_TOKENS:
        raise ValueError('Joueurs du schéma invalides')
    if not isinstance(shapes, list) or len(shapes) > MAX_SHAPES:
        raise ValueError('Tracés du schéma invalides')
    ids = set()
    if versioned:
        for token in tokens:
            if not isinstance(token, dict) or not isinstance(token.get('id'), str) or not 1 <= len(token['id']) <= 64 or token['id'] in ids:
                raise ValueError('Identifiant de pion invalide')
            ids.add(token['id'])
        validate_keys(schema['ball'], ids, ball=True, version=version)
    known = {p['id'] for p in json.loads(DEMO.read_text())['players']}
    for token in tokens:
        if versioned:
            validate_keys(token.get('keys'), ids, version=version)
            token = {k: v for k, v in token.items() if k not in {'id', 'keys'}}
        if not isinstance(token, dict) or not {'x', 'y'} <= set(token):
            raise ValueError('Joueur du schéma invalide')
        fraction(token['x'])
        fraction(token['y'])
        if 'player_id' in token:
            # A named token belongs to this match; a pawn belongs to nobody and says so.
            if set(token) != {'player_id', 'x', 'y'}:
                raise ValueError('Joueur du schéma invalide')
            remote = isinstance(token['player_id'], str) and re.fullmatch(r'(?:fd|espn)-[0-9]+', token['player_id'])
            if token['player_id'] not in known and not remote:
                raise ValueError('Joueur inconnu')
        else:
            if not set(token) <= {'team', 'label', 'x', 'y'} or 'team' not in token:
                raise ValueError('Joueur du schéma invalide')
            if token['team'] not in {'home', 'away', 'neutral'}:
                raise ValueError('Équipe inconnue')
            if not isinstance(token.get('label', ''), str) or len(token.get('label', '')) > 3:
                raise ValueError('Étiquette invalide')
    for shape in shapes:
        if not isinstance(shape, dict) or set(shape) != {'kind', 'points'}:
            raise ValueError('Tracé invalide')
        if shape['kind'] not in STROKES:
            raise ValueError('Tracé inconnu')
        points = shape['points']
        if not isinstance(points, list) or not 2 <= len(points) <= MAX_POINTS:
            raise ValueError('Tracé invalide')
        for point in points:
            if not isinstance(point, list) or len(point) != 2:
                raise ValueError('Point invalide')
            fraction(point[0])
            fraction(point[1])

    if version == 3:
        validate_sequence(schema)


def validate_sequence(schema):
    """Unique references, times already resolved and no cycles, without recursion."""
    keys = {}
    for track in [schema['ball']] + [token['keys'] for token in schema['tokens']]:
        for key in track:
            if key['id'] in keys:
                raise ValueError('Identifiant de position en double')
            keys[key['id']] = key
    done = set()
    for identifier in keys:
        chain = set()
        current = identifier
        while current not in done:
            if current in chain:
                raise ValueError('Boucle dans les liens temporels')
            chain.add(current)
            key = keys[current]
            if 'after' not in key:
                break
            parent = keys.get(key['after'])
            if parent is None or key['t'] != parent['t'] + key['offset']:
                raise ValueError('Lien temporel incohérent')
            current = key['after']
        done.update(chain)
    steps = schema['steps']
    if not isinstance(steps, list) or len(steps) > 120:
        raise ValueError('Étapes invalides')
    ids = set()
    for step in steps:
        if not isinstance(step, dict) or set(step) != {'id', 'name', 't'}:
            raise ValueError('Étape invalide')
        if not isinstance(step['id'], str) or not 1 <= len(step['id']) <= 64 or step['id'] in ids:
            raise ValueError('Identifiant d’étape invalide')
        ids.add(step['id'])
        if not isinstance(step['name'], str) or not 1 <= len(step['name']) <= 80:
            raise ValueError('Nom d’étape invalide')
        if type(step['t']) is not int or not 0 <= step['t'] <= 1200:
            raise ValueError('Temps d’étape invalide')


@contextmanager
def connect(path):
    db = sqlite3.connect(path, timeout=10)
    db.execute('PRAGMA journal_mode=WAL')
    db.execute('CREATE TABLE IF NOT EXISTS operations '
               '(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, payload TEXT NOT NULL)')
    try:
        yield db
    finally:
        db.close()


def append(db, op):
    payload = json.dumps(validate(op), sort_keys=True, ensure_ascii=False)
    with db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('SELECT seq, payload FROM operations WHERE id=?', (op['id'],)).fetchone()
        if row:
            if row[1] != payload:
                raise ValueError('Identifiant déjà utilisé avec un autre contenu')
            return row[0]
        return db.execute('INSERT INTO operations(id,payload) VALUES (?,?)',
                          (op['id'], payload)).lastrowid


def make_server(host, port, path, token, apk=None):
    with connect(path):
        pass
    espn = FootballData(path)
    release = Release(apk)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass  # Never log tokens or note contents.

        def log_request(self, code='-', size='-'):
            # One line per request: method, path without the query, status, duration. Enough
            # to see the server alive, nothing of what the device sends. The Docker healthcheck
            # comes by every 30 s; logging it would be noise.
            route = urllib.parse.urlparse(self.path).path if hasattr(self, 'path') else '-'
            if route == '/v1/health':
                return
            started = getattr(self, 'started', None)
            elapsed = f' {(time.monotonic() - started) * 1000:.0f} ms' if started else ''
            log.info('%s %s %s%s', self.command or '-', route, code, elapsed)

        def reply(self, code, data):
            raw = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def send_apk(self, latest):
            # Public like the football routes: an APK carries no secret, and a phone that only
            # follows matches, with no token, needs its updates too. Android installs it only
            # over an app signed with the same key.
            try:
                source = latest['file'].open('rb') if latest else None
            except OSError:
                source = None
            if source is None:
                return self.reply(404, {'error': 'Aucune version publiée'})
            with source:
                size = os.fstat(source.fileno()).st_size
                self.send_response(200)
                self.send_header('Content-Type', 'application/vnd.android.package-archive')
                self.send_header('Content-Length', str(size))
                self.end_headers()
                while chunk := source.read(1 << 16):
                    self.wfile.write(chunk)

        def authorized(self):
            if not token or not hmac.compare_digest(self.headers.get('Authorization', '').encode(), ('Bearer ' + token).encode()):
                self.reply(401, {'error': 'Authentification requise'})
                return False
            return True

        def do_GET(self):
            self.started = time.monotonic()
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == '/v1/health':
                # The feed behind the football routes is public: there is no key to leave out,
                # and the field stays so that an older client keeps recognising this server.
                return self.reply(200, {'service': 'fonote', 'football_configured': True,
                                        'app': {'minimum': MIN_APP,
                                                'latest': described(release.latest())}})
            if parsed.path == '/v1/app/fonote.apk':
                return self.send_apk(release.latest())
            try:
                data = football(espn, parsed.path, urllib.parse.parse_qs(parsed.query))
                if data is not None:
                    return self.reply(200, data)
            except LookupError:
                return self.reply(404, {'error': 'Donnée football inconnue'})
            except ValueError:
                return self.reply(400, {'error': 'Paramètres football invalides'})
            except (RuntimeError, OSError, KeyError, TypeError, AttributeError):
                return self.reply(503, {'error': 'Données football indisponibles'})
            if not self.authorized():
                return
            if parsed.path == '/v1/matches':
                return self.reply(200, [json.loads(DEMO.read_text())])
            if parsed.path == '/v1/operations':
                with connect(path) as db:
                    rows = db.execute('SELECT seq,payload FROM operations ORDER BY seq').fetchall()
                return self.reply(200, [{'seq': s, 'operation': json.loads(p)} for s, p in rows])
            self.reply(404, {'error': 'Route inconnue'})

        def do_PUT(self):
            if not self.authorized():
                return
            route = urllib.parse.urlparse(self.path).path
            prefix = '/v1/football/follows/'
            if not route.startswith(prefix):
                return self.reply(404, {'error': 'Route inconnue'})
            try:
                device = str(UUID(route[len(prefix):]))
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 65536:
                    return self.reply(413, {'error': 'Taille de requête invalide'})
                return self.reply(200, espn.set_follows(device, json.loads(self.rfile.read(length))))
            except (ValueError, TypeError, LookupError):
                return self.reply(400, {'error': 'Suivis invalides'})

        def do_POST(self):
            self.started = time.monotonic()
            if not self.authorized():
                return
            refresh = re.fullmatch(r'/v1/football/competitions/([A-Z0-9]+)/seasons/(\d{4})/refresh', self.path)
            detail = re.fullmatch(r'/v1/football/matches/(\d+)/refresh', self.path)
            if refresh or detail:
                try:
                    data = (espn.refresh_archive(*refresh.groups()) if refresh else
                            espn.match(detail.group(1), refresh=True))
                    return self.reply(200 if data else 404, data or {'error': 'Match inconnu'})
                except LookupError:
                    return self.reply(404, {'error': 'Compétition inconnue'})
                except ValueError:
                    return self.reply(400, {'error': 'Paramètres invalides'})
                except OSError:
                    return self.reply(503, {'error': 'Fournisseur indisponible'})
            if self.path != '/v1/operations':
                return self.reply(404, {'error': 'Route inconnue'})
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 4 * 1024 * 1024:
                    return self.reply(413, {'error': 'Taille de requête invalide'})
                op = json.loads(self.rfile.read(length))
                with connect(path) as db:
                    seq = append(db, op)
                self.reply(200, {'seq': seq, 'operation': op})
            except (ValueError, KeyError, TypeError, AttributeError):
                self.reply(400, {'error': 'Opération invalide ou identifiant réutilisé'})

    class FootballServer(ThreadingHTTPServer):
        def server_close(self):
            espn.close()
            super().server_close()

    try:
        return FootballServer((host, port), Handler)
    except Exception:
        espn.close()
        raise


def sources(folder):
    """Modification times of the server's own modules.

    A file being written is skipped rather than reported missing: an editor that truncates
    before it writes would otherwise look like a change, and then like a change back.
    """
    marks = {}
    for path in sorted([*Path(folder).glob('*.py'), *Path(folder).glob('*.json')]):
        try:
            marks[path.name] = path.stat().st_mtime
        except OSError:
            continue
    return marks


def supervise(interval=1.0):
    """Run the server in a child process, restarted whenever its own sources change.

    The watching process never imports what it watches, so a half-typed edit costs only the
    child: nothing is listening until the next save, and that save brings a working server
    back. Reloading on purpose drops the ESPN memory cache and re-reads the settings, which
    is the point of reloading.
    """
    here = Path(__file__).resolve().parent
    command = [sys.executable, *sys.argv]
    environment = {**os.environ, 'FONOTE_RELOAD_CHILD': '1'}
    child, seen = subprocess.Popen(command, env=environment), sources(here)
    try:
        while True:
            time.sleep(interval)
            current = sources(here)
            if current == seen:
                continue
            seen = current
            if child.poll() is None:
                child.terminate()
                child.wait()
            print('Sources modifiées : redémarrage du serveur')
            child = subprocess.Popen(command, env=environment)
    except KeyboardInterrupt:
        pass
    finally:
        if child.poll() is None:
            child.terminate()
            child.wait()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--db', default='fonote.sqlite3')
    parser.add_argument('--apk-dir',
                        help='dossier de la version Android à distribuer (APK et output-metadata.json)')
    parser.add_argument('--reload', action='store_true',
                        help='redémarrer le serveur à chaque modification de backend/*.py')
    args = parser.parse_args()
    if args.reload and not os.environ.get('FONOTE_RELOAD_CHILD'):
        supervise()
        raise SystemExit
    try:
        load_env()
    except ValueError as error:
        parser.error(str(error))
    token = os.environ.get('FONOTE_TOKEN', '')
    if token and (len(token) < 24 or not token.isascii()):
        parser.error('Définir FONOTE_TOKEN avec au moins 24 caractères ASCII aléatoires')
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(message)s',
                        datefmt='%Y-%m-%d %H:%M:%S')
    server = make_server(args.host, args.port, args.db, token, args.apk_dir)
    print(f'Fonote : http://{args.host}:{args.port} (usage personnel, arrêter avec Ctrl+C)')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()
