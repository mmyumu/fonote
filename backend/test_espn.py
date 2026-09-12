import copy
import json
import threading
import time
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import Mock, patch

from backend.espn import (CACHE, INK, LIVE_CACHE, MIN_CONTRAST, MIN_DISTANCE, RETRY, Espn,
                          contrast, distance, luminance, minute, parse_colour, readable, stage,
                          status)


class Feed:
    """A stand-in for ESPN that counts how many times it was actually read."""

    def __init__(self, payload=None, refuses=None, waits=None):
        self.payload = scoreboard() if payload is None else payload
        self.refuses, self.waits, self.reads = refuses, waits, 0

    def __call__(self, url, timeout=None):
        self.reads += 1
        if self.waits is not None:
            self.waits.wait(timeout=5)
        if self.refuses is not None:
            raise self.refuses
        return self

    def __enter__(self):
        return self

    def __exit__(self, *done):
        return False

    def read(self, *size):
        return json.dumps(self.payload).encode()


def event(identifier='401', when='2026-09-04T19:05:00Z', state='pre',
          name='STATUS_SCHEDULED', score=('0', '0'), slug='2026-27-ligue-1'):
    """A calendar entry as a scoreboard publishes it."""
    kind = {'type': {'state': state, 'name': name}}
    return {'id': identifier, 'date': when, 'season': {'slug': slug}, 'status': kind,
            'competitions': [{'date': when, 'status': kind,
                              'venue': {'fullName': 'Parc des Princes'},
                              'competitors': [
                                  {'homeAway': 'home', 'score': score[0],
                                   'team': {'id': '160', 'displayName': 'Paris Saint-Germain',
                                            'shortDisplayName': 'PSG', 'abbreviation': 'PSG',
                                            'logo': 'https://espn/160.png'}},
                                  {'homeAway': 'away', 'score': score[1],
                                   'team': {'id': '174', 'displayName': 'AS Monaco',
                                            'shortDisplayName': 'Monaco', 'abbreviation': 'ASM',
                                            'logo': 'https://espn/174.png'}}]}]}


def scoreboard(events=None, league='710', named='French Ligue 1', slug='fra.1'):
    return {'leagues': [{'id': league, 'name': named, 'slug': slug,
                         'logos': [{'href': 'https://espn/%s.png' % slug}]}],
            'events': [event()] if events is None else events}


def header(state='post', name='STATUS_FULL_TIME', score=('6', '1')):
    """The identity of a match as a match sheet carries it."""
    return {'id': '401', 'timeValid': True,
            'league': {'id': '775', 'name': 'UEFA Champions League', 'slug': 'uefa.champions',
                       'logos': [{'href': 'https://espn/cl.png'}]},
            'season': {'year': 2026, 'name': '2026-27 UEFA Champions League, League Phase'},
            'competitions': [{'date': '2026-09-04T19:05Z',
                              'status': {'type': {'state': state, 'name': name}},
                              'competitors': [
                                  {'homeAway': 'home', 'score': score[0],
                                   'team': {'id': '160', 'displayName': 'Paris Saint-Germain',
                                            'shortDisplayName': 'PSG', 'abbreviation': 'PSG'}},
                                  {'homeAway': 'away', 'score': score[1],
                                   'team': {'id': '174', 'displayName': 'AS Monaco',
                                            'shortDisplayName': 'Monaco', 'abbreviation': 'ASM'}}]}]}


def moment(kind, shown, team=None, actors=(), wallclock=None, scoring=False):
    return {'type': {'type': kind, 'text': kind.title()},
            'clock': {'displayValue': shown, 'value': 0.0}, 'period': {'number': 1},
            'team': {'id': team} if team else {}, 'scoringPlay': scoring,
            'participants': [{'athlete': {'id': str(a)}} for a in actors],
            'text': kind, 'wallclock': wallclock}


def events():
    """Kickoff, a substitution on each side and the break, as ESPN publishes them."""
    return [moment('kickoff', '', wallclock='2026-09-04T19:05:12Z'),
            moment('substitution', "52'", '160', (111, 105)),
            moment('goal', "58'", '174', (205, 206), scoring=True),
            moment('substitution', "73'", '174', (211, 200)),
            moment('halftime', "45'+3'", wallclock='2026-09-04T19:52:15Z'),
            moment('start-2nd-half', "45'", wallclock='2026-09-04T20:09:15Z'),
            moment('end-regular-time', "90'+6'", wallclock='2026-09-04T21:00:14Z')]


