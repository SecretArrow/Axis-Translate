# Axis Translate

[![CI](https://github.com/SecretArrow/Axis-Translate/actions/workflows/ci.yml/badge.svg)](https://github.com/SecretArrow/Axis-Translate/actions/workflows/ci.yml?query=branch%3Amain)
[![E2E](https://github.com/SecretArrow/Axis-Translate/actions/workflows/e2e.yml/badge.svg)](https://github.com/SecretArrow/Axis-Translate/actions/workflows/e2e.yml?query=branch%3Amain)
[![Release](https://github.com/SecretArrow/Axis-Translate/actions/workflows/release.yml/badge.svg)](https://github.com/SecretArrow/Axis-Translate/actions/workflows/release.yml/badge=main)

**Axis Translate is a privacy-first, offline AI translation app.**
A quantized LLM runs locally through llama.cpp, so every translation stays on
your device: no cloud translation service, no accounts, no tracking, no
telemetry. The app uses the network exactly once per model — to download and
SHA-256-verify the GGUF weights — and after that it works with airplane mode on.

## Monorepo layout

| Path | Platform | Language | Details |
| --- | --- | --- | --- |
| [`android/`](android/README.md) | Android (minSdk 26) | Kotlin + Compose (Material 3) + C++ JNI bridge | Text, photo, camera, voice, conversation, documents, glossary, batch, floating translator, model manager with import **and export** |
| [`desktop/`](desktop/README.md) | Windows + Linux (Ubuntu) | **C11** (+ llama.cpp runtime) | Modern Nuklear + SDL2 UI, model download/import/**export**, history, settings, streaming generation |

Both apps share the same model ecosystem (GGUF files, SHA-256-verified
manifest) — export a model on Android and import it on desktop, or the other
way around.

## Models

Model installation is **manifest-driven**:
[`android/app/src/main/assets/model_manifest.json`](android/app/src/main/assets/model_manifest.json)
and [`desktop/assets/model_manifest.json`](desktop/assets/model_manifest.json)
(schema version 1) are kept in sync and are the single source of truth —
display name, download URL, size, context length, license and per-model
SHA-256 digest. The model manager downloads the selected entry on first
launch, verifies it byte-for-byte (**SHA-256** + size) and only then activates
it.

| Model | Role | Size | Notes |
| --- | --- | --- | --- |
| **Qwen3.5 2B (Q4_K_M)** | **Default** — recommended | 1.28 GB | Latest-generation multilingual model, verified GGUF build; 32 languages |
| **Qwen3.5 0.8B (Q4_K_M)** | Balanced / smaller devices | 532 MB | Same generation, lighter; good speed/size balance |
| **Qwen2.5 0.5B Instruct (Q4_K_M)** | Compact fallback | 491 MB | Previous generation; useful as a compact fallback |

- **The network is used only to download models.** After a model is
  installed, every translation is computed locally.
- Any compatible `.gguf` file can be **imported** from local storage (or
  **exported** back out) via the model manager — no download required.

## CI/CD

All builds, tests and releases run on **GitHub Actions** — nothing is built
locally. The workflows chain on `main` via `workflow_run`:

```
 push to main / PR to main
        |
        v
   +------------------+  Android: ktlint auto-fix (commits style fixes), then
   |       CI         |  ktlintCheck + Android Lint + unit tests + assembleDebug
   |  (android job)   |  Desktop: cmake build + CTest unit tests on
   |  (desktop jobs)  |  ubuntu + windows + headless smoke run
   +------------------+
        |  workflow_run: success on main
        v
   +------------------+  connectedDebugAndroidTest on an API 30 x86_64
   |       E2E        |  emulator (-Paxis.abis=x86_64, KVM accelerated)
   +------------------+
        |  workflow_run: success on main
        v
   +------------------+  Android: assembleRelease -Paxis.splitApks=true
   |     Release      |        -> per-ABI APKs + SHA256SUMS.txt
   |  (android job)   |  Desktop: release builds for ubuntu + windows
   |  (desktop job)   |        -> AxisTranslateDesktop-v<version>-linux-x64.tar.gz
   +------------------+        -> AxisTranslateDesktop-v<version>-windows-x64.zip
        |                + all checksums -> GitHub Release
        v
   GitHub Release
```

Failures in CI skip E2E; failures in E2E skip Release — a release can never
be published from a red chain. Superseded runs are cancelled (`concurrency`
groups), Gradle user home and CMake FetchContent sources are cached, and
every job has a hard timeout.

### Release artifacts

Version is `1.0.<CI run number>`:

| Artifact | Platform |
| --- | --- |
| `AxisTranslate-v<version>-arm64-v8a.apk` | Android — modern 64-bit devices (recommended) |
| `AxisTranslate-v<version>-armeabi-v7a.apk` | Android — older 32-bit ARM devices |
| `AxisTranslate-v<version>-x86.apk` | Android — 32-bit x86 devices |
| `AxisTranslate-v<version>-x86_64.apk` | Android — emulators / x86_64 devices |
| `AxisTranslateDesktop-v<version>-linux-x64.tar.gz` | Desktop — Ubuntu Linux |
| `AxisTranslateDesktop-v<version>-windows-x64.zip` | Desktop — Windows 10/11 x64 |
| `SHA256SUMS.txt` | SHA-256 checksums of every artifact |

### Manual runs

Each workflow can be triggered by hand: **Actions** tab → select the workflow
(**CI** / **E2E** / **Release**) → **Run workflow**. Tag pushes matching `v*`
run **CI** and **Release** directly.

## Privacy

- No tracking, no analytics, no crash reporting.
- No cloud translation service — nothing you translate ever leaves the device.
- History, favorites and settings live in local storage (Room/DataStore on
  Android, config files on desktop); OCR runs on-device with bundled ML Kit
  models.

## Release signing (Android)

CI signs release APKs with the committed **DEMO keystore**
(`android/keystore/axis-translate-release.jks`, alias `axis-translate`,
password `axis-translate-release`) so every CI artifact is installable and
signing is reproducible. It is a public demo key — replace it before shipping
to production. `android/app/build.gradle.kts` also accepts
`AXIS_KEYSTORE_FILE` / `AXIS_KEYSTORE_PASSWORD` / `AXIS_KEY_ALIAS` /
`AXIS_KEY_PASSWORD` environment variables for a real keystore.

## Building locally (optional)

Official binaries come from CI; local builds are optional and only needed for
development — see [android/README.md](android/README.md) and
[desktop/README.md](desktop/README.md).

## License

Apache-2.0. See [LICENSE](LICENSE).
