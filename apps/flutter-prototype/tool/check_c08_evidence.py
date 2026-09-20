#!/usr/bin/env python3
"""Validate the bounded C08 Profile integration evidence without closing C08."""

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


def evaluate(payload: dict[str, Any], require_physical: bool = False) -> dict[str, Any]:
    metrics = payload.get("metrics")
    _require(isinstance(metrics, dict), "metrics object missing")
    _require(metrics.get("schema") == "meshx.c08-profile-performance/1", "schema mismatch")
    _require(metrics.get("mode") == "profile", "evidence is not Profile mode")
    if require_physical:
        _require(metrics.get("physicalDevice") is True, "physical device required")
    _require(metrics.get("firstListMessageCount") == 220, "220-message dataset is not isolated")
    _require(metrics.get("loadedMessageCount", 0) >= 2000, "2000-message dataset missing")
    _require(metrics.get("inputCharacters") == 1000, "1000-character input missing")
    _require(metrics.get("largeTextAndThemeNoFlutterException") is True, "large-text/theme check missing")
    _require(metrics.get("primarySendAccessibilityLabelPresent") is True, "send accessibility label missing")
    _require(metrics.get("discoveryReconnectRecoveredOnline") is True, "discovery/reconnect did not recover")
    _require(metrics.get("flutterException") is None, "Flutter exception recorded")
    login_ms = metrics.get("loginToOnlineMs")
    _require(isinstance(login_ms, int) and 0 <= login_ms <= 5000, "login to ONLINE exceeded 5s")
    recoveries = metrics.get("controllerRecoveryMs")
    _require(isinstance(recoveries, list) and len(recoveries) == 5, "five controller recoveries required")
    _require(
        all(isinstance(value, int) and 0 <= value <= 5000 for value in recoveries),
        "controller recovery exceeded 5s",
    )

    windows = metrics.get("windows")
    _require(isinstance(windows, list), "measurement windows missing")
    by_name = {window.get("name"): window for window in windows if isinstance(window, dict)}
    results: list[dict[str, Any]] = []
    for name in ("scroll-220", "scroll-2000", "input-1000"):
        window = by_name.get(name)
        _require(isinstance(window, dict), f"{name} window missing")
        refresh = window.get("refreshPeriodMicros")
        p95 = window.get("p95WorkloadMicros")
        count = window.get("frameCount")
        stalls = window.get("workloadFramesOver250ms")
        _require(isinstance(refresh, int) and refresh > 0, f"{name} refresh period invalid")
        _require(isinstance(p95, int) and p95 >= 0, f"{name} p95 invalid")
        _require(isinstance(count, int) and count > 0, f"{name} has no frame samples")
        _require(stalls == 0, f"{name} contains >250ms application work")
        if name.startswith("scroll"):
            _require(window.get("durationMs", 0) >= 30000, f"{name} is shorter than 30s")
        _require(p95 <= refresh * 2, f"{name} p95 exceeds two refresh periods")
        results.append(
            {
                "name": name,
                "frameCount": count,
                "p95WorkloadMicros": p95,
                "budgetMicros": refresh * 2,
                "maxWorkloadMicros": window.get("maxWorkloadMicros"),
                "rssDeltaBytes": window.get("rssDeltaBytes"),
            }
        )

    _require(metrics.get("passedMeasuredSlice") is True, "probe did not complete")
    _require(metrics.get("c08GateClosed") is False, "integration evidence must not close C08")
    _require(metrics.get("formalMainEntrypoint") is False, "unexpected entrypoint classification")
    return {
        "status": "PASS_MEASURED_SLICE",
        "platform": metrics.get("platform"),
        "physicalDevice": metrics.get("physicalDevice"),
        "windows": results,
        "loginToOnlineMs": login_ms,
        "controllerRecoveryMs": recoveries,
        "c08GateClosed": False,
        "remaining": [
            "formal lib/main.dart candidate",
            "five real cold launches",
            "five real OS background/foreground recoveries",
            "screen reader and full accessibility matrix",
            "Android physical device and representative old OS",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--require-physical", action="store_true")
    args = parser.parse_args()
    try:
        payload = json.loads(args.evidence.read_text())
        result = evaluate(payload, args.require_physical)
    except (OSError, json.JSONDecodeError, EvidenceError) as error:
        print(json.dumps({"status": "FAIL", "reason": str(error)}, indent=2))
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
