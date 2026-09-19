#!/usr/bin/env python3
"""Validate C08 cold-process and real-OS lifecycle evidence as a partial slice."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


class EvidenceError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def evaluate(payload: dict[str, Any], host: dict[str, Any]) -> dict[str, Any]:
    _require(payload.get("schema") == "meshx.c08-process-lifecycle/1", "schema mismatch")
    _require(payload.get("mode") == "profile", "process probe is not Profile mode")
    _require(payload.get("formalMainEntrypoint") is False, "entrypoint classification changed")
    _require(payload.get("c08GateClosed") is False, "partial probe must not close C08")
    runs = payload.get("runs")
    _require(isinstance(runs, list), "runs missing")
    restored = [
        run
        for run in runs
        if isinstance(run, dict)
        and run.get("restoredCredentials") is True
        and isinstance(run.get("cacheInteractiveMs"), int)
    ]
    _require(len(restored) >= 5, "five restored cold process launches required")
    cold_results: list[dict[str, Any]] = []
    for run in restored[-5:]:
        index = run.get("index")
        _require(run.get("stage") == "ready", f"run {index} did not reach ready")
        _require(run.get("uniqueMessages") is True, f"run {index} contains duplicate messages")
        _require(run.get("loadedMessageCount", 0) >= 2000, f"run {index} lacks 2000 messages")
        first_frame = run.get("firstFrameMs")
        cache = run.get("cacheInteractiveMs")
        online = run.get("onlineMs")
        _require(isinstance(first_frame, int) and 0 <= first_frame <= 5000, f"run {index} first frame exceeded 5s")
        _require(isinstance(cache, int) and 0 <= cache <= 5000, f"run {index} cache exceeded 5s")
        _require(isinstance(online, int) and 0 <= online <= 5000, f"run {index} ONLINE exceeded 5s")
        _require(run.get("peakActiveConnections") == 1, f"run {index} opened concurrent connections")
        cold_results.append(
            {
                "index": index,
                "firstFrameMs": first_frame,
                "cacheInteractiveMs": cache,
                "onlineMs": online,
                "readyMs": run.get("readyMs"),
                "rssReadyBytes": run.get("rssReadyBytes"),
            }
        )

    _require(host.get("schema") == "meshx.c08-host-cold/1", "host cold schema mismatch")
    host_rows = host.get("rows")
    _require(isinstance(host_rows, list) and len(host_rows) == 5, "five host cold rows required")
    expected_indices = [run["index"] for run in cold_results]
    _require(
        [row.get("processRun") for row in host_rows] == expected_indices,
        "host cold rows do not match the five restored process runs",
    )
    for row in host_rows:
        elapsed = row.get("hostLaunchToReadyMs")
        _require(
            isinstance(elapsed, int) and 0 <= elapsed <= 5000,
            f"host-observed run {row.get('processRun')} exceeded 5s",
        )
    for result, host_row in zip(cold_results, host_rows, strict=True):
        result["hostLaunchToReadyMs"] = host_row["hostLaunchToReadyMs"]

    recoveries = payload.get("osRecoveries")
    _require(isinstance(recoveries, list) and len(recoveries) >= 5, "five OS recoveries required")
    recovery_results: list[dict[str, Any]] = []
    for recovery in recoveries[-5:]:
        index = recovery.get("index")
        _require(recovery.get("backgroundAt") is not None, f"recovery {index} lacks background")
        _require(recovery.get("offlineObservedAt") is not None, f"recovery {index} never went offline")
        _require(recovery.get("foregroundAt") is not None, f"recovery {index} lacks foreground")
        duration = recovery.get("onlineAfterForegroundMs")
        _require(isinstance(duration, int) and 0 <= duration <= 5000, f"recovery {index} ONLINE exceeded 5s")
        recovery_results.append(
            {"index": index, "processRun": recovery.get("processRun"), "onlineAfterForegroundMs": duration}
        )

    return {
        "status": "PASS_PROCESS_LIFECYCLE_SLICE",
        "platform": payload.get("platform"),
        "coldRuns": cold_results,
        "osRecoveries": recovery_results,
        "c08GateClosed": False,
        "remaining": [
            "formal lib/main.dart candidate",
            "production HTTPS transport",
            "Android physical device",
            "representative minimum and intermediate OS versions",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--host-summary", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = evaluate(
            json.loads(args.evidence.read_text()),
            json.loads(args.host_summary.read_text()),
        )
    except (OSError, json.JSONDecodeError, EvidenceError) as error:
        print(json.dumps({"status": "FAIL", "reason": str(error)}, indent=2))
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
