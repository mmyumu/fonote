import json
from pathlib import Path
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from backend.server import DEMO, connect, make_server

MATCH = json.loads(DEMO.read_text())
PLAYERS = [p['id'] for p in MATCH['players']]


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'notes.sqlite3'
        self.token = 'a-personal-test-token-123456'
        self.server = make_server('127.0.0.1', 0, self.path, self.token)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.base = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.temp.cleanup()

    def request(self, op=None, token=None, path='/v1/operations'):
        req = Request(self.base + path,
                      data=json.dumps(op).encode() if op is not None else None,
                      headers={'Authorization': 'Bearer ' + (self.token if token is None else token)})
        with urlopen(req, timeout=3) as response:
            return json.load(response)

    def note(self, **fields):
        """A note in the current shape: one moment, one entry per player involved."""
        op = dict(id=str(uuid4()), note_id=str(uuid4()), kind='note',
                  match_id=MATCH['id'], minute=42,
                  entries=[dict(player_id=PLAYERS[0], action='goal')])
        op.update(fields)
        return op

    def legacy_note(self):
        """The single-player shape written before notes could name several players."""
        return dict(id=str(uuid4()), note_id=str(uuid4()), kind='note',
                    match_id=MATCH['id'], minute=42, player_id=PLAYERS[0], action='goal')

    def test_retry_is_idempotent_and_survives_reopen(self):
        op = self.note()
        self.assertEqual(self.request(op), self.request(op))
        with connect(self.path) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM operations').fetchone()[0], 1)
        self.assertEqual(self.request()[0]['operation'], op)

    def test_same_id_cannot_overwrite(self):
        op = self.note()
        self.request(op)
        op['minute'] = 43
        with self.assertRaises(HTTPError) as error:
            self.request(op)
        self.assertEqual(error.exception.code, 400)
        self.assertEqual(self.request()[0]['operation']['minute'], 42)

    def test_concurrent_retries_create_one_operation(self):
        op = self.note()
        with ThreadPoolExecutor(max_workers=4) as pool:
            responses = list(pool.map(lambda _: self.request(op), range(8)))
        self.assertTrue(all(r['seq'] == responses[0]['seq'] for r in responses))
        self.assertEqual(len(self.request()), 1)

    def test_authentication(self):
        for path in ['/v1/matches', '/v1/operations']:
            with self.assertRaises(HTTPError) as error:
                self.request(token='wrong', path=path)
            self.assertEqual(error.exception.code, 401)
        with self.assertRaises(HTTPError) as error:
            self.request(self.note(), token='wrong')
        self.assertEqual(error.exception.code, 401)

    def test_invalid_input_never_persists(self):
        rejected = [
            ('minute', True), ('minute', 151), ('id', 'bad'), ('match_id', 'unknown'),
            ('entries', [dict(player_id='unknown', action='goal')]),
            ('entries', [dict(player_id=PLAYERS[0], action='unknown')]),
            ('entries', [dict(player_id=PLAYERS[0])]),
            ('entries', [dict(player_id=PLAYERS[0], action='goal', extra=1)]),
            ('entries', [dict(player_id=PLAYERS[0], action='goal')] * 2),
            ('entries', dict(player_id=PLAYERS[0], action='goal')),
            ('player_id', PLAYERS[1]),
        ]
        for field, value in rejected:
            with self.assertRaises(HTTPError) as error:
                self.request(self.note(**{field: value}))
            self.assertEqual(error.exception.code, 400, f'{field}={value}')
        for field, value in [('player_id', 'unknown'), ('action', 'unknown'), ('minute', 151)]:
            op = self.legacy_note()
            op[field] = value
            with self.assertRaises(HTTPError) as error:
                self.request(op)
            self.assertEqual(error.exception.code, 400, f'{field}={value}')
        self.assertEqual(self.request(), [])

    def test_note_names_several_players_with_their_own_actions(self):
        op = self.note(entries=[dict(player_id=PLAYERS[0], action='goal'),
                                dict(player_id=PLAYERS[1], action='assist'),
                                dict(player_id=PLAYERS[2], action='negative')])
        self.request(op)
        self.assertEqual(self.request()[0]['operation'], op)

    def test_note_without_player_is_a_general_note(self):
        op = self.note(entries=[])
        comment = dict(id=str(uuid4()), note_id=op['note_id'], kind='comment', text='Gros pressing')
        self.request(op)
        self.request(comment)
        self.assertEqual([r['operation'] for r in self.request()], [op, comment])

    def test_single_player_notes_written_before_stay_valid(self):
        op = self.legacy_note()
        self.request(op)
        self.assertEqual(self.request()[0]['operation'], op)

    def test_delete_can_be_undone_by_a_restore(self):
        note = self.note()
        delete = dict(id=str(uuid4()), note_id=note['note_id'], kind='delete')
        restore = dict(id=str(uuid4()), note_id=note['note_id'], kind='restore')
        for op in [note, delete, restore]:
            self.request(op)
        self.assertEqual([r['operation'] for r in self.request()], [note, delete, restore])
        with self.assertRaises(HTTPError) as error:
            self.request(dict(id=str(uuid4()), note_id=note['note_id'], kind='restore', text='x'))
        self.assertEqual(error.exception.code, 400)

    def test_comment_delete_and_retry_keep_order(self):
        note = self.note()
        comment = dict(id=str(uuid4()), note_id=note['note_id'], kind='comment', text='Belle frappe !')
        delete = dict(id=str(uuid4()), note_id=note['note_id'], kind='delete')
        for op in [note, comment, delete, note, delete]:
            self.request(op)
        rows = self.request()
        self.assertEqual([r['operation'] for r in rows], [note, comment, delete])
        self.assertEqual([r['seq'] for r in rows], [1, 2, 3])

    def test_demo_contains_two_elevens(self):
        matches = self.request(path='/v1/matches')
        self.assertEqual(matches[0]['id'], MATCH['id'])
        self.assertEqual(len(matches[0]['players']), 22)
        self.assertEqual({p['team'] for p in matches[0]['players']}, {'home', 'away'})


if __name__ == '__main__':
    unittest.main()
