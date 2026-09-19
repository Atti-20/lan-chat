"""Small REST navigation from the MVC-exported OpenAPI snapshot, not annotation regexes."""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path
import re

METHODS = ("get", "post", "put", "patch", "delete", "options", "head", "trace")


def cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def outputs(document: dict, root: Path) -> dict[str, str]:
    groups: dict[str, list[tuple[str, str, dict]]] = defaultdict(list)
    for route, item in sorted(document["paths"].items()):
        for verb in METHODS:
            if verb not in item:
                continue
            operation = item[verb]
            source = operation.get("x-source", {})
            owner, method = source.get("class", ""), source.get("method", "")
            if (not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", owner)
                    or not re.fullmatch(r"[A-Za-z_]\w*", method)):
                raise ValueError(f"Missing/invalid MVC x-source for {verb.upper()} {route}; export RestContractTest first")
            if not operation.get("operationId"):
                raise ValueError(f"Missing operationId for {verb.upper()} {route}")
            path = root / "services/server/src/main/java" / (owner.replace(".", "/") + ".java")
            if not path.is_file():
                raise ValueError(f"MVC source no longer exists: {owner}; verify/export the REST snapshot")
            groups[owner].append((verb, route, operation))
    count = sum(len(items) for items in groups.values())
    common = [
        "来源：[OpenAPI 快照](../../contracts/rest/openapi.json)，由 RestContractTest 的实际 MVC 映射导出；更新：`python3 tooling/workspace.py generate`。",
        "这是可定位的结构快照，是否与当前实现一致由 MVC 契约测试验证；业务权限、动态模型与错误条件见 [覆盖说明](../../contracts/rest/README.md)。",
    ]
    summary = ["# REST API 导航（自动生成）", "", *common, "",
               f"{len(document['paths'])} 个路径、{count} 个操作、{len(document.get('components', {}).get('schemas', {}))} 个 schema。先按 Controller 定位，再按需展开操作清单；无需默认读取完整 OpenAPI。", "",
               "| MVC 源码 | 操作数 | 操作清单 |", "|---|---:|---|"]
    details = ["# REST 操作清单（自动生成）", "", *common, "",
               "认证列仅表示网关入口；公开入口的刷新/退出仍可能要求 Cookie 或请求体令牌，业务权限继续由服务代码保证。", ""]
    for owner, entries in sorted(groups.items()):
        name = owner.rsplit(".", 1)[1]
        source_link = "../../services/server/src/main/java/" + owner.replace(".", "/") + ".java"
        anchor = owner.replace(".", "-").lower()
        summary.append(f"| [{name}]({source_link}) | {len(entries)} | [展开](api-routes.md#{anchor}) |")
        details.extend([f'<a id="{anchor}"></a>', f"## {name}", "", f"源码：[{owner}]({source_link})", "",
                        "| 方法 | 路径 | operationId / Java 方法 | 网关认证 |", "|---|---|---|---|"])
        for verb, route, operation in entries:
            security = operation.get("security", document.get("security", []))
            auth = " / ".join(" + ".join(sorted(requirement)) or "公开入口" for requirement in security) or "公开入口"
            symbol = operation["operationId"] + " / " + operation["x-source"]["method"]
            details.append(f"| {verb.upper()} | `{cell(route)}` | `{cell(symbol)}` | {cell(auth)} |")
        details.append("")
    return {"docs/generated/api-summary.md": "\n".join(summary) + "\n",
            "docs/generated/api-routes.md": "\n".join(details).rstrip() + "\n"}
