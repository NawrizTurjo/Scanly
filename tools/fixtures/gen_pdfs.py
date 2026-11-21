import importlib
import io
import re
from contextlib import ExitStack
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image
from pypdf import PdfWriter
from pypdf.generic import ArrayObject, DictionaryObject, NameObject, RectangleObject, TextStringObject
from reportlab.lib.pagesizes import A3, A4, A5, LETTER, landscape
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen.canvas import Canvas

from common import page_image, rng_for, write_truth

SEARCH = ("momentum", "velocity", "pressure", "pageword-02", "pageword-11")


class Document:
    def __init__(self):
        self.buffer = io.BytesIO()
        self.canvas = Canvas(self.buffer, pagesize=A4, invariant=1, pageCompression=1)
        self.canvas.setTitle("Applied mechanics workshop")
        self.canvas.setAuthor("Workshop fixture")
        self.pages = []
        self.outline = []
        self.headings = []
        self.links = []
        self.content_boxes = []
        self.search = {}

    def start_page(self, size, rotation):
        w, h = (round(value, 3) for value in size)
        self.canvas.setPageSize((w, h))
        box = [0, 0, w, h]
        self.pages.append(dict(mediabox=box, cropbox=box, rotation=rotation,
                               displayed_size=[h, w] if rotation % 180 else [w, h]))
        return w, h

    def text(self, x, y, line, size=11, bold=False):
        self.canvas.setFont("Helvetica-Bold" if bold else "Helvetica", size)
        self.canvas.drawString(x, y, line)
        words = re.findall(r"[a-z0-9]+(?:-[a-z0-9]+)*", line.lower())
        for word in SEARCH:
            hits = words.count(word)
            if hits:
                entry = self.search.setdefault(word, dict(total_hits=0, pages=[]))
                entry["total_hits"] += hits
                page = len(self.pages)
                if page not in entry["pages"]:
                    entry["pages"].append(page)

    def link(self, title, rect, target_title, target_page):
        self.text(rect[0], rect[1] + 3, title)
        self.canvas.linkRect("", target_title, rect, relative=0, thickness=0)
        self.links.append(dict(from_page=len(self.pages), rect=list(rect), to_page=target_page))


def heading_plan(chapters, sections, subsections):
    plan = []
    for ch in range(1, chapters + 1):
        plan.append((f"Chapter {ch}  Motion study", 1))
        for sec in range(1, sections + 1):
            plan.append((f"Section {ch}.{sec}  Trial observations", 2))
            for sub in range(1, subsections + 1):
                plan.append((f"{ch}.{sec}.{sub}  Measurement method", 3))
    return plan


def render_document(count, kind, scans, sizes=None, rotations=None):
    doc = Document()
    canvas = doc.canvas
    outline = kind == "outline"
    if outline:
        plan = heading_plan(3, 3, 1)
        offset = 2
        targets = {title: i + offset + 1 for i, (title, _) in enumerate(plan)}
    elif kind == "headings":
        plan = heading_plan(2, 3, 2)
        offset = 0
    for i in range(count):
        rotation = rotations[i] if rotations else 0
        w, h = doc.start_page(sizes[i] if sizes else A4, rotation)
        if kind == "scan":
            canvas.drawImage(scans[i % len(scans)], 0, 0, w, h)
        elif kind == "margins":
            box = [168 - i * 12, 295 + i * 15, w - 170 + i * 10, h - 275 - i * 8]
            doc.content_boxes.append(box)
            x0, y0, x1, y1 = box
            canvas.setLineWidth(0)
            canvas.rect(x0, y0, x1 - x0, y1 - y0)
            doc.text(x0 + 8, y1 - 24, f"Workshop {i + 1}: velocity and pressure")
            doc.text(x0 + 8, y1 - 48, "Compare momentum between trials.")
        else:
            if outline and i < offset:
                doc.text(48, h - 62, "Contents" if i == 0 else "Workshop introduction", 24, True)
                if i == 0:
                    for j, (title, level) in enumerate(item for item in plan if item[1] == 1):
                        y = h - 115 - j * 30
                        doc.link(title, (48, y, 300, y + 16), title, targets[title])
            elif kind in ("outline", "headings") and i - offset < len(plan):
                title, level = plan[i - offset]
                doc.text(48, h - 62, title, (24, 18, 14)[level - 1], True)
                node = dict(title=title, level=level, page=i + 1)
                if outline:
                    canvas.bookmarkPage(title)
                    canvas.addOutlineEntry(title, title, level=level - 1)
                    doc.outline.append(node)
                else:
                    doc.headings.append(node)
            else:
                doc.text(48, h - 62, "Applied mechanics workshop", 21, True)
            if outline and i in (1, 3, 16):
                title = "Section 2.3  Trial observations"
                doc.link("See section 2.3", (48, h - 220, 240, h - 203), title, targets[title])
            doc.text(48, h - 94, f"Trial sheet {i + 1:03}   pageword-{i + 1:02}")
            doc.text(48, h - 121, "Compare the velocity and momentum before each trial.")
            if i % 2 == 0:
                doc.text(48, h - 145, "Record the pressure, then estimate the velocity.")
            if kind in ("large", "vector", "mixed"):
                canvas.setStrokeColorRGB(.19, .34, .44)
                canvas.rect(48, h - 360, 145 + i % 23, 90)
            if kind in ("vector", "mixed"):
                canvas.line(48, h - 360, 180, h - 285)
                canvas.bezier(57, h - 350, 71, h - 281, 145, h - 345, 177, h - 281)
            if kind == "mixed":
                canvas.drawImage(scans[i % len(scans)], 235, h - 465, 155, 219)
            doc.text(48, 42, f"Page {i + 1}")
        canvas.showPage()
    canvas.save()
    for page in doc.pages:
        page["displayed_width"] = page["displayed_size"][0]
    truth = dict(page_count=count, pages=doc.pages, has_text=kind != "scan",
                 search=doc.search, corrupt=False)
    if outline:
        truth.update(outline=doc.outline, links=doc.links)
    if kind == "headings":
        truth.update(headings=doc.headings, outline=[])
    if doc.content_boxes:
        truth["content_boxes"] = doc.content_boxes
    return doc.buffer.getvalue(), truth


