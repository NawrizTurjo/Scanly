import cv2
import numpy as np
from PIL import Image, ImageDraw

from common import (background, composite, contact_sheet, font, glare,
                    jpeg, page_image, placement, record, rng_for, write_truth)


def card(index):
    image = Image.new("RGB", (856, 540), "#edf1e9")
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 856, 109), fill=("#226d86", "#496787", "#387569", "#775475")[index])
    draw.text((42, 28), "Workshop Staff Card", fill="white", font=font("arial.ttf", 45))
    draw.rounded_rectangle((43, 165, 251, 466), radius=12, fill="#cfdbdc")
    draw.ellipse((102, 211, 193, 306), fill="#5d8397")
    draw.rounded_rectangle((73, 322, 223, 435), radius=45, fill="#5d8397")
    draw.text((292, 214), "TEST PERSON", fill="#26343c", font=font("arial.ttf", 48))
    draw.text((292, 300), f"SPECIMEN 000-00{index + 1}", fill="#26343c", font=font("arial.ttf", 32))
    return image


def spread(index):
    image = Image.new("RGB", (1240, 877))
    for side in range(2):
        source = page_image("print", index * 2 + side)
        image.paste(source.resize((620, 877), Image.Resampling.LANCZOS), (620 * side, 0))
    y, x = np.indices((877, 1240), dtype=np.float32)
    bend = 15 * np.sin(np.pi * ((x + 0.5) / 1240) * 2) * np.exp(-((x - 619.5) / 260) ** 2)
    warped = cv2.remap(np.asarray(image), x, y - bend * np.sin(np.pi * y / 876),
                       cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    gutter = 1 - .32 * np.exp(-((x - 619.5) / 31) ** 2)
    return Image.fromarray((warped * gutter[..., None]).astype("uint8"))


def whiteboard(index):
    image = Image.new("RGB", (1080, 700), "#e6e8e7")
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 1079, 699), outline="#878f91", width=19)
    labels = ("Forces and motion", "F = m a", "Compare the slopes", "Record trial results")
    for row, (label, color) in enumerate(zip(labels, ("#222222", "#233fa0", "#a63331", "#227058"))):
        y = 65 + row * 132
        draw.text((90 + index * row * 12, y), label, fill=color, font=font("segoepr.ttf", 51))
        draw.line([(100, y + 76), (260, y + 79), (450, y + 72)], fill=color, width=5)
    return image


def generate(out):
    directory = out / "special"
    entries = {}
    for group, (category, count, render) in enumerate(
            (("id-cards", 4, card), ("books", 3, spread), ("whiteboards", 3, whiteboard))):
        for index in range(count):
            rng = rng_for(2, group, index)
            size = (1600, 1200)
            texture = ("wood", "laminate", "carpet", "checks")[index]
            source = render(index)
            quad, matrix = placement(source.size, size, rng)
            image = composite(source, background(size, texture, rng), matrix, True)
            effects = dict(drop_shadow=True, large_glare=False)
            if category == "id-cards" and index % 2 == 0:
                image, effects["glare"] = glare(image, quad, rng)
            if category == "whiteboards":
                lighting = np.linspace(.77 + index * .035, 1.035, size[0])
                image *= lighting[None, :, None]
                effects["lighting"] = [float(lighting[0]), float(lighting[-1])]
            image += rng.normal(0, 1.4, size[::-1]).astype(np.float32)[..., None]
            relative = f"{category}/{category}_{index + 1:02}.jpg"
            jpeg(directory / relative, Image.fromarray(np.clip(image, 0, 255).astype("uint8")))
            entries[relative] = record(category, size, source.size, quad, matrix, texture, effects)
            if category == "books":
                midpoint = (source.width - 1) / 2
                axis = np.float32([[[midpoint, 0], [midpoint, source.height - 1]]])
                entries[relative]["gutter"] = cv2.perspectiveTransform(axis, matrix)[0].tolist()
    write_truth(directory, "gen_special", entries)
    contact_sheet(directory, entries)
