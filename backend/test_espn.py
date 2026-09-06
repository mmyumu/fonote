import copy
import unittest
from unittest.mock import Mock

from backend.espn import (CACHE, INK, LIVE_CACHE, MIN_CONTRAST, MIN_DISTANCE, Espn, contrast,
                          distance, luminance, minute, parse_colour, readable, select_event)


def fixture():
    return {'id': 1, 'utcDate': '2026-09-04T19:05:00Z', 'competition': {'code': 'FL1'},
            'homeTeam': {'name': 'Paris Saint-Germain FC'}, 'awayTeam': {'name': 'AS Monaco FC'}}


def event():
    return {'id': '2', 'date': '2026-09-04T19:05Z', 'competitions': [{'competitors': [
        {'homeAway': 'home', 'team': {'displayName': 'Paris Saint-Germain'}},
        {'homeAway': 'away', 'team': {'displayName': 'AS Monaco'}}]}]}


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


def summary(home_colour='21304D', away_colour='ED1C24', away_alternate='FFFFFF', keyEvents=None):
    colours = {'home': {'color': home_colour, 'id': '160'},
               'away': {'color': away_colour, 'alternateColor': away_alternate, 'id': '174'}}
    return {'keyEvents': events() if keyEvents is None else keyEvents,
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
        adapter.fetch = Mock(side_effect=[{'events': [event()]}, data or summary()])
        return adapter

    def test_matches_both_teams_and_time_and_rejects_ambiguity(self):
        match, candidate = fixture(), event()
        self.assertEqual(select_event(match, [candidate]), candidate)
        self.assertIsNone(select_event(match, [candidate, candidate]))
        candidate['date'] = '2026-09-05T19:05Z'
        self.assertIsNone(select_event(match, [candidate]))
        candidate = event()
        candidate['competitions'][0]['competitors'][0]['team']['displayName'] = 'Paris FC'
        self.assertIsNone(select_event(match, [candidate]))

    def test_only_starters_and_distinct_source_ids(self):
        match = fixture()
        original = copy.deepcopy(match)
        result = self.adapter().enrich(match)
        self.assertEqual(match, original)
        self.assertEqual(result['id'], 1)
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
            result = self.adapter(data).enrich(fixture())
            self.assertEqual(result['lineup_status'], 'unavailable')
            self.assertNotIn('lineup', result['homeTeam'])

    def test_formation_place_orders_the_list_only_when_complete(self):
        data = summary()
        for roster in data['rosters']:
            for index, player in enumerate(roster['roster'][:11]):
                player['formationPlace'] = str(11 - index)
        result = self.adapter(data).enrich(fixture())
        places = [p['formationPlace'] for p in result['homeTeam']['lineup']]
        self.assertEqual(places, [str(n) for n in range(1, 12)])
        self.assertEqual(result['homeTeam']['lineup'][0]['name'], 'Player 110')

        partial = summary()
        partial['rosters'][0]['roster'][0]['formationPlace'] = '5'
        result = self.adapter(partial).enrich(fixture())
        self.assertEqual([p['name'] for p in result['homeTeam']['lineup']],
                         [f'Player {100 + i}' for i in range(11)])

    def test_run_of_play_names_both_actors_in_the_published_order(self):
        result = self.adapter().enrich(fixture())
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
        result = self.adapter().enrich(fixture())
        bench = result['homeTeam']['bench']
        self.assertEqual([p['fonote_id'] for p in bench],
                         ['espn-%d' % n for n in range(111, 118)])
        starters = {p['fonote_id'] for p in result['homeTeam']['lineup']}
        self.assertFalse(starters & {p['fonote_id'] for p in bench})

        # A nameless substitute costs one late marker, never the composition.
        broken = summary()
        broken['rosters'][0]['roster'][12]['athlete'] = {'id': 'x'}
        result = self.adapter(broken).enrich(fixture())
        self.assertEqual(result['lineup_status'], 'available')
        self.assertEqual(len(result['homeTeam']['lineup']), 11)
        self.assertEqual(len(result['homeTeam']['bench']), 6)

    def test_match_without_a_published_run_of_play_still_composes(self):
        result = self.adapter(summary(keyEvents=[])).enrich(fixture())
        self.assertEqual(result['lineup_status'], 'available')
        self.assertEqual(result['timeline'], [])
        self.assertEqual(result['clock'], {})
        self.assertEqual(len(result['homeTeam']['lineup']), 11)

    def test_refreshing_a_match_costs_one_request_not_two(self):
        adapter = Espn()
        adapter.fetch = Mock(side_effect=[{'events': [event()]}, summary(), summary(), summary()])
        for _ in range(3):
            self.assertEqual(adapter.enrich(fixture())['lineup_status'], 'available')
        # The scoreboard answers "which event is this" once; the answer cannot change.
        asked = [call.args[1] for call in adapter.fetch.call_args_list]
        self.assertEqual(asked, ['scoreboard', 'summary', 'summary', 'summary'])

    def test_a_match_in_play_is_re_read_far_sooner_than_one_that_is_not(self):
        for status, expected in [('IN_PLAY', LIVE_CACHE), ('PAUSED', LIVE_CACHE),
                                 ('TIMED', CACHE), (None, CACHE)]:
            adapter = self.adapter()
            match = fixture()
            if status:
                match['status'] = status
            adapter.enrich(match)
            for call in adapter.fetch.call_args_list:
                self.assertEqual(call.args[3] if len(call.args) > 3 else call.kwargs['ttl'],
                                 expected, status)

    def test_an_unmatched_fixture_is_retried_rather_than_remembered(self):
        adapter = Espn()
        elsewhere = event()
        elsewhere['competitions'][0]['competitors'][0]['team']['displayName'] = 'Paris FC'
        adapter.fetch = Mock(side_effect=[{'events': [elsewhere]}, {'events': [event()]}, summary()])
        self.assertEqual(adapter.enrich(fixture())['lineup_status'], 'unmatched')
        self.assertEqual(adapter.enrich(fixture())['lineup_status'], 'available')

    def test_provider_counts_travel_beside_the_notes(self):
        result = self.adapter().enrich(fixture())
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
        del bare['boxscore']
        del bare['gameInfo']
        for roster in bare['rosters']:
            for player in roster['roster']:
                del player['stats']
        result = self.adapter(bare).enrich(fixture())
        self.assertEqual(result['lineup_status'], 'available')
        self.assertEqual(result['team_stats'], {})
        self.assertEqual(result['ground'], {'venue': '', 'attendance': None, 'referee': ''})
        self.assertEqual(result['homeTeam']['lineup'][0]['stats'], {})

    def test_club_colours_stay_readable_and_distinct(self):
        result = self.adapter().enrich(fixture())
        home = result['homeTeam']['colour']
        away = result['awayTeam']['colour']
        for colour in (home, away):
            self.assertRegex(colour, r'^#[0-9A-F]{6}$')
            self.assertGreaterEqual(contrast(parse_colour(colour), INK), MIN_CONTRAST)
        # A navy host is lightened rather than shown as the near-black the club publishes.
        self.assertGreater(luminance(parse_colour(home)), luminance((0x21, 0x30, 0x4D)))
        self.assertGreaterEqual(distance(parse_colour(home), parse_colour(away)), MIN_DISTANCE)

    def test_two_similar_clubs_use_the_away_alternate(self):
        both_red = self.adapter(summary('ED1C24', 'E91514', '004C37')).enrich(fixture())
        away = parse_colour(both_red['awayTeam']['colour'])
        self.assertGreaterEqual(distance(parse_colour(both_red['homeTeam']['colour']), away),
                                MIN_DISTANCE)
        self.assertEqual(away, readable((0x00, 0x4C, 0x37)))

    def test_missing_colours_fall_back_without_breaking_the_pitch(self):
        result = self.adapter(summary('', 'not-a-colour', None)).enrich(fixture())
        self.assertEqual(result['homeTeam']['colour'], '#7D9CD9')
        self.assertRegex(result['awayTeam']['colour'], r'^#[0-9A-F]{6}$')
        self.assertGreaterEqual(distance(parse_colour(result['homeTeam']['colour']),
                                         parse_colour(result['awayTeam']['colour'])), MIN_DISTANCE)

    def test_failure_preserves_match_for_general_notes(self):
        adapter = Espn()
        adapter.fetch = Mock(side_effect=OSError('offline'))
        result = adapter.enrich(fixture())
        self.assertEqual(result['lineup_status'], 'provider_error')
        self.assertEqual(result['id'], 1)

    def test_unsupported_competition_does_not_call_provider(self):
        match = fixture()
        match['competition']['code'] = 'unknown'
        adapter = self.adapter()
        self.assertEqual(adapter.enrich(match)['lineup_status'], 'unsupported')
        adapter.fetch.assert_not_called()
