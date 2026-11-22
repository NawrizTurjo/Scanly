import argparse
from pathlib import Path

import cv2

import gen_desk
import gen_pdfs
import gen_scanner
import gen_special


def main():
    parser = argparse.ArgumentParser(description="Generate synthetic Scanly test fixtures.")
    parser.add_argument("--out", type=Path, default=Path("test-data"))
    parser.add_argument("--only", choices=("scanner", "special", "pdf", "desk"))
    args = parser.parse_args()
    cv2.setNumThreads(1)
    generators = {"scanner": gen_scanner, "special": gen_special, "pdf": gen_pdfs, "desk": gen_desk}
    for name, module in generators.items():
        if args.only in (None, name):
            module.generate(args.out)
            print(f"Generated {name} fixtures")


if __name__ == "__main__":
    main()
