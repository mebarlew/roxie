"""Roxie v2: 96x96 anime-style pixel art, drawn from shapes so every pose stays consistent.

Shapes are drawn without anti-aliasing straight onto the pixel grid, then a pass adds the
outer outline. Poses only change the eyes and mouth.
"""

from PIL import Image, ImageDraw

SIZE = 96
CX = 48

# Roxie's look: brown hair, blue eyes, blue cat-ear beanie (Blueberry). Other colours are swaps.
PAL = {
    "outline": (70, 42, 46),
    "hair": (150, 96, 72),
    "hair_light": (196, 136, 102),
    "hair_shade": (118, 72, 58),
    "hair_line": (86, 50, 42),
    "skin": (253, 230, 216),
    "skin_shade": (242, 204, 188),
    "blush": (246, 150, 156),
    "white": (255, 255, 255),
    "sclera_shade": (226, 222, 240),
    "iris_dark": (36, 48, 130),
    "iris": (78, 118, 220),
    "iris_light": (150, 196, 255),
    "lash": (40, 28, 50),
    "mouth": (160, 78, 84),
    "tie": (126, 186, 240),
    "tie_shade": (84, 136, 214),
    "tie_light": (206, 232, 255),
    "hat": (110, 160, 240),
    "hat_shade": (78, 118, 206),
    "hat_light": (170, 205, 255),
    "ear_inner": (255, 176, 204),
}


def C(name):
    return PAL[name] + (255,)


def mirror_pts(pts):
    return [(SIZE - 1 - x, y) for x, y in pts]


def both(d, pts, fill, outline=None):
    d.polygon(pts, fill=fill, outline=outline)
    d.polygon(mirror_pts(pts), fill=fill, outline=outline)


def draw_hair_back(d):
    d.ellipse((17, 9, 78, 70), fill=C("hair"))
    # Side locks falling past the jaw.
    both(d, [(17, 40), (24, 40), (27, 62), (25, 84), (21, 90), (18, 78), (15, 60)], C("hair"))
    both(d, [(19, 48), (22, 48), (23, 70), (20, 84), (18, 70)], C("hair_shade"))


def draw_face(d):
    d.ellipse((25, 26, 70, 74), fill=C("skin"))
    d.polygon([(27, 60), (36, 75), (44, 80), (48, 81), (52, 80), (60, 75), (69, 60)], fill=C("skin"))
    # Soft shade under the bangs and along the jaw.
    d.line([(29, 66), (36, 74), (44, 79)], fill=C("skin_shade"))
    d.line([(67, 66), (60, 74), (52, 79)], fill=C("skin_shade"))


def draw_bangs(d):
    d.chord((19, 10, 77, 60), 180, 360, fill=C("hair"))
    strands = [
        # (top-left x, top-right x, tip x, tip y, colour): tips stop above the eyes,
        # except the long outer locks and the one strand between the eyes.
        (23, 31, 24, 62, "hair"),
        (26, 38, 30, 47, "hair_light"),
        (34, 46, 39, 48, "hair"),
        (43, 53, 48, 55, "hair_light"),
        (50, 62, 57, 48, "hair"),
        (58, 70, 66, 47, "hair_light"),
        (65, 73, 72, 62, "hair"),
    ]
    for left, right, tip_x, tip_y, colour in strands:
        d.polygon([(left, 18), (right, 18), (tip_x, tip_y)], fill=C(colour))
    for left, right, tip_x, tip_y, _ in strands:
        # Strand edges only below the crown, so there is no hard line across the top.
        for x0 in (left, right):
            t = (32 - 18) / (tip_y - 18)
            d.line([(round(x0 + (tip_x - x0) * t), 32), (tip_x, tip_y)], fill=C("hair_line"))
    d.arc((28, 14, 68, 40), 200, 340, fill=C("hair_light"), width=2)
    for x0, x1 in ((34, 37), (46, 48), (58, 60)):
        d.line([(x0, 18), (x1, 27)], fill=C("hair_shade"))


