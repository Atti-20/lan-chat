#!/usr/bin/env python3
"""Drive exact iOS cold-process and foreground recovery cycles for C08."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from typing import Any, Callable

BUNDLE = "com.meshx.meshxFlutterProbe"
SETTINGS_BUNDLE = "com.apple.Preferences"
REMOTE_EVIDENCE = "Library/Application Support/meshx-a05/c08-process-probe.json"


def command(*args: str, capture: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=True, text=True, capture_output=capture)


def device_args(device: str) -> list[str]:
    return ["--device", device]


def pull(device: str, destination: Path) -> dict[str, Any]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.unlink(missing_ok=True)
    command(
        "xcrun",
        "devicectl",
        "device",
        "copy",
        "from",
        *device_args(device),
        "--domain-type",
        "appDataContainer",
        "--domain-identifier",
        BUNDLE,
        "--source",
        REMOTE_EVIDENCE,
        "--destination",
        str(destination),
    )
    return json.loads(destination.read_text())


def runner_pids(device: str) -> list[int]:
    with tempfile.TemporaryDirectory(prefix="meshx-c08-processes-") as folder:
        output = Path(folder) / "processes.json"
        command(
            "xcrun",
            "devicectl",
            "device",
            "info",
            "processes",
            *device_args(device),
            "--json-output",
            str(output),
        )
        data = json.loads(output.read_text())
    result = []
    for process in data.get("result", {}).get("runningProcesses", []):
        executable = str(process.get("executable", ""))
        if executable.endswith("/Runner.app/Runner"):
            result.append(int(process["processIdentifier"]))
    return result


def terminate_runner(device: str) -> None:
    for pid in runner_pids(device):
        command(
            "xcrun",
            "devicectl",
            "device",
            "process",
            "terminate",
            *device_args(device),
            "--pid",
            str(pid),
            "--kill",
        )


def launch(device: str, bundle: str) -> None:
    command(
        "xcrun",
        "devicectl",
        "device",
        "process",
        "launch",
        *device_args(device),
        bundle,
    )


def wait_for(
    device: str,
    scratch: Path,
    predicate: Callable[[dict[str, Any]], bool],
    label: str,
    timeout: float = 30,
) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            data = pull(device, scratch)
            if predicate(data):
                return data
        except (OSError, ValueError, subprocess.CalledProcessError) as error:
            last_error = error
        time.sleep(0.5)
    raise RuntimeError(f"Timed out waiting for {label}: {last_error}")


def cold(device: str, output: Path, count: int) -> None:
    scratch = output / ".poll.json"
    try:
        baseline = pull(device, scratch)
        expected = len(baseline.get("runs", []))
    except (OSError, ValueError, subprocess.CalledProcessError):
        expected = 0
    host_rows: list[dict[str, Any]] = []
    for _ in range(count):
        terminate_runner(device)
        host_started = datetime.now(timezone.utc).isoformat()
        host_clock = time.monotonic()
        launch(device, BUNDLE)
        expected += 1
        data = wait_for(
            device,
            scratch,
            lambda value, expected=expected: len(value.get("runs", [])) >= expected
            and value["runs"][expected - 1].get("stage") in {"ready", "failed"},
            f"process run {expected}",
        )
        (output / f"cold-run-{expected}.json").write_text(
            json.dumps(data, indent=2)
        )
        if data["runs"][expected - 1].get("stage") != "ready":
            raise RuntimeError(f"Process run {expected} failed")
        elapsed = round((time.monotonic() - host_clock) * 1000)
        host_rows.append(
            {
                "processRun": expected,
                "hostStartedAt": host_started,
                "hostLaunchToReadyMs": elapsed,
            }
        )
        (output / "cold-host-summary.json").write_text(
            json.dumps(
                {
                    "schema": "meshx.c08-host-cold/1",
                    "bundleId": BUNDLE,
                    "rows": host_rows,
                },
                indent=2,
            )
        )
        print(f"cold run {expected} ready in <= {elapsed}ms host-observed")
    scratch.unlink(missing_ok=True)


def recover(device: str, output: Path, count: int) -> None:
    scratch = output / ".poll.json"
    launch(device, BUNDLE)
    data = wait_for(
        device,
        scratch,
        lambda value: bool(value.get("runs")) and value["runs"][-1].get("stage") == "ready",
        "Runner ready before OS cycles",
    )
    expected = len(data.get("osRecoveries", []))
    for _ in range(count):
        launch(device, SETTINGS_BUNDLE)
        expected += 1
        wait_for(
            device,
            scratch,
            lambda value, expected=expected: len(value.get("osRecoveries", [])) >= expected
            and value["osRecoveries"][expected - 1].get("offlineObservedAt") is not None,
            f"background/offline cycle {expected}",
        )
        launch(device, BUNDLE)
        data = wait_for(
            device,
            scratch,
            lambda value, expected=expected: value["osRecoveries"][expected - 1].get(
                "onlineAfterForegroundMs"
            )
            is not None,
            f"foreground/ONLINE cycle {expected}",
        )
        (output / f"os-recovery-{expected}.json").write_text(
            json.dumps(data, indent=2)
        )
        print(f"OS recovery {expected} complete")
    pull(device, output / "ios-process-lifecycle-final.json")
    scratch.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("cold", "recover", "pull", "terminate"))
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--count", type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.count <= 10:
        parser.error("--count must be between 1 and 10")
    args.output.mkdir(parents=True, exist_ok=True)
    if args.action == "cold":
        cold(args.device, args.output, args.count)
    elif args.action == "recover":
        recover(args.device, args.output, args.count)
    elif args.action == "pull":
        pull(args.device, args.output / "ios-process-lifecycle-final.json")
    else:
        terminate_runner(args.device)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
