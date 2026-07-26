#!/usr/bin/env python3

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Callable


ROOT = Path(__file__).resolve().parents[2]
SEMVER_PATTERN = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$"
)


class VersionCheckError(RuntimeError):
    """Raised when a version cannot be read from a project file."""


def read_text(relative_path: str) -> str:
    path = ROOT / relative_path
    if not path.is_file():
        raise VersionCheckError(f"文件不存在：{relative_path}")

    return path.read_text(encoding="utf-8")


def read_json(relative_path: str) -> dict:
    try:
        return json.loads(read_text(relative_path))
    except json.JSONDecodeError as exc:
        raise VersionCheckError(
            f"{relative_path} 不是合法 JSON：{exc}"
        ) from exc


def read_json_version(relative_path: str) -> str:
    data = read_json(relative_path)
    version = data.get("version")

    if not isinstance(version, str):
        raise VersionCheckError(
            f"{relative_path} 缺少字符串类型的 version"
        )

    return version


def read_package_lock_root_version(relative_path: str) -> str:
    data = read_json(relative_path)

    packages = data.get("packages")
    if not isinstance(packages, dict):
        raise VersionCheckError(
            f"{relative_path} 缺少 packages 对象"
        )

    root_package = packages.get("")
    if not isinstance(root_package, dict):
        raise VersionCheckError(
            f"{relative_path} 缺少 packages['']"
        )

    version = root_package.get("version")
    if not isinstance(version, str):
        raise VersionCheckError(
            f"{relative_path} 的 packages[''] 缺少 version"
        )

    return version


def read_maven_version(relative_path: str) -> str:
    path = ROOT / relative_path

    try:
        document = ET.parse(path)
    except (ET.ParseError, OSError) as exc:
        raise VersionCheckError(
            f"无法解析 {relative_path}：{exc}"
        ) from exc

    root = document.getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}

    version_element = root.find("m:version", namespace)
    if version_element is None or not version_element.text:
        raise VersionCheckError(
            f"{relative_path} 缺少项目级 <version>"
        )

    return version_element.text.strip()


def read_toml_package_version(relative_path: str) -> str:
    text = read_text(relative_path)

    section_match = re.search(
        r"(?ms)^\[package\]\s*$"
        r"(?P<body>.*?)"
        r"(?=^\[[^\n]+\]\s*$|\Z)",
        text,
    )

    if not section_match:
        raise VersionCheckError(
            f"{relative_path} 缺少 [package] 区域"
        )

    version_match = re.search(
        r'(?m)^version\s*=\s*"([^"]+)"\s*$',
        section_match.group("body"),
    )

    if not version_match:
        raise VersionCheckError(
            f"{relative_path} 的 [package] 缺少 version"
        )

    return version_match.group(1)


def read_cargo_lock_package_version(
    relative_path: str,
    package_name: str,
) -> str:
    text = read_text(relative_path)

    package_blocks = re.split(
        r"(?m)^\[\[package\]\]\s*$",
        text,
    )[1:]

    for block in package_blocks:
        name_match = re.search(
            r'(?m)^name\s*=\s*"([^"]+)"\s*$',
            block,
        )
        if not name_match or name_match.group(1) != package_name:
            continue

        version_match = re.search(
            r'(?m)^version\s*=\s*"([^"]+)"\s*$',
            block,
        )
        if not version_match:
            raise VersionCheckError(
                f"{relative_path} 中的 {package_name} 缺少 version"
            )

        return version_match.group(1)

    raise VersionCheckError(
        f"{relative_path} 中没有找到包 {package_name}"
    )


def read_gradle_version_name(relative_path: str) -> str:
    text = read_text(relative_path)

    match = re.search(
        r'(?m)^\s*versionName\s+["\']([^"\']+)["\']\s*$',
        text,
    )

    if not match:
        raise VersionCheckError(
            f"{relative_path} 缺少 versionName"
        )

    return match.group(1)


def read_readme_version(relative_path: str) -> str:
    text = read_text(relative_path)

    match = re.search(
        r"当前开发版本为\s+\*\*[Vv]?(\d+\.\d+\.\d+)\*\*",
        text,
    )

    if not match:
        raise VersionCheckError(
            f"{relative_path} 中没有找到“当前开发版本为 **vX.Y.Z**”"
        )

    return match.group(1)


def read_regex_version(
    relative_path: str,
    pattern: str,
    description: str,
) -> str:
    match = re.search(pattern, read_text(relative_path), re.MULTILINE)
    if not match:
        raise VersionCheckError(
            f"{relative_path} 中没有找到{description}"
        )
    return match.group(1)