def draw_eye(img, cx, cy, flip, state):
    """One eye centred at (cx, cy). flip mirrors the outer corner to the right."""
    d = ImageDraw.Draw(img)
    s = -1 if flip else 1
    if state in ("closed", "happy", "sleepy"):
        if state == "happy":
            d.arc((cx - 7, cy - 4, cx + 7, cy + 6), 200, 340, fill=C("lash"), width=2)
        elif state == "sleepy":
            d.arc((cx - 7, cy - 8, cx + 7, cy + 2), 20, 160, fill=C("lash"), width=2)
        else:
            d.line([(cx - 7, cy + 1), (cx + 7, cy + 1)], fill=C("lash"), width=2)
        d.line([(cx - 8 * s, cy), (cx - 10 * s, cy - 2)], fill=C("lash"))
        return
    # Sclera and iris on their own layer, clipped to the eye shape.
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    e = ImageDraw.Draw(layer)
    eye_box = (cx - 7, cy - 7, cx + 7, cy + 8)
    e.ellipse(eye_box, fill=C("white"))
    iy = cy - 2 if state == "up" else cy
    iris_box = (cx - 5, iy - 8, cx + 5, iy + 8)
    e.ellipse(iris_box, fill=C("iris"))
    e.chord(iris_box, 200, 340, fill=C("iris_dark"))
    e.chord((cx - 4, iy, cx + 4, iy + 8), 15, 165, fill=C("iris_light"))
    e.rectangle((cx - 1, iy - 2, cx, iy + 1), fill=C("iris_dark"))
    hx = cx - 2 * s
    e.rectangle((min(hx, hx - 2 * s), iy - 5, max(hx, hx - 2 * s), iy - 2), fill=C("white"))
    lx = cx + 2 * s
    e.rectangle((min(lx, lx + s), iy + 4, max(lx, lx + s), iy + 4), fill=C("white"))
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse(eye_box, fill=255)
    img.paste(layer, (0, 0), mask)
    # Thick upper lash with a wing at the outer corner, a short soft lower lid.
    d.arc((cx - 9, cy - 8, cx + 9, cy + 10), 195, 345, fill=C("lash"), width=3)
    outer = cx - 9 * s
    d.line([(outer, cy - 2), (outer - 3 * s, cy - 5)], fill=C("lash"), width=2)
    d.line([(cx - 2, cy + 9), (cx + 2, cy + 9)], fill=C("iris_dark"))


def draw_mouth(d, state):
    if state == "talk":
        d.ellipse((45, 68, 51, 74), fill=C("mouth"))
        d.line([(47, 73), (49, 73)], fill=C("blush"))
    elif state == "open_smile":
        d.chord((43, 66, 53, 75), 0, 180, fill=C("mouth"))
        d.line([(46, 73), (50, 73)], fill=C("blush"))
    elif state == "o":
        d.ellipse((46, 69, 50, 73), fill=C("mouth"))
    elif state == "flat":
        d.line([(46, 71), (50, 71)], fill=C("mouth"))
    else:
        d.arc((43, 64, 53, 72), 30, 150, fill=C("mouth"))


def draw_cheeks(d):
    for x in (31, 57):
        for i in range(3):
            d.line([(x + i * 3, 71), (x + i * 3 + 2, 69)], fill=C("blush"))
    d.point((CX, 64), fill=C("skin_shade"))
    d.point((CX + 1, 64), fill=C("skin_shade"))


def draw_ties(d):
    for cx in (20, 75):
        for cy in (44, 49, 54):
            d.ellipse((cx - 2, cy - 2, cx + 2, cy + 2), fill=C("tie"), outline=C("tie_shade"))
            d.point((cx - 1, cy - 1), fill=C("tie_light"))


def draw_hat(d):
    # Beanie with cat ears, sitting on top of the bangs.
    both(d, [(23, 20), (24, 2), (37, 12)], C("hat"), C("outline"))
    both(d, [(26, 17), (26, 8), (33, 13)], C("ear_inner"))
    d.chord((21, 8, 75, 44), 180, 360, fill=C("hat"), outline=C("outline"))
    d.rectangle((20, 24, 76, 30), fill=C("hat_light"), outline=C("outline"))
    for x in range(23, 75, 3):
        d.line([(x, 25), (x, 29)], fill=C("hat"))
    d.arc((28, 11, 68, 34), 210, 330, fill=C("hat_light"), width=2)


EYES = {"idle": "open", "blink": "closed", "talking": "open", "listening": "open",
        "thinking": "up", "sleepy": "sleepy", "happy": "happy"}
MOUTHS = {"idle": "smile", "blink": "smile", "talking": "talk", "listening": "o",
          "thinking": "flat", "sleepy": "flat", "happy": "open_smile"}


