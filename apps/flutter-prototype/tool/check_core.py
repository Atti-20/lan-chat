#!/usr/bin/env python3
"""Reject any dependency escaping the actual pure Dart core implementation."""
from pathlib import Path
import re

CORE = Path(__file__).resolve().parents[1] / 'lib/core'

def check(core=CORE):
    files = list(core.rglob('*.dart'))
    if not files:
        raise ValueError('Dart core is missing')
    for file in files:
        source = file.read_text()
        for directive in re.findall(r'\b(?:import|export|part)\s+([^;]+);', source):
            for uri in re.findall(r"['\"]([^'\"]+)['\"]", directive):
                if uri in ('dart:core', 'dart:collection', 'dart:convert', 'dart:math', 'dart:async'):
                    continue
                target = (file.parent / uri).resolve()
                if ':' in uri or not target.is_relative_to(core.resolve()) or not target.is_file():
                    raise ValueError(f'{file.name}: core dependency escapes into {uri}')
    return len(files)

if __name__ == '__main__':
    print(f'PASS: {check()} pure Dart core files have no Flutter/theme/plugin/platform dependency.')
