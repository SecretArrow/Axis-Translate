# Axis Translate — Desktop (C edition)

Offline AI translation for **Windows** and **Linux (Ubuntu)**, written in pure **C11**.
The application layer (UI, model manager, translation pipeline, downloads, storage) is
100% C; inference is powered by [llama.cpp](https://github.com/ggml-org/llama.cpp)
(pinned to `v0.4.1`, same runtime as the Android app) through its C API.

| | |
| --- | --- |
| **UI** | [Nuklear](https://github.com/Immediate-Mode-UI/Nuklear) 4.13.3 (vendored, MIT) + SDL2 — modern dark/light Material-3-inspired theme |
| **JSON** | [jsmn](https://github.com/zserge/jsmn) (vendored, MIT) |
| **Downloads** | WinHTTP + Schannel (Windows, zero deps) / libcurl (Linux) |
| **Checksums** | built-in streaming SHA-256 |

## Features

- **Translate** — 19 languages, auto source detection, streaming generation output,
  natural/formal/casual styles, long-input chunking, copy to clipboard.
- **Models** — download from the built-in manifest (Qwen3 0.6B/1.7B, Qwen2.5 0.5B —
  all SHA-256-verified), **import** any local GGUF, **export** the installed model
  to any folder, verify checksum, remove, engine reload.
- **History** — persisted per-translation log, click to reload an entry.
- **Settings** — dark/light theme, CPU threads, context length, temperature,
  max output tokens, default style. Everything stored in the user config dir.
- **Fully offline inference** — the only network usage is the model download.

## Build (Linux / Ubuntu)

```bash
sudo apt install -y build-essential cmake libsdl2-dev libcurl4-openssl-dev
cmake -S desktop -B desktop/build -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build -j
./desktop/build/axis-translate
```

Unit tests (pure-C logic: chunker, prompt builder, manifest parser, SHA-256,
settings/history store, language detection):

```bash
ctest --test-dir desktop/build --output-on-failure
```

## Build (Windows)

Requires Visual Studio 2022 (C/C++ workload) and CMake. SDL2 is fetched and
built automatically from source — no vcpdk/package manager needed:

```bat
cmake -S desktop -B desktop\build -DCMAKE_BUILD_TYPE=Release
cmake --build desktop\build --config Release -j
desktop\build\Release\axis-translate.exe
```

Everything else (llama.cpp, WinHTTP-based downloads) is dependency-free.

## Headless smoke test (CI)

```bash
AXIS_SMOKE=1 SDL_VIDEODRIVER=dummy ./axis-translate   # runs a few frames, exits 0
axis-translate --smoke                                  # same, via flag
```

## Storage layout

| OS | Path |
| --- | --- |
| Linux | `~/.config/axis-translate/` (`$XDG_CONFIG_HOME` respected) |
| Windows | `%LOCALAPPDATA%\AxisTranslate\` |

- `settings.conf` — key=value settings
- `history.log` — one JSON object per saved translation
- `models/<entry-id>/<file>.gguf` — downloaded models
- `models/imported/*.gguf` — imported models

Model files are plain GGUF — export from the desktop app and import on Android
(or the other way around) freely.

## Source map

```
desktop/
├── CMakeLists.txt         # build for both platforms (tests included)
├── assets/model_manifest.json  # same manifest as the Android app
├── third_party/           # nuklear.h, jsmn.h (vendored)
├── src/
│   ├── main.c             # entry point, --version/--smoke flags
│   ├── app.c              # state, worker thread, job queue, layout
│   ├── backend.c          # SDL2 + Nuklear backend (official demo adapted)
│   ├── theme.c            # Material-3-inspired dark/light styling
│   ├── ui_translate.c     # home screen
│   ├── ui_models.c        # model manager (download/import/export/verify/remove)
│   ├── ui_history.c       # saved translations
│   ├── ui_settings.c     # preferences + about
│   ├── ui_browser.c       # embedded file browser modal
│   ├── engine.c           # llama.cpp C wrapper (chat template, generation loop)
│   ├── models.c           # model lifecycle manager
│   ├── download_winhttp.c # Windows downloader (WinHTTP)
│   ├── download_curl.c    # Linux downloader (libcurl)
│   ├── manifest.c         # manifest parser (jsmn)
│   ├── translate logic: chunker.c prompt.c langs.c
│   ├── store.c            # settings + history persistence
│   └── str.c fs.c sha256.c platform_{win,posix}.c
└── tests/test_main.c      # assert-based unit tests (run by CTest)
```

## Licenses

Application code: same license as the repository. Vendored third-party:
Nuklear (MIT), jsmn (MIT). llama.cpp is fetched at configure time and linked
statically (MIT). Fonts are loaded from the operating system at runtime.
