import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from test_workspace import workspace

verification = workspace.verification


class VerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="meshx verification ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def step(self, code="pass", **changes):
        return {"scope": "example", "name": "child", "cwd": ".",
                "argv": [sys.executable, "-c", code], **changes}

    def run_plan(self, steps, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return verification.run_plan(steps, self.root, [], **kwargs)

    def test_real_failure_preserves_code_and_does_not_execute_later_steps(self):
        marker = self.root / "should not exist"
        result = self.run_plan([self.step("raise SystemExit(7)"),
                                self.step("from pathlib import Path; Path('should not exist').touch()")])
        self.assertEqual(result["exitCode"], 7)
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual([step["status"] for step in result["steps"]], ["FAIL", "NOT_RUN"])
        self.assertFalse(marker.exists())

    def test_real_child_keeps_working_directory_and_argument_bytes(self):
        module = self.root / "module with spaces"
        module.mkdir()
        argument = "a b;$(not-a-command) & literal"
        step = self.step("import json,os,sys; open('result.json','w').write(json.dumps([os.getcwd(),sys.argv[1]]))",
                         cwd="module with spaces")
        step["argv"].append(argument)
        result = self.run_plan([step])
        self.assertEqual(result["status"], "PASS")
        observed = json.loads((module / "result.json").read_text())
        self.assertEqual(Path(observed[0]).resolve(), module.resolve())
        self.assertEqual(observed[1], argument)

    def test_missing_tool_is_blocked_and_not_a_child_failure(self):
        result = self.run_plan([self.step(argv=[str(self.root / "absent tool")]), self.step()])
        self.assertEqual(result["exitCode"], verification.BLOCKED_EXIT)
        self.assertEqual([s["status"] for s in result["steps"]], ["BLOCKED", "NOT_RUN"])
        self.assertIsNone(result["steps"][0]["exitCode"])
        self.assertIn("TOOL_MISSING", result["steps"][0]["reason"])

    def test_missing_input_and_unsupported_platform_do_not_execute(self):
        with patch.object(verification.subprocess, "run") as run:
            result = self.run_plan([self.step(requires=["missing/lock"], platforms=["unsupported-platform"])])
            run.assert_not_called()
        self.assertEqual(result["status"], "BLOCKED")
        self.assertIn("INPUT_MISSING", result["steps"][0]["reason"])
        self.assertIn("PLATFORM_UNAVAILABLE", result["steps"][0]["reason"])

    def test_os_spawn_error_is_blocked(self):
        with patch.object(verification.subprocess, "run", side_effect=OSError("cannot spawn")):
            result = self.run_plan([self.step()])
        self.assertEqual(result["status"], "BLOCKED")
        self.assertIsNone(result["steps"][0]["exitCode"])

    def test_signal_exit_is_nonzero_and_raw_code_is_reported(self):
        with patch.object(verification.subprocess, "run", return_value=subprocess.CompletedProcess([], -15)):
            result = self.run_plan([self.step()])
        self.assertEqual(result["exitCode"], 143)
        self.assertEqual(result["steps"][0]["exitCode"], -15)
        self.assertEqual(result["status"], "FAIL")

    def test_interrupt_never_becomes_pass(self):
        with patch.object(verification.subprocess, "run", side_effect=KeyboardInterrupt):
            result = self.run_plan([self.step(), self.step()])
        self.assertEqual(result["exitCode"], 130)
        self.assertEqual(result["status"], "NOT_RUN")
        self.assertEqual([s["status"] for s in result["steps"]], ["NOT_RUN", "NOT_RUN"])

    def test_dry_run_does_not_check_prerequisites_or_execute(self):
        with (patch.object(verification, "prerequisites") as check,
              patch.object(verification.subprocess, "run") as run):
            result = self.run_plan([self.step(argv=["missing"])], dry_run=True)
            check.assert_not_called()
            run.assert_not_called()
        self.assertEqual(result["exitCode"], 0)
        self.assertEqual(result["status"], "NOT_RUN")

    def test_doctor_only_checks_inputs_and_leaves_all_validation_not_run(self):
        with patch.object(verification.subprocess, "run") as run, contextlib.redirect_stdout(io.StringIO()):
            result = verification.doctor([self.step(), self.step(requires=["missing"])], self.root)
            run.assert_not_called()
        self.assertEqual(result["exitCode"], verification.BLOCKED_EXIT)
        self.assertEqual([s["status"] for s in result["steps"]], ["NOT_RUN", "NOT_RUN"])
        self.assertEqual([s["prerequisiteStatus"] for s in result["steps"]], ["PASS", "BLOCKED"])

    def test_history_scan_rejects_shallow_checkout_before_scanner_runs(self):
        with patch.object(verification.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "true\n")) as run:
            result = self.run_plan([self.step(fullGitHistory=True)])
            run.assert_called_once()
            self.assertEqual(run.call_args.args[0], ["git", "rev-parse", "--is-shallow-repository"])
        self.assertEqual(result["status"], "BLOCKED")
        self.assertIn("CHECKOUT_INCOMPLETE", result["steps"][0]["reason"])

    def test_full_history_allows_the_existing_scan_command(self):
        outputs = [subprocess.CompletedProcess([], 0, "false\n"), subprocess.CompletedProcess([], 0)]
        with patch.object(verification.subprocess, "run", side_effect=outputs) as run:
            result = self.run_plan([self.step(fullGitHistory=True)])
            self.assertEqual(run.call_count, 2)
        self.assertEqual(result["status"], "PASS")

    def test_empty_plans_and_empty_argv_cannot_succeed(self):
        with self.assertRaises(ValueError):
            self.run_plan([])
        with self.assertRaises(ValueError):
            self.run_plan([self.step(argv=[])])

    def test_report_preserves_every_status_and_null_exit_code(self):
        result = self.run_plan([self.step(argv=["/no/such/executable"]), self.step()])
        path = self.root / "report with spaces" / "results.json"
        verification.write_report(result, str(path))
        self.assertEqual(json.loads(path.read_text()), result)

    def test_windows_launchers_and_python_interpreter_do_not_require_shell(self):
        for tool, expected in [("npm", "npm.cmd"), ("flutter", "flutter.bat"),
                               ("./mvnw", str(self.root / "mvnw.cmd")),
                               ("./gradlew", str(self.root / "gradlew.bat")), ("python3", sys.executable)]:
            with patch.dict(os.environ, {}, clear=True):
                argv = verification.command_argv(self.step(argv=[tool, "argument with spaces"]), self.root, [], "win32")
            self.assertEqual(argv, [expected, "argument with spaces"])

    def test_environment_overrides_keep_space_paths_in_one_argument(self):
        with patch.dict(os.environ, {"MESHX_GITLEAKS_BIN": "/a b/gitleaks", "MESHX_MAVEN_REPO": "/a b/m2"}):
            self.assertEqual(verification.command_argv(self.step(argv=["gitleaks", "git", "."]), self.root, []),
                             ["/a b/gitleaks", "git", "."])
            self.assertEqual(verification.command_argv(self.step(argv=["./mvnw", "test"]), self.root, ["-Dtest=Example"], "darwin"),
                             ["./mvnw", "-Dmaven.repo.local=/a b/m2", "test", "-Dtest=Example"])

    def test_all_group_retains_order_and_rejects_empty_scope(self):
        config = {"verification": {"one": [{"cwd": ".", "argv": ["one"]}], "two": []},
                  "verificationGroups": {"all": ["one", "two"]}}
        with patch.object(workspace, "manifest", return_value=config):
            with self.assertRaisesRegex(ValueError, "empty verification scope"):
                workspace.verification_plan("all")
            config["verification"]["two"] = [{"cwd": ".", "argv": ["two"]}]
            self.assertEqual([s["scope"] for s in workspace.verification_plan("all")], ["one", "two"])

    def test_cli_unknown_scope_is_nonzero_and_report_failure_is_not_swallowed(self):
        script = str(Path(workspace.__file__).resolve())
        invalid = subprocess.run([sys.executable, script, "verify", "not-a-scope"], cwd=self.root,
                                 capture_output=True, text=True)
        self.assertEqual(invalid.returncode, 2)
        self.assertIn("[FAIL]", invalid.stderr)
        invalid_report = subprocess.run([sys.executable, script, "verify", "web", "--dry-run", "--report", str(self.root)],
                                        cwd=self.root, capture_output=True, text=True)
        self.assertEqual(invalid_report.returncode, 1)
        self.assertIn("[NOT_RUN]", invalid_report.stdout)
        self.assertIn("[FAIL]", invalid_report.stderr)


if __name__ == "__main__":
    unittest.main()
