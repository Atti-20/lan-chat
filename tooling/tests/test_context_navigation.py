import contextlib
import copy
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_workspace import workspace


class ContextNavigationTests(unittest.TestCase):
    def test_flutter_build_artifacts_do_not_change_source_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "ios/Runner/AppDelegate.swift"
            source.parent.mkdir(parents=True)
            source.write_text("// app", encoding="utf-8")
            before = workspace.source_files(root, {".swift"})
            generated = root / "ios/Flutter/ephemeral/Packages/Generated/Package.swift"
            generated.parent.mkdir(parents=True)
            generated.write_text("// generated", encoding="utf-8")
            self.assertEqual(workspace.source_files(root, {".swift"}), before)
            self.assertEqual(before, [source])

    def test_task_routes_use_real_entries_deduplicate_root_and_fit_budget(self):
        config = workspace.manifest()
        modules = {item["id"]: item for item in config["modules"]}
        for route in config["taskRoutes"]:
            result = workspace.task_context(route, modules)
            self.assertEqual(result["instructions"].count("AGENTS.md"), 1)
            self.assertEqual(len(result["instructions"]), len(set(result["instructions"])))
            self.assertLessEqual(len(workspace.format_context(result).encode("utf-8")), workspace.CONTEXT_SUMMARY_MAX_BYTES)
            for path in [*result["entries"], *result["tests"]]:
                self.assertTrue((workspace.ROOT / path).is_file(), path)

    def test_context_uses_only_ancestor_instructions_and_nearest_override(self):
        with tempfile.TemporaryDirectory(prefix="meshx context ") as directory:
            root = Path(directory)
            contents = {"AGENTS.md": "根入口", "apps/AGENTS.md": "apps",
                        "apps/web/AGENTS.md": "fallback", "apps/web/AGENTS.override.md": "specific",
                        "apps/flutter-prototype/AGENTS.md": "unrelated"}
            for name, value in contents.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(value, encoding="utf-8")
            item = {"id": "web", "path": "apps/web", "role": "Web",
                    "entries": ["src/main.ts"], "relatedDocs": [], "verificationScopes": ["web"]}
            with patch.object(workspace, "ROOT", root):
                result = workspace.module_context(item)
            expected = ["AGENTS.md", "apps/AGENTS.md", "apps/web/AGENTS.override.md"]
            self.assertEqual(result["instructions"], expected)
            self.assertEqual(result["instructionBytes"], sum(len(contents[p].encode("utf-8")) for p in expected))
            self.assertNotIn("flutter", workspace.format_context(result))
            self.assertEqual(result["entries"], ["apps/web/src/main.ts"])

    def test_empty_override_falls_back_to_module_agents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "AGENTS.override.md").touch()
            (root / "AGENTS.md").write_text("base")
            with patch.object(workspace, "ROOT", root):
                self.assertEqual(workspace.instruction_chain("."), ["AGENTS.md"])

    def test_context_check_rejects_missing_docs_unknown_verification_and_oversize(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "AGENTS.md").write_text("rules")
            config = {"modules": [{"id": "demo", "path": ".", "role": "演示" * 2000,
                       "entries": [], "relatedDocs": [{"path": "missing.md", "when": "demo"}],
                       "verificationScopes": ["unknown"]}], "verification": {}}
            with patch.object(workspace, "ROOT", root):
                errors = workspace.check_context_entries(config)
            self.assertTrue(any("Missing related document" in e for e in errors))
            self.assertTrue(any("Unknown verification scope" in e for e in errors))
            self.assertTrue(any("summary exceeds" in e for e in errors))

    def test_contracts_dry_run_lists_commands_without_executing_or_reading_contracts(self):
        output = io.StringIO()
        with (patch.object(workspace.sys, "argv", ["workspace.py", "verify", "contracts", "--dry-run"]),
              patch.object(workspace, "check_contracts") as check,
              patch.object(workspace.subprocess, "run") as run,
              contextlib.redirect_stdout(output)):
            self.assertEqual(workspace.main(), 0)
            check.assert_not_called()
            run.assert_not_called()
        self.assertIn("npm --prefix tooling/contracts run check", output.getvalue())
        self.assertIn("-Dtest=RestContractTest", output.getvalue())

    def test_flutter_cannot_forward_one_argument_to_all_its_different_tools(self):
        with (patch.object(workspace.sys, "argv", ["workspace.py", "verify", "flutter", "--", "--flag"]),
              patch.object(workspace.subprocess, "run") as run,
              contextlib.redirect_stderr(io.StringIO())):
            with self.assertRaises(SystemExit) as failure:
                workspace.main()
            self.assertEqual(failure.exception.code, 2)
            run.assert_not_called()


class ApiNavigationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="meshx api navigation ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        path = self.root / "services/server/src/main/java/com/demo/AuditController.java"
        path.parent.mkdir(parents=True)
        path.write_text("// Source location only; routes must come from the snapshot.")
        operation = {"operationId": "recent", "x-source": {"class": "com.demo.AuditController", "method": "recent"}}
        self.document = {"paths": {"/api/v1/audit": {"get": copy.deepcopy(operation)},
                                    "/api/v2/audit": {"parameters": [], "get": copy.deepcopy(operation)}},
                         "components": {"schemas": {}}, "security": [{"bearerAuth": []}]}
        self.document["paths"]["/api/v1/audit"]["get"]["security"] = []

    def test_snapshot_routes_include_all_aliases_ignore_path_metadata_and_inherit_security(self):
        outputs = workspace.api_summary.outputs(self.document, self.root)
        summary, routes = outputs["docs/generated/api-summary.md"], outputs["docs/generated/api-routes.md"]
        self.assertIn("2 个路径、2 个操作", summary)
        self.assertIn("api-routes.md#com-demo-auditcontroller", summary)
        self.assertIn("GET | `/api/v1/audit` | `recent / recent` | 公开入口", routes)
        self.assertIn("GET | `/api/v2/audit` | `recent / recent` | bearerAuth", routes)
        self.assertNotIn("parameters |", routes)

    def test_missing_source_cannot_silently_disappear_from_summary(self):
        del self.document["paths"]["/api/v1/audit"]["get"]["x-source"]
        with self.assertRaisesRegex(ValueError, "MVC x-source"):
            workspace.api_summary.outputs(self.document, self.root)

    def test_stale_java_location_requires_snapshot_review(self):
        self.document["paths"]["/api/v1/audit"]["get"]["x-source"]["class"] = "com.demo.RemovedController"
        with self.assertRaisesRegex(ValueError, "source no longer exists"):
            workspace.api_summary.outputs(self.document, self.root)
