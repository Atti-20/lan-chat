# 仓库维护与敏感信息

仓库只保存可复现构建所需的源码、测试、依赖锁文件、数据库结构与迁移、配置模板、CI、部署脚本及维护文档。图标等原始资源和构建工具 wrapper 属于必要输入。

## 提交规则

- 真实密钥、会话令牌、签名文件、个人隧道配置、数据库备份与用户上传文件不进入 Git。
- `.env.example` 只保留空凭证值；本地 `.env` 和凭证文件保存在被忽略的位置或仓库外。
- 编译产物、缓存、运行日志和自动化截图放在对应忽略目录中。报告按主题归档至 `docs/reports/`（文件名末尾附日期），并由 `docs/README.md` 维护索引与命名约定。交付安装包由 Release 工作流构建。
- 测试中明确标注的固定假数据可以保留。Gitleaks 只豁免 `FileControllerTest` 的两个确定性假令牌，限定文件路径和精确值；其他规则全部启用。
- 生产部署必须使用独立生成的数据库、Redis、JWT 与管理员凭证，不可沿用开发或 CI 的示例值。

## 提交前检查

安装 [Gitleaks](https://github.com/gitleaks/gitleaks)，然后在仓库根目录执行：

```bash
git diff --cached --name-status
python3 scripts/ci/check-repository-hygiene.py
gitleaks git . --staged --config=.gitleaks.toml --redact=100
gitleaks git . --log-opts="--all" --config=.gitleaks.toml --redact=100
```

`Repository hygiene` 工作流在推送和 PR 中检查文件范围及完整拉取历史，使用固定版本和 SHA-256 校验的扫描器。它是检测措施，不能替代提交前检查或服务端推送保护。

## 私有访问与泄露处理

授权维护者通过自己的 GitHub 登录和 SSH 密钥访问私有仓库。Codex 使用当前机器已有的授权，不需要公开副本、额外账号或写入仓库的访问令牌。

若确认真实凭证曾被提交，先撤销或轮换，再清理受影响历史并检查 PR、Release 和缓存。仅删除当前文件或设为私有不能撤回别人已经保存的副本。按 [GitHub 敏感数据清理说明](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)处理。
