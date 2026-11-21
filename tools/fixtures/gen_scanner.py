import cv2
import numpy as np
from PIL import Image

from common import (PAGE_SIZE, background, composite, contact_sheet, gaussian,
                    glare, jpeg, page_image, placement, record, rng_for, write_truth)

CATEGORIES = ("clean", "skewed", "shadows", "glare", "low-light",
              "busy-background", "handwriting", "tables")


def generate(out, categories=CATEGORIES):
    directory = out / "scanner" / "synthetic"
    pages = directory / "_pages"
    pages.mkdir(parents=True, exist_ok=True)
    entries = {}
    rendered = {}
    for category in categories:
        for index in range(6):
            rng = rng_for(1, CATEGORIES.index(category), index)
            kind = {"handwriting": "hand", "tables": "table"}.get(category, "print")
            page_id = f"{kind}_{index + 1:02}"
            if page_id not in rendered:
                rendered[page_id] = page_image(kind, index)
                rendered[page_id].save(pages / f"{page_id}.png", optimize=True, dpi=(150, 150))
            size = (1600, 1200) if index == 5 else (1200, 1600)
            texture = ("wood", "laminate", "checks", "carpet", "wood", "laminate")[index]
            if category == "clean" and index in (1, 4):
                texture = "white"
            if category == "busy-background":
                texture = "clutter"
            quad, matrix = placement(PAGE_SIZE, size, rng, category == "skewed")
            effects = dict(drop_shadow=index % 2 == 0, large_glare=False)
            image = composite(rendered[page_id], background(size, texture, rng),
                              matrix, effects["drop_shadow"])
            if category == "shadows":
                cx, cy = quad[index % 4] * .62 + quad.mean(axis=0) * .38
                rx, ry = size[0] * .32, size[1] * .40
                depth = float(rng.uniform(.30, .65))
                shadow = 1 - depth * gaussian(size, cx, cy, rx, ry)
                effects["shadow"] = dict(cx=float(cx), cy=float(cy), rx=rx, ry=ry, depth=depth)
                if index % 2 == 0:
                    x, y = map(int, quad.mean(axis=0))
                    blob = np.zeros(size[::-1], np.uint8)
                    rect = (x - 115, y - 190, x + 115, y + 190)
                    cv2.rectangle(blob, rect[:2], rect[2:], 255, -1)
                    shadow *= 1 - .27 * cv2.GaussianBlur(blob, (0, 0), 19) / 255
                    effects["shadow"]["phone"] = dict(rect=list(rect), blur=19, depth=.27)
                image *= shadow[..., None]
            if category == "low-light":
                exposure = float(rng.uniform(.30, .50))
                image = 255 * (image * exposure / 255) ** 1.13
                image *= [1.10, .96, .77]
                image = cv2.blur(image, (7, 1))
                effects.update(exposure=exposure, gamma=1.13, motion_blur=7,
                               white_balance=[1.10, .96, .77])
            else:
                gains = rng.uniform(.96, 1.015, 3)
                image *= gains
                effects["white_balance"] = gains.tolist()
            sigma = 8 if category == "low-light" else 2
            image += rng.normal(0, sigma, size[::-1]).astype(np.float32)[..., None]
            blur = .75 if category == "low-light" else .55
            image = cv2.GaussianBlur(image, (3, 3), blur)
            effects.update(noise_sigma=sigma, blur_sigma=blur)
            if category == "glare":
                effects["large_glare"] = index in (0, 3)
                image, effects["glare"] = glare(image, quad, rng, effects["large_glare"])
            relative = f"{category}/{category}_{index + 1:02}.jpg"
            jpeg(directory / relative, Image.fromarray(np.clip(image, 0, 255).astype("uint8")))
            entries[relative] = record(category, size, PAGE_SIZE, quad, matrix, texture, effects)
            entries[relative]["page_id"] = page_id
    write_truth(directory, "gen_scanner", entries)
    contact_sheet(directory, entries)
