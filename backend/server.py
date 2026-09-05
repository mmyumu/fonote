"""Fonote personal server. Python 3.12+, no external dependencies."""
import argparse
import hmac
import json
import os
import sqlite3
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import UUID

DEMO = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/match.json'
# Each action carries its own polarity; the client derives colour and balance from it,
# so 'positive' and 'negative' are the catch-all members of each side, not the axis itself.
ACTIONS = {'positive', 'goal', 'assist', 'pass', 'dribble', 'shot_on', 'defense', 'save',
           'negative', 'own_goal', 'lost_ball', 'pass_missed', 'dribble_lost', 'shot_off',
           'duel_lost', 'save_missed', 'yellow', 'red'}


def validate(op):
    if not isinstance(op, dict):
        raise ValueError('Objet attendu')
    UUID(op['id'])
    UUID(op['note_id'])
    kind = op['kind']
    # 'restore' undoes a 'delete'. Only an explicit restore does, so a deletion still wins
    # over a concurrent edit arriving from another device.
    if kind not in {'note', 'comment', 'delete', 'restore'}:
        raise ValueError('Type inconnu')
    allowed = {'id', 'note_id', 'kind'}
    if kind == 'note':
        match = json.loads(DEMO.read_text())
        if op['match_id'] != match['id']:
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
            if entry['player_id'] not in known:
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
    if set(op) != allowed:
        raise ValueError('Champs invalides')
    return op


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


def make_server(host, port, path, token):
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
            if not hmac.compare_digest(self.headers.get('Authorization', '').encode(), ('Bearer ' + token).encode()):
                self.reply(401, {'error': 'Authentification requise'})
                return False
            return True

        def do_GET(self):
            if not self.authorized():
                return
            if self.path == '/v1/matches':
                return self.reply(200, [json.loads(DEMO.read_text())])
            if self.path == '/v1/operations':
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


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--db', default='fonote.sqlite3')
    args = parser.parse_args()
    token = os.environ.get('FONOTE_TOKEN', '')
    if len(token) < 24 or not token.isascii():
        parser.error('Définir FONOTE_TOKEN avec au moins 24 caractères ASCII aléatoires')
    server = make_server(args.host, args.port, args.db, token)
    print(f'Fonote : http://{args.host}:{args.port} (usage personnel, arrêter avec Ctrl+C)')
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()
