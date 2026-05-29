# Open Source Private Configuration

Quant can be published as a public repository while keeping local credentials,
private agent skills, and release signing material in a separate private Git
repository mounted as `private/`.

## Business Chain

```
public quant repo
  -> config.example.yaml for public defaults
  -> optional private/ submodule for real runtime secrets and skills
  -> scripts/setup-private.sh creates local symlinks
  -> Gradle / Ktor / Agent keep using the established local paths
```

The public repository must build without `private/`. Local deployment still uses
root `config.yaml` as the runtime source of truth when the private submodule is
installed.

## Private Repository Layout

Use this structure in the private repository:

```text
private/
  config.yaml
  config.yaml1             # optional legacy/local snapshot, not used by runtime
  .env.model
  claude/
    settings.local.json
  claude-skills/
  agent-analysis-skills/
  android/
    release.keystore
    keystore.properties
  plans/
  research-docs/             # research 研究设计文档（因子构造 / loss / 调优公式 / 有效性结论），不开源
  temp/
```

### Research Docs — Never Open Source

research 的研究设计文档承载"我们怎么研究"的核心方法（因子构造公式、loss 设计、调优结论、逐因子有效性、研究方法学），统一放在 `private/research-docs/`，**绝不进入公开仓**：

- 三份研究 topic 的 HTML 设计文档：`volume-price-factor-formula.html`（factor 因子挖掘）、`sentiment-next-day-formula.html`（trend 趋势跟踪）、`pivot-reversal-formula.html`（reversal 趋势反转），以及逐因子有效性说明文档。
- 以这些文档作为研究指导（SSOT）；研究迭代完成后回头更新文档内容，保持与实现一致。
- 公开仓的 `:strategy-server:research` Kotlin 工程骨架可以开源；私有化的只是研究设计文档与敏感的因子构造/调优结论。
- 强制规则同步见根 `CLAUDE.md` 的「Research 文档私有化」小节。

`compose-app/keystore.properties` supports the standard Android signing keys:

```properties
storeFile=../private/android/release.keystore
storePassword=<your-store-password>
keyAlias=<your-key-alias>
keyPassword=<your-key-password>
```

The same values can be provided through environment variables:

```bash
ANDROID_KEYSTORE_PATH=/absolute/path/release.keystore
ANDROID_KEYSTORE_PASSWORD=...
ANDROID_KEY_ALIAS=...
ANDROID_KEY_PASSWORD=...
```

## Setup

```bash
git submodule add <private-repo-url> private
git submodule update --init --recursive
./scripts/setup-private.sh
```

If a local file already exists, the setup script keeps it. To replace existing
files or directories with symlinks:

```bash
FORCE_PRIVATE_LINKS=true ./scripts/setup-private.sh
```

## Public Repository Rules

- Keep `config.example.yaml` public and sanitized.
- Never track `config.yaml`, `.env.model`, `.claude/`, `agent/analysis-skills/`,
  Android keystores, keystore properties, plans, or temp scratch files.
- Do not commit generated deploy packages, logs, databases, or build outputs.
- If a secret was ever committed, remove it from Git history before publishing
  and rotate the secret. Moving it to `private/` is not enough.