def read_docker_artifact_contract(
    relative_path: str,
    expected_version: str,
) -> str:
    text = read_text(relative_path)
    if "/workspace/target/lan-chat-server-*.jar /app/lanchat.jar" not in text:
        raise VersionCheckError(
            f"{relative_path} 必须按通配符复制 Maven 版本产物"
        )
    if re.search(r"lan-chat-server-\d+\.\d+\.\d+\.jar", text):
        raise VersionCheckError(
            f"{relative_path} 不得硬编码 Maven 版本产物名"
        )
    return expected_version


def main() -> int:
    version_file = read_text("VERSION")
    expected_version = version_file.strip()

    if not SEMVER_PATTERN.fullmatch(expected_version):
        print(
            "ERROR: VERSION 必须是标准 MAJOR.MINOR.PATCH 格式，"
            f"当前值为：{expected_version!r}",
            file=sys.stderr,
        )
        return 1
    if version_file != f"{expected_version}\n":
        print(
            "ERROR: VERSION 必须只包含一行语义版本并以换行结束。",
            file=sys.stderr,
        )
        return 1

    checks: list[tuple[str, Callable[[], str]]] = [
        (
            "Maven",
            lambda: read_maven_version("pom.xml"),
        ),
        (
            "Server node configuration",
            lambda: read_regex_version(
                "src/main/resources/application.yml",
                r"^\s+version:\s*(\d+\.\d+\.\d+)\s*$",
                "lanchat.node.version",
            ),
        ),
        (
            "Server node Java default",
            lambda: read_regex_version(
                "src/main/java/com/lanchat/config/LanChatNodeProperties.java",
                r'^\s*private String version = "(\d+\.\d+\.\d+)";\s*$',
                "节点版本默认值",
            ),
        ),
        (
            "Docker Maven artifact",
            lambda: read_docker_artifact_contract(
                "Dockerfile",
                expected_version,
            ),
        ),
        (
            "Web package.json",
            lambda: read_json_version("frontend/package.json"),
        ),
        (
            "Web package-lock.json",
            lambda: read_json_version("frontend/package-lock.json"),
        ),
        (
            "Web package-lock root",
            lambda: read_package_lock_root_version(
                "frontend/package-lock.json"
            ),
        ),
        (
            "Desktop package.json",
            lambda: read_json_version(
                "apps/desktop/package.json"
            ),
        ),
        (
            "Desktop package-lock.json",
            lambda: read_json_version(
                "apps/desktop/package-lock.json"
            ),
        ),
        (
            "Desktop package-lock root",
            lambda: read_package_lock_root_version(
                "apps/desktop/package-lock.json"
            ),
        ),
        (
            "Desktop Cargo.toml",
            lambda: read_toml_package_version(
                "apps/desktop/src-tauri/Cargo.toml"
            ),
        ),
        (
            "Desktop Cargo.lock",
            lambda: read_cargo_lock_package_version(
                "apps/desktop/src-tauri/Cargo.lock",
                "lanchat-desktop",
            ),
        ),
        (
            "Tauri configuration",
            lambda: read_json_version(
                "apps/desktop/src-tauri/tauri.conf.json"
            ),
        ),
        (
            "Mobile package.json",
            lambda: read_json_version(
                "apps/mobile/package.json"
            ),
        ),
        (
            "Mobile package-lock.json",
            lambda: read_json_version(
                "apps/mobile/package-lock.json"
            ),
        ),
        (
            "Mobile package-lock root",
            lambda: read_package_lock_root_version(
                "apps/mobile/package-lock.json"
            ),
        ),
        (
            "Android versionName",
            lambda: read_gradle_version_name(
                "apps/mobile/android/app/build.gradle"
            ),
        ),
        (
            "README current version",
            lambda: read_readme_version("README.md"),
        ),
    ]

    print(f"MeshX expected version: {expected_version}")
    print()

    failed = False

    for label, reader in checks:
        try:
            actual_version = reader()
        except VersionCheckError as exc:
            failed = True
            print(f"[ERROR] {label:<28} {exc}")
            continue

        if actual_version == expected_version:
            print(f"[OK]    {label:<28} {actual_version}")
        else:
            failed = True
            print(
                f"[FAIL]  {label:<28} "
                f"{actual_version} != {expected_version}"
            )

    print()

    if failed:
        print(
            "Version consistency check failed.",
            file=sys.stderr,
        )
        return 1

    print("Version consistency check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
