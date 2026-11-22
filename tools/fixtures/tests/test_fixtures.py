import json
import re
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

import cv2
import numpy as np
from PIL import Image
from pypdf import PdfReader
from pypdf.errors import FileNotDecryptedError, PdfReadError

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "test-data"
SCANNER = DATA / "scanner" / "synthetic"
DESK = DATA / "scanner" / "desk"
SPECIAL = DATA / "special"
PDF = DATA / "pdf"


def truth(directory, key="images"):
    return json.loads((directory / "truth.json").read_text(encoding="utf-8"))[key]


class FixtureTests(unittest.TestCase):
    def test_desk_geometry(self):
        entries = truth(DESK)
        self.assertEqual(Counter(e["category"] for e in entries.values()),
                         dict.fromkeys(("desk-sheet", "white-on-light", "notebook-spread",
                                        "notebook-left", "notebook-right", "clipped-sheet",
                                        "occluded", "dim-desk", "book-closeup", "book-gutter",
                                        "shadow-band", "near-border"), 7))
        self.assertTrue((DESK / "_contact_sheet.jpg").is_file())
        for name, info in entries.items():
            with self.subTest(image=name):
                w, h = info["size"]
                self.assertIn((w, h), ((1500, 2000), (2000, 1500)))
                with Image.open(DESK / name) as photo:
                    self.assertEqual(photo.size, (w, h))
                category = info["category"]
                if category == "notebook-spread":
                    self.assertEqual(info["alt_corners"], [])
                pw, ph = info["page_size"]
                source = np.float32([[[0, 0], [pw - 1, 0], [pw - 1, ph - 1], [0, ph - 1]]])
                mapped = cv2.perspectiveTransform(source, np.array(info["homography"]))[0]
                np.testing.assert_allclose(mapped, info["corners"], atol=.01)
                for corners in [info["corners"], *info["alt_corners"]]:
                    quad = np.asarray(corners, np.float32)
                    self.assertEqual(quad.shape, (4, 2))
                    bounds = np.array([w - 1, h - 1])
                    self.assertTrue(np.all(quad >= 0) and np.all(quad <= bounds))
                    self.assertTrue(cv2.isContourConvex(quad))
                    share = cv2.contourArea(quad, oriented=True) / (w * h)
                    lower, upper = {"book-closeup": (.65, .90), "book-gutter": (.40, .70),
                                    "shadow-band": (.50, .85)}.get(category, (.25, .80))
                    self.assertTrue(lower <= share <= upper, share)
                    on_border = np.any((quad == 0) | (quad == bounds), axis=1)
                    self.assertEqual(int(on_border.sum()), 2 if category == "clipped-sheet" else 0)
                effects = info["effects"]
                if category.startswith("book-"):
                    gutter_right = effects["gutter_side"] == "right"
                    dx = pw - 1 if gutter_right else 1 - pw
                    far_edge = source[:, [1, 2] if gutter_right else [0, 3]] + [dx, 0]
                    far = cv2.perspectiveTransform(far_edge.astype(np.float32),
                                                   np.array(info["homography"]))[0]
                    self.assertTrue(np.any(np.all(far < 0, axis=0) |
                                           np.all(far > bounds, axis=0)))
                    self.assertTrue(0 < effects["page_bow"] <= .02)
                elif category == "shadow-band":
                    self.assertTrue(.20 <= effects["shadow_area"] <= .45, effects["shadow_area"])
                    self.assertTrue(.30 <= effects["shadow_depth"] <= .55)
                    self.assertTrue(0 < effects["light_gradient"] <= .35)
                elif category == "near-border":
                    quad = np.asarray(info["corners"])
                    distances = np.column_stack((quad, bounds - quad))
                    nearest = np.sort(distances, axis=0)[1] / np.hypot(w, h)
                    close = (.003 <= nearest) & (nearest <= .015)
                    self.assertEqual(np.count_nonzero(close[[0, 2]]), 1)
                    self.assertEqual(np.count_nonzero(close[[1, 3]]), 1)
                    self.assertTrue(np.all(np.sort(nearest)[2:] >= 5 * .015))

    def test_desk_determinism(self):
        script = ("from pathlib import Path; import sys, cv2, gen_desk; "
                  "cv2.setNumThreads(1); gen_desk.generate(Path(sys.argv[1]), "
                  "('notebook-left', 'clipped-sheet', *gen_desk.HARD_CATEGORIES), samples=1)")
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            for directory in (first, second):
                subprocess.run([sys.executable, "-B", "-c", script, directory], check=True,
                               cwd=ROOT / "tools" / "fixtures")
            a = Path(first) / "scanner" / "desk"
            b = Path(second) / "scanner" / "desk"
            for name in (*truth(a), "truth.json", "_contact_sheet.jpg"):
                with self.subTest(file=name):
                    self.assertEqual((a / name).read_bytes(), (b / name).read_bytes())
                    if name.endswith("_01.jpg"):
                        self.assertEqual((a / name).read_bytes(), (DESK / name).read_bytes())

    def test_images_geometry_and_references(self):
        for directory, count in ((SCANNER, 48), (SPECIAL, 10)):
            entries = truth(directory)
            self.assertEqual(len(entries), count)
            self.assertTrue((directory / "_contact_sheet.jpg").is_file())
            self.assertEqual(Counter(info["category"] for info in entries.values()),
                             {name: 6 for name in ("clean", "skewed", "shadows", "glare", "low-light",
                                                   "busy-background", "handwriting", "tables")}
                             if directory == SCANNER else {"id-cards": 4, "books": 3, "whiteboards": 3})
            for name, info in entries.items():
                with self.subTest(image=name):
                    with Image.open(directory / name) as photo:
                        self.assertEqual(photo.size, tuple(info["size"]))
                    if directory == SCANNER:
                        with Image.open(directory / "_pages" / (info["page_id"] + ".png")) as page:
                            self.assertEqual(page.size, (1240, 1754))
                    size = np.array(info["size"])
                    quad = np.array(info["corners"], np.float32)
                    self.assertTrue(np.all(quad >= size * .02))
                    self.assertTrue(np.all(quad <= size * .98))
                    self.assertTrue(cv2.isContourConvex(quad))
                    ratio = cv2.contourArea(quad) / np.prod(size)
                    self.assertTrue(.40 <= ratio <= .85, ratio)
                    if info["category"] == "skewed":
                        sides = np.roll(quad, -1, axis=0) - quad
                        sides /= np.linalg.norm(sides, axis=1, keepdims=True)
                        maximum = np.max(np.abs(np.sum(sides * np.roll(sides, 1, axis=0), axis=1)))
                        self.assertTrue(.22 <= maximum <= .5, maximum)
                    w, h = info["page_size"]
                    source = np.float32([[[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]]])
                    matrix = np.array(info["homography"])
                    mapped = cv2.perspectiveTransform(source, matrix)[0]
                    self.assertLessEqual(np.linalg.norm(mapped - quad, axis=1).max(), .5)
                    if info["category"] == "books":
                        axis = np.float32([[[(w - 1) / 2, 0], [(w - 1) / 2, h - 1]]])
                        np.testing.assert_allclose(info["gutter"], cv2.perspectiveTransform(axis, matrix)[0],
                                                   atol=.5)

    def test_glare_area(self):
        count = 0
        for name, info in truth(SCANNER).items():
            if info["category"] != "glare":
                continue
            with self.subTest(image=name):
                with Image.open(SCANNER / name) as photo:
                    saturated = np.asarray(photo).min(axis=2) >= 253
                mask = np.zeros(saturated.shape, np.uint8)
                cv2.fillConvexPoly(mask, np.rint(info["corners"]).astype(np.int32), 1)
                fraction = np.count_nonzero(saturated & (mask == 1)) / mask.sum()
                self.assertEqual(fraction > .08, info["effects"]["large_glare"], fraction)
                count += info["effects"]["large_glare"]
        self.assertEqual(count, 2)

    def test_pdf_geometry_and_search(self):
        entries = truth(PDF, "files")
        self.assertEqual(len(entries), 23)
        for name, info in entries.items():
            with self.subTest(pdf=name):
                path = PDF / name
                self.assertTrue(path.is_file())
                if info["corrupt"]:
                    continue
                reader = PdfReader(path, strict=True, password=info.get("encryption", {}).get("password"))
                self.assertEqual(len(reader.pages), info["page_count"])
                self.assertEqual(len(info["pages"]), info["page_count"])
                extracted = []
                for page, expected in zip(reader.pages, info["pages"]):
                    self.assertEqual(page.rotation, expected["rotation"])
                    np.testing.assert_allclose(list(page.mediabox), expected["mediabox"], atol=.002)
                    np.testing.assert_allclose(list(page.cropbox), expected["cropbox"], atol=.002)
                    size = [float(page.cropbox.width), float(page.cropbox.height)]
                    if page.rotation % 180:
                        size.reverse()
                    np.testing.assert_allclose(size, expected["displayed_size"], atol=.002)
                    extracted.append(page.extract_text())
                self.assertEqual(any(text.strip() for text in extracted), info["has_text"])
                for word, expect in info["search"].items():
                    counts = [re.findall(r"[a-z0-9]+(?:-[a-z0-9]+)*", text.lower()).count(word)
                              for text in extracted]
                    self.assertEqual(sum(counts), expect["total_hits"], word)
                    self.assertEqual([i for i, count in enumerate(counts, 1) if count], expect["pages"])

    def test_pdf_password_and_corruption(self):
        reader = PdfReader(PDF / "encrypted-aes256.pdf", strict=True)
        self.assertTrue(reader.is_encrypted)
        with self.assertRaises(FileNotDecryptedError):
            len(reader.pages)
        self.assertFalse(reader.decrypt("wrong"))
        self.assertTrue(reader.decrypt("scanly-test"))
        self.assertEqual(len(reader.pages), 2)
        self.assertEqual(reader.trailer["/Encrypt"]["/Length"], 256)
        self.assertEqual(reader.trailer["/Encrypt"]["/CF"]["/StdCF"]["/CFM"], "/AESV3")
        with self.assertRaises(PdfReadError):
            PdfReader(PDF / "batch-folder" / "broken.pdf", strict=True)

    def test_pdf_outline_and_links(self):
        info = truth(PDF, "files")["textbook-outline.pdf"]
        reader = PdfReader(PDF / "textbook-outline.pdf", strict=True)

        def flatten(items, level=1):
            for item in items:
                if isinstance(item, list):
                    yield from flatten(item, level + 1)
                else:
                    yield dict(title=item.title, level=level,
                               page=reader.get_destination_page_number(item) + 1)

        self.assertEqual(list(flatten(reader.outline)), info["outline"])
        targets = {page.indirect_reference.idnum: i for i, page in enumerate(reader.pages, 1)}
        links = []
        for i, page in enumerate(reader.pages, 1):
            for reference in page.get("/Annots", []):
                annotation = reference.get_object()
                self.assertEqual(annotation["/Subtype"], "/Link")
                links.append(dict(from_page=i, rect=list(annotation["/Rect"]),
                                  to_page=targets[annotation["/Dest"][0].idnum]))
        for actual, expected in zip(links, info["links"], strict=True):
            self.assertEqual(actual["from_page"], expected["from_page"])
            self.assertEqual(actual["to_page"], expected["to_page"])
            np.testing.assert_allclose(actual["rect"], expected["rect"], atol=.002)

    def test_pdf_headings_and_sanitizer_features(self):
        files = truth(PDF, "files")
        headings = PdfReader(PDF / "textbook-no-outline.pdf")
        self.assertEqual(headings.outline, [])
        for heading in files["textbook-no-outline.pdf"]["headings"]:
            self.assertIn(heading["title"], headings.pages[heading["page"] - 1].extract_text())
        reader = PdfReader(PDF / "with-js-attachment.pdf")
        info = files["with-js-attachment.pdf"]["features"]
        root = reader.root_object
        self.assertEqual(root["/Names"]["/JavaScript"]["/Names"][1]["/JS"],
                         info["document_javascript"])
        self.assertEqual(root["/OpenAction"]["/JS"], info["open_action_javascript"])
        self.assertIn(info["embedded_file"], reader.attachments)
        annotation = reader.pages[info["attachment_page"] - 1]["/Annots"][0].get_object()
        self.assertEqual(annotation["/Subtype"], "/FileAttachment")
        self.assertEqual(annotation["/FS"]["/F"], info["embedded_file"])
        self.assertEqual(list(annotation["/Rect"]), info["attachment_rect"])

    def test_size_budget(self):
        total = (DATA / "README.md").stat().st_size
        for directory in (SCANNER, SPECIAL, PDF):
            total += sum(path.stat().st_size for path in directory.rglob("*") if path.is_file())
        self.assertLessEqual(total, 25_000_000)

    def test_determinism(self):
        def files(root):
            return {path.relative_to(root): path.read_bytes()
                    for path in root.rglob("*") if path.is_file()}

        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            script = ROOT / "tools" / "fixtures" / "generate_all.py"
            for category in ("scanner", "special", "pdf"):
                for directory in (first, second):
                    subprocess.run([sys.executable, str(script), "--only", category, "--out", directory],
                                   check=True, capture_output=True, cwd=ROOT)
                first_files, second_files = files(Path(first)), files(Path(second))
                self.assertEqual(first_files.keys(), second_files.keys())
                for name, data in first_files.items():
                    with self.subTest(file=name):
                        self.assertTrue(data == second_files[name], f"Different bytes for {name}")
