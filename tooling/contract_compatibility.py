"""Conservative V1 compatibility gate, separate from regeneration/drift checks.

Existing operation/schema/event semantics are frozen. Additive REST operations and
schemas are allowed; changes within an existing shape require an explicit review.
This deliberately over-reports compatible changes rather than guessing variance
through OpenAPI unions, opaque payloads or application authorization rules.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = 'contracts/compatibility/v1-baseline.json'
ANNOTATIONS = {'description', 'summary', 'title', 'example', 'examples', 'externalDocs',
               'x-java-source', 'x-source', 'x-permission-source'}


def semantic(value):
    if isinstance(value, dict):
        return {key: semantic(child) for key, child in sorted(value.items()) if key not in ANNOTATIONS}
    if isinstance(value, list):
        return [semantic(child) for child in value]
    return value


def snapshot(root=ROOT):
    def read(path):
        return json.loads((root / path).read_text())
    api = read('contracts/rest/openapi.json')
    return semantic({
        'openapi': api['openapi'], 'paths': api['paths'], 'components': api['components'],
        'envelope': read('contracts/websocket/envelope.schema.json'),
        'events': read('contracts/websocket/events.schema.json'),
        'generation': read('tooling/contracts/generation.json'),
        # Fixtures are executable expectations, not ordinary generated output.
        'vectorsSha256': hashlib.sha256((root / 'contracts/fixtures/core-v1.json').read_bytes()).hexdigest(),
    })


def differences(old, new, path='$'):
    if isinstance(old, dict) and isinstance(new, dict):
        for key in sorted(old.keys() | new.keys()):
            if key not in old:
                yield f'{path}/{key}: added'
            elif key not in new:
                yield f'{path}/{key}: removed'
            else:
                yield from differences(old[key], new[key], f'{path}/{key}')
    elif old != new:
        yield f'{path}: changed'


def check(current, baseline):
    errors = []
    for key, old in baseline.items():
        if key == 'paths':
            # New endpoints are additive. Existing methods, request/response and
            # security metadata remain frozen, including unknown schema keywords.
            for path, methods in old.items():
                for method, operation in methods.items():
                    actual = current.get('paths', {}).get(path, {}).get(method)
                    errors.extend(differences(operation, actual, f'REST {method.upper()} {path}'))
        elif key == 'components':
            for group, entries in old.items():
                for name, value in entries.items():
                    actual = current.get('components', {}).get(group, {}).get(name)
                    errors.extend(differences(value, actual, f'REST {group}/{name}'))
        else:
            errors.extend(differences(old, current.get(key), key))
    return errors


def check_files(root=ROOT):
    path = root / BASELINE
    if not path.exists():
        return ['Missing reviewed V1 compatibility baseline']
    baseline = json.loads(path.read_text())
    if baseline.get('version') != 1 or not baseline.get('reviewNote'):
        return ['Invalid reviewed V1 compatibility baseline']
    return check(snapshot(root), baseline['snapshot'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--accept-reviewed-change', metavar='REVIEW_NOTE',
                        help='Explicitly replace baseline AFTER review; never used by generate or CI')
    args = parser.parse_args()
    if args.accept_reviewed_change:
        if len(args.accept_reviewed_change.strip()) < 20:
            parser.error('Provide a concrete review record/reason, at least 20 characters')
        path = args.root / BASELINE
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({'version': 1, 'reviewNote': args.accept_reviewed_change,
                                    'snapshot': snapshot(args.root)}, ensure_ascii=False, indent=2) + '\n')
        print('Reviewed baseline written; inspect and retain its diff with the change.')
        return 0
    errors = check_files(args.root)
    for error in errors[:40]:
        print('CONTRACT_REVIEW_REQUIRED: ' + error)
    if errors:
        print(f'{len(errors)} semantic changes require review. Regeneration alone cannot accept them.')
        return 1
    print('V1 compatibility baseline passed (bounded structural gate; runtime regressions are separate).')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
