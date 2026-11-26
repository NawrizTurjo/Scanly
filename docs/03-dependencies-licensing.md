# 03 — Dependencies and licensing

Rule: the app ships only Apache-2.0 / MIT / BSD code. **Pin exact versions at S1.** A Sonnet research task checks the latest *stable* release of each one; don't use alphas unless the row says so.

## Planned (all free)

| Library | Purpose | License | Notes |
|---|---|---|---|
| Kotlin + Coroutines | Language, async | Apache-2.0 | |
| Jetpack Compose (BOM) + Material 3 | UI | Apache-2.0 | Use Material 3 *Expressive* components only where they're stable |
| Google Material Symbols Outlined | Tab and scan icons | Apache-2.0 | Vector resources from [Material Design Icons](https://github.com/google/material-design-icons) |
| Navigation 3 | Screen navigation | Apache-2.0 | |
| AndroidX Activity, Core, and Lifecycle | Compose activity, window insets, and ViewModels | Apache-2.0 | |
| CameraX (core, camera2, lifecycle, view) | Preview, ImageAnalysis, ImageCapture | Apache-2.0 | |
| OpenCV 5.1.0, slim build (`third_party/opencv`) | Detection, warp, enhancement | Apache-2.0 (4.5+) | Since 1.1.4 built from the 5.1.0 source by `tools/opencv-slim/build.sh` with only core, flann, geometry, imgproc, imgcodecs (JPEG, PNG) and photo; same NDK 27.2, TBB and Carotene/KleidiCV HALs as the official AAR. Bundled 3rd-party code: TBB (Apache-2.0), libjpeg-turbo (BSD/IJG), libpng (libpng license), zlib, KleidiCV (Apache-2.0), Carotene (BSD-3) |
| Haze + Haze Glass (`dev.chrisbanes.haze`) | Optional glass look: backdrop blur and refraction on bars | Apache-2.0 | 2.1.0. Used only when Settings → Glass effect is on |
| Room + KSP | Local DB | Apache-2.0 | |
| DataStore (Preferences) | Settings | Apache-2.0 | |
| Dagger Hilt and AndroidX Hilt | DI and Compose ViewModel integration | Apache-2.0 | |
| Coil 3 | Thumbnail/image loading | Apache-2.0 | |
| PdfBox-Android (`com.tom-roush:pdfbox-android`) | Merge, split, compress, write, flatten | Apache-2.0 | Check that it's still maintained at S1; it's large, so enable R8 shrinking |
| `android.graphics.pdf.PdfRenderer` | PDF viewing | Platform (free) | Built in |
| Reorderable (`sh.calvin.reorderable`) | Drag-to-reorder in Compose lists/grids | Apache-2.0 | Or a hand-written version if it's small |
| Test: JUnit, Turbine, Robolectric, Compose UI test, Espresso | Tests | EPL-1.0 (JUnit 4, test-only) / Apache-2.0 | Test-only, never shipped |
| Test (optional): `org.openpnp:opencv` | OpenCV on the desktop JVM for unit tests | BSD/Apache | Test-only |
| Release tool: `bsdiff4` (Python) | Writes delta-update patches (`tools/apk-patch/make_patch.py`) | BSD-2-Clause | Release-side only, never shipped; the app applies patches with `java.util.zip` |

## Pinned at S1 (2026-11-22, latest stable from Google Maven / Maven Central)

| Library | Version | Notes |
|---|---|---|
| Gradle (wrapper) | 9.9.2 | |
| Android Gradle Plugin | 9.5.2 | Built-in Kotlin: no `org.jetbrains.kotlin.android` plugin |
| Kotlin (compose compiler, serialization plugins) | 2.4.21 | 2.5.0 is only RC |
| KSP | 2.3.14 | |
| Coroutines | 1.11.0 | |
| Compose BOM | 2026.11.01 | Material 3 1.5.0 through the BOM |
| activity-compose / core-ktx | 1.13.0 / 1.19.1 | |
| Lifecycle (runtime/viewmodel compose, viewmodel-navigation3) | 2.11.1 | |
| **Navigation 3** (runtime, ui) | 1.2.2 | **D5: Navigation 3** (stable since 1.0; Navigation Compose 2.10.2 not used) |
| CameraX | 1.7.0 | P1 |
| Room | 2.8.5 | P1 |
| DataStore Preferences | 1.3.0 | |
| Hilt / hilt-lifecycle-viewmodel-compose | 2.60.1 / 1.4.0 | |
| Coil 3 | 3.6.3 | P1 |
| OpenCV (`org.opencv:opencv`) | 5.1.0 | Same major as the Python lab (opencv-python 5.1) |
| PdfBox-Android | 2.0.27.0 | **Last release 2023-01**: no newer Apache-licensed alternative on Android, so keep it behind the `core:pdf` interface (R8) |
| [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android/releases/tag/4.9.0) | 4.9.0 | Apache-2.0. Built locally in `third_party/tesseract` with Tesseract 5.5.1 and Leptonica 1.85.0 (BSD-2-Clause). The slim build uses LSTM OCR from bitmaps and shares OpenCV's NDK runtime. See the module README for rebuild instructions and native sizes. |
| [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0) English model | 4.1.0 | Apache-2.0. `eng.traineddata`, 4,113,088 bytes before APK compression. |
| [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast/tree/4.1.0) Bengali model | 4.1.0 | Apache-2.0. `ben.traineddata`, 855,841 bytes before APK compression. |
| Reorderable | 3.1.0 | P1 |
| WorkManager | 2.12.0 | P6/P7b |
| Biometric | 1.1.0 stable (1.4.0-rc01 newest) | P4: re-check; prefer 1.4.0 once stable |
| Test: JUnit 4.13.2, Turbine 1.2.1, Robolectric 4.18.1, androidx.test.ext 1.3.0, Espresso 3.7.0 | | |
| Test (optional): `org.openpnp:opencv` | 4.9.0-0 | **Behind OpenCV 5.1**: JVM parity tests may differ slightly; decide at P2 (instrumented tests on the emulator instead) |

Searchable PDF export embeds the Liberation Sans font already bundled with PdfBox-Android under the [SIL Open Font License](https://github.com/TomRoush/PdfBox-Android/blob/master/LICENSE.txt). Recognised scan text is re-derived after backup restore when automatic recognition is enabled.

Dev-side tools (never shipped): Android Studio, SDK, adb, Gradle, Python 3 + `opencv-python`, `numpy`, `pillow`, `reportlab`/`pypdf` (fixture generation), and scrcpy (screen mirroring, Apache-2.0). All free.

## Don't use
| What | Why |
|---|---|
| iText, MuPDF / PyMuPDF on Android, Ghostscript | **AGPL/GPL** (PyMuPDF is fine in desktop-only dev tools like `tools/pdf-fixwidth/`, which are never shipped or linked into the app) |
| Scanbot, Dynamsoft, Genius Scan SDK, Nutrient/PSPDFKit, Apryse/PDFTron, Foxit | Commercial / paid licences |
| Firebase, analytics, crash reporters, ads SDKs | Break the offline/no-tracking rule |
| AndroidPdfViewer (barteksc) | Unmaintained; PdfRenderer is enough |

## Optional, decided at the S1 gate: Google ML Kit Document Scanner
`play-services-mlkit-document-scanner` is free, with no watermark. It has excellent edge detection, auto-capture, and ML stain/shadow cleanup. **But:** it uses Google's fixed UI, it's closed source and runs through Play services (it downloads its model through Play services), and you can't customise the camera screen, the modes, or the pipeline. That clashes with "my own scanner". **Recommendation:** don't ship it. You could use it *once* during Phase 2 as a quality benchmark (scan the same pages with both and compare). See 07-risks.

## Costs
Everything above is ৳0. The only possible cost is a Google Play developer account (US$25 one time), which isn't planned. You'll sideload the APK.
