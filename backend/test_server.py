import json
from pathlib import Path
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from backend.server import DEMO, MIN_APP, connect, football, make_server, sources

MATCH = json.loads(DEMO.read_text())
PLAYERS = [p['id'] for p in MATCH['players']]


class ReleaseTest(unittest.TestCase):
    """The APK a server hands out, as the Android build leaves it: the file and its metadata."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name) / 'apk'
        self.folder.mkdir()
        self.server = make_server('127.0.0.1', 0, Path(self.temp.name) / 'notes.sqlite3',
                                  'a-personal-test-token-123456', self.folder)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.base = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.temp.cleanup()

    def publish(self, content, code=3, name='1.2.0', package='fr.fonote', output=None):
        file = f'fonote-{name}.apk'
        (self.folder / file).write_bytes(content)
        (self.folder / 'output-metadata.json').write_text(json.dumps({
            'version': 3, 'applicationId': package, 'variantName': 'release',
            'elements': [{'type': 'SINGLE', 'versionCode': code, 'versionName': name,
                          'outputFile': output or file}]}))

    def latest(self):
        with urlopen(self.base + '/v1/health', timeout=3) as response:
            return json.load(response)['app']['latest']

    def test_a_published_apk_is_described_and_served_without_a_token(self):
        import hashlib
        content = b'PK' + bytes(range(256)) * 400
        self.publish(content)
        self.assertEqual(self.latest(), {'code': 3, 'name': '1.2.0', 'size': len(content),
                                         'sha256': hashlib.sha256(content).hexdigest()})
        with urlopen(self.base + '/v1/app/fonote.apk', timeout=3) as response:
            self.assertEqual(response.headers['Content-Type'], 'application/vnd.android.package-archive')
            self.assertEqual(response.read(), content)

    def test_a_new_version_is_seen_without_a_restart(self):
        self.publish(b'first', code=3, name='1.2.0')
        self.assertEqual(self.latest()['code'], 3)
        (self.folder / 'fonote-1.2.0.apk').unlink()
        self.publish(b'second', code=4, name='1.3.0')
        import hashlib
        self.assertEqual(self.latest()['sha256'], hashlib.sha256(b'second').hexdigest())

    def test_nothing_is_offered_from_an_incomplete_or_foreign_folder(self):
        cases = [dict(package='org.other'), dict(output='../notes.sqlite3'), dict(code='three')]
        for case in cases:
            with self.subTest(case=case):
                self.publish(b'apk', **case)
                self.assertIsNone(self.latest())
                with self.assertRaises(HTTPError) as error:
                    urlopen(self.base + '/v1/app/fonote.apk', timeout=3)
                self.assertEqual(error.exception.code, 404)
        # Metadata copied before its APK: the version is not there yet.
        for path in self.folder.iterdir():
            path.unlink()
        self.publish(b'apk', output='fonote-9.9.9.apk')
        self.assertIsNone(self.latest())


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

    def test_health_is_public_without_exposing_credentials(self):
        self.assertEqual(self.request(token='wrong', path='/v1/health'),
                         {'service': 'fonote', 'football_configured': True,
                          'app': {'minimum': MIN_APP, 'latest': None}})
        with self.assertRaises(HTTPError) as error:
            urlopen(self.base + '/v1/app/fonote.apk', timeout=3)
        self.assertEqual(error.exception.code, 404)

    def test_calendar_does_not_require_personal_token(self):
        from unittest.mock import patch
        with patch('backend.football_data.FootballData.competitions', return_value={'competitions': []}):
            self.assertEqual(self.request(token='wrong', path='/v1/football/competitions'),
                             {'competitions': []})

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

    def test_football_routes_are_narrowly_mapped(self):
        """Only the contract's own routes reach the feed, and each with only its own arguments."""
        asked = []

        class Adapter:
            def competitions(self):
                return 'catalogue'

            def fixtures(self, date_from, date_to, codes=None, lineups=False):
                asked.append(('fixtures', date_from, date_to, codes, lineups))
                return 'calendrier'

            def teams(self, code):
                return {'teams': []} if code == 'FL1' else None

            def team_fixtures(self, team, date_from, date_to, limit=100):
                asked.append(('club', team, date_from, date_to, limit))
                return 'club'

            def match(self, identifier):
                return {'id': int(identifier)} if identifier == '123' else None

        adapter = Adapter()
        self.assertEqual(football(adapter, '/v1/football/competitions', {}), 'catalogue')
        self.assertEqual(football(adapter, '/v1/football/matches', {
            'dateFrom': ['2026-09-05'], 'dateTo': ['2026-09-06'],
            'competitions': ['FL1,PL'], 'ignored': ['secret']}), 'calendrier')
        self.assertEqual(asked[-1], ('fixtures', '2026-09-05', '2026-09-06', ['FL1', 'PL'], False))
        # Announcing which compositions are out costs a reading per match, so it is asked for.
        football(adapter, '/v1/football/matches', {'lineups': ['1']})
        self.assertTrue(asked[-1][4])
        football(adapter, '/v1/football/matches', {'lineups': ['oui']})
        self.assertFalse(asked[-1][4])
        self.assertEqual(football(adapter, '/v1/football/matches/123', {}), {'id': 123})
        self.assertEqual(football(adapter, '/v1/football/competitions/FL1/teams', {}), {'teams': []})

        # Anything the feed does not know is said to be unknown, never served as something else.
        for route in ['/v1/football/matches/999', '/v1/football/competitions/XX/teams']:
            with self.assertRaises(LookupError):
                football(adapter, route, {})
        # Anything outside the contract is not a football route at all.
        for route in ['/v1/football/matches/not-a-number', '/v1/football/teams/66/squad',
                      '/v1/football/teams/not-a-number/matches', '/v1/operations', '/v1/health']:
            self.assertIsNone(football(adapter, route, {}))

    def test_followed_club_calendar_route(self):
        from unittest.mock import patch
        asked = []

        class Adapter:
            def team_fixtures(self, team, date_from, date_to, limit=100):
                asked.append((team, date_from, date_to, limit))
                return {'matches': [{'id': 123}]}

        query = {'dateFrom': ['2026-09-07'], 'dateTo': ['2027-09-07'],
                 'limit': ['100'], 'token': ['must-not-pass']}
        self.assertEqual(football(Adapter(), '/v1/football/teams/66/matches', query),
                         {'matches': [{'id': 123}]})
        # The club, the span and the cap travel; nothing else does.
        self.assertEqual(asked, [(66, '2026-09-07', '2027-09-07', 100)])
        with patch('backend.football_data.FootballData.team_fixtures', return_value={'matches': [{'id': 123}]}):
            self.assertEqual(self.request(token='wrong', path='/v1/football/teams/66/matches?limit=100'),
                             {'matches': [{'id': 123}]})

    def test_a_feed_that_will_not_answer_is_reported_as_unavailable(self):
        from unittest.mock import patch
        with patch('backend.football_data.FootballData.competitions', side_effect=OSError('feed muet')):
            with self.assertRaises(HTTPError) as error:
                self.request(token='wrong', path='/v1/football/competitions')
            self.assertEqual(error.exception.code, 503)

    def test_notes_written_against_the_former_provider_are_still_accepted(self):
        """The journal is immutable: an 'fd-' note predates ESPN and must still read back."""
        op = self.note(match_id='fd-123', entries=[dict(player_id='fd-456', action='pass')])
        self.request(op)
        self.assertEqual(self.request()[0]['operation'], op)

    def test_notes_are_written_against_espn_matches(self):
        op = self.note(match_id='espn-401915445',
                       entries=[dict(player_id='espn-456', action='pass')])
        self.request(op)
        self.assertEqual(self.request()[-1]['operation'], op)

    def test_a_coach_is_noted_like_a_player(self):
        notes = [self.note(match_id='espn-401915445',
                           entries=[dict(player_id='espn-coach-148', action='yellow'),
                                    dict(player_id='espn-456', action='negative')]),
                 self.match_note(match_id='espn-401915445', players=['espn-coach-493'])]
        for op in notes:
            self.request(op)
        self.assertEqual([r['operation'] for r in self.request()], notes)

    def test_a_coach_belongs_to_an_espn_match(self):
        rejected = [self.note(entries=[dict(player_id='espn-coach-148', action='yellow')]),
                    self.note(match_id='espn-401915445',
                              entries=[dict(player_id='espn-coach-', action='yellow')]),
                    self.note(match_id='espn-401915445',
                              entries=[dict(player_id='fd-coach-148', action='yellow')])]
        for op in rejected:
            with self.assertRaises(HTTPError) as error:
                self.request(op)
            self.assertEqual(error.exception.code, 400, op)
        self.assertEqual(self.request(), [])

    def test_espn_players_sync_under_the_original_match_id(self):
        op = self.note(match_id='fd-123', entries=[dict(player_id='espn-456', action='pass')])
        self.assertEqual(self.request(op)['operation'], op)

    def test_note_without_player_is_a_general_note(self):
        op = self.note(entries=[])
        comment = dict(id=str(uuid4()), note_id=op['note_id'], kind='comment', text='Gros pressing')
        self.request(op)
        self.request(comment)
        self.assertEqual([r['operation'] for r in self.request()], [op, comment])

    def match_note(self, **fields):
        """A note on the match as a whole: no minute, no action, maybe a club and some names."""
        return self.note(**{'minute': None, 'entries': [], 'team': None, 'players': [], **fields})

    def test_a_note_on_the_whole_match_has_no_minute(self):
        op = self.match_note()
        comment = dict(id=str(uuid4()), note_id=op['note_id'], kind='comment', text='Lyon subit tout le match')
        self.request(op)
        self.request(comment)
        self.assertEqual([r['operation'] for r in self.request()], [op, comment])

    def test_a_match_note_may_be_about_a_club_or_name_players(self):
        notes = [self.match_note(players=[PLAYERS[0], PLAYERS[1]]),
                 self.match_note(team='away'),
                 self.match_note(match_id='espn-401915445', players=['espn-456']),
                 self.note(minute=None, entries=[])]
        for op in notes:
            self.request(op)
        self.assertEqual([r['operation'] for r in self.request()], notes)

    def free_note(self, **fields):
        """A written note pinned to a minute: a match note that took the time."""
        return self.match_note(**{'minute': 34, **fields})

    def test_a_free_note_names_a_club_or_players_at_a_minute(self):
        notes = [self.free_note(players=[PLAYERS[0], PLAYERS[1]]),
                 self.free_note(team='home'),
                 self.free_note(match_id='espn-401915445', players=['espn-456', 'espn-coach-148']),
                 self.free_note()]
        for op in notes:
            self.request(op)
        self.assertEqual([r['operation'] for r in self.request()], notes)

    def test_a_match_note_names_players_without_crediting_them(self):
        legacy = self.legacy_note()
        legacy['minute'] = None
        rejected = [
            self.match_note(entries=[dict(player_id=PLAYERS[0], action='goal')]),
            legacy,
            self.match_note(team='neutral'),
            self.match_note(players=['unknown']),
            self.match_note(players=[PLAYERS[0], PLAYERS[0]]),
            self.match_note(players=[dict(player_id=PLAYERS[0])]),
            self.match_note(players=PLAYERS[0]),
            self.match_note(team='home', players=[PLAYERS[0]]),
            # A club and names belong to a note that credits nobody, and to no other.
            self.note(team='home', players=[]),
            self.note(players=[PLAYERS[1]]),
            self.free_note(team='home', players=[PLAYERS[0]]),
        ]
        for op in rejected:
            with self.assertRaises(HTTPError) as error:
                self.request(op)
            self.assertEqual(error.exception.code, 400, op)
        self.assertEqual(self.request(), [])

    def test_single_player_notes_written_before_stay_valid(self):
        op = self.legacy_note()
        self.request(op)
        self.assertEqual(self.request()[0]['operation'], op)

    def schema(self, **fields):
        """A tactical note's board: a couple of players and the pass between them."""
        board = dict(board='blank',
                     tokens=[dict(player_id=PLAYERS[0], x=0.3, y=0.4),
                             dict(team='away', label='6', x=0.5, y=0.5)],
                     shapes=[dict(kind='pass', points=[[0.3, 0.4], [0.42, 0.46], [0.7, 0.62]])])
        board.update(fields)
        return board

    def diagram(self, note_id=None, **fields):
        return dict(id=str(uuid4()), note_id=note_id or str(uuid4()), kind='diagram',
                    schema=self.schema(**fields))

    def animated_schema(self):
        return dict(version=2, board='blank', shapes=[], tokens=[
            dict(id='a', team='home', x=.1, y=.2, keys=[dict(t=30, x=.7, y=.2)]),
            dict(id='b', team='away', x=.3, y=.5, keys=[dict(t=10, x=.3, y=.5), dict(t=40, x=.9, y=.5)])
        ], ball=[dict(t=0, x=.1, y=.2, owner='a'),
                 dict(t=30, x=.7, y=.2, owner='a', flight=True),
                 dict(t=40, x=.9, y=.5, owner='b', kind='pass')])

    def test_independent_tracks_roundtrip(self):
        op = self.diagram(**self.animated_schema())
        self.request(op)
        self.assertEqual(self.request()[0]['operation'], op)
        self.assertEqual(self.request(op)['operation'], op)

    def test_version_three_sequence_roundtrip(self):
        schema = self.animated_schema()
        schema.update(version=3, steps=[dict(id='step', name='Réception', t=40)])
        for track_index, track in enumerate([schema['ball']] + [t['keys'] for t in schema['tokens']]):
            for index, key in enumerate(track):
                key['id'] = f'{track_index}-{index}'
        schema['ball'][2].update(after=schema['ball'][1]['id'], offset=10)
        op = self.diagram(**schema)
        self.request(op)
        self.assertEqual(self.request()[0]['operation'], op)
        self.assertEqual(self.request(op)['operation'], op)
        self.assertEqual(len(self.request()), 1)

    def test_invalid_tracks_rejected(self):
        import copy
        from backend.server import validate_schema
        good = self.animated_schema()
        for edit in [
            lambda s: s['tokens'][1].update(id='a'),
            lambda s: s['ball'][0].update(owner='missing'),
            lambda s: s['ball'][0].update(t=True),
            lambda s: s['ball'][1].update(t=0),
            lambda s: s['ball'][0].update(x=float('nan')),
            lambda s: s['ball'][1].update(flight='yes'),
            lambda s: s['tokens'][0]['keys'][0].update(owner='a'),
            lambda s: s['tokens'][0]['keys'][0].update(t=1201),
            lambda s: s['tokens'][0]['keys'][0].update(path=[[0, 0]]),
            lambda s: s['tokens'][0].update(keys=[dict(t=i, x=0, y=0) for i in range(121)]),
        ]:
            broken = copy.deepcopy(good)
            edit(broken)
            with self.assertRaises(ValueError):
                validate_schema(broken)

    def test_tactical_note_carries_its_schema_beside_the_note(self):
        note = self.note(entries=[dict(player_id=PLAYERS[0], action='pass')])
        drawn = self.diagram(note['note_id'])
        self.request(note)
        self.request(drawn)
        self.assertEqual([r['operation'] for r in self.request()], [note, drawn])

    def test_a_schema_is_rewritten_stroke_by_stroke_under_the_same_note(self):
        note_id = str(uuid4())
        first = self.diagram(note_id)
        second = self.diagram(note_id, shapes=first['schema']['shapes']
                              + [dict(kind='run', points=[[0.7, 0.6], [0.8, 0.8]])])
        self.request(first)
        self.request(second)
        # Both versions stay in the log; a reader takes the last, as it does for a note.
        self.assertEqual([r['operation'] for r in self.request()], [first, second])

    def test_an_empty_board_is_a_schema_undone(self):
        op = self.diagram(tokens=[], shapes=[])
        self.assertEqual(self.request(op)['operation'], op)

    def test_a_full_board_carries_the_whole_pitch(self):
        op = self.diagram(board='full',
                          tokens=[dict(player_id=p, x=0.5, y=0.5) for p in PLAYERS[:22]])
        self.assertEqual(self.request(op)['operation'], op)

    def test_invalid_schema_never_persists(self):
        rejected = [
            dict(board='ailleurs'),
            dict(tokens=[dict(player_id='unknown', x=0.5, y=0.5)]),
            dict(tokens=[dict(player_id=PLAYERS[0], x=1.5, y=0.5)]),
            dict(tokens=[dict(player_id=PLAYERS[0], x=0.5, y=0.5, team='home')]),
            dict(tokens=[dict(x=0.5, y=0.5)]),
            dict(tokens=[dict(team='arbitre', x=0.5, y=0.5)]),
            dict(tokens=[dict(team='home', label='trop long', x=0.5, y=0.5)]),
            dict(tokens=[dict(player_id=PLAYERS[0], x=True, y=0.5)]),
            dict(tokens=[dict(player_id=PLAYERS[0], x=0.5, y=0.5)] * 31),
            dict(shapes=[dict(kind='tunnel', points=[[0, 0], [1, 1]])]),
            dict(shapes=[dict(kind='pass', points=[[0, 0]])]),
            dict(shapes=[dict(kind='pass', points=[[0, 0], [1, 1]], extra=1)]),
            dict(shapes=[dict(kind='pass', points=[[0, 0], [1, 1, 1]])]),
            dict(shapes=[dict(kind='pass', points=[[0, 0], [1, 1]])] * 41),
            dict(shapes=[dict(kind='pass', points=[[0, 0], [1, 1]] * 17)]),
        ]
        for broken in rejected:
            with self.assertRaises(HTTPError) as error:
                self.request(self.diagram(**broken))
            self.assertEqual(error.exception.code, 400, broken)
        # A schema is a field of its own: neither missing nor smuggled onto another kind.
        for op in [dict(id=str(uuid4()), note_id=str(uuid4()), kind='diagram'),
                   self.note(schema=self.schema())]:
            with self.assertRaises(HTTPError) as error:
                self.request(op)
            self.assertEqual(error.exception.code, 400, op['kind'])
        self.assertEqual(self.request(), [])

    def test_a_schema_accepts_the_players_of_a_remote_match(self):
        op = self.diagram(tokens=[dict(player_id='espn-456', x=0.5, y=0.5)])
        self.assertEqual(self.request(op)['operation'], op)

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


class ReloadTest(unittest.TestCase):
    def test_sources_track_the_server_modules_and_tolerate_a_vanishing_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'server.py').write_text('x = 1')
            (root / 'espn.py').write_text('y = 2')
            (root / 'notes.txt').write_text('ignored')
            marks = sources(root)
            self.assertEqual(sorted(marks), ['espn.py', 'server.py'])

            # A save is a change; reading the same tree twice is not.
            self.assertEqual(marks, sources(root))
            (root / 'espn.py').write_text('y = 3')
            import os as system
            system.utime(root / 'espn.py', (0, 0))
            self.assertNotEqual(marks, sources(root))

            # A file removed mid-write is skipped, never an exception.
            (root / 'espn.py').unlink()
            self.assertEqual(sorted(sources(root)), ['server.py'])
