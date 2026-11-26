#!/usr/bin/env bash
# Builds a slim OpenCV 5.1.0 for Scanly: only the modules the app calls (core, imgproc, imgcodecs,
# photo, geometry + its flann dependency) and only the JPEG and PNG codecs. Everything else matches
# the official Android AAR (NDK 27.2, static modules in one libopencv_java5.so, TBB threading,
# Carotene and KleidiCV HALs on arm64), so processed scans stay identical.
#
# Usage: tools/opencv-slim/build.sh <opencv-source-dir> <work-dir> [abi...]
#   Needs: NDK 27.2.12479018 under $ANDROID_HOME, CMake >= 3.22, Ninja, Python 3, JDK 21.
#   Output: <work-dir>/stripped/<abi>/libopencv_java5.so and <work-dir>/<abi>/build/opencv_android/opencv/src.
set -euo pipefail

SRC=${1:?OpenCV source dir (git tag 5.1.0)}
WORK=${2:?work dir}
shift 2
ABIS=("${@:-arm64-v8a x86_64}")
ANDROID_HOME=${ANDROID_HOME:-/g/Android/Sdk}
NDK="$ANDROID_HOME/ndk/27.2.12479018"
[ -d "$NDK" ] || { echo "Missing NDK 27.2.12479018 at $NDK" >&2; exit 1; }

for abi in ${ABIS[@]}; do
  build="$WORK/$abi/build"
  mkdir -p "$build"
  # No Intel IPP: the official x86_64 build has it, but x86_64 only runs on the emulator and IPP
  # would more than double that library (23 -> 54 MB). The arm64 build has none either way.
  cmake -S "$SRC" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_NATIVE_API_LEVEL=21 \
    -DANDROID_STL=c++_shared \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$WORK/$abi/install" \
    -DINSTALL_CREATE_DISTRIB=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_LIST=core,flann,geometry,imgproc,imgcodecs,photo,java,java_bindings_generator \
    -DBUILD_JAVA=ON -DBUILD_opencv_java=ON -DBUILD_opencv_java_bindings_generator=ON \
    -DBUILD_KOTLIN_EXTENSIONS=OFF \
    -DBUILD_ANDROID_PROJECTS=ON -DANDROID_SDK="$ANDROID_HOME" \
    -DBUILD_ANDROID_EXAMPLES=OFF \
    -DBUILD_EXAMPLES=OFF -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_DOCS=OFF \
    -DWITH_OPENCL=OFF -DWITH_IPP=OFF \
    -DWITH_TBB=ON \
    -DWITH_ITT=OFF \
    -DWITH_JPEG=ON -DBUILD_JPEG=ON \
    -DWITH_PNG=ON -DBUILD_PNG=ON \
    -DWITH_WEBP=OFF -DWITH_TIFF=OFF -DWITH_OPENJPEG=OFF -DWITH_JASPER=OFF \
    -DWITH_OPENEXR=OFF -DWITH_AVIF=OFF \
    -DWITH_IMGCODEC_GIF=OFF -DWITH_IMGCODEC_HDR=OFF -DWITH_IMGCODEC_SUNRASTER=OFF \
    -DWITH_IMGCODEC_PXM=OFF -DWITH_IMGCODEC_PFM=OFF \
    -DWITH_HARFBUZZ=OFF -DWITH_PROTOBUF=OFF -DWITH_FLATBUFFERS=OFF \
    -DWITH_ANDROID_MEDIANDK=OFF -DWITH_FFMPEG=OFF
  # Only the native library is needed; OpenCV's own Gradle packaging step is skipped. The Java API
  # is generated into $build/opencv_android/opencv/src.
  ninja -C "$build" opencv_java
  mkdir -p "$WORK/stripped/$abi"
  "$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip" -o "$WORK/stripped/$abi/libopencv_java5.so"     "$build/jni/$abi/libopencv_java5.so"
done
