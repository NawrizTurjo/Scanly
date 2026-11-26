// Slim OpenCV 5.1.0: the Java API for the modules Scanly uses plus prebuilt native libraries.
// Rebuild with tools/opencv-slim/build.sh (see third_party/opencv/README.md). Apache-2.0, see LICENSE.
plugins { id("paperless.android.library") }

android {
    namespace = "org.opencv"
    // Generated upstream code: lint findings there aren't ours to fix.
    lint.abortOnError = false
}
