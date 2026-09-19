import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("workspace", Path(__file__).parents[1] / "workspace.py")
workspace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workspace)


class WorkspaceTests(unittest.TestCase):
    def test_schema_generation_rejects_constraints_it_cannot_represent(self):
        schema = json.loads(workspace.read("contracts/websocket/envelope.schema.json"))
        schema["properties"]["event"]["minLength"] = 1
        with self.assertRaisesRegex(ValueError, "Unsupported envelope field"):
            workspace.generated_envelope(schema)

    def test_contract_check_detects_java_type_drift(self):
        original = workspace.read
        def altered(path):
            text = original(path)
            return text.replace("private Long timestamp", "private String timestamp") if path.endswith("WebSocketEnvelope.java") else text
        with patch.object(workspace, "read", side_effect=altered):
            self.assertTrue(any("shape differs" in e for e in workspace.check_contracts()))

    def test_runtime_and_reverse_dependencies_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="meshx workspace ") as directory:
            root = Path(directory)
            file = root / "packages/domain-ts/src/example.ts"
            file.parent.mkdir(parents=True)
            file.write_text("import { ref } from 'vue'\nexport * from '../../../apps/web/src/main'\nwindow.location.href\n")
            with patch.object(workspace, "ROOT", root):
                errors = workspace.check_shared_dependencies()
            self.assertEqual(len(errors), 3)

    def test_allowed_domain_to_protocol_import_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / "packages/domain-ts/src/example.ts"
            file.parent.mkdir(parents=True)
            file.write_text("import type { WsEnvelope } from '../../protocol/src/index'\n")
            with patch.object(workspace, "ROOT", root):
                self.assertEqual(workspace.check_shared_dependencies(), [])

    def test_fresh_snapshot_allows_absent_local_evidence_but_checks_source_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            for name in ['AGENTS.md', 'ARCHITECTURE.md', 'README.md', 'docs/README.md', 'docs/AGENTS.md']:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('')
            (root / 'README.md').write_text('[local evidence](output/previous-run/)\n[missing source](contracts/missing.json)\n')
            with patch.object(workspace, 'ROOT', root), patch.object(workspace, 'manifest', return_value={'modules': []}), \
                    patch.object(workspace, 'check_context_entries', return_value=[]), \
                    patch.object(workspace, 'generate', return_value=[]), patch.object(workspace, 'check_contracts', return_value=[]):
                errors = workspace.check_workspace()
            self.assertEqual(errors, ['Broken link in README.md: contracts/missing.json'])

    def test_dry_run_does_not_execute_commands(self):
        with patch.object(workspace.subprocess, "run") as run:
            result = workspace.run_command({"cwd": ".", "argv": ["missing-tool"]}, [], True)
            self.assertEqual(result, 0)
            run.assert_not_called()

    def test_command_keeps_arguments_and_space_paths_separate(self):
        with tempfile.TemporaryDirectory(prefix="meshx workspace ") as directory:
            root = Path(directory)
            (root / "module").mkdir()
            (root / "module/tool").touch()
            (root / "module/tool").chmod(0o755)
            with patch.object(workspace, "ROOT", root), patch.object(workspace.subprocess, "run") as run:
                run.return_value.returncode = 7
                result = workspace.run_command({"cwd": "module", "argv": ["./tool", "a b"]}, ["--flag"], False)
                self.assertEqual(result, 7)
                run.assert_called_once_with(["./tool", "a b", "--flag"], cwd=root / "module", check=False)


if __name__ == "__main__":
    unittest.main()
