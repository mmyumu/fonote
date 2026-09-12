"""Catalogue éditorial Fonote ; aucun accès réseau pour construire les suivis."""
import copy
import json
from pathlib import Path

CATALOGUE = json.loads(Path(__file__).with_name('competitions.json').read_text())
COMPETITIONS = {item['code']: item for item in CATALOGUE['competitions']}


def catalogue():
    return dict(copy.deepcopy(CATALOGUE), count=len(COMPETITIONS))
