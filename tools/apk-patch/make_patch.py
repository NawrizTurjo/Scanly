"""Writes a Scanly delta-update patch: old APK + patch -> new APK.

Usage: python tools/apk-patch/make_patch.py OLD.apk NEW.apk OUT.patch

Format (read by feature/settings ApkPatch.kt), big-endian:
  "SCNPATCH" | u32 version = 1
  u64 source size | source SHA-256 | u64 target size | target SHA-256
  u64 control length | u64 diff length | u64 extra length   (compressed)
  zlib(control) | zlib(diff) | zlib(extra)
Control holds (add, copy, seek) i64 triples, as in bsdiff. Needs `pip install bsdiff4` (BSD).
The script applies its own patch before writing it and fails if the result differs.
"""
import hashlib
import struct
import sys
import zlib

import bsdiff4.core as bsdiff

MAGIC = b"SCNPATCH"


def make_patch(old: bytes, new: bytes) -> bytes:
    encoded, diff, extra = bsdiff.diff(old, new)
    control = [tuple(bsdiff.decode_int64(encoded[j:j + 8]) for j in range(i, i + 24, 8))
               for i in range(0, len(encoded), 24)]
    control_bytes = b"".join(struct.pack(">qqq", add, copy, seek) for add, copy, seek in control)
    rebuilt = bsdiff.patch(old, encoded, diff, extra)
    if rebuilt != new:
        raise SystemExit("bsdiff round trip failed")
    sections = [zlib.compress(part, 9) for part in (control_bytes, diff, extra)]
    header = MAGIC + struct.pack(">I", 1)
    header += struct.pack(">Q", len(old)) + hashlib.sha256(old).digest()
    header += struct.pack(">Q", len(new)) + hashlib.sha256(new).digest()
    header += struct.pack(">QQQ", *(len(part) for part in sections))
    return header + b"".join(sections)


def apply_patch(old: bytes, patch: bytes) -> bytes:
    """Independent reader of the file format, used to check what we write."""
    assert patch[:8] == MAGIC and struct.unpack(">I", patch[8:12])[0] == 1
    offset = 12
    source_size, = struct.unpack(">Q", patch[offset:offset + 8]); offset += 8
    source_hash = patch[offset:offset + 32]; offset += 32
    target_size, = struct.unpack(">Q", patch[offset:offset + 8]); offset += 8
    target_hash = patch[offset:offset + 32]; offset += 32
    lengths = struct.unpack(">QQQ", patch[offset:offset + 24]); offset += 24
    assert source_size == len(old) and hashlib.sha256(old).digest() == source_hash
    parts = []
    for length in lengths:
        parts.append(zlib.decompress(patch[offset:offset + length]))
        offset += length
    assert offset == len(patch)
    control_bytes, diff, extra = parts
    control = [struct.unpack(">qqq", control_bytes[i:i + 24]) for i in range(0, len(control_bytes), 24)]
    encoded = b"".join(bsdiff.encode_int64(value) for triple in control for value in triple)
    new = bsdiff.patch(old, encoded, diff, extra)
    assert hashlib.sha256(new).digest() == target_hash
    return new


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    old_path, new_path, out_path = sys.argv[1:]
    old = open(old_path, "rb").read()
    new = open(new_path, "rb").read()
    patch = make_patch(old, new)
    if apply_patch(old, patch) != new:
        raise SystemExit("patch check failed")
    with open(out_path, "wb") as out:
        out.write(patch)
    print(f"{out_path}: {len(patch) / 1e6:.2f} MB patch for a {len(new) / 1e6:.2f} MB APK")


if __name__ == "__main__":
    main()
