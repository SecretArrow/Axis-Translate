# Axis Translate

**Production-grade offline AI translation app for Android.**

Axis Translate performs all translation, OCR and inference **on the device**.
After the model is installed, translation keeps working with airplane mode ON,
Wi-Fi OFF and mobile data OFF.

- Runtime: llama.cpp (pinned `v0.4.1`, native JNI integration)
- Model manager: GGUF download, SHA-256 verification, manual import
- Offline OCR: ML Kit on-device text recognition (Latin / CJK / Devanagari)
- UI: Jetpack Compose, Material 3

## CI/CD

All builds, tests and releases run on **GitHub Actions** (never locally):

| Workflow | Purpose |
|----------|---------|
| `ci.yml` | ktlint auto-fix, Android Lint, unit tests, debug build |
| `e2e.yml` | Instrumented tests on an API 30 x86_64 emulator |
| `release.yml` | Release APKs **split per ABI** + GitHub Release |

## Status

See the badges below once CI is wired.

## License

Apache-2.0. See [LICENSE](LICENSE).
