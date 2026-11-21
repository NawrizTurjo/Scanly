#!/usr/bin/env bash
# Screenshot the emulator, downsized for review: bash tools/shot.sh <name> [width]
set -e
ADB=${ADB:-/g/Android/Sdk/platform-tools/adb.exe}
out="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/work/shots/$1.png"
mkdir -p "$(dirname "$out")"
"$ADB" exec-out screencap -p > "$out.full"
"$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/.venv/Scripts/python" -c "
import cv2,sys; im=cv2.imread(sys.argv[1]); w=int(sys.argv[3]); h=int(im.shape[0]*w/im.shape[1]); cv2.imwrite(sys.argv[2], cv2.resize(im,(w,h),interpolation=cv2.INTER_AREA))" "$out.full" "$out" "${2:-420}"
rm -f "$out.full"; echo "$out"
