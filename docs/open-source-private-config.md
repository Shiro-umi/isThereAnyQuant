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
  temp/
```

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
