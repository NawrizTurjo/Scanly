# Slim OpenCV 5.1.0

Scanly's own OpenCV build: only `core`, `flann`, `geometry`, `imgproc`, `imgcodecs` (JPEG and PNG) and
`photo`, instead of the 14-module Maven AAR. arm64 drops from 32.7 MB to 15.2 MB.

Everything else matches the official `org.opencv:opencv:5.1.0` AAR, so processed scans don't change:
NDK 27.2.12479018 (clang 18.0.3), static modules linked into one `libopencv_java5.so`, TBB threads,
Carotene and KleidiCV on arm64. The x86_64 build (emulator only) leaves out Intel IPP to stay at 23 MB.

## Rebuild

```sh
git clone --depth 1 --branch 5.1.0 https://github.com/opencv/opencv.git /g/opencv-build/src
bash tools/opencv-slim/build.sh /g/opencv-build/src /g/opencv-build/out arm64-v8a x86_64
```

Then copy:
- `out/stripped/<abi>/libopencv_java5.so` to `src/main/jniLibs/<abi>/`
- from `out/arm64-v8a/build/opencv_android/opencv/src/org/opencv/`: the `core`, `geometry`,
  `imgcodecs`, `imgproc`, `photo` and `utils` packages, and `android/OpenCVLoader.java`,
  `android/StaticHelper.java`, `android/Utils.java` (the camera views need resources Scanly doesn't ship).

`libc++_shared.so` comes from the official 5.1.0 AAR (NDK 27.2); Tesseract needs it too.

## Check that scans don't change

`OutputDigestProbe` (core/imaging androidTest) runs every fixture through detection and all six
filters and logs a SHA-256 per output. Run it with the old and the new library on arm64 (the
emulator translates ARM code) and diff the logs:

```sh
adb install -r -t --abi arm64-v8a core/imaging/build/outputs/apk/androidTest/debug/imaging-debug-androidTest.apk
adb shell am instrument -w -e digest 1 -e class dev.turjo.paperless.core.imaging.OutputDigestProbe   dev.turjo.paperless.core.imaging.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s DIGEST:I
```

2026-11-26: the slim build matched the official AAR exactly (19 fixtures x 6 filters + quads).
