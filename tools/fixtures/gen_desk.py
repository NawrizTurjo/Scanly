import cv2
import numpy as np
from PIL import Image, ImageDraw

from common import (background, composite, contact_sheet, font, gaussian, page_image,
                    pixel_corners, record, rng_for, write_truth)

CATEGORIES = ("desk-sheet", "white-on-light", "notebook-spread", "notebook-left",
              "notebook-right", "clipped-sheet", "occluded", "dim-desk")
SAMPLES = 7


def rounded_mask(size, radius, inset=0):
    w, h = size
    mask = Image.new("L", size)
    ImageDraw.Draw(mask).rounded_rectangle(
        (inset, inset, w - 1 - inset, h - 1 - inset), radius=radius, fill=255)
    return mask


def prop(scene, image, box, angle, shadow=True):
    w, h = scene.shape[1::-1]
    x, y, width, height = box
    offset = (pixel_corners(image.size) / (np.array(image.size) - 1) - .5) * [width, height]
    rotation = cv2.getRotationMatrix2D((0, 0), angle, 1)[:, :2]
    quad = offset @ rotation.T + [x, y]
    matrix = cv2.getPerspectiveTransform(pixel_corners(image.size), quad.astype(np.float32))
    rgba = np.asarray(image)
    alpha = rgba[..., 3].astype(np.float32) / 255
    matte = cv2.warpPerspective(alpha, matrix, (w, h))
    rgb = cv2.warpPerspective(rgba[..., :3] * alpha[..., None], matrix, (w, h))
    if shadow:
        shade = cv2.warpAffine(matte, np.float32([[1, 0, 7], [0, 1, 11]]), (w, h))
        # Blur on a smaller grid so wide soft shadows stay inexpensive.
        shade = cv2.resize(cv2.GaussianBlur(cv2.resize(shade, (w // 4, h // 4)), (0, 0), 3),
                           (w, h))
        scene *= 1 - .32 * shade[..., None]
    scene *= 1 - matte[..., None]
    scene += rgb


def textile(size, kind, rng):
    w, h = size
    y, x = np.ogrid[:h, :w]
    light = cv2.resize(rng.normal(0, 1, (7, 8)).astype(np.float32), size,
                       interpolation=cv2.INTER_CUBIC)
    weave = 2 * (np.cos(x * 1.7) + np.cos(y * 1.8))
    if kind == "floral":
        cloth = Image.new("RGB", size, (178, 164, 151))
        draw = ImageDraw.Draw(cloth)
        for cy in range(-60, h + 140, 215):
            for cx in range(-60, w + 140, 245):
                cx, cy_offset = np.array([cx, cy]) + rng.integers(-52, 53, 2)
                draw.line((cx - 40, cy_offset + 80, cx, cy_offset), fill=(112, 123, 99), width=6)
                draw.ellipse((cx - 52, cy_offset + 36, cx - 17, cy_offset + 61), fill=(127, 136, 112))
                for angle in np.linspace(0, 2 * np.pi, 6, endpoint=False):
                    px, py = cx + 24 * np.cos(angle), cy_offset + 24 * np.sin(angle)
                    draw.ellipse((px - 16, py - 19, px + 16, py + 19), fill=(158, 115, 116))
                draw.ellipse((cx - 10, cy_offset - 10, cx + 10, cy_offset + 10), fill=(175, 141, 116))
        base = cv2.GaussianBlur(np.asarray(cloth), (0, 0), 3)
    elif kind == "checks":
        u = x + .15 * y + 17 * np.sin(y / 82)
        v = y - .13 * x + 12 * np.sin(x / 128)
        weave += 21 * ((u // 17 + v // 17) % 2) + 13 * ((u // 51 + v // 51) % 2)
        weave += 24 * np.sin((x + .23 * y) / 67 + np.sin(y / 74))
        base = (53, 47, 44)
    else:
        base = (53, 56, 59)
    return np.clip(base + (5 * light + weave)[..., None], 0, 255).astype(np.uint8)


def laptop():
    size = (640, 270)
    y, x = np.ogrid[:270, :640]
    sheen = 27 * np.exp(-((x - 90) / 340) ** 2) + 18 * y / 270
    rgb = np.clip(np.array([154, 158, 164]) + sheen[..., None], 0, 255).astype(np.uint8)
    lid = Image.fromarray(rgb).convert("RGBA")
    draw = ImageDraw.Draw(lid)
    draw.rounded_rectangle((2, 2, 637, 267), radius=18, outline=(217, 220, 225), width=2)
    draw.line((74, 251, 564, 251), fill=(96, 100, 108), width=3)
    draw.line((74, 255, 564, 255), fill=(223, 225, 228), width=2)
    draw.line((21, 265, 618, 265), fill=(123, 126, 130), width=3)
    lid.putalpha(rounded_mask(size, 18))
    return lid


def writing_tool(pencil, index):
    image = Image.new("RGBA", (340, 25))
    draw = ImageDraw.Draw(image)
    color = ((37, 65, 128), (75, 72, 85), (34, 100, 93))[index % 3]
    if pencil:
        draw.polygon([(18, 5), (306, 5), (337, 12), (306, 20), (18, 20)], fill=(206, 159, 51))
        draw.polygon([(306, 5), (337, 12), (306, 20)], fill=(212, 178, 137))
        draw.polygon([(327, 10), (339, 12), (327, 15)], fill=(58, 55, 48))
        draw.rounded_rectangle((0, 5, 28, 20), radius=5, fill=(162, 104, 106))
        draw.rectangle((21, 5, 32, 20), fill=(168, 170, 171))
        draw.line((34, 8, 304, 8), fill=(239, 197, 86), width=2)
    else:
        draw.rounded_rectangle((1, 3, 316, 22), radius=9, fill=color)
        draw.line((22, 8, 295, 8), fill=tuple(c + 55 for c in color), width=2)
        draw.line((8, 4, 80, 4), fill=(168, 179, 192), width=3)
        draw.polygon([(311, 5), (339, 12), (311, 20)], fill=(54, 56, 63))
    return image


def desk(size, category, index, rng):
    w, h = size
    light = category == "white-on-light"
    texture = "white" if light else ("wood", "floral", "laminate", "wood", "floral",
                                     "laminate", "wood")[index]
    if texture == "floral":
        scene = textile(size, texture, rng).astype(np.float32)
    else:
        scene = background(size, texture, rng).astype(np.float32)
    if not light:
        lap = Image.fromarray(textile((720, 260), "checks", rng)).convert("RGBA")
        mask = Image.new("L", lap.size)
        draw = ImageDraw.Draw(mask)
        draw.ellipse((-55, 4, 352, 360), fill=255)
        draw.ellipse((341, 0, 762, 365), fill=255)
        lap.putalpha(mask)
        prop(scene, lap, (.53 * w, 1.06 * h, .94 * w, .40 * h), -7)
        mat_size = (640, 540)
        mat = Image.new("RGBA", mat_size, ((139, 48, 45), (73, 110, 105))[index % 2])
        inner = rounded_mask(mat_size, 18, 8)
        mat.paste(Image.fromarray(textile(mat_size, "mat", rng)), (0, 0), inner)
        mat.putalpha(rounded_mask(mat_size, 23))
        prop(scene, mat, (.51 * w, .52 * h, 1.04 * w, .68 * h), rng.uniform(-9, 7))
    side = -1 if index % 2 else 1
    extra = page_image("table" if index % 3 == 0 else "print", index)
    prop(scene, extra.convert("RGBA"), (w * (.5 + side * .44), .46 * h, .39 * w, .52 * h),
         rng.uniform(-24, 22))
    prop(scene, laptop(), (w * (.5 + side * .21), rng.uniform(.025, .13) * h, .74 * w, .26 * h),
         rng.uniform(-9, 9))
    for pen in range(2):
        prop(scene, writing_tool(pen == 1, index),
             (w * (.5 - side * (.28 + .07 * pen)), h * (.25 + .055 * pen),
              .30 * w, .016 * w), side * (22 + 13 * pen) + rng.uniform(-10, 10))
    case = Image.new("RGBA", (160, 124))
    draw = ImageDraw.Draw(case)
    draw.rounded_rectangle((1, 1, 158, 122), radius=46, fill=(34, 37, 42))
    draw.rounded_rectangle((7, 5, 149, 105), radius=37, fill=(49, 53, 60))
    draw.arc((16, 10, 135, 83), 205, 327, fill=(84, 90, 99), width=4)
    draw.line((15, 67, 145, 72), fill=(24, 27, 32), width=3)
    draw.ellipse((77, 79, 83, 84), fill=(105, 151, 128))
    prop(scene, case, (w * (.5 - side * .40), .22 * h, .145 * w, .11 * h), side * 18)
    mug = Image.new("RGBA", (190, 158))
    draw = ImageDraw.Draw(mug)
    draw.ellipse((125, 49, 186, 113), outline=(189, 183, 174), width=15)
    draw.ellipse((3, 12, 159, 155), fill=(158, 153, 146))
    draw.ellipse((3, 1, 158, 141), fill=(226, 217, 204))
    draw.ellipse((15, 12, 147, 130), fill=(113, 79, 58))
    draw.ellipse((20, 22, 142, 128), fill=(66, 42, 32))
    draw.arc((23, 24, 139, 125), 42, 145, fill=(150, 104, 71), width=3)
    prop(scene, mug, (w * (.5 + side * .46), (.76 if index % 3 else .30) * h,
                      .20 * w, .14 * h), rng.uniform(-35, 35))
    return scene, texture


def notebook_page(index, rng):
    w, h = 620, 877
    page = Image.new("RGB", (w, h), (239, 238, 229) if index % 2 else (248, 246, 240))
    draw = ImageDraw.Draw(page)
    for y in range(76, h - 23, 26):
        draw.line((0, y, w, y), fill=(185, 204, 219), width=1)
    draw.line((63, 0, 63, h), fill=(211, 145, 151), width=2)
    draw.line((0, 62, w, 62), fill=(211, 145, 151), width=1)
    draw.text((78, 27), ("Workshop notes", "Trial calculations", "Observations")[index % 3],
              font=font("Inkfree.ttf", 25), fill=(57, 65, 91))
    ink = ((41, 58, 106), (61, 59, 56))[index % 2]
    face = font("Inkfree.ttf" if index % 3 else "segoepr.ttf", 20)
    fragments = ("slope of the measured curve", "use the mean of both trials",
                 "t = 0.25 s     x = 1.82 cm", "change in momentum",
                 "estimate from the graph", "check units before comparing",
                 "Δv = 2.1 − 1.4", "a = (v − u) / t")
    for y in range(100, int(rng.integers(620, 835)), 26):
        if index % 3 == 1 and 325 <= y < 495:
            continue
        if rng.random() < .18:
            continue
        x = int(rng.integers(76, 125))
        if rng.random() < .76:
            phrase = rng.choice(fragments)
            draw.text((x, y - 21 + int(rng.integers(-3, 4))), phrase, font=face, fill=ink)
            x += int(draw.textlength(phrase, font=face)) + 18
        for _ in range(int(rng.integers(4, 13))):
            length = int(rng.integers(17, 55))
            if x + length > w - 28:
                break
            points = [(x + t, y + float(rng.uniform(-11, 2))) for t in range(0, length, 4)]
            draw.line(points, fill=ink, width=int(rng.integers(1, 3)), joint="curve")
            x += length + int(rng.integers(6, 17))
    if index % 3 == 1:
        draw.line([(149, 342), (147, 474), (342, 477)], fill=ink, width=2)
        draw.line([(158, 453), (197, 421), (218, 413), (259, 387), (325, 361)],
                  fill=ink, width=2)
        for x, y in ((175, 441), (215, 426), (249, 392), (291, 381), (315, 358)):
            draw.ellipse((x - 2, y - 2, x + 2, y + 2), fill=ink)
        draw.text((355, 405), "trial 2", font=face, fill=ink)
    return np.asarray(page)


def notebook(index, rng):
    pages = np.hstack([notebook_page(2 * index + side, rng) for side in range(2)])
    h, w = pages.shape[:2]
    y, x = np.indices((h, w), dtype=np.float32)
    fold = 1 - .27 * np.exp(-((x - (w - 1) / 2) / 22) ** 2)
    # The gutter endpoints stay on the corner chords while each page arches outward.
    bow = np.sin(np.pi * (x % (w / 2)) / (w / 2 - 1)) * rng.uniform(6, 12)
    cover = ((57, 86, 81), (112, 56, 54), (49, 63, 94))[index % 3]
    pad = 15
    paper = np.clip(pages * fold[..., None], 0, 255).astype(np.uint8)
    paper = np.pad(paper, ((pad, pad), (0, 0), (0, 0)), mode="edge")
    py, px = np.indices(paper.shape[:2], dtype=np.float32)
    mapped_y = (py - pad + bow[0]) * (h - 1) / (h - 1 + 2 * bow[0]) + pad
    warped = cv2.remap(paper, px, mapped_y, cv2.INTER_LINEAR)
    mask = (mapped_y >= pad) & (mapped_y <= h - 1 + pad)
    cover_size = (w + 2 * pad, h + 2 * pad)
    book = Image.new("RGBA", cover_size, cover)
    book.putalpha(rounded_mask(cover_size, 11))
    rgba = np.array(book)
    rgba[:, pad:pad + w, :3][mask] = warped[mask]
    return Image.fromarray(rgba), (w, h), pad


def placement(size, source, category, rng):
    w, h = size
    cropped_book = category in ("notebook-left", "notebook-right")
    clipped = category == "clipped-sheet"
    for _ in range(300):
        aspect = source[0] / source[1]
        if cropped_book:
            aspect /= 2
        height = min(h * rng.uniform(.61, .85), w * rng.uniform(.76, .94) / aspect)
        if cropped_book or clipped:
            height = min(height, w * .69 / aspect)
        corners = (pixel_corners((2, 2)) - .5) * [height * aspect, height]
        corners += rng.uniform(-.06, .06, (4, 2)) * height
        rotation = cv2.getRotationMatrix2D((0, 0), rng.uniform(-15, 15), 1)[:, :2]
        quad = (corners @ rotation.T + [w * rng.uniform(.43, .57), h * rng.uniform(.49, .62)])
        if cropped_book:
            quad[:, 0] += w * (.18 if category == "notebook-left" else -.18)
        if clipped:
            quad[:, 0] += -.09 * w - quad[:, 0].min()
            if quad[[0, 3], 0].max() >= 0 or quad[[1, 2], 0].min() <= 0:
                continue
        target_size = (source[0] // 2, source[1]) if cropped_book else source
        src = pixel_corners(target_size)
        if category == "notebook-left":
            src[[1, 2], 0] = (source[0] - 1) / 2
        elif category == "notebook-right":
            src[[0, 3], 0] = (source[0] - 1) / 2
            src[[1, 2], 0] = source[0] - 1
        matrix = cv2.getPerspectiveTransform(src, quad.astype(np.float32))
        if cropped_book:
            outside = cv2.perspectiveTransform(pixel_corners(source)[None], matrix)[0]
            far = outside[[1, 2], 0] if category == "notebook-left" else outside[[0, 3], 0]
            if not (np.all(far > w - 1) if category == "notebook-left" else np.all(far < 0)):
                continue
        if clipped:
            for left, right in ((0, 1), (3, 2)):
                quad[left] += (quad[right] - quad[left]) * (-quad[left, 0] /
                                                            (quad[right, 0] - quad[left, 0]))
                quad[left, 0] = 0
        quad = quad.astype(np.float32)
        if (np.all(quad >= 0) and np.all(quad <= np.array(size) - 1)
                and cv2.isContourConvex(quad)
                and .25 <= cv2.contourArea(quad) / (w * h) <= .80):
            return quad, matrix, target_size
    raise ValueError(f"No valid {category} placement for {size}")


def render(size, category, index, rng):
    w, h = size
    scene, texture = desk(size, category, index, rng)
    book = category.startswith("notebook-")
    if book:
        page, source, pad = notebook(index, rng)
    else:
        page = page_image(("print", "hand", "table")[index % 3], index + 30)
        source = page.size
    quad, matrix, target_size = placement(size, source, category, rng)
    if book:
        book_matrix = matrix @ np.array([[1, 0, -pad], [0, 1, -pad], [0, 0, 1]])
        outer = cv2.perspectiveTransform(pixel_corners(page.size)[None], book_matrix)[0]
        # The cover and bowed paper share one perspective transform.
        rgba = np.asarray(page)
        alpha = cv2.warpPerspective(rgba[..., 3], book_matrix, size) / np.float32(255)
        shadow = cv2.warpAffine(alpha, np.float32([[1, 0, 8], [0, 1, 13]]), size)
        scene *= 1 - .30 * cv2.GaussianBlur(shadow, (0, 0), 8)[..., None]
        rgb = rgba[..., :3] * (rgba[..., 3:4] / np.float32(255))
        scene *= 1 - alpha[..., None]
        scene += cv2.warpPerspective(rgb, book_matrix, size)
    else:
        scene = composite(page, scene, matrix, drop_shadow=category != "white-on-light")

    effects = dict(drop_shadow=category != "white-on-light", jpeg_quality=88)
    if book:
        effects["cover_corners"] = np.round(outer.astype(float), 2).tolist()
    if category == "occluded":
        x, y = quad[index % 4]
        center = quad.mean(axis=0)
        x, y = .93 * np.array([x, y]) + .07 * center
        if index % 2:
            prop(scene, writing_tool(False, index), (x, y, .36 * w, .022 * w), -41 + 12 * index)
            effects["occluder"] = "pen"
        else:
            thumb = Image.new("RGBA", (240, 97))
            draw = ImageDraw.Draw(thumb)
            skin = ((153, 99, 76), (197, 137, 106))[index % 3 == 0]
            draw.rounded_rectangle((0, 2, 237, 94), radius=44, fill=skin)
            draw.rounded_rectangle((173, 19, 228, 76), radius=21,
                                   fill=tuple(c + 21 for c in skin))
            draw.arc((83, 10, 126, 89), 273, 83, fill=tuple(c - 17 for c in skin), width=2)
            dx, dy = center - [x, y]
            length = .26 * w
            angle = np.degrees(np.arctan2(-dy, dx))
            direction = np.array([dx, dy]) / np.hypot(dx, dy)
            tx, ty = np.array([x, y]) - length * .31 * direction
            prop(scene, thumb, (tx, ty, length, .091 * w), angle)
            effects["occluder"] = "thumb"
    dim = category == "dim-desk"
    exposure = float(rng.uniform(.38, .63) if dim else rng.uniform(.88, 1.02))
    gains = [1.14, .97, .77] if dim else ([1.04, 1, .94] if index % 2 else [.96, 1, 1.04])
    light = gaussian(size, rng.uniform(0, w), rng.uniform(0, .6 * h), .80 * w, .87 * h)
    vignette = gaussian(size, w / 2, h / 2, .80 * w, .83 * h)
    shadow_x, shadow_y = quad[index % 4] * .64 + quad.mean(axis=0) * .36
    depth = float(rng.uniform(.10, .28))
    shade = gaussian(size, shadow_x, shadow_y, .16 * w, .24 * h)
    scene *= (exposure * (.73 + .27 * light) * (.64 + .36 * vignette) * (1 - depth * shade))[..., None]
    scene *= gains
    noise = 7 if dim else 2.2
    blur = .78 if dim else .59
    scene += rng.normal(0, noise, (h, w)).astype(np.float32)[..., None]
    scene = cv2.GaussianBlur(scene, (3, 3), blur)
    effects.update(exposure=exposure, white_balance=gains, noise_sigma=noise,
                   blur_sigma=blur, shadow_depth=depth)
    image = Image.fromarray(np.clip(scene, 0, 255).astype(np.uint8))
    # Keep the visible clipped outline in truth and fit its homography to that outline.
    if category in ("clipped-sheet", "notebook-left", "notebook-right"):
        matrix = cv2.getPerspectiveTransform(pixel_corners(target_size), quad)
    entry = record(category, size, target_size, quad, matrix, texture, effects)
    entry["alt_corners"] = []
    return image, entry


def generate(out, categories=CATEGORIES, samples=SAMPLES):
    directory = out / "scanner" / "desk"
    entries = {}
    for category in categories:
        for index in range(samples):
            rng = rng_for(4, CATEGORIES.index(category), index)
            size = (2000, 1500) if index == 6 else (1500, 2000)
            image, entry = render(size, category, index, rng)
            name = f"{category}/{category}_{index + 1:02}.jpg"
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            image.save(path, quality=88, subsampling=2, optimize=True)
            entries[name] = entry
    write_truth(directory, "gen_desk", entries)
    contact_sheet(directory, entries)
