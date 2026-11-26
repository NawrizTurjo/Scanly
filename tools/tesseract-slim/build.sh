#!/usr/bin/env bash
set -euo pipefail

# Usage: build.sh <4.9.0 checkout> <work-dir> <baseline|slim> [abi...]
SRC=$(cd "${1:?Tesseract4Android 4.9.0 checkout}" && pwd)
mkdir -p "${2:?work dir}"
WORK=$(cd "$2" && pwd)
MODE=${3:?baseline or slim}
shift 3
if [[ $# == 0 ]]; then
  set -- arm64-v8a x86_64
fi

REV=15c534717b1cb58261b58d4e4c1200c7f81f668c
if [[ $(git -C "$SRC" rev-parse HEAD) != "$REV" ]]; then
  echo "Expected Tesseract4Android tag 4.9.0 ($REV)" >&2
  exit 1
fi

NDK="${ANDROID_HOME:-/g/Android/Sdk}/ndk/27.2.12479018"
if [[ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "Missing NDK 27.2.12479018 at $NDK" >&2
  exit 1
fi
CMAKE=${CMAKE:-cmake}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
CPP=tesseract4android/src/main/cpp
case "$MODE" in
  baseline)
    SOURCE="$SRC/$CPP"
    stl=c++_static
    libs=(tesseract leptonica jpeg pngx)
    ;;
  slim)
    # Start from the tagged files so previous builds cannot affect the patch.
    SOURCE="$WORK/slim-source"
    mkdir -p "$SOURCE"
    git -C "$SRC" archive "$REV:$CPP" | tar -xf - -C "$SOURCE"
    patch --batch -p1 -d "$SOURCE" < "$SCRIPT_DIR/slim.patch"
    stl=c++_shared
    libs=(tesseract leptonica)
    ;;
  *)
    echo "Unknown mode: $MODE" >&2
    exit 1
    ;;
esac

strip=("$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip*)
for abi in "$@"; do
  build="$WORK/$MODE/$abi"
  "$CMAKE" -S "$SOURCE" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 \
    -DANDROID_STL="$stl" -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
  "$CMAKE" --build "$build" --parallel "${JOBS:-8}" --target tesseract

  destination="$WORK/stripped/$MODE/$abi"
  mkdir -p "$destination"
  for lib in "${libs[@]}"; do
    component=$lib
    case "$lib" in
      jpeg) component=libjpeg ;;
      pngx) component=libpng ;;
    esac
    "${strip[0]}" --strip-unneeded -o "$destination/lib$lib.so" "$build/$component/lib$lib.so"
  done
done