def outline(img):
    """Add a 1px outline around everything opaque."""
    px = img.load()
    edge = []
    for y in range(SIZE):
        for x in range(SIZE):
            if px[x, y][3]:
                continue
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < SIZE and 0 <= ny < SIZE and px[nx, ny][3]:
                    edge.append((x, y))
                    break
    for x, y in edge:
        px[x, y] = C("outline")
    return img


def recolor(img, variant):
    """Swap the accent colours exactly like the app does at runtime."""
    import sprite as base

    v = base.VARIANTS[variant]
    darken = tuple(round(c * 0.45) for c in v["iris"])
    swap = {PAL[k]: v[k] for k in ("hat", "hat_shade", "hat_light", "ear_inner", "iris", "iris_light")}
    swap[PAL["iris_dark"]] = darken
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = px[x, y]
            if a and (r, g, b) in swap:
                px[x, y] = swap[(r, g, b)] + (a,)
    return img


def roxie(pose="idle", hat=True, variant="Blueberry"):
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_hair_back(d)
    draw_face(d)
    draw_cheeks(d)
    eye = EYES[pose]
    draw_eye(img, 37, 58, False, eye)
    draw_eye(img, 59, 58, True, eye)
    draw_mouth(d, MOUTHS[pose])
    draw_bangs(d)
    if hat:
        draw_hat(d)
    else:
        draw_ties(d)
    img = outline(img)
    return img if variant == "Blueberry" else recolor(img, variant)


def sheet(path):
    """Presentation sheet: every pose, the candy colours, and old vs new at bubble size."""
    import sprite as base

    scale, cw, ch, gap = 3, 300, 350, 22
    poses = list(EYES)
    width = len(poses) * cw + (len(poses) + 1) * gap
    height = 150 + 2 * ch + 30 + 40
    img = base.gradient(width, height, (255, 228, 240), (214, 228, 255)).convert("RGBA")
    d = ImageDraw.Draw(img)
    title = base.ImageFont.truetype(base.PIXEL_FONT, 64)
    label = base.ImageFont.truetype(base.FONT_DIR + "LiberationSans-Bold.ttf", 26)
    small = base.ImageFont.truetype(base.FONT_DIR + "LiberationSans-Regular.ttf", 22)
    base.centered_text(d, width / 2, 24, "Roxie, anime pixel style", title)
    base.centered_text(d, width / 2, 98, "same Roxie: brown hair, blue eyes, cat-ear beanie · 96 x 96",
                       small, (120, 100, 160))

    def tile(x, y, pose, variant, caption):
        base.card(img, (x, y, x + cw, y + ch))
        spr = roxie(pose, variant=variant).resize((SIZE * scale, SIZE * scale), Image.NEAREST)
        img.alpha_composite(spr, (x + (cw - spr.width) // 2, y + 6))
        base.centered_text(ImageDraw.Draw(img), x + cw / 2, y + ch - 46, caption, label)

    y = 150
    for i, p in enumerate(poses):
        tile(gap + i * (cw + gap), y, p, "Blueberry", p)
    y += ch + 30
    variants = list(base.VARIANTS)
    for i, v in enumerate(variants):
        tile(gap + i * (cw + gap), y, "idle", v, v + (" (default)" if i == 0 else ""))

    # Old vs new, side by side in the two empty slots of the colour row.
    x0 = gap + len(variants) * (cw + gap)
    base.card(img, (x0, y, x0 + 2 * cw + gap, y + ch))
    for j, (face, caption) in enumerate(((base.sprite("idle").resize((160, 160), Image.NEAREST), "old"),
                                         (roxie("idle").resize((192, 192), Image.NEAREST), "new"))):
        bx, by, disc = x0 + 50 + j * 300, y + 50, 210
        ImageDraw.Draw(img).ellipse((bx, by, bx + disc, by + disc), fill=(255, 255, 255, 255),
                                    outline=(200, 190, 235), width=4)
        img.alpha_composite(face, (bx + (disc - face.width) // 2, by + (disc - face.height) // 2 + 8))
        base.centered_text(ImageDraw.Draw(img), bx + disc / 2, by + disc + 20, caption, label)
    img.convert("RGB").save(path)


if __name__ == "__main__":
    sheet("/home/claw/assistant/outbox/roxie-v3-sheet.png")