def summary(home_colour='21304D', away_colour='ED1C24', away_alternate='FFFFFF',
            keyEvents=None, head=None):
    colours = {'home': {'color': home_colour, 'id': '160', 'abbreviation': 'PSG',
                        'logos': [{'href': 'https://espn/160.png', 'rel': ['full', 'default']},
                                  {'href': 'https://espn/160-dark.png', 'rel': ['full', 'dark']}]},
               'away': {'color': away_colour, 'alternateColor': away_alternate, 'id': '174',
                        'abbreviation': 'ASM', 'logos': [{'href': 'https://espn/174.png'}]}}
    return {'header': head or header(),
            'keyEvents': events() if keyEvents is None else keyEvents,
            'boxscore': {'teams': [
                {'team': {'id': '160'}, 'statistics': [{'name': 'possessionPct', 'displayValue': '68.2'},
                                                       {'name': 'totalShots', 'displayValue': '23'}]},
                {'team': {'id': '174'}, 'statistics': [{'name': 'possessionPct', 'displayValue': '31.8'},
                                                       {'name': 'totalShots', 'displayValue': '4'}]}]},
            'gameInfo': {'venue': {'fullName': 'Parc des Princes'}, 'attendance': 0,
                         'officials': [{'displayName': 'Jerome Brisard'}]},
            'rosters': [{'homeAway': side, 'team': dict({'displayName': name}, **colours[side]), 'formation': '4-4-2',
                         'roster': [{'starter': i < 11, 'athlete': {'id': str(offset + i),
                                    'displayName': f'Player {offset + i}'}, 'jersey': str(i + 1),
                                    'stats': [{'name': 'totalGoals', 'displayValue': str(i % 2)},
                                              {'name': 'saves', 'displayValue': '4'}]}
                                    for i in range(18)]}
                        for side, name, offset in [('home', 'Paris Saint-Germain', 100),
                                                   ('away', 'AS Monaco', 200)]]}


