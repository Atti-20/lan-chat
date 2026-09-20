"""Run the workspace manifest's existing commands; never install or hide failures."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import time

BLOCKED_EXIT = 3


def command_argv(command: dict, root: Path, extra: list[str], platform: str | None = None) -> list[str]:
    argv = list(command["argv"]) + extra
    if not argv or not all(isinstance(value, str) and value for value in argv):
        raise ValueError("A verification command must contain non-empty argv strings")
    platform = sys.platform if platform is None else platform
    if argv[0] == "python3":
        argv[0] = sys.executable
    if argv[0] == "gitleaks" and os.environ.get("MESHX_GITLEAKS_BIN"):
        argv[0] = os.environ["MESHX_GITLEAKS_BIN"]
    if "mvnw" in Path(argv[0]).name and os.environ.get("MESHX_MAVEN_REPO"):
        argv.insert(1, "-Dmaven.repo.local=" + os.environ["MESHX_MAVEN_REPO"])
    if platform == "win32":
        argv[0] = {"npm": "npm.cmd", "flutter": "flutter.bat",
                   "./mvnw": str(root / command["cwd"] / "mvnw.cmd"),
                   "./gradlew": str(root / command["cwd"] / "gradlew.bat")}.get(argv[0], argv[0])
    return argv


def executable_exists(name: str, cwd: Path) -> bool:
    if Path(name).is_absolute() or "/" in name or "\\" in name:
        path = cwd / name
        return path.is_file() and (os.name == "nt" or os.access(path, os.X_OK))
    return shutil.which(name) is not None


def prerequisites(command: dict, argv: list[str], root: Path) -> list[str]:
    cwd = root / command["cwd"]
    errors = []
    if not cwd.is_dir():
        errors.append(f"INPUT_MISSING: working directory {cwd}")
    if command.get("platforms") and sys.platform not in command["platforms"]:
        errors.append(f"PLATFORM_UNAVAILABLE: requires {', '.join(command['platforms'])}; host is {sys.platform}")
    if not executable_exists(argv[0], cwd):
        errors.append(f"TOOL_MISSING: {argv[0]}")
    for name in command.get("tools", []):
        if not executable_exists(name, cwd):
            errors.append(f"TOOL_MISSING: {name}")
    for name in command.get("requires", []):
        if not (root / name).exists():
            errors.append(f"INPUT_MISSING: {name}; restore the existing locked dependencies/input first")
    if command.get("fullGitHistory") and not errors:
        try:
            result = subprocess.run(["git", "rev-parse", "--is-shallow-repository"], cwd=cwd,
                                    check=False, capture_output=True, text=True)
            if result.returncode or result.stdout.strip() != "false":
                errors.append("CHECKOUT_INCOMPLETE: full local Git history is required; no fetch was performed")
        except OSError as error:
            errors.append(f"TOOL_UNAVAILABLE: {error}")
    return errors


def run_step(command: dict, root: Path, extra: list[str], dry_run: bool = False,
             stopped: bool = False) -> dict:
    argv = command_argv(command, root, extra)
    result = {"scope": command["scope"], "name": command["name"],
              "cwd": str(root / command["cwd"]), "argv": argv,
              "status": "NOT_RUN", "exitCode": None, "durationSeconds": 0.0}
    if dry_run or stopped:
        result["reason"] = "dry-run; command was not executed" if dry_run else "a previous step failed or was blocked"
        return result
    errors = prerequisites(command, argv, root)
    if errors:
        result.update(status="BLOCKED", reason="; ".join(errors))
        return result
    print(f"[RUN] {command['scope']}/{command['name']} ({result['cwd']}): {shlex.join(argv)}", flush=True)
    start = time.monotonic()
    try:
        code = subprocess.run(argv, cwd=root / command["cwd"], check=False).returncode
        result.update(status="PASS" if code == 0 else "FAIL", exitCode=code)
        if code:
            result["reason"] = f"child command exited {code}; output is preserved above"
    except OSError as error:
        result.update(status="BLOCKED", reason=f"TOOL_UNAVAILABLE: {error}")
    except KeyboardInterrupt:
        result.update(status="NOT_RUN", exitCode=130, reason="interrupted; verification did not complete")
    result["durationSeconds"] = round(time.monotonic() - start, 3)
    return result


def exit_code(result: dict) -> int:
    if result["status"] == "BLOCKED":
        return BLOCKED_EXIT
    code = result["exitCode"] or 0
    return 128 - code if code < 0 else code


def print_step(result: dict) -> None:
    message = f"[{result['status']}] {result['scope']}/{result['name']}"
    if result["exitCode"] is not None:
        message += f" (exit {result['exitCode']})"
    if result.get("reason"):
        message += ": " + result["reason"]
    if result["status"] == "NOT_RUN":
        message += " | " + shlex.join(result["argv"])
    print(message, flush=True)


def run_plan(plan: list[dict], root: Path, extra: list[str], dry_run: bool = False) -> dict:
    if not plan:
        raise ValueError("Verification plan is empty; no successful empty scopes")
    results = []
    code = 0
    failure_status = None
    for command in plan:
        result = run_step(command, root, extra, dry_run, stopped=bool(code))
        results.append(result)
        print_step(result)
        if not code and exit_code(result):
            code = exit_code(result)
            failure_status = result["status"]
    status = "NOT_RUN" if dry_run else failure_status or "PASS"
    print(f"[{status}] verification summary: " + ", ".join(
        f"{state}={sum(item['status'] == state for item in results)}"
        for state in ("PASS", "FAIL", "BLOCKED", "NOT_RUN")), flush=True)
    return {"mode": "verify", "status": status, "exitCode": code, "steps": results}


def doctor(plan: list[dict], root: Path) -> dict:
    """Inspect command/input availability, not SDK health or business-service readiness."""
    if not plan:
        raise ValueError("Doctor plan is empty")
    results = []
    for command in plan:
        argv = command_argv(command, root, [])
        errors = prerequisites(command, argv, root)
        state = "BLOCKED" if errors else "PASS"
        reason = "; ".join(errors) if errors else "command and declared inputs found; tests/builds remain NOT_RUN"
        result = {"scope": command["scope"], "name": command["name"], "status": "NOT_RUN",
                  "prerequisiteStatus": state, "exitCode": None, "argv": argv,
                  "cwd": str(root / command["cwd"]), "reason": reason}
        results.append(result)
        print(f"[{state}] prerequisite {command['scope']}/{command['name']}: {reason}", flush=True)
    blocked = any(item["prerequisiteStatus"] == "BLOCKED" for item in results)
    status = "BLOCKED" if blocked else "PASS"
    print(f"[{status}] doctor: command/input checks only; validation NOT_RUN. "
          "No install, upgrade, SDK license acceptance, device or service startup.", flush=True)
    return {"mode": "doctor", "status": status, "exitCode": BLOCKED_EXIT if blocked else 0, "steps": results}


def write_report(report: dict, path: str | None) -> None:
    if path:
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