def encrypted(writer, output):
    import pypdf._encryption as encryption
    import pypdf._writer as writer_module

    rng = rng_for(3)
    source = SimpleNamespace(token_bytes=rng.bytes)
    provider = importlib.import_module(encryption.CryptAES.__module__)
    # Fixture-only keys and IVs. Limit the seeded byte source to pypdf's modules.
    with ExitStack() as stack:
        for module in (encryption, writer_module, provider):
            stack.enter_context(patch.object(module, "secrets", source))
        writer.encrypt("scanly-test", owner_password="scanly-fixture-owner", algorithm="AES-256")
        writer.write(output)


def dictionary(**entries):
    return DictionaryObject({NameObject("/" + key): value for key, value in entries.items()})


def attachments_and_actions(writer):
    root = writer.root_object
    if "/Names" not in root:
        root[NameObject("/Names")] = DictionaryObject()
    action = dictionary(S=NameObject("/JavaScript"), JS=TextStringObject("void 0;"))
    root["/Names"][NameObject("/JavaScript")] = dictionary(
        Names=ArrayObject(
            [TextStringObject("Fixture"), action]))
    root[NameObject("/OpenAction")] = dictionary(
        S=NameObject("/JavaScript"), JS=TextStringObject("void 1;"))
    writer.add_attachment("notes.txt", b"Synthetic workshop notes.\n")
    filespec = root["/Names"]["/EmbeddedFiles"]["/Names"][1]
    writer.add_annotation(0, dictionary(Type=NameObject("/Annot"),
                                        Subtype=NameObject("/FileAttachment"),
                                        Rect=RectangleObject([72, 90, 94, 112]),
                                        FS=filespec, Name=NameObject("/PushPin")))


def generate(out):
    directory = out / "pdf"
    (directory / "batch-folder").mkdir(parents=True, exist_ok=True)
    scans = []
    for i in range(5):
        buffer = io.BytesIO()
        page = page_image("print", i)
        page.thumbnail((760, 1075), Image.Resampling.LANCZOS)
        page.save(buffer, format="JPEG", quality=75, subsampling=2, optimize=True)
        scans.append(ImageReader(buffer))
    definitions = [
        ("single-page", 1, "text"), ("multipage-15", 15, "text"), ("large-300", 300, "large"),
        ("image-only-scan", 5, "scan"), ("text-vector", 3, "vector"), ("mixed", 4, "mixed"),
        ("rotated-pages", 6, "text"), ("mixed-page-sizes", 6, "text"),
        ("textbook-outline", 24, "outline"), ("textbook-no-outline", 20, "headings"),
        ("encrypted-aes256", 2, "text"), ("with-js-attachment", 2, "text"),
        ("wide-margins", 4, "margins"),
    ]
    batch_kinds = ("text", "vector", "scan", "margins", "mixed", "text", "vector", "text", "scan")
    definitions.extend((f"batch-folder/sample-{i + 1:02}", i % 6 + 1, kind)
                       for i, kind in enumerate(batch_kinds))
    entries = {}
    for name, count, kind in definitions:
        sizes = [A4, LETTER, A5, landscape(A3), A4, (842, 680)] if name == "mixed-page-sizes" else None
        rotations = [0, 90, 180, 270, 0, 90] if name == "rotated-pages" else None
        data, truth = render_document(count, kind, scans, sizes, rotations)
        filename = f"{name}.pdf"
        target = directory / filename
        if rotations or name in ("encrypted-aes256", "with-js-attachment"):
            writer = PdfWriter(clone_from=io.BytesIO(data))
            if rotations:
                for page, rotation in zip(writer.pages, rotations):
                    page.rotate(rotation)
                writer.write(target)
            elif name == "encrypted-aes256":
                truth["encryption"] = dict(password="scanly-test", algorithm="AES-256")
                encrypted(writer, target)
            else:
                truth["features"] = dict(document_javascript="void 0;", open_action_javascript="void 1;",
                                         embedded_file="notes.txt", attachment_page=1,
                                         attachment_rect=[72, 90, 94, 112])
                attachments_and_actions(writer)
                writer.write(target)
        else:
            target.write_bytes(data)
        entries[filename] = truth
    original = (directory / "single-page.pdf").read_bytes()
    xref = original.rindex(b"\nxref\n")
    (directory / "batch-folder" / "broken.pdf").write_bytes(original[:xref + 45])
    entries["batch-folder/broken.pdf"] = dict(corrupt=True, page_count=None, pages=[])
    write_truth(directory, "gen_pdfs", entries, key="files")