class EspnTest(unittest.TestCase):
    def adapter(self, data=None):
        adapter = Espn()
        data = summary() if data is None else data
        adapter.fetch = Mock(side_effect=lambda *a, **k: copy.deepcopy(data))
        return adapter

    def leagues(self, boards):
        """An adapter whose calendars differ from one league to the next."""
        adapter = Espn()

        def answer(league, resource, params, ttl=CACHE):
            if league not in boards:
                raise OSError('feed muet')
            return copy.deepcopy(boards[league])
        adapter.fetch = Mock(side_effect=answer)
        return adapter

    def test_says_what_the_match_is_doing_in_the_words_the_client_reads(self):
        for state, name, expected in [('pre', 'STATUS_SCHEDULED', 'TIMED'),
                                      ('in', 'STATUS_FIRST_HALF', 'IN_PLAY'),
                                      ('in', 'STATUS_HALFTIME', 'PAUSED'),
                                      ('post', 'STATUS_FULL_TIME', 'FINISHED'),
                                      ('post', 'STATUS_POSTPONED', 'POSTPONED'),
                                      ('post', 'STATUS_CANCELED', 'CANCELLED'),
                                      ('in', 'STATUS_ABANDONED', 'SUSPENDED'),
                                      (None, None, 'SCHEDULED')]:
            with self.subTest(name):
                self.assertEqual(status({'status': {'type': {'state': state, 'name': name}}}),
                                 expected)

    def test_names_the_round_only_where_a_round_means_something(self):
        # A league plays one competition all season, whatever its calendar calls the season.
        self.assertEqual(stage('2026-27-ligue-1', 'FL1'), 'REGULAR_SEASON')
        self.assertEqual(stage('regular-season', 'PL'), 'REGULAR_SEASON')
        # A cup names its phase, on a calendar by slug and on a match sheet by title.
        self.assertEqual(stage('league-phase', 'CL'), 'GROUP_STAGE')
        self.assertEqual(stage('2026-27 UEFA Champions League, League Phase', 'CL'), 'GROUP_STAGE')
        self.assertEqual(stage('round-of-16', 'CL'), 'LAST_16')
        self.assertEqual(stage('final', 'CL'), 'FINAL')
        # An unknown phase travels as its own words rather than being called something else.
        self.assertEqual(stage('replay-round', 'CL'), 'REPLAY_ROUND')

    def test_a_calendar_entry_carries_what_a_card_is_drawn_from(self):
        result = self.leagues({'fra.1': scoreboard()}).fixtures('2026-09-04', '2026-09-04', ['FL1'])
        self.assertEqual(result['count'], 1)
        match = result['matches'][0]
        self.assertEqual(match['id'], 401)
        # The client parses this instant; the contract has always carried its seconds.
        self.assertEqual(match['utcDate'], '2026-09-04T19:05:00Z')
        self.assertEqual(match['status'], 'TIMED')
        self.assertEqual(match['stage'], 'REGULAR_SEASON')
        self.assertEqual(match['competition'],
                         {'id': 710, 'name': 'French Ligue 1', 'code': 'FL1',
                          'type': 'LEAGUE', 'emblem': 'https://espn/fra.1.png'})
        self.assertEqual(match['homeTeam'], {'id': 160, 'name': 'Paris Saint-Germain',
                                             'shortName': 'PSG', 'tla': 'PSG',
                                             'crest': 'https://espn/160.png'})
        self.assertEqual(match['venue'], 'Parc des Princes')

    def test_a_match_still_to_come_has_no_score_to_show(self):
        board = scoreboard([event(score=('0', '0'))])
        match = self.leagues({'fra.1': board}).fixtures('2026-09-04', '2026-09-04', ['FL1'])['matches'][0]
        self.assertEqual(match['score'], {'fullTime': {'home': '', 'away': ''}})

        played = scoreboard([event(state='post', name='STATUS_FULL_TIME', score=('3', '1'))])
        match = self.leagues({'fra.1': played}).fixtures('2026-09-04', '2026-09-04', ['FL1'])['matches'][0]
        self.assertEqual(match['score'], {'fullTime': {'home': '3', 'away': '1'}})

    def test_one_calendar_is_made_of_every_league_and_survives_a_silent_one(self):
        early = event('1', '2026-09-04T15:00:00Z')
        late = event('2', '2026-09-04T21:00:00Z')
        adapter = self.leagues({'fra.1': scoreboard([late]),
                                'eng.1': scoreboard([early], '700', 'Premier League', 'eng.1')})
        result = adapter.fixtures('2026-09-04', '2026-09-04')
        # Ten leagues answer nothing at all; the two that answer still make a calendar.
        self.assertEqual([m['id'] for m in result['matches']], [1, 2])
        self.assertEqual([m['competition']['code'] for m in result['matches']], ['PL', 'FL1'])

    def test_a_club_is_followed_through_its_league_and_its_european_nights(self):
        home = event('1', '2026-09-12T15:00:00Z')
        away = event('2', '2026-09-15T19:00:00Z', slug='league-phase')
        other = event('3', '2026-09-13T15:00:00Z')
        for side in other['competitions'][0]['competitors']:
            side['team']['id'] = '999'
        adapter = self.leagues({'fra.1': scoreboard([home, other]),
                                'uefa.champions': scoreboard([away], '775', 'UCL', 'uefa.champions')})
        adapter.clubs = {'160': 'FL1'}
        result = adapter.team_fixtures(160, '2026-09-11', '2026-09-30')
        # The club's own matches, in order, and never a match it is not playing.
        self.assertEqual([m['id'] for m in result['matches']], [1, 2])
        self.assertEqual(adapter.team_fixtures(160, '2026-09-11', '2026-09-30', 1)['count'], 1)

    def test_only_matches_around_kickoff_are_asked_about_their_composition(self):
        """A badge is worth a reading tonight, never a reading per fixture of the season."""
        now = datetime.now(timezone.utc)

        def when(hours):
            return (now + timedelta(hours=hours)).strftime('%Y-%m-%dT%H:%M:%SZ')

        board = scoreboard([event('1', when(1)),      # kick-off in an hour
                            event('2', when(-1)),     # started an hour ago
                            event('3', when(72)),     # in three days
                            event('4', when(-48))])   # avant-hier
        adapter = self.leagues({'fra.1': board})
        asked = []
        adapter.lineup_ready = Mock(side_effect=lambda i: asked.append(i) or 'available')
        found = {m['id']: m['lineup_status']
                 for m in adapter.fixtures('2026-09-04', '2026-09-04', ['FL1'], lineups=True)['matches']}
        self.assertEqual(sorted(asked), [1, 2])
        self.assertEqual(found, {1: 'available', 2: 'available', 3: 'unknown', 4: 'unknown'})

    def test_a_calendar_says_nothing_about_compositions_unless_asked(self):
        adapter = self.leagues({'fra.1': scoreboard()})
        adapter.lineup_ready = Mock()
        matches = adapter.fixtures('2026-09-04', '2026-09-04', ['FL1'])['matches']
        self.assertNotIn('lineup_status', matches[0])
        adapter.lineup_ready.assert_not_called()

    def test_a_composition_is_announced_only_once_both_elevens_are_out(self):
        adapter = self.adapter()
        self.assertEqual(adapter.lineup_ready('401'), 'available')
        # A fixture whose sheet is published but still empty is not something to advertise.
        empty = summary()
        for roster in empty['rosters']:
            roster['roster'] = []
        self.assertEqual(self.adapter(empty).lineup_ready('401'), 'unavailable')
        # One side named and not the other is not a composition either.
        half = summary()
        half['rosters'][1]['roster'] = []
        self.assertEqual(self.adapter(half).lineup_ready('401'), 'unavailable')

    def test_the_badge_prepays_the_opening_of_the_match(self):
        """The sheet read to draw the badge is the one the match itself is read from."""
        adapter = self.adapter()
        self.assertEqual(adapter.lineup_ready('401'), 'available')
        self.assertEqual(adapter.match('401')['lineup_status'], 'available')
        asked = [call.args[:3] for call in adapter.fetch.call_args_list]
        self.assertEqual(asked, [('all', 'summary', {'event': '401'})] * 2)

    def test_the_catalogue_names_and_badges_every_competition(self):
        adapter = self.leagues({'fra.1': scoreboard()})
        found = adapter.competitions()
        self.assertEqual(found['count'], 1)
        self.assertEqual(found['competitions'][0]['code'], 'FL1')
        self.assertEqual(found['competitions'][0]['emblem'], 'https://espn/fra.1.png')

    def test_a_match_sheet_stands_on_its_own_without_naming_a_league(self):
        adapter = self.adapter()
        result = adapter.match('401')
        self.assertEqual(result['id'], 401)
        self.assertEqual(result['utcDate'], '2026-09-04T19:05:00Z')
        self.assertEqual(result['status'], 'FINISHED')
        self.assertEqual(result['stage'], 'GROUP_STAGE')
        self.assertEqual(result['competition']['code'], 'CL')
        self.assertEqual(result['score'], {'fullTime': {'home': '6', 'away': '1'}})
        # One reading of one feed, asked of no league in particular.
        self.assertEqual(len(adapter.fetch.call_args_list), 1)
        self.assertEqual(adapter.fetch.call_args_list[0].args[:3],
                         ('all', 'summary', {'event': '401'}))

    def test_only_starters_and_distinct_source_ids(self):
        result = self.adapter().match('401')
        self.assertEqual(result['lineup_status'], 'available')
        for side in ('home', 'away'):
            players = result[side + 'Team']['lineup']
            self.assertEqual(len(players), 11)
            self.assertTrue(all(p['fonote_id'].startswith('espn-') for p in players))

    def test_missing_or_duplicate_starters_never_create_partial_lineup(self):
        for duplicate in (False, True):
            data = summary()
            if duplicate:
                data['rosters'][1]['roster'][1]['athlete']['id'] = '200'
            else:
                data['rosters'][1]['roster'][0]['starter'] = False
            result = self.adapter(data).match('401')
            self.assertEqual(result['lineup_status'], 'unavailable')
            self.assertNotIn('lineup', result['homeTeam'])
            # The fixture itself survives a composition that does not.
            self.assertEqual(result['id'], 401)
            self.assertEqual(result['homeTeam']['name'], 'Paris Saint-Germain')

    def test_formation_place_orders_the_list_only_when_complete(self):
        data = summary()
        for roster in data['rosters']:
            for index, player in enumerate(roster['roster'][:11]):
                player['formationPlace'] = str(11 - index)
        result = self.adapter(data).match('401')
        places = [p['formationPlace'] for p in result['homeTeam']['lineup']]
        self.assertEqual(places, [str(n) for n in range(1, 12)])
        self.assertEqual(result['homeTeam']['lineup'][0]['name'], 'Player 110')

        partial = summary()
        partial['rosters'][0]['roster'][0]['formationPlace'] = '5'
        result = self.adapter(partial).match('401')
        self.assertEqual([p['name'] for p in result['homeTeam']['lineup']],
                         [f'Player {100 + i}' for i in range(11)])

    def test_run_of_play_names_both_actors_in_the_published_order(self):
        result = self.adapter().match('401')
        changes = [e for e in result['timeline'] if e['kind'] == 'substitution']
        self.assertEqual([(c['minute'], c['team'], c['players']) for c in changes],
                         [(52, 'home', ['espn-111', 'espn-105']),
                          (73, 'away', ['espn-211', 'espn-200'])])
        # A goal is known by its flag, never by the wording of its type.
        goal = [e for e in result['timeline'] if e['scoring']][0]
        self.assertEqual((goal['minute'], goal['team'], goal['players']),
                         (58, 'away', ['espn-205', 'espn-206']))
        self.assertEqual(result['clock'], {'kickoff': '2026-09-04T19:05:12Z',
                                           'halftime': '2026-09-04T19:52:15Z',
                                           'second_half': '2026-09-04T20:09:15Z',
                                           'end': '2026-09-04T21:00:14Z',
                                           'end_minute': 96})

    def test_added_time_survives_a_clock_clamped_at_the_boundary(self):
        # ESPN reports halftime at 45'+3' with clock.value still 2700: only the text is right.
        self.assertEqual(minute({'clock': {'displayValue': "45'+3'", 'value': 2700.0}}), 48)
        self.assertEqual(minute({'clock': {'displayValue': "9'", 'value': 507.0}}), 9)
        self.assertEqual(minute({'clock': {'displayValue': '', 'value': 0.0}}), 0)
        self.assertIsNone(minute({'clock': {}}))
        self.assertIsNone(minute({}))

    def test_bench_is_named_without_ever_risking_the_eleven(self):
        result = self.adapter().match('401')
        bench = result['homeTeam']['bench']
        self.assertEqual([p['fonote_id'] for p in bench],
                         ['espn-%d' % n for n in range(111, 118)])
        starters = {p['fonote_id'] for p in result['homeTeam']['lineup']}
        self.assertFalse(starters & {p['fonote_id'] for p in bench})

        # A nameless substitute costs one late marker, never the composition.
        broken = summary()
        broken['rosters'][0]['roster'][12]['athlete'] = {'id': 'x'}
        result = self.adapter(broken).match('401')
        self.assertEqual(result['lineup_status'], 'available')
        self.assertEqual(len(result['homeTeam']['lineup']), 11)
        self.assertEqual(len(result['homeTeam']['bench']), 6)

    def test_match_without_a_published_run_of_play_still_composes(self):
        result = self.adapter(summary(keyEvents=[])).match('401')
        self.assertEqual(result['lineup_status'], 'available')
        self.assertEqual(result['timeline'], [])
        self.assertEqual(result['clock'], {})
        self.assertEqual(len(result['homeTeam']['lineup']), 11)

    def test_a_match_in_play_is_re_read_far_sooner_than_one_that_is_not(self):
        """The feed is filed under the usual delay, then held for less once it reads live."""
        for state, name, expected in [('in', 'STATUS_FIRST_HALF', LIVE_CACHE),
                                      ('in', 'STATUS_HALFTIME', LIVE_CACHE),
                                      ('post', 'STATUS_FULL_TIME', None)]:
            with self.subTest(name):
                adapter = self.adapter(summary(head=header(state=state, name=name)))
                shortened = []
                adapter.shorten = Mock(side_effect=lambda *a: shortened.append(a[3]))
                adapter.match('401')
                self.assertEqual(shortened, [] if expected is None else [expected])

    def test_overview_keeps_live_details_for_five_minutes(self):
        adapter = Espn()
        feed = Feed(summary(head=header(state='in', name='STATUS_FIRST_HALF')))
        now = time.monotonic()
        with patch('backend.espn.urlopen', feed), patch('backend.espn.time.monotonic', return_value=now):
            adapter.match('401')
            with patch('backend.espn.time.monotonic', return_value=now + CACHE - 1):
                adapter.match('401', live=False)
                adapter.lineup_ready('401')
                self.assertEqual(feed.reads, 1)
            with patch('backend.espn.time.monotonic', return_value=now + CACHE):
                adapter.match('401', live=False)
                self.assertEqual(feed.reads, 2)

    def test_today_calendar_is_held_for_five_minutes(self):
        adapter = Espn()
        today = datetime.now(timezone.utc).date().isoformat()
        now = time.monotonic()
        feed = Feed()
        with patch('backend.espn.urlopen', feed), patch('backend.espn.time.monotonic', return_value=now):
            adapter.fixtures(today, today, ['FL1'])
            with patch('backend.espn.time.monotonic', return_value=now + CACHE - 1):
                adapter.fixtures(today, today, ['FL1'])
                self.assertEqual(feed.reads, 1)
            with patch('backend.espn.time.monotonic', return_value=now + CACHE):
                adapter.fixtures(today, today, ['FL1'])
                self.assertEqual(feed.reads, 2)

    def test_a_reading_already_held_costs_no_second_request(self):
        adapter = Espn()
        adapter.cache = {adapter.url_for('all', 'summary', {'event': '401'}):
                         (time.monotonic() + 60, summary())}
        # No fetch is stubbed: reaching the network here would raise rather than answer.
        self.assertEqual(adapter.match('401')['lineup_status'], 'available')

    def test_an_unknown_match_is_said_to_be_unknown(self):
        self.assertIsNone(self.adapter({'header': {}}).match('999'))

    def test_provider_counts_travel_beside_the_notes(self):
        result = self.adapter().match('401')
        self.assertEqual(result['team_stats'],
                         {'home': {'possessionPct': '68.2', 'totalShots': '23'},
                          'away': {'possessionPct': '31.8', 'totalShots': '4'}})
        self.assertEqual(result['ground'], {'venue': 'Parc des Princes', 'attendance': 0,
                                            'referee': 'Jerome Brisard'})
        # Counted per player too, on the eleven and on the bench alike.
        self.assertEqual(result['homeTeam']['lineup'][1]['stats'],
                         {'totalGoals': '1', 'saves': '4'})
        self.assertEqual(result['homeTeam']['bench'][0]['stats'],
                         {'totalGoals': '1', 'saves': '4'})

    def test_a_summary_without_counts_still_composes(self):
        bare = summary()
        bare.pop('boxscore')
        bare.pop('gameInfo')
        for roster in bare['rosters']:
            for player in roster['roster']:
                player.pop('stats')
        result = self.adapter(bare).match('401')
        self.assertEqual(result['lineup_status'], 'available')
        self.assertEqual(result['team_stats'], {})
        self.assertEqual(result['homeTeam']['lineup'][0]['stats'], {})

    def test_two_similar_clubs_use_the_away_alternate(self):
        result = self.adapter(summary(away_colour='21304D')).match('401')
        home = result['homeTeam']['colour']
        away = result['awayTeam']['colour']
        self.assertNotEqual(home, away)
        self.assertGreaterEqual(distance(parse_colour(home), parse_colour(away)), MIN_DISTANCE)
        for colour in (home, away):
            self.assertGreaterEqual(contrast(parse_colour(colour), INK), MIN_CONTRAST)

    def test_trigram_and_crest_travel_beside_the_composition(self):
        result = self.adapter().match('401')
        self.assertEqual(result['homeTeam']['abbreviation'], 'PSG')
        # The badge drawn for a dark background wins where a club publishes one.
        self.assertEqual(result['homeTeam']['crest'], 'https://espn/160-dark.png')
        self.assertEqual(result['awayTeam']['crest'], 'https://espn/174.png')

    def test_readable_never_leaves_a_shirt_number_unreadable(self):
        for raw in ('000000', '21304D', 'ED1C24', 'FFFFFF'):
            self.assertGreaterEqual(contrast(readable(parse_colour(raw)), INK), MIN_CONTRAST)
        self.assertIsNone(parse_colour('nope'))
        self.assertLess(luminance((0, 0, 0)), luminance((255, 255, 255)))


