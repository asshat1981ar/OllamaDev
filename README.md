# Ollama Swarm (OllamaDev)

A native Android app (Jetpack Compose, Kotlin, Material 3) for orchestrating a swarm of
AI agents against your own **Ollama** servers — running locally, on your LAN, or via
Ollama Cloud. No Gemini or other cloud AI Studio dependency: every generation call
resolves against an Ollama node you configure in the app.

## Requirements

- [Android Studio](https://developer.android.com/studio)
- [Ollama](https://ollama.com) running locally or reachable on your network
- At least one model pulled in that Ollama instance

## Setting up Ollama

```bash
ollama serve
ollama pull llama3.1
```

By default Ollama listens on `11434`. Once it's running and a model is pulled, add it
as a node inside the app (Nodes screen):

| Where the app runs        | Ollama node URL                          |
| -------------------------- | ----------------------------------------- |
| Android emulator            | `http://10.0.2.2:11434`                  |
| Physical device (same LAN) | `http://<your-computer-lan-ip>:11434`    |

`10.0.2.2` is the emulator's special alias back to the host machine's `localhost`. For a
physical device, use your computer's actual LAN IP (`ipconfig getifaddr en0` on macOS,
`ip addr` on Linux) — the device and the Ollama host must be on the same network.

Optionally, point a node at `https://ollama.com` and put a Bearer key in `.env` (see
`.env.example`) to use Ollama Cloud instead of/alongside a local server. **No Gemini API
key is required anywhere in this app.**

## Run locally

1. Open Android Studio.
2. Select **Open** and choose the directory containing this project.
3. Allow Android Studio to sync/fix any incompatibilities as it imports the project.
4. Start Ollama and pull a model (see above).
5. Run the app on an emulator or physical device, then add your Ollama node from the
   Nodes screen using the table above.

## Build & release

```bash
# Debug APK (no signing config needed — the debug build type sets none)
./gradlew :app:assembleDebug --console=plain
```

`versionCode` / `versionName` are derived from git via `scripts/version.sh`
(`code` = commit count, `name` = latest semver tag or `1.0.0`, with a
`-<short-sha>` suffix when the worktree is dirty). No manual version bumping.

**Release builds are env-gated signed.** A `release` signing config is created
only when `OLLAMADEV_KEYSTORE_PATH` is set in the environment; without it the
release APK is produced **unsigned** (safe for local builds/CI). To sign:

```bash
export OLLAMADEV_KEYSTORE_PATH=/path/to/your.keystore
export OLLAMADEV_KEYSTORE_PASS='<store-and-key-password>'
export OLLAMADEV_KEYSTORE_ALIAS='<key-alias>'
./gradlew :app:assembleRelease --console=plain  # add -x lintVitalRelease if lint errors
```

Keystore paths/passwords are never committed — they are environment-only.

## Troubleshooting

- **Node shows offline / connection refused** — make sure `ollama serve` is running and
  reachable from the device: `curl http://<node-url>/api/tags` from a machine on the
  same network as the emulator/device should list your pulled models.
- **Wrong host/IP** — from the emulator, `localhost`/`127.0.0.1` refers to the emulator
  itself, not your computer; use `10.0.2.2`. From a physical device, `10.0.2.2` doesn't
  resolve to anything useful; use your computer's real LAN IP, and confirm both are on
  the same Wi-Fi/network with no client isolation.
- **Cleartext HTTP blocked** — Android blocks plaintext `http://` traffic by default on
  release builds. Debug builds in this project permit cleartext for local development
  (see `app/src/debug/res/xml/network_security_config.xml`); a release build talking to
  a non-HTTPS Ollama server would need the same treatment applied deliberately.
- **Model not found / not pulled** — run `ollama pull <model>` on the machine hosting
  Ollama, then re-check `http://<node-url>/api/tags` for it before selecting it in the
  app.
