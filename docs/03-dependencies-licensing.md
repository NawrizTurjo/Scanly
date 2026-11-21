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
| OpenCV Android (`org.opencv:opencv` on Maven Central) | Detection, warp, enhancement | Apache-2.0 (4.5+) | Use `abiFilters` to keep the APK small |
| Room + KSP | Local DB | Apache-2.0 | |
| DataStore (Preferences) | Settings | Apache-2.0 | |
| Dagger Hilt and AndroidX Hilt | DI and Compose ViewModel integration | Apache-2.0 | |
| Coil 3 | Thumbnail/image loading | Apache-2.0 | |
| PdfBox-Android (`com.tom-roush:pdfbox-android`) | Merge, split, compress, write, flatten | Apache-2.0 | Check that it's still maintained at S1; it's large, so enable R8 shrinking |
| `android.graphics.pdf.PdfRenderer` | PDF viewing | Platform (free) | Built in |
| Reorderable (`sh.calvin.reorderable`) | Drag-to-reorder in Compose lists/grids | Apache-2.0 | Or a hand-written version if it's small |
| Test: JUnit, Turbine, Robolectric, Compose UI test, Espresso | Tests | EPL-1.0 (JUnit 4, test-only) / Apache-2.0 | Test-only, never shipped |
| Test (optional): `org.openpnp:opencv` | OpenCV on the desktop JVM for unit tests | BSD/Apache | Test-only |

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
