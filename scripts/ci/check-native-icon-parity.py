#!/usr/bin/env python3
"""Fail fast when shipped Android/iOS launcher assets drift from MeshX assets.

The desktop icon tree is the release source of truth. Android (including Flutter) ships exact density exports from its android/
subtree; Flutter iOS uses every matching Xcode slot from its ios/ subtree.
Keeping the comparison byte-for-byte makes icon parity a deterministic CI
contract instead of a manual visual convention.
"""

from __future__ import annotations

import hashlib
import json
import xml.etree.ElementTree as ET
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DESKTOP_ICONS = ROOT / "apps/desktop/src-tauri/icons"
ANDROID_RES = ROOT / "apps/android/app/src/main/res"
IOS_ICONS = ROOT / "apps/ios/ios/App/App/Assets.xcassets/AppIcon.appiconset"
FLUTTER_ANDROID = ROOT / "apps/flutter-prototype/android/app/src/main/res"
FLUTTER_IOS = ROOT / "apps/flutter-prototype/ios/Runner/Assets.xcassets/AppIcon.appiconset"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_file(path: Path, errors: list[str]) -> None:
    if not path.is_file():
        errors.append(f"缺少图标资源：{path.relative_to(ROOT)}")


def main() -> int:
    errors: list[str] = []
    pairs: list[tuple[Path, Path]] = [
        (
            DESKTOP_ICONS / "ios/AppIcon-512@2x.png",
            IOS_ICONS / "AppIcon-512@2x.png",
        ),
    ]
    for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        for name in (
            "ic_launcher.png",
            "ic_launcher_round.png",
            "ic_launcher_foreground.png",
        ):
            pairs.append((
                DESKTOP_ICONS / "android" / f"mipmap-{density}" / name,
                ANDROID_RES / f"mipmap-{density}" / name,
            ))

    # Flutter uses the same density exports and Xcode image slots, without
    # regenerating an independently scaled variant of the brand.
    pairs.extend((canonical, FLUTTER_ANDROID / shipped.relative_to(ANDROID_RES))
                 for canonical, shipped in list(pairs) if shipped.is_relative_to(ANDROID_RES))
    catalog = FLUTTER_IOS / "Contents.json"
    require_file(catalog, errors)
    if catalog.is_file():
        for item in json.loads(catalog.read_text(encoding="utf-8"))["images"]:
            source = ("AppIcon-512@2x.png" if item["idiom"] == "ios-marketing"
                      else f'AppIcon-{item["size"]}@{item["scale"]}.png')
            pairs.append((DESKTOP_ICONS / "ios" / source, FLUTTER_IOS / item["filename"]))

    for canonical, shipped in pairs:
        require_file(canonical, errors)
        require_file(shipped, errors)
        if canonical.is_file() and shipped.is_file() and digest(canonical) != digest(shipped):
            errors.append(
                "图标内容不一致："
                f"{shipped.relative_to(ROOT)} 应与 {canonical.relative_to(ROOT)} 保持一致"
            )

    adaptive_pairs = (
        (
            DESKTOP_ICONS / "android/mipmap-anydpi-v26/ic_launcher.xml",
            ANDROID_RES / "mipmap-anydpi-v26/ic_launcher.xml",
        ),
        (
            ANDROID_RES / "mipmap-anydpi-v26/ic_launcher.xml",
            ANDROID_RES / "mipmap-anydpi-v26/ic_launcher_round.xml",
        ),
        (
            DESKTOP_ICONS / "android/values/ic_launcher_background.xml",
            ANDROID_RES / "values/ic_launcher_background.xml",
        ),
    )
    adaptive_pairs += tuple(
        (canonical, FLUTTER_ANDROID / shipped.relative_to(ANDROID_RES))
        for canonical, shipped in adaptive_pairs
    )
    for res in (ANDROID_RES, FLUTTER_ANDROID):
        manifest = res.parent / "AndroidManifest.xml"
        require_file(manifest, errors)
        if manifest.is_file():
            app = ET.parse(manifest).getroot().find("application")
            ns = "{http://schemas.android.com/apk/res/android}"
            for attr, value in (("icon", "@mipmap/ic_launcher"),
                                ("roundIcon", "@mipmap/ic_launcher_round")):
                if app is None or app.get(ns + attr) != value:
                    errors.append(f"启动器图标入口不一致：{manifest.relative_to(ROOT)} {attr}")

    for canonical, shipped in adaptive_pairs:
        require_file(canonical, errors)
        require_file(shipped, errors)
        if canonical.is_file() and shipped.is_file() and digest(canonical) != digest(shipped):
            errors.append(
                "Android 自适应图标配置不一致："
                f"{shipped.relative_to(ROOT)} 应与 {canonical.relative_to(ROOT)} 保持一致"
            )

    background = ANDROID_RES / "values/ic_launcher_background.xml"
    if background.is_file() and "#0F172A" not in background.read_text(encoding="utf-8"):
        errors.append("Android 自适应背景未使用 MeshX 深色底")

    legacy_adaptive_layers = (
        ANDROID_RES / "drawable/ic_launcher_background.xml",
        ANDROID_RES / "drawable/ic_launcher_foreground.xml",
    )
    for path in legacy_adaptive_layers:
        if path.exists():
            errors.append(f"Android 仍保留未使用的旧自适应图标资源：{path.relative_to(ROOT)}")

    if errors:
        print("Native icon parity check failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1

    print(f"Native icon parity verified for {len(pairs)} raster exports and Android adaptive layers.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
