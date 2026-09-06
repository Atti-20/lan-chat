#!/usr/bin/env python3
"""Fail fast when shipped Android/iOS launcher assets drift from MeshX assets.

The desktop icon tree is the release source of truth. Android ships exact
density exports from its android/ subtree and iOS ships the 1024px iOS export.
Keeping the comparison byte-for-byte makes icon parity a deterministic CI
contract instead of a manual visual convention.
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DESKTOP_ICONS = ROOT / "apps/desktop/src-tauri/icons"
ANDROID_RES = ROOT / "apps/android/app/src/main/res"
IOS_ICONS = ROOT / "apps/ios/ios/App/App/Assets.xcassets/AppIcon.appiconset"


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
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            pairs.append((
                DESKTOP_ICONS / "android" / f"mipmap-{density}" / name,
                ANDROID_RES / f"mipmap-{density}" / name,
            ))

    for canonical, shipped in pairs:
        require_file(canonical, errors)
        require_file(shipped, errors)
        if canonical.is_file() and shipped.is_file() and digest(canonical) != digest(shipped):
            errors.append(
                "图标内容不一致："
                f"{shipped.relative_to(ROOT)} 应与 {canonical.relative_to(ROOT)} 保持一致"
            )

    adaptive_files = (
        ANDROID_RES / "mipmap-anydpi-v26/ic_launcher.xml",
        ANDROID_RES / "mipmap-anydpi-v26/ic_launcher_round.xml",
        ANDROID_RES / "drawable/ic_launcher_background.xml",
        ANDROID_RES / "drawable/ic_launcher_foreground.xml",
    )
    for path in adaptive_files:
        require_file(path, errors)

    foreground = ANDROID_RES / "drawable/ic_launcher_foreground.xml"
    background = ANDROID_RES / "drawable/ic_launcher_background.xml"
    if foreground.is_file():
        text = foreground.read_text(encoding="utf-8")
        if "M74,92h35l147,137L403,92h35v194" not in text:
            errors.append("Android 自适应前景未使用 MeshX 标记")
        if "65.3,45.828" in text:
            errors.append("Android 自适应前景仍包含默认机器人素材")
    if background.is_file() and "#0F172A" not in background.read_text(encoding="utf-8"):
        errors.append("Android 自适应背景未使用 MeshX 午夜色")

    if errors:
        print("Native icon parity check failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1

    print(f"Native icon parity verified for {len(pairs)} raster exports and Android adaptive layers.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
