import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('compatibility', ROOT / 'tooling/contract_compatibility.py')
compatibility = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compatibility)


class CompatibilityGateTest(unittest.TestCase):
    def test_real_baseline_and_additive_endpoint_or_documentation(self):
        baseline = compatibility.snapshot()
        self.assertFalse(compatibility.check(baseline, baseline))
        current = copy.deepcopy(baseline)
        current['paths']['/api/v1/future'] = {'get': {'responses': {}}}
        self.assertFalse(compatibility.check(current, baseline))
        self.assertEqual(compatibility.semantic({'type': 'string', 'description': 'changed'}), {'type': 'string'})

    def test_breaking_changes_fail_even_after_all_generated_files_are_regenerated(self):
        old = compatibility.snapshot()
        mutations = [
            lambda s: s['paths'].pop('/api/v1/auth/login'),
            lambda s: s['paths']['/api/v1/auth/login'].pop('post'),
            lambda s: s['components']['schemas']['LoginDTO']['properties'].pop('username'),
            lambda s: s['components']['schemas']['LoginDTO']['required'].append('deviceName'),
            lambda s: s['components']['schemas']['LoginDTO']['properties']['deviceName'].update(nullable=False),
            lambda s: s['components']['schemas']['LoginDTO']['properties']['deviceType']['x-normalized-values'].remove('ios'),
            lambda s: s['components']['schemas']['ResultLoginVO']['properties']['code'].update(type='string'),
            lambda s: s['paths']['/api/v1/chat/conversations']['get'].update(security=[]),
            lambda s: s['envelope']['properties']['version'].update(const=2),
            lambda s: s['events']['$defs']['ChatAckPayload']['properties']['sequence'].update(type='string'),
            lambda s: s['events']['$defs']['SyncRequestPayload']['properties']['limit'].update(maximum=50),
            lambda s: s.update(vectorsSha256='silently-rewritten-expectations'),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                current = copy.deepcopy(old)
                mutate(current)
                self.assertTrue(compatibility.check(current, old))

    def test_cli_exits_nonzero_for_removed_method_in_isolated_copy(self):
        with tempfile.TemporaryDirectory(prefix='meshx-contract-') as directory:
            root = Path(directory)
            for path in ['contracts/rest/openapi.json', 'contracts/websocket/envelope.schema.json',
                         'contracts/websocket/events.schema.json', 'contracts/fixtures/core-v1.json',
                         'tooling/contracts/generation.json', compatibility.BASELINE]:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / path).read_bytes())
            target = root / 'contracts/rest/openapi.json'
            api = json.loads(target.read_text())
            del api['paths']['/api/v1/auth/login']['post']
            target.write_text(json.dumps(api))
            result = subprocess.run([sys.executable, str(ROOT / 'tooling/contract_compatibility.py'),
                                     '--root', str(root)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn('REST POST /api/v1/auth/login', result.stdout)
