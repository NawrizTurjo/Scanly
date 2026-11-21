# Synthetic fixture generators

From the repository root:

```powershell
& G:/Paperless-Scanner/.venv/Scripts/python.exe tools/fixtures/generate_all.py
& G:/Paperless-Scanner/.venv/Scripts/python.exe -m unittest discover -s tools/fixtures/tests -v
```

Use `--out PATH` to select another output directory, or `--only scanner|special|pdf` to generate one set. The PDF generator can run independently because it renders its flat JPEGs from the same source renderer as the scanner.

The seed is 1234. Independent random streams are derived from the generator, category, and example indexes. JPEG settings and PDF metadata are fixed. The AES-256 example uses deterministic fixture keys and IVs through a local patch of pypdf's randomness sources. These are only for the known-password fixture. Generate with the same dependency versions and available Windows fonts for byte-identical output.

To add a scanner category, append its name to `CATEGORIES` in `gen_scanner.py` so existing random streams keep their indexes. Choose the source page, background, and effect and record the effect parameters in truth. Update the category counts in the tests and regenerate the fixtures and contact sheet.

Dependencies used are NumPy, OpenCV, Pillow, ReportLab, pypdf, and cryptography.
