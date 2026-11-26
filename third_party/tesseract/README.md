# Slim Tesseract4Android 4.9.0

Scanly vendors the standard Tesseract4Android Java API and builds the native libraries from
tag `4.9.0` (commit `15c534717b1cb58261b58d4e4c1200c7f81f668c`). That tag contains
Tesseract 5.5.1 and Leptonica 1.85.0. See [LICENSE](LICENSE) and
[Leptonica's license](src/main/assets/licenses/BSD-2-Clause.txt).

Scanly decodes JPEG pages with Android's `BitmapFactory`, converts them to grayscale
ARGB_8888 bitmaps, and passes those pixels through `setImage(Bitmap)`. It uses
`OEM_LSTM_ONLY` and the default `PSM_SINGLE_BLOCK` with the bundled tessdata_fast 4.1.0
`eng` and `ben` models. `getHOCRText(0)` enables progress and cancellation, and Scanly reads the
recognized text and word results through `getUTF8Text()` and the result iterator.

- `DISABLED_LEGACY_ENGINE` and Tesseract's own list of legacy-only sources remove the legacy
  classifier, legacy training support, and orientation/script detection. The app's engine and page
  segmentation modes don't use them. Training executables were already excluded by upstream.
  The JNI clear path still resets the document dictionary between pages and skips the legacy
  adaptive classifier reset.
- Leptonica's JPEG and PNG I/O use the upstream stubs. This drops `libjpeg.so` and `libpngx.so`
  entirely. The bitmap-to-Pix conversion needs raw pixels only. The Java wrappers omit the two
  library loads and keep their original package names and signatures. `getLibraryFlavor()` reports
  `standard`, matching the selected upstream flavor.
- Leptonica's PDF and PostScript writers use the upstream stubs. Scanly's OCR path produces hOCR
  and word results, with PDF generation handled elsewhere. The patch updates the disabled
  `cropFilesToPdf` stub signature to match Leptonica's public header. GIF, TIFF, WebP and JPEG 2000
  codecs were already disabled in the upstream Android build. Leptonica's JP2 header reader remains.
- The published AAR links libc++ statically. This build links `c++_shared` from the same NDK that
  OpenCV uses and packages the runtime through `third_party/opencv`. The standalone OCR test APK
  also includes the OpenCV module at runtime to get the shared library.

## Rebuild

Use Git Bash with CMake 3.22.1, Ninja, and NDK `27.2.12479018` (Clang 18.0.3) under
`ANDROID_HOME`. Set `CMAKE` to the executable path if needed. The script uses Android API 21,
`RelWithDebInfo` (`-O2 -g -DNDEBUG`), the standard flavor (no OpenMP), upstream's
`GRAPHICS_DISABLED` and `FAST_FLOAT`, and 16 KiB page alignment. It strips each output with
the NDK's `llvm-strip --strip-unneeded`.

```sh
git clone --depth 1 --branch 4.9.0 https://github.com/adaptech-cz/Tesseract4Android.git _src/upstream
bash tools/tesseract-slim/build.sh _src/upstream _src/native baseline
bash tools/tesseract-slim/build.sh _src/upstream _src/native slim
```

Copy `_src/native/stripped/slim/<abi>/*.so` into `src/main/jniLibs/<abi>/` in this module.
The baseline mode builds the upstream CMake sources with the original static STL linkage.

## Stripped native sizes

| Library | arm64-v8a AAR | arm64-v8a slim | x86_64 AAR | x86_64 slim |
| --- | ---: | ---: | ---: | ---: |
| `libtesseract.so` | 4,164,360 B | 2,294,672 B | 4,332,136 B | 2,446,520 B |
| `libleptonica.so` | 2,878,240 B | 2,759,024 B | 3,104,384 B | 2,980,760 B |
| `libjpeg.so` | 251,888 B | Removed | 281,728 B | Removed |
| `libpngx.so` | 219,880 B | Removed | 231,224 B | Removed |
| **Total** | **7,514,368 B** | **5,053,696 B** | **7,949,472 B** | **5,427,280 B** |

The local unmodified build produced a 4,167,432-byte arm64 `libtesseract.so` and a
4,335,208-byte x86_64 `libtesseract.so`. Both are within 0.08% of the published AAR.

## Compare recognized text

Build the standalone test APK with `./gradlew.bat :core:ocr:assembleDebugAndroidTest`
for each build. For the upstream comparison, use the original catalog and repository entries
and the original OCR dependency with this same test source and assets. Compare logs per ABI across
the two builds.

```sh
adb install -r -t --abi arm64-v8a core/ocr/build/outputs/apk/androidTest/debug/ocr-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -e digest 1 -e class dev.turjo.paperless.core.ocr.OcrDigestProbe dev.turjo.paperless.core.ocr.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s OCRDIGEST:I
```

Repeat with `--abi x86_64`. Each run reports nine SHA-256 digests of UTF-8 text and previews of
the first 80 characters with line breaks escaped.