class ReadingTest(unittest.TestCase):
    """What a refresh costs the feed. A client asks as often as it likes; ESPN is spared."""

    def read(self, adapter):
        return adapter.fetch('fra.1', 'scoreboard', {'limit': 1})

    def test_a_second_reading_within_the_delay_never_reaches_the_feed(self):
        adapter, feed = Espn(), Feed()
        with patch('backend.espn.urlopen', feed):
            for _ in range(10):
                self.assertEqual(self.read(adapter), feed.payload)
        self.assertEqual(feed.reads, 1)

    def test_a_reading_that_has_run_out_is_read_again(self):
        adapter, feed = Espn(), Feed()
        with patch('backend.espn.urlopen', feed):
            self.read(adapter)
            url = adapter.url_for('fra.1', 'scoreboard', {'limit': 1})
            adapter.cache[url] = (time.monotonic() - 1, adapter.cache[url][1])
            self.read(adapter)
        self.assertEqual(feed.reads, 2)

    def test_a_feed_that_will_not_answer_is_served_from_what_it_last_said(self):
        adapter, feed = Espn(), Feed()
        url = adapter.url_for('fra.1', 'scoreboard', {'limit': 1})
        with patch('backend.espn.urlopen', feed):
            self.read(adapter)
            adapter.cache[url] = (time.monotonic() - 1, adapter.cache[url][1])
            with patch('backend.espn.urlopen', Feed(refuses=OSError('flux muet'))) as silent:
                self.assertEqual(self.read(adapter), feed.payload)
                # Held back five minutes: the next refresh is answered without asking again.
                self.assertEqual(self.read(adapter), feed.payload)
                self.assertEqual(silent.reads, 1)
        self.assertGreater(adapter.cache[url][0], time.monotonic() + RETRY - 5)

    def test_a_feed_that_will_not_answer_and_never_did_says_so(self):
        adapter = Espn()
        feed = Feed(refuses=OSError('flux muet'))
        with patch('backend.espn.urlopen', feed):
            for _ in range(8):
                with self.assertRaises(OSError):
                    self.read(adapter)
            self.assertEqual(feed.reads, 1)
            with patch('backend.espn.time.monotonic', return_value=time.monotonic() + RETRY + 1):
                with self.assertRaises(OSError):
                    self.read(adapter)
            self.assertEqual(feed.reads, 2)

    def test_a_burst_of_refreshes_on_one_page_costs_one_request(self):
        adapter, held = Espn(), threading.Event()
        feed = Feed(waits=held)
        answers = []
        with patch('backend.espn.urlopen', feed):
            readers = [threading.Thread(target=lambda: answers.append(self.read(adapter)))
                       for _ in range(8)]
            for reader in readers:
                reader.start()
            # Let them all pile up on the page being read before it is allowed to answer.
            time.sleep(.2)
            held.set()
            for reader in readers:
                reader.join(timeout=5)
        self.assertEqual(feed.reads, 1)
        self.assertEqual(answers, [feed.payload] * 8)

    def test_pages_are_read_side_by_side_and_never_queue(self):
        adapter, held = Espn(), threading.Event()
        feed = Feed(waits=held)
        with patch('backend.espn.urlopen', feed):
            slow = threading.Thread(target=lambda: adapter.fetch('fra.1', 'scoreboard', {}))
            slow.start()
            time.sleep(.1)
            other = threading.Thread(target=lambda: adapter.fetch('eng.1', 'scoreboard', {}))
            other.start()
            time.sleep(.1)
            # The second page reached the feed without waiting for the first to come back.
            self.assertEqual(feed.reads, 2)
            held.set()
            slow.join(timeout=5)
            other.join(timeout=5)


if __name__ == '__main__':
    unittest.main()
