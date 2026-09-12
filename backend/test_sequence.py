"""Format shared with Android, and consistency of timing relations."""
import copy
import unittest
from server import validate_schema


class SequenceTest(unittest.TestCase):
    def schema(self):
        return dict(version=3, board='blank', shapes=[], steps=[dict(id='step', name='Appel', t=10)],
                    tokens=[dict(id='a', team='home', x=.1, y=.2,
                                 keys=[dict(id='a0', t=0, x=.1, y=.2),
                                       dict(id='a1', t=10, x=.5, y=.2)]),
                            dict(id='b', team='home', x=.4, y=.5,
                                 keys=[dict(id='b0', t=10, x=.4, y=.5, after='a1', offset=0),
                                       dict(id='b1', t=20, x=.7, y=.5, after='b0', offset=10)])], ball=[])

    def test_linked_sequence(self):
        validate_schema(self.schema())

    def test_tackle_on_a_player_track(self):
        schema = self.schema()
        schema['tokens'][0]['keys'][1]['kind'] = 'tackle'
        validate_schema(schema)
        schema['tokens'][0]['keys'][1]['kind'] = 'foul'
        with self.assertRaises(ValueError):
            validate_schema(schema)

    def test_invalid_references_and_times(self):
        for mutate in [
            lambda s: s['tokens'][1]['keys'][0].update(after='missing'),
            lambda s: s['tokens'][1]['keys'][0].update(offset=1),
            lambda s: s['tokens'][1]['keys'][0].update(offset=True),
            lambda s: s['tokens'][1]['keys'][0].update(id='a0'),
            lambda s: s['tokens'][0]['keys'][1].update(after='b0', offset=0),
            lambda s: s['tokens'][0]['keys'][0].update(baked=1),
            lambda s: s['steps'][0].update(t=1201),
            lambda s: s['steps'][0].update(name=''),
            lambda s: s['steps'].append(copy.deepcopy(s['steps'][0])),
        ]:
            schema = self.schema()
            mutate(schema)
            with self.assertRaises(ValueError):
                validate_schema(schema)

    def test_long_dependency_chain(self):
        schema = self.schema()
        schema['tokens'] = []
        previous = None
        for actor in range(30):
            keys = []
            for time in range(120):
                key = dict(id=f'{actor}-{time}', t=time, x=.5, y=.5)
                if previous:
                    key.update(after=previous['id'], offset=time-previous['t'])
                keys.append(key)
                previous = key
            schema['tokens'].append(dict(id=str(actor), team='home', x=.5, y=.5, keys=keys))
        validate_schema(schema)
