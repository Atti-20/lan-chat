#!/usr/bin/env python3
"""Compatibility entry point for the shared token generator."""
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tooling'))
from design_tokens import outputs

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for name, expected in outputs().items():
        path = ROOT / name
        if args.check:
            if not path.exists() or path.read_text() != expected:
                raise SystemExit(f'Stale {name}; run python3 tooling/workspace.py generate')
        else:
            path.write_text(expected)
    print('CSS and Dart design tokens match tokens.json.')
