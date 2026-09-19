#!/usr/bin/env python3
"""Audit prepared Flutter candidate inputs without approving a release."""

from __future__ import annotations

import argparse
import hashlib
import json
import plistlib
import re
import subprocess
import sys
import zipfile
from pathlib import Path


APP = Path(__file__).resolve().parents[1]
ROOT = APP.parents[1]
MANIFEST = APP / "candidate-readiness.json"
EXPECTED_BLOCKERS = {
    "APP_IDENTITY_UNAPPROVED",
    "VERSION_UNAPPROVED",
    "SIGNING_NOT_CONFIGURED",
    "UPGRADE_POLICY_UNAPPROVED",
    "REMOTE_CI_NOT_RUN",
}


class CandidateError(RuntimeError):
    pass


def read(path: Path) -> str:
    if not path.is_file():
        raise CandidateError(f"missing input: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def match(text: str, pattern: str, label: str) -> str:
    found = re.search(pattern, text, re.MULTILINE)
    if not found:
        raise CandidateError(f"cannot resolve {label}")
    return found.group(1)


def safe_build_arguments(arguments: object, expected: list[str]) -> None:
    if arguments != expected:
        raise CandidateError(f"build arguments must equal {expected!r}")
    joined = " ".join(expected).lower()
    forbidden = ("--dart-define", "integration_test", "fixture", "--debug")
    if any(value in joined for value in forbidden):
        raise CandidateError("candidate build contains debug or fixture input")


def source_checks(data: dict) -> list[str]:
    if data.get("schemaVersion") != 1:
        raise CandidateError("unsupported candidate manifest schema")
    if data.get("status") != "PREPARED_NOT_APPROVED":
        raise CandidateError("candidate status must remain PREPARED_NOT_APPROVED")
    if data.get("releaseEligible") is not False:
        raise CandidateError("an unapproved prototype cannot be release eligible")
    if set(data.get("blockers", [])) != EXPECTED_BLOCKERS:
        raise CandidateError("candidate approval blockers drifted")
    if data.get("entrypoint") != "lib/main.dart":
        raise CandidateError("formal candidate entrypoint must be lib/main.dart")
    main = read(APP / data["entrypoint"])
    if any(value in main for value in ("integration_test", "PROBE_", "fixture")):
        raise CandidateError("formal main references a test or fixture input")

    pubspec = read(APP / "pubspec.yaml")
    version = match(pubspec, r"^version:\s*([^\s]+)$", "Flutter version")
    if version != data.get("artifactVersion"):
        raise CandidateError("pubspec version differs from candidate manifest")
    dart_constraint = match(pubspec, r"^\s*sdk:\s*\^(\d+\.\d+\.\d+)$", "Dart SDK constraint")
    if dart_constraint != data.get("dartSdk"):
        raise CandidateError("pubspec Dart SDK constraint differs from candidate manifest")
    lock = APP / data["dependencyLock"]["path"]
    digest = hashlib.sha256(lock.read_bytes()).hexdigest()
    if digest != data["dependencyLock"]["sha256"]:
        raise CandidateError("pubspec.lock hash differs from candidate manifest")
    inputs = data.get("buildInputs")
    if inputs != {"dartDefines": [], "fixtureFiles": []}:
        raise CandidateError("candidate builds must have no injected or fixture input")

    android = data["android"]
    if android.get("signing") != "UNSIGNED":
        raise CandidateError("Android prototype artifact must remain unsigned")
    gradle = read(APP / "android/app/build.gradle.kts")
    if match(gradle, r'applicationId\s*=\s*"([^"]+)"', "Android applicationId") != android["applicationId"]:
        raise CandidateError("Android applicationId differs from candidate manifest")
    for key in ("minSdk", "targetSdk"):
        actual = int(match(gradle, rf"{key}\s*=\s*(\d+)", f"Android {key}"))
        if actual != android[key]:
            raise CandidateError(f"Android {key} differs from candidate manifest")
    safe_build_arguments(
        android.get("buildArguments"),
        ["flutter", "build", "apk", "--release", "--no-pub"],
    )
    main_manifest = read(APP / "android/app/src/main/AndroidManifest.xml")
    if 'android:usesCleartextTraffic="false"' not in main_manifest:
        raise CandidateError("Android main manifest must deny cleartext traffic")
    if "networkSecurityConfig" in main_manifest:
        raise CandidateError("Android debug network config leaked into main manifest")
    debug_manifest = read(APP / "android/app/src/debug/AndroidManifest.xml")
    if "networkSecurityConfig" not in debug_manifest:
        raise CandidateError("Android local-network exception must remain debug-only")

    ios = data["ios"]
    if ios.get("signing") != "UNSIGNED":
        raise CandidateError("iOS prototype artifact must remain unsigned")
    project = read(APP / "ios/Runner.xcodeproj/project.pbxproj")
    bundle_ids = set(re.findall(r"PRODUCT_BUNDLE_IDENTIFIER\s*=\s*([^;]+);", project))
    app_bundle_ids = {value for value in bundle_ids if not value.endswith(".RunnerTests")}
    if app_bundle_ids != {ios["bundleId"]}:
        raise CandidateError("iOS bundle ID differs from candidate manifest")
    minimums = set(re.findall(r"IPHONEOS_DEPLOYMENT_TARGET\s*=\s*([^;]+);", project))
    if minimums != {ios["minimumVersion"]}:
        raise CandidateError("iOS deployment targets are inconsistent")
    safe_build_arguments(
        ios.get("buildArguments"),
        ["flutter", "build", "ios", "--release", "--no-codesign", "--no-pub"],
    )
    if "NSAppTransportSecurity" in read(APP / "ios/Runner/Info.plist"):
        raise CandidateError("iOS release Info.plist contains a debug ATS exception")
    if "NSAllowsLocalNetworking" not in read(APP / "ios/Runner/Info-Debug.plist"):
        raise CandidateError("iOS local-network exception must remain debug-only")

    workflow = read(ROOT / ".github/workflows/flutter-prototype.yml")
    if f"--branch {data['flutterSdk']}" not in workflow:
        raise CandidateError("workflow Flutter SDK pin differs from candidate manifest")
    for scope in ("flutter-android-candidate", "flutter-ios-candidate"):
        if f"verify {scope}" not in workflow:
            raise CandidateError(f"workflow does not call unified {scope} verification")
    return [
        "manifest-state",
        "formal-main",
        "version-and-lock",
        "no-injected-input",
        "android-identity-and-network-policy",
        "ios-identity-and-network-policy",
        "pinned-workflow-and-unified-builds",
    ]


def android_artifact(data: dict) -> dict:
    path = APP / data["android"]["artifact"]
    if not path.is_file() or path.stat().st_size == 0:
        raise CandidateError("Android candidate artifact is missing or empty")
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        if not {"AndroidManifest.xml", "classes.dex"}.issubset(names):
            raise CandidateError("Android candidate APK structure is incomplete")
        if any(re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC)", name, re.IGNORECASE) for name in names):
            raise CandidateError("Android prototype APK unexpectedly has a v1 signing certificate")
    content = path.read_bytes()
    if b"APK Sig Block 42" in content or path.with_suffix(path.suffix + ".idsig").exists():
        raise CandidateError("Android prototype APK unexpectedly has a signing block")
    return {"path": str(path.relative_to(ROOT)), "sha256": hashlib.sha256(content).hexdigest(), "bytes": path.stat().st_size, "signing": "UNSIGNED"}


def ios_artifact(data: dict) -> dict:
    app = APP / data["ios"]["artifact"]
    plist = app / "Info.plist"
    if not plist.is_file():
        raise CandidateError("iOS candidate bundle or Info.plist is missing")
    with plist.open("rb") as source:
        info = plistlib.load(source)
    expected_version, expected_build = data["artifactVersion"].split("+", 1)
    expected = {
        "CFBundleIdentifier": data["ios"]["bundleId"],
        "CFBundleShortVersionString": expected_version,
        "CFBundleVersion": expected_build,
    }
    for key, value in expected.items():
        if str(info.get(key)) != value:
            raise CandidateError(f"iOS artifact {key} differs from manifest")
    executable = app / str(info.get("CFBundleExecutable", ""))
    if not executable.is_file() or executable.stat().st_size == 0:
        raise CandidateError("iOS candidate executable is missing or empty")
    signature = subprocess.run(
        ["codesign", "-dv", "--verbose=4", str(app)],
        check=False,
        capture_output=True,
        text=True,
    )
    details = signature.stdout + signature.stderr
    if signature.returncode == 0 or "Authority=" in details:
        raise CandidateError("iOS prototype bundle unexpectedly carries a signature")
    return {"path": str(app.relative_to(ROOT)), "sha256": hashlib.sha256(executable.read_bytes()).hexdigest(), "executableBytes": executable.stat().st_size, "signing": "UNSIGNED"}


def clean_generated(target: Path | None = None) -> dict:
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
        raise CandidateError("refusing to remove an unrecognized Java source file")
    target.unlink()
    label = str(target.relative_to(ROOT)) if target.is_relative_to(ROOT) else target.name
    return {"removed": True, "path": label}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("source", "clean-generated", "android-artifact", "ios-artifact"), nargs="?", default="source")
    args = parser.parse_args()
    try:
        if args.mode == "clean-generated":
            print(json.dumps({"status": "PASS", "generatedSource": clean_generated()}, indent=2))
            return 0
        data = json.loads(read(MANIFEST))
        checks = source_checks(data)
        report: dict = {
            "status": "PASS",
            "candidateStatus": data["status"],
            "releaseEligible": data["releaseEligible"],
            "checks": checks,
            "blockers": data["blockers"],
        }
        if args.mode == "android-artifact":
            report["artifact"] = android_artifact(data)
        if args.mode == "ios-artifact":
            report["artifact"] = ios_artifact(data)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (CandidateError, KeyError, TypeError, ValueError, json.JSONDecodeError, OSError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
