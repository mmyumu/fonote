"""Stockage football indépendant du journal des annotations."""
import json
import sqlite3
import threading
import time
from contextlib import contextmanager


class FootballStore:
    def __init__(self, path):
        self.path = str(path)
        self.lock = threading.RLock()
        with self.connect() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS football_responses (
                    url TEXT PRIMARY KEY, payload TEXT NOT NULL, fetched REAL NOT NULL, expires REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS football_matches (
                    id TEXT PRIMARY KEY, code TEXT, season INTEGER, date TEXT, payload TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS football_search ON football_matches(code, season, date, id);
                CREATE TABLE IF NOT EXISTS football_details (
                    id TEXT PRIMARY KEY, payload TEXT NOT NULL, fetched REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS football_jobs (
                    key TEXT PRIMARY KEY, kind TEXT, payload TEXT, priority INTEGER,
                    state TEXT, updated REAL, due REAL, attempts INTEGER DEFAULT 0, error TEXT DEFAULT '');
                CREATE TABLE IF NOT EXISTS football_meta (key TEXT PRIMARY KEY, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS football_follows (device TEXT PRIMARY KEY, payload TEXT NOT NULL);
            ''')
            db.execute("UPDATE football_jobs SET state='pending' WHERE state='running'")

    @contextmanager
    def connect(self):
        with self.lock:
            db = sqlite3.connect(self.path, timeout=10)
            db.row_factory = sqlite3.Row
            try:
                with db:
                    yield db
            finally:
                db.close()

    def meta(self, key, default=None):
        with self.connect() as db:
            row = db.execute('SELECT payload FROM football_meta WHERE key=?', (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def put_meta(self, key, value):
        with self.connect() as db:
            db.execute('INSERT OR REPLACE INTO football_meta VALUES (?,?)', (key, json.dumps(value)))

    def response(self, url):
        with self.connect() as db:
            row = db.execute('SELECT * FROM football_responses WHERE url=?', (url,)).fetchone()
        return dict(row) if row else None

    def keep_response(self, url, data, ttl):
        now = time.time()
        with self.connect() as db:
            db.execute('INSERT OR REPLACE INTO football_responses VALUES (?,?,?,?)',
                       (url, json.dumps(data), now, now + ttl))

    def matches(self, matches):
        with self.connect() as db:
            for item in matches:
                if not item.get('id'):
                    continue
                old = db.execute('SELECT payload FROM football_matches WHERE id=?', (str(item['id']),)).fetchone()
                previous = json.loads(old[0]) if old else {}
                if previous.get('lineup_status') == 'available':
                    item = dict(item, lineup_status='available')
                db.execute('INSERT OR REPLACE INTO football_matches VALUES (?,?,?,?,?)',
                           (str(item['id']), item.get('competition', {}).get('code'), item.get('season'),
                            item.get('utcDate'), json.dumps(item)))

    def known(self, codes=None, season=None, start=None, end=None):
        where, args = [], []
        if codes:
            where.append('code IN (' + ','.join('?' for _ in codes) + ')'); args.extend(codes)
        if season is not None:
            where.append('season=?'); args.append(season)
        if start:
            where.append('substr(date,1,10)>=?'); args.append(start)
        if end:
            where.append('substr(date,1,10)<=?'); args.append(end)
        with self.connect() as db:
            rows = db.execute('SELECT payload FROM football_matches' +
                              (' WHERE ' + ' AND '.join(where) if where else '') + ' ORDER BY date,id', args)
            return [json.loads(r[0]) for r in rows]

    def detail(self, identifier):
        with self.connect() as db:
            row = db.execute('SELECT * FROM football_details WHERE id=?', (str(identifier),)).fetchone()
        return (json.loads(row['payload']), row['fetched']) if row else (None, 0)

    def keep_detail(self, item):
        previous, _ = self.detail(item['id'])
        if previous and previous.get('lineup_status') == 'available' and item.get('lineup_status') != 'available':
            # Actualiser le score et le statut sans perdre les compositions déjà connues.
            for side in ('homeTeam', 'awayTeam'):
                item[side] = dict(previous.get(side, {}), **item.get(side, {}))
            item['lineup_status'] = 'available'
        with self.connect() as db:
            db.execute('INSERT OR REPLACE INTO football_details VALUES (?,?,?)',
                       (str(item['id']), json.dumps(item), time.time()))
        self.matches([item])
        return item

    def job(self, key):
        with self.connect() as db:
            row = db.execute('SELECT * FROM football_jobs WHERE key=?', (key,)).fetchone()
        return dict(row) if row else None

    def enqueue(self, key, kind, payload, priority=10, ttl=None):
        now = time.time()
        with self.connect() as db:
            old = db.execute('SELECT * FROM football_jobs WHERE key=?', (key,)).fetchone()
            if old:
                db.execute('UPDATE football_jobs SET priority=min(priority,?) WHERE key=?', (priority, key))
                if old['state'] in ('pending', 'running', 'error', 'partial'):
                    return
                if ttl is None or now - old['updated'] < ttl:
                    return
            db.execute("INSERT OR REPLACE INTO football_jobs VALUES (?,?,?,?, 'pending',?,?,0,'')",
                       (key, kind, json.dumps(payload), priority, now, now))

    def claim(self):
        with self.connect() as db:
            row = db.execute("SELECT * FROM football_jobs WHERE state IN ('pending','error') AND due<=? "
                             'ORDER BY priority,updated LIMIT 1', (time.time(),)).fetchone()
            if row:
                db.execute("UPDATE football_jobs SET state='running' WHERE key=?", (row['key'],))
        return dict(row) if row else None

    def finish(self, job, error=None, partial=False):
        attempts = job['attempts'] + 1 if error else 0
        delay = min(3600, 30 * 2 ** min(attempts, 7))
        with self.connect() as db:
            db.execute('UPDATE football_jobs SET state=?,updated=?,due=?,attempts=?,error=? WHERE key=?',
                       ('error' if error else 'partial' if partial else 'done', time.time(),
                        time.time() + delay, attempts, str(error or '')[:300], job['key']))
