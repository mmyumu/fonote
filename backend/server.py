"""Fonote personal server. Python 3.12+, no external dependencies."""
import argparse
import hmac
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import UUID

if __package__:
    from .espn import Espn
else:
    from espn import Espn

DEMO = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/match.json'
# Each action carries its own polarity; the client derives colour and balance from it,
# so 'positive' and 'negative' are the catch-all members of each side, not the axis itself.
ACTIONS = {'positive', 'goal', 'assist', 'pass', 'dribble', 'shot_on', 'defense', 'save',
           'negative', 'own_goal', 'lost_ball', 'pass_missed', 'dribble_lost', 'shot_off',
           'duel_lost', 'save_missed', 'yellow', 'red'}
# What a stroke on a tactical schema can mean. Meaning is carried by the shape of the line the
# client draws — solid, dashed, waved, doubled — never by a colour, which already names a team.
STROKES = {'pass', 'run', 'carry', 'shot'}
# Bounds a hand-drawn schema stays well inside; anything past them is a client gone wrong,
# not a moment of football. Twenty-two players is the whole pitch.
MAX_TOKENS, MAX_SHAPES, MAX_POINTS = 30, 40, 32


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


def football_data(path, token):
    """Small, deliberately transparent proxy so the provider key never ships in the APK."""
    if not token:
        raise RuntimeError('FOOTBALL_DATA_TOKEN absent')
    request = urllib.request.Request('https://api.football-data.org/v4/' + path.lstrip('/'),
                                     headers={'X-Auth-Token': token, 'User-Agent': 'Fonote/1'})
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        # Do not pass provider bodies through: they can change and are not part of our contract.
        raise RuntimeError(f'football-data.org: HTTP {error.code}') from error


def football_path(route, query):
    """Map the narrow public Fonote contract to the upstream API."""
    if route == '/v1/football/competitions':
        return 'competitions'
    if route == '/v1/football/matches':
        allowed = {key: values[-1] for key, values in query.items()
                   if key in {'dateFrom', 'dateTo', 'competitions'} and values}
        return 'matches' + (('?' + urllib.parse.urlencode(allowed)) if allowed else '')
    parts = route.strip('/').split('/')
    if len(parts) == 5 and parts[:3] == ['v1', 'football', 'competitions'] and parts[4] == 'teams':
        return 'competitions/' + urllib.parse.quote(parts[3], safe='') + '/teams'
    if len(parts) == 4 and parts[:3] == ['v1', 'football', 'matches'] and parts[3].isdigit():
        return 'matches/' + parts[3]
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
        remote_match = isinstance(op['match_id'], str) and re.fullmatch(r'fd-[0-9]+', op['match_id'])
        if op['match_id'] != match['id'] and not remote_match:
            raise ValueError('Match inconnu')
        if type(op['minute']) is not int or not 0 <= op['minute'] <= 150:
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
        for entry in entries:
            if not isinstance(entry, dict) or not {'player_id', 'action'} <= set(entry):
                raise ValueError('Participant invalide')
            if not legacy and set(entry) != {'player_id', 'action'}:
                raise ValueError('Participant invalide')
            remote_player = isinstance(entry['player_id'], str) and re.fullmatch(r'(?:fd|espn)-[0-9]+', entry['player_id'])
            if entry['player_id'] not in known and not (remote_match and remote_player):
                raise ValueError('Joueur inconnu')
            if entry['action'] not in ACTIONS:
                raise ValueError('Action inconnue')
        people = [entry['player_id'] for entry in entries]
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


def validate_schema(schema):
    """The geometry of a tactical note: who stands where, and the run of play between them.

    Deliberately geometry alone. A token names a player and stops there, so nothing here can
    disagree with the composition, the bilan or the journal about who he is or what he did.
    """
    if not isinstance(schema, dict) or set(schema) != {'board', 'tokens', 'shapes'}:
        raise ValueError('Schéma invalide')
    if schema['board'] not in {'blank', 'full'}:
        raise ValueError('Terrain inconnu')
    tokens, shapes = schema['tokens'], schema['shapes']
    if not isinstance(tokens, list) or len(tokens) > MAX_TOKENS:
        raise ValueError('Joueurs du schéma invalides')
    if not isinstance(shapes, list) or len(shapes) > MAX_SHAPES:
        raise ValueError('Tracés du schéma invalides')
    known = {p['id'] for p in json.loads(DEMO.read_text())['players']}
    for token in tokens:
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


def make_server(host, port, path, token, football_token=None):
    espn = Espn()
    with connect(path):
        pass

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass  # Never log tokens or note contents.

        def reply(self, code, data):
            raw = json.dumps(data, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def authorized(self):
            if not token or not hmac.compare_digest(self.headers.get('Authorization', '').encode(), ('Bearer ' + token).encode()):
                self.reply(401, {'error': 'Authentification requise'})
                return False
            return True

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == '/v1/health':
                return self.reply(200, {'service': 'fonote', 'football_configured': bool(football_token)})
            upstream = football_path(parsed.path, urllib.parse.parse_qs(parsed.query))
            if upstream:
                try:
                    data = football_data(upstream, football_token)
                    if re.fullmatch(r'matches/[0-9]+', upstream):
                        data = espn.enrich(data)
                    return self.reply(200, data)
                except (RuntimeError, OSError):
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

        def do_POST(self):
            if not self.authorized():
                return
            if self.path != '/v1/operations':
                return self.reply(404, {'error': 'Route inconnue'})
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 16384:
                    return self.reply(413, {'error': 'Taille de requête invalide'})
                op = json.loads(self.rfile.read(length))
                with connect(path) as db:
                    seq = append(db, op)
                self.reply(200, {'seq': seq, 'operation': op})
            except (ValueError, KeyError, TypeError, AttributeError):
                self.reply(400, {'error': 'Opération invalide ou identifiant réutilisé'})

    return ThreadingHTTPServer((host, port), Handler)


def sources(folder):
    """Modification times of the server's own modules.

    A file being written is skipped rather than reported missing: an editor that truncates
    before it writes would otherwise look like a change, and then like a change back.
    """
    marks = {}
    for path in sorted(Path(folder).glob('*.py')):
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
    server = make_server(args.host, args.port, args.db, token, os.environ.get('FOOTBALL_DATA_TOKEN'))
    print(f'Fonote : http://{args.host}:{args.port} (usage personnel, arrêter avec Ctrl+C)')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()
