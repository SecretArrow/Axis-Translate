# Axis Translate

[![CI](https://github.com/SecretArrow/Axis-Translate/actions/workflows/ci.yml/badge.svg)](https://github.com/SecretArrow/Axis-Translate/actions/workflows/ci.yml?query=branch%3Amain)
[![E2E](https://github.com/SecretArrow/Axis-Translate/actions/workflows/e2e.yml/badge.svg)](https://github.com/SecretArrow/Axis-Translate/actions/workflows/e2e.yml?query=branch%3Amain)
[![Release](https://github.com/SecretArrow/Axis-Translate/actions/workflows/release.yml/badge.svg)](https://github.com/SecretArrow/Axis-Translate/actions/workflows/release.yml?query=branch%3Amain)

**Axis Translate is a privacy-first, offline AI translation app for Android.**
A quantized LLM runs locally through llama.cpp, so every translation, every
OCR pass and every prompt stays on your device: no cloud translation service,
no accounts, no tracking, no telemetry. The app uses the network exactly once
per model — to download and SHA-256-verify the GGUF weights on first launch —
and after that it works with airplane mode on. Photos, camera, voice,
documents, glossary and batch translation are all powered by the same local
engine.

## Features

| Feature | Description |
| --- | --- |
| Text translation | Auto language detection, chunked long-input translation, cancellable with live progress |
| Photo translation | Extract and translate text from photos in your gallery |
| Camera translation | Live on-device OCR over the CameraX viewfinder |
| Voice translation | Speak and get an instant translation |
| Conversation mode | Two-language side-by-side dialogue for face-to-face conversation |
| Documents | Translate long documents while preserving paragraph structure |
| History | Every translation is saved locally (Room) — searchable and reusable |
| Favorites | Star translations for quick access |
| Glossary | Enforce custom terminology (names, brands, jargon) in every translation |
| Batch queue | Queue multiple items and translate them sequentially |
| Floating translator | Translate from a floating overlay while using other apps |
| Quick Settings tile | Jump straight into translation from the notification shade |
| Model manager | Download models on first launch with **SHA-256 verification**, or import a local `.gguf` file — all models run fully offline after install |

## Architecture

### Native inference engine

- **llama.cpp is pinned to `v0.4.1`** via CMake `FetchContent` in
  [`app/src/main/cpp/CMakeLists.txt`](app/src/main/cpp/CMakeLists.txt) — the
  tag is fetched once at configure time, which makes CI builds reproducible.
- The JNI bridge [`app/src/main/cpp/axis_engine_jni.cpp`](app/src/main/cpp/axis_engine_jni.cpp)
  compiles to `libaxis_engine.so` (C++17) and links the llama.cpp static
  libraries into a single small `.so`.
- CPU backend only — every other backend (CUDA, Metal, Vulkan, OpenCL, BLAS,
  RPC) is disabled at configure time to keep the APK small and native builds fast.

### Translation pipeline

`TranslationManager` owns the engine lifecycle (lazy model load, voluntary
unload, cooperative cancellation) and runs this pipeline:

```
 Input (text / photo / camera / voice / document)
   |
   v
 Normalize  (NFC, trim, collapse blank lines)
   |
   v
 Language detection  (auto mode: script + n-gram detection)
   |
   v
 Chunking  (long text -> safe chunks, paragraph-aware)
   |
   v
 Prompt builder  (language pair + glossary + style)
   |
   v
 llama.cpp v0.4.1 inference  (JNI -> libaxis_engine.so, C++17)
   |  streaming, per-chunk progress, cooperative cancellation
   v
 Reassemble  (chunks rejoin mirroring the original paragraph breaks)
   |
   v
 Output
```

### App stack

| Layer | Technology |
| --- | --- |
| UI | Jetpack Compose + Material 3, single activity, Navigation Compose |
| Async | Kotlin coroutines + Flow |
| Persistence | Room (history, favorites, glossary) |
| Settings | DataStore Preferences |
| OCR | ML Kit on-device text recognition — bundled, Latin / Chinese / Japanese / Korean / Devanagari scripts, works offline |
| Camera | CameraX |
| Native | llama.cpp `v0.4.1` via CMake FetchContent + JNI bridge (`app/src/main/cpp/axis_engine_jni.cpp`) |
| Serialization | kotlinx-serialization |

## CI/CD

All builds, tests and releases run on **GitHub Actions** — nothing is built
locally. The three workflows chain on `main` via `workflow_run`:

```
 push to main / PR to main
        |
        v
   +---------+   ktlint auto-fix commit? --yes--> push "style: ktlint
   |   CI    |                                   auto-fix [auto-fix]" and end
   +---------+   the run (green); a fresh CI run validates the fix
        |   ktlintCheck - Android Lint - unit tests - assembleDebug
        |  workflow_run: conclusion == success, branch main
        v
   +---------+
   |   E2E   |   connectedDebugAndroidTest on an API 30 x86_64 emulator
   +---------+   (-Paxis.abis=x86_64, KVM accelerated)
        |  workflow_run: conclusion == success, branch main
        v
   +---------+   assembleRelease -Paxis.splitApks=true
   | Release |   -> per-ABI APKs + SHA256SUMS.txt -> GitHub Release
   +---------+
```

### Workflows

| Workflow | Trigger | What it does |
| --- | --- | --- |
| **CI** (`ci.yml`) | push to `main`, tags `v*`, PRs to `main`, manual | ktlint auto-fix (see below), `ktlintCheck`, Android Lint (`lintDebug`), unit tests (`testDebugUnitTest`), debug build (`assembleDebug`); uploads the debug APK artifact |
| **E2E** (`e2e.yml`) | `workflow_run` after CI succeeds on `main`, manual | Instrumented tests (`connectedDebugAndroidTest`) on an API 30 x86_64 emulator with KVM, emulator image cached between runs |
| **Release** (`release.yml`) | `workflow_run` after E2E succeeds on `main`, tags `v*`, manual | `assembleRelease -Paxis.splitApks=true`, renames APKs per ABI, generates `SHA256SUMS.txt`, publishes a GitHub Release |

Superseded runs are cancelled (`concurrency` groups), Gradle user home is
cached by `gradle/actions/setup-gradle@v4`, and every job has a hard timeout.
Failures in CI skip E2E; failures in E2E skip Release — a release can never be
published from a red chain.

### ktlint auto-fix

On every push to `main`, CI first runs `./gradlew ktlintFormat`. If that
changes any file, CI commits the result as
`style: ktlint auto-fix [auto-fix]`, pushes it to `main` and ends the current
run green — a fresh CI run then validates the formatted code. This cannot
loop:

- the auto-fix step is skipped when the head commit message contains
  `[auto-fix]`, and
- ktlint formatting is idempotent, so the fresh run finds nothing to fix.

On pull requests nothing is pushed; the run simply fails with the ktlint
violations listed.

### Release artifacts

Release builds are **split per ABI** (no universal APK). Version is
`1.0.<CI run number>` (`versionCode` = run number):

| Artifact | ABI | Best for |
| --- | --- | --- |
| `AxisTranslate-v<version>-arm64-v8a.apk` | arm64-v8a | Modern 64-bit devices (recommended) |
| `AxisTranslate-v<version>-armeabi-v7a.apk` | armeabi-v7a | Older 32-bit ARM devices |
| `AxisTranslate-v<version>-x86.apk` | x86 | 32-bit x86 devices |
| `AxisTranslate-v<version>-x86_64.apk` | x86_64 | Emulators / x86_64 devices |
| `SHA256SUMS.txt` | — | SHA-256 checksums of all APKs in the release |

Every release is published with `generate_release_notes: true` (auto change
log from commits and PRs) plus the ABI guidance above.

### Manual runs

Each workflow can be triggered by hand: **Actions** tab → select the workflow
(**CI** / **E2E** / **Release**) → **Run workflow**. Tag pushes matching `v*`
run **CI** and **Release** directly.

## Models

Model installation is **manifest-driven**: `app/src/main/assets/model_manifest.json`
(schema version 1) is the single source of truth for available models —
display name, description, download URL, size, context length, license and
per-model SHA-256 digest. The model manager downloads the selected entry on
first launch, verifies it byte-for-byte (**SHA-256** + size) and only then
activates it.

| Model | Role | Notes |
| --- | --- | --- |
| **Qwen3.5 2B Q4_K_M GGUF** | Default — best quality | Latest-generation multilingual model (2026, 201 languages). Real, verified GGUF — 1,281 MB (1,280,835,840 bytes), Apache-2.0; recommended on devices with 4 GB RAM or more |
| **Qwen3.5 0.8B Q4_K_M GGUF** | Fast & light | Same generation as the 2B variant, snappier on standard phones — 533 MB (532,517,120 bytes), Apache-2.0 |
| **Qwen2.5-0.5B-Instruct Q4_K_M GGUF** | Verified fallback | 491 MB (491,400,032 bytes), Apache-2.0 (Qwen license), same llama.cpp runtime; instruction-driven translation quality |

The engine applies each model's **own chat template** and pre-fills an empty
reasoning block for hybrid-reasoning models (Qwen3 / Qwen3.5) so translations
come back directly, without reasoning traces.

- **The network is used only to download models** (first launch / model
  manager). After a model is installed, every translation is computed locally.
- Any compatible `.gguf` file can be **imported manually** from device storage
  via the model manager — no download required.

## Privacy

- No tracking, no analytics, no crash reporting.
- No cloud translation service — nothing you translate ever leaves the device.
- History, favorites and settings live in the local Room database and
  DataStore; OCR runs on-device with bundled ML Kit models.

## Release signing

CI signs release APKs with the committed **DEMO keystore**
`keystore/axis-translate-release.jks` (alias `axis-translate`, password
`axis-translate-release`) so every CI artifact is installable and signing is
reproducible. It is a public demo key — replace it before shipping to
production.

For a production keystore, export the following environment variables for
the Release job (e.g. materialized from repository secrets — do not commit
the real keystore). `app/build.gradle.kts` reads them directly:

| Environment variable | Purpose |
| --- | --- |
| `AXIS_KEYSTORE_FILE` | Absolute path to the keystore file (e.g. materialized from a base64 secret) |
| `AXIS_KEYSTORE_PASSWORD` | Keystore password |
| `AXIS_KEY_ALIAS` | Key alias |
| `AXIS_KEY_PASSWORD` | Key password (defaults to the keystore password when unset) |

When these are unset, the build falls back to the committed demo keystore.

## Building locally (optional)

Official binaries come from CI; local builds are optional and only needed for
development:

```bash
# Debug APK (all ABIs)
./gradlew assembleDebug

# Release APKs split per ABI, versioned from a build number
./gradlew assembleRelease -Paxis.splitApks=true -Paxis.buildNumber=999

# Instrumented tests on a connected x86_64 emulator/device
./gradlew connectedDebugAndroidTest -Paxis.abis=x86_64

# Style
./gradlew ktlintFormat ktlintCheck

# Unit tests + Android Lint
./gradlew testDebugUnitTest lintDebug

# Speed up repeated native builds with ccache
./gradlew assembleRelease -Paxis.ccache=true
```

Gradle tunables used by CI: `-Paxis.abis=<list>` (restrict native ABIs),
`-Paxis.splitApks=true` (per-ABI release splits), `-Paxis.buildNumber=N`
(versionCode), `-Paxis.ccache=true` (ccache launcher for native compilation).

## License

Apache-2.0. See [LICENSE](LICENSE).
