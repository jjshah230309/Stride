# Swift reference harness

The Android app is a port, so "does it behave like the iPhone app?" needs an answer better than
reading two code bases side by side. This tool compiles the platform-independent parts of the iOS
app (analysis, speech, file formats, backups, the live-segment engine) into a command-line program
and uses it two ways:

- **`generate`** runs the real Swift code over synthetic activities and writes the results to
  `android/core/src/test/resources/golden`. The Kotlin tests run the same inputs and compare, so a
  port mistake shows up as a failing test with the first differing number or word.
- **`verify DIR`** decodes a folder or backup written by the Android app using the iOS models and
  readers, which is the strictest check that an iPhone could open what Android wrote.

```sh
Tools/swift-reference/run.sh generate
./android/gradlew -p android :core:test           # also writes android/core/build/interop
Tools/swift-reference/run.sh verify "$PWD/android/core/build/interop"
```

It needs a Swift 6 toolchain (Linux or macOS); set `SWIFT=/path/to/swift` if it is not on `PATH`.

## How it works

`prepare.sh` copies the pure-logic iOS sources into `Sources/StrideReference/ios/` (git-ignored) and
removes the Apple-only imports. `Shims.swift` supplies empty stand-ins for the few Apple types those
files mention. **The iOS app itself is never modified.** Two copied files get small patches, both
asserted so a change in the iOS code fails loudly instead of silently:

- `Persistence.swift`: the Documents folder is redirected to a scratch directory (never the real
  one), keys are written sorted, so output is reproducible.
- `Backup.swift`: the header timestamp is fixed.

Everything is deterministic (seeded generator, fixed dates, fixed ids), so re-running `generate`
rewrites identical files unless the iOS behaviour changed. A diff in `golden/` after a run therefore
means the iPhone app now behaves differently and the Kotlin port needs the same change.

## What the goldens cover

| File | From the iOS code | Compared in Kotlin by |
|---|---|---|
| `analysis.json` | `ActivityAnalyzer` over a hilly run with pauses, a ride with power, a hike without heart rate, a trail run with GPS dropouts | `GoldenAnalysisTest` |
| `segments.json` | `SegmentMatcher` including reversed, wrong-sport and too-short segments | `GoldenAnalysisTest` |
| `speech.json` | every spoken number, and all 17 metrics x 3 styles x 2 unit systems x interval or distance, plus start, finish, lap, alerts and segment calls | `GoldenSpeechTest` |
| `fmt.json` | `Fmt` number formatting, including values that sit exactly on rounding ties | `GoldenSpeechTest` |
| `exports.json`, `route-export.json` | GPX, TCX, CSV and FIT writers, byte for byte | `GoldenFormatsTest` |
| `imports.json` | GPX, TCX and FIT readers, including format sniffing | `GoldenFormatsTest` |
| `fitness.json` | Fitness, Fatigue and Form over 401 days | `GoldenFormatsTest` |
| `live-segments.json` | `LiveSegmentEngine` stepped through four runs and a ride | `LiveSegmentsGoldenTest` |
| `ios-documents/`, `ios.stridebackup` | the iOS data folder and backup file as the app writes them | `GoldenDocumentsTest` |
