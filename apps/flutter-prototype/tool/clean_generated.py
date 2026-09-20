#!/usr/bin/env python3
"""Remove only Flutter's recognised generated Android registrant."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


APP = Path(__file__).resolve().parents[1]
ROOT = APP.parents[1]


class GeneratedSourceError(RuntimeError):
    pass


def read(path: Path) -> str:
    if not path.is_file():
        raise GeneratedSourceError(f"missing input: {path}")
    return path.read_text(encoding="utf-8")


def clean_generated(target: Path | None = None) -> dict[str, object]:
    target = target or APP / "android/app/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java"
    if not target.exists():
        return {"removed": False}
    content = read(target)
    markers = (
        "package io.flutter.plugins;",
        "Generated file. Do not edit.",
        "public final class GeneratedPluginRegistrant",
    )
    if not all(marker in content for marker in markers):
        raise GeneratedSourceError("refusing to remove an unrecognized Java source file")
    target.unlink()
    label = str(target.relative_to(ROOT)) if target.is_relative_to(ROOT) else target.name
    return {"removed": True, "path": label}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("clean-generated",), nargs="?", default="clean-generated")
    parser.parse_args()
    try:
        print(json.dumps({"status": "PASS", "generatedSource": clean_generated()}, indent=2))
        return 0
    except (GeneratedSourceError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
