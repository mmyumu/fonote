"""Tests de bout en bout du stockage et des tâches, sans accès ESPN réel."""
import copy
import io
import json
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlparse
from unittest.mock import patch

from backend.catalogue import catalogue, COMPETITIONS
from backend.football_data import FootballData, NetworkGate
from backend.football_store import FootballStore
from backend.test_espn import event, scoreboard, summary


class FootballDataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'football.sqlite3'
        self.calls = []
        self.board = scoreboard([event('633850', '2022-12-18T15:00:00Z', slug='final')])
        self.board['events'][0]['season']['year'] = 2022
        self.detail = summary()
        self.adapter = FootballData(self.path, start=False, opener=self.feed, interval=0, background=0)

    def tearDown(self):
        self.adapter.close()
        self.temp.cleanup()

    def feed(self, url, timeout=10):
        self.calls.append(url)
        if '/seasons?' in url:
            data = {'items': [{'$ref': 'https://espn/seasons/2022?lang=en'}], 'pageCount': 1}
        elif '/seasons/' in url:
            data = {'startDate': '2022-01-01T00:00Z', 'endDate': '2022-12-31T23:59Z'}
        elif '/scoreboard' in url:
            data = self.board
        elif '/summary' in url:
            data = self.detail
        elif '/teams' in url:
            data = {'sports': [{'leagues': [{'teams': [{'team': {'id': '160', 'displayName': 'Paris'}}]}]}]}
        else:
            raise AssertionError(url)
        return io.BytesIO(json.dumps(data).encode())

    def drain(self):
        for _ in range(100):
            job = self.adapter.store.claim()
            if job is None:
                return
            self.adapter.execute(job)
        self.fail('La file ne se vide pas')

    def test_catalogue_is_local_classified_and_preserves_ids(self):
        data = self.adapter.competitions()
        self.assertEqual(len(data['competitions']), 23)
        self.assertEqual(sum(c['gender'] == 'female' for c in data['competitions']), 7)
        self.assertEqual(COMPETITIONS['FL1']['id'], 710)
        self.assertEqual(COMPETITIONS['WC']['id'], 606)
        self.assertEqual(self.calls, [])

    def test_first_search_imports_calendar_only_then_survives_restart(self):
        self.assertEqual(self.adapter.search('WC', 2022)['state'], 'loading')
        self.assertEqual(self.calls, [])
        self.drain()
        data = self.adapter.search('WC', 2022, phase='FINAL')
        self.assertEqual(data['state'], 'ready')
        self.assertEqual([m['id'] for m in data['matches']], [633850])
        self.assertEqual(len([u for u in self.calls if '/scoreboard' in u]), 1)
        self.assertFalse(any('/summary' in u for u in self.calls))
        asked = len(self.calls)
        self.adapter.close()
        self.adapter = FootballData(self.path, start=False, opener=self.feed, interval=0, background=0)
        self.assertEqual(self.adapter.search('WC', 2022)['count'], 1)
        self.drain()
        self.assertEqual(len(self.calls), asked)

    def test_duplicate_searches_share_one_job(self):
        with ThreadPoolExecutor(max_workers=5) as pool:
            list(pool.map(lambda _: self.adapter.search('WC', 2022), range(20)))
        with self.adapter.store.connect() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM football_jobs').fetchone()[0], 1)
        self.drain()
        self.assertEqual(sum('/scoreboard' in u for u in self.calls), 1)

    def test_interrupted_import_is_resumed(self):
        self.adapter.search('WC', 2022)
        self.assertIsNotNone(self.adapter.store.claim())
        self.adapter.close()
        self.adapter = FootballData(self.path, start=False, opener=self.feed, interval=0, background=0)
        self.drain()
        self.assertEqual(self.adapter.search('WC', 2022)['state'], 'ready')

    def test_source_error_is_not_an_empty_completed_archive(self):
        self.adapter.search('WC', 2022)
        with patch.object(self.adapter.gate, 'read', side_effect=OSError('unavailable')):
            self.drain()
        data = self.adapter.search('WC', 2022)
        self.assertEqual(data['state'], 'error')
        self.assertEqual(data['coverage'], 'partial')
        self.assertEqual(data['matches'], [])
        self.assertIsNotNone(self.adapter.store.job('season:WC:2022'))

    def test_a_calendar_widens_to_the_competitions_a_followed_club_plays_in(self):
        """Monaco is followed, Ligue 1 is not: its league nights still have to be read."""
        self.adapter.store.put_meta('team_codes:174', ['FL1', 'UECL'])
        self.assertEqual(self.adapter.widen(['CL', 'EL'], ['174']), ['CL', 'EL', 'FL1', 'UECL'])
        # A club the server has never imported a calendar for says nothing about itself, and
        # nothing is not an empty answer: everything is read rather than losing its matches.
        self.assertEqual(self.adapter.widen(['CL'], ['9999']), [])
        # Following no competition has always meant the whole catalogue.
        self.assertEqual(self.adapter.widen([], ['174']), [])
        with self.assertRaises(ValueError):
            self.adapter.widen(['CL'], ['tout'])

    def test_a_narrowed_calendar_only_reads_the_competitions_it_named(self):
        self.adapter.store.put_meta('team_codes:174', ['WC'])
        self.adapter.fixtures('2022-12-18', '2022-12-18', self.adapter.widen(['WC'], ['174']))
        self.drain()
        slugs = {u.split('/soccer/')[1].split('/')[0] for u in self.calls if 'scoreboard' in u}
        self.assertEqual(slugs, {COMPETITIONS['WC']['slug']})

    def test_calendar_import_does_not_claim_a_composition(self):
        self.adapter.fixtures('2022-12-18', '2022-12-18', ['WC'], lineups=True)
        self.drain()
        data = self.adapter.fixtures('2022-12-18', '2022-12-18', ['WC'], lineups=True)
        self.assertNotEqual(data['matches'][0].get('lineup_status'), 'available')
        self.assertFalse(any('summary' in u for u in self.calls))

    def test_truncated_range_is_split_and_single_day_remains_partial(self):
        self.board = scoreboard([event(str(i), '2022-12-18T15:00:00Z') for i in range(1, 501)])
        self.adapter.fixtures('2022-12-18', '2022-12-19', ['WC'])
        self.drain()
        data = self.adapter.fixtures('2022-12-18', '2022-12-19', ['WC'])
        self.assertEqual(data['state'], 'partial')
        self.assertEqual(data['count'], 500)
        self.assertEqual(sum('scoreboard' in u for u in self.calls), 3)

    def test_search_facets_and_pagination_do_not_refetch(self):
        self.board = scoreboard([event(str(i), '2022-12-18T15:00:00Z', slug='final') for i in range(1, 65)])
        self.adapter.search('WC', 2022); self.drain()
        first = self.adapter.search('WC', 2022)
        second = self.adapter.search('WC', 2022, page=1)
        self.assertEqual(len(first['matches']), 50)
        self.assertEqual(len(second['matches']), 14)
        self.assertTrue(first['hasMore']); self.assertFalse(second['hasMore'])
        self.assertEqual(first['phases'], ['FINAL'])
        self.assertEqual(len(first['teams']), 2)
        self.assertEqual(self.adapter.search('WC', 2022, team='174')['count'], 64)
        self.assertEqual(self.adapter.search('WC', 2022, phase='GROUP_STAGE')['count'], 0)
        self.assertEqual(sum('scoreboard' in u for u in self.calls), 1)

    def test_finished_detail_is_persisted_and_reused(self):
        first = self.adapter.match('401')
        self.assertEqual(first['lineup_status'], 'available')
        self.assertEqual(self.adapter.match('401'), first)
        self.adapter.close()
        self.adapter = FootballData(self.path, start=False, opener=self.feed, interval=0, background=0)
        self.assertEqual(self.adapter.match('401'), first)
        self.assertEqual(sum('summary' in u for u in self.calls), 1)

    def test_unfinished_detail_is_refreshed_and_keeps_composition(self):
        first = self.adapter.match('401')
        first['status'] = 'IN_PLAY'
        self.adapter.store.keep_detail(first)
        with self.adapter.store.connect() as db:
            db.execute('UPDATE football_details SET fetched=0')
            db.execute('UPDATE football_responses SET expires=0')
        self.detail = dict(self.detail, rosters=[])
        fresh = self.adapter.match('401')
        self.assertEqual(fresh['status'], 'FINISHED')
        self.assertEqual(fresh['lineup_status'], 'available')
        self.assertEqual(len(fresh['homeTeam']['lineup']), 11)
        self.assertEqual(sum('summary' in u for u in self.calls), 2)

    def test_follow_union_does_not_merge_preferences_or_delete_matches(self):
        self.adapter.set_follows('one', {'competitions': ['710'], 'teams': []})
        self.adapter.set_follows('two', {'competitions': ['WC'], 'teams': []})
        self.assertIsNotNone(self.adapter.store.job('current:FL1'))
        self.assertIsNotNone(self.adapter.store.job('current:WC'))
        self.adapter.store.matches([{'id': 4, 'competition': {'code': 'FL1'}, 'season': 2022}])
        self.adapter.set_follows('one', {'competitions': [], 'teams': []})
        self.assertIsNone(self.adapter.store.job('current:FL1'))
        self.assertIsNotNone(self.adapter.store.job('current:WC'))
        self.assertEqual(len(self.adapter.store.known(['FL1'])), 1)

    def test_teams_are_cached_classified_and_support_follow_preparation(self):
        self.adapter.teams('WFL1'); self.drain()
        data = self.adapter.teams('WFL1')
        self.assertEqual(data['teams'][0]['gender'], 'female')
        self.adapter.set_follows('one', {'competitions': [], 'teams': ['160']})
        self.assertIsNotNone(self.adapter.store.job('current:WFL1'))
        self.assertEqual(sum('/teams' in u for u in self.calls), 1)

    def test_invalid_catalogue_search_is_rejected_before_network(self):
        with self.assertRaises(LookupError): self.adapter.search('unknown', 2022)
        with self.assertRaises(ValueError): self.adapter.search('WC', 0)
        with self.assertRaises(ValueError): self.adapter.search('WC', 2022, start='2022-02-30')
        self.assertEqual(self.calls, [])

    def test_stale_response_is_not_used_to_mark_failed_import_complete(self):
        self.adapter.request('https://espn/scoreboard', 0)
        with patch.object(self.adapter.gate, 'read', side_effect=OSError('unavailable')):
            with self.assertRaises(OSError): self.adapter.request('https://espn/scoreboard', 0)
        self.assertIsNotNone(self.adapter.store.response('https://espn/scoreboard'))


class GateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = FootballStore(Path(self.temp.name) / 'db')

    def tearDown(self):
        self.temp.cleanup()

    def test_requests_are_serial_and_spaced(self):
        times = []
        def read(url, timeout):
            times.append(time.monotonic())
            return io.BytesIO(b'{}')
        gate = NetworkGate(self.store, interval=.025, background=.025, opener=read)
        with ThreadPoolExecutor(max_workers=3) as pool:
            list(pool.map(gate.read, ['one', 'two', 'three']))
        self.assertEqual(len(times), 3)
        self.assertTrue(all(b - a >= .024 for a, b in zip(times, times[1:])))

    def test_foreground_precedes_background_waiting_for_a_slot(self):
        calls = []
        gate = NetworkGate(self.store, interval=.05, background=.05,
                           opener=lambda url, timeout: calls.append(url) or io.BytesIO(b'{}'))
        gate.last = time.monotonic()
        with ThreadPoolExecutor(max_workers=2) as pool:
            bg = pool.submit(gate.read, 'background', 10)
            fg = pool.submit(gate.read, 'foreground', 0)
            fg.result(); bg.result()
        self.assertEqual(calls, ['foreground', 'background'])

    def test_429_respects_retry_after_and_pauses_other_urls_across_restart(self):
        def refused(url, timeout):
            raise HTTPError(url, 429, 'limited', {'Retry-After': '120'}, None)
        gate = NetworkGate(self.store, interval=0, background=0, opener=refused)
        with self.assertRaises(HTTPError): gate.read('https://espn/one')
        self.assertGreaterEqual(self.store.meta('network_pause')['until'], time.time() + 119)
        other = NetworkGate(self.store, opener=lambda *a, **k: self.fail('Network while paused'))
        with self.assertRaises(OSError): other.read('https://espn/two')

    def test_403_stops_automatic_retry(self):
        def refused(url, timeout): raise HTTPError(url, 403, 'refused', {}, None)
        gate = NetworkGate(self.store, opener=refused)
        with self.assertRaises(HTTPError): gate.read('https://espn/one')
        self.assertTrue(self.store.meta('network_pause')['blocked'])
        with self.assertRaises(OSError): gate.read('https://espn/two')
