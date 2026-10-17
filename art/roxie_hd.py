"""Roxie: 96x96 anime pixel art, head and shoulders, drawn from shapes so every pose stays consistent.

Grown-up glam look: slim face, curtain bangs, winged lashes, glossy lips, choker and an
off-shoulder top. Beanie, choker, top and eyes take the user's candy colour. Poses only
change the eyes and mouth.
"""

from PIL import Image, ImageDraw

import sprite as base

SIZE = 96
CX = 48

PAL = {
    "outline": (70, 42, 46),
    "hair": (124, 74, 58),
    "hair_light": (172, 112, 84),
    "hair_shine": (214, 160, 124),
    "hair_shade": (90, 52, 46),
    "skin": (252, 228, 212),
    "skin_shade": (236, 194, 178),
    "blush": (248, 178, 178),
    "white": (255, 255, 255),
    "lash": (44, 26, 40),
    "lip": (208, 78, 106),
    "lip_light": (244, 148, 168),
    "mouth": (118, 40, 60),
}

EYES = {"idle": "open", "blink": "closed", "talking": "open", "listening": "open",
        "thinking": "up", "sleepy": "sleepy", "happy": "happy"}
MOUTHS = {"idle": "smile", "blink": "smile", "talking": "talk", "listening": "o",
          "thinking": "flat", "sleepy": "flat", "happy": "open_smile"}


def C(name):
    return PAL[name]


def mirror_pts(pts):
    return [(SIZE - 1 - x, y) for x, y in pts]


def both(d, pts, fill, outline=None):
    d.polygon(pts, fill=fill, outline=outline)
    d.polygon(mirror_pts(pts), fill=fill, outline=outline)


def draw_back(d, v):
    d.ellipse((18, 12, 78, 74), fill=C("hair"))
    d.rectangle((28, 56, 68, 86), fill=C("hair_shade"))
    both(d, [(18, 44), (31, 44), (32, 70), (29, 95), (12, 95), (11, 70)], C("hair"))
    both(d, [(16, 62), (19, 62), (19, 92), (15, 92)], C("hair_shade"))
    # Bare shoulders, an off-shoulder top in the user's colour.
    d.polygon([(2, 95), (10, 86), (30, 80), (66, 80), (86, 86), (94, 95)], fill=C("skin"))
    d.polygon([(0, 95), (4, 89), (28, 91), (48, 92), (68, 91), (92, 89), (95, 95)], fill=v["outfit"])
    d.line([(5, 89), (28, 91), (48, 92), (68, 91), (91, 89)], fill=v["hat_light"])
    d.rectangle((42, 62, 54, 82), fill=C("skin"))
    d.polygon([(42, 68), (54, 68), (54, 73), (48, 76), (42, 73)], fill=C("skin_shade"))
    d.rectangle((42, 75, 54, 77), fill=v["hat"])
    d.rectangle((47, 78, 49, 79), fill=v["hat_light"])


def draw_face(d):
    d.ellipse((27, 25, 69, 65), fill=C("skin"))
    d.polygon([(28, 50), (35, 63), (43, 70), (48, 72), (53, 70), (61, 63), (68, 50)], fill=C("skin"))
    d.line([(67, 54), (60, 64), (53, 70)], fill=C("skin_shade"))
    d.line([(49, 60), (48, 62)], fill=C("skin_shade"))
    for x in (31, 57):
        d.ellipse((x, 59, x + 8, 62), fill=C("blush"))


def draw_brows(d):
    for cx in (38, 58):
        d.arc((cx - 7, 38, cx + 7, 46), 205, 335, fill=C("hair_shade"))


def draw_eye(img, cx, cy, flip, state, v):
    """Almond eye with a winged upper lash. flip puts the outer corner on the right."""
    d = ImageDraw.Draw(img)
    s = -1 if flip else 1
    if state in ("closed", "sleepy", "happy"):
        if state == "happy":
            d.arc((cx - 6, cy - 3, cx + 6, cy + 5), 200, 340, fill=C("lash"), width=2)
        else:
            d.arc((cx - 7, cy - 6, cx + 7, cy + 4), 20, 160, fill=C("lash"), width=2)
        d.line([(cx - 7 * s, cy - 1), (cx - 10 * s, cy - 3)], fill=C("lash"), width=2)
        return
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    e = ImageDraw.Draw(layer)
    eye_box = (cx - 7, cy - 4, cx + 7, cy + 5)
    e.ellipse(eye_box, fill=C("white"))
    iy = cy - 2 if state == "up" else cy
    iris_box = (cx - 4, iy - 5, cx + 4, iy + 5)
    dark = tuple(round(c * 0.45) for c in v["iris"])
    e.ellipse(iris_box, fill=v["iris"])
    e.chord(iris_box, 200, 340, fill=dark)
    e.chord((cx - 3, iy, cx + 3, iy + 5), 15, 165, fill=v["iris_light"])
    e.rectangle((cx - 1, iy - 1, cx, iy + 1), fill=dark)
    e.rectangle((cx - 2 * s - (s < 0), iy - 3, cx - 2 * s - (s < 0) + 1, iy - 2), fill=C("white"))
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse(eye_box, fill=255)
    img.paste(layer, (0, 0), mask)
    d.arc((cx - 8, cy - 5, cx + 8, cy + 7), 195, 345, fill=C("lash"), width=3)
    d.line([(cx - 8 * s, cy - 1), (cx - 11 * s, cy - 4)], fill=C("lash"), width=2)
    d.line([(cx - 7 * s, cy + 3), (cx - 5 * s, cy + 4)], fill=C("lash"))


def draw_mouth(d, state):
    if state == "talk":
        d.ellipse((45, 63, 51, 69), fill=C("mouth"), outline=C("lip"))
    elif state == "open_smile":
        d.chord((44, 62, 52, 70), 0, 180, fill=C("mouth"), outline=C("lip"))
        d.line([(46, 66), (50, 66)], fill=C("white"))
    elif state == "o":
        d.ellipse((46, 64, 50, 68), fill=C("mouth"), outline=C("lip"))
    elif state == "flat":
        d.line([(46, 66), (50, 66)], fill=C("lip"), width=2)
    else:
        d.chord((45, 63, 51, 69), 0, 180, fill=C("lip"))
        d.line([(45, 65), (51, 65)], fill=C("lip"))
        d.line([(46, 66), (50, 66)], fill=C("mouth"))
        d.point((44, 65), fill=C("mouth"))
        d.point((52, 65), fill=C("mouth"))
        d.point((49, 67), fill=C("lip_light"))


def draw_bangs(d):
    d.chord((22, 12, 74, 52), 180, 360, fill=C("hair"))
    swoop = [(48, 31), (24, 31), (22, 46), (24, 60), (28, 68), (31, 60), (33, 50), (37, 42), (43, 36)]
    both(d, swoop, C("hair"))
    for pts in ([(42, 35), (36, 40), (32, 50)], [(53, 35), (59, 40), (63, 50)]):
        d.line(pts, fill=C("hair_light"))
    lock = [(27, 58), (31, 60), (32, 76), (29, 92), (24, 95), (25, 80)]
    both(d, lock, C("hair"))
    both(d, [(28, 66), (29, 66), (29, 84), (27, 88)], C("hair_light"))


def draw_hat(d, v):
    both(d, [(24, 22), (25, 3), (38, 13)], v["hat"], C("outline"))
    both(d, [(27, 19), (27, 9), (34, 14)], v["ear_inner"])
    d.chord((21, 6, 75, 46), 180, 360, fill=v["hat"], outline=C("outline"))
    d.arc((28, 10, 68, 32), 210, 330, fill=v["hat_light"], width=2)
    d.rectangle((20, 22, 76, 29), fill=v["hat_light"], outline=C("outline"))
    for x in range(23, 75, 3):
        d.line([(x, 23), (x, 28)], fill=v["hat"])


def outline(img):
    """Add a 1px outline around everything opaque."""
    px = img.load()
    w, h = img.size
    edge = []
    for y in range(h):
        for x in range(w):
            if px[x, y][3]:
                continue
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < w and 0 <= ny < h and px[nx, ny][3]:
                    edge.append((x, y))
                    break
    for x, y in edge:
        px[x, y] = C("outline") + (255,)
    return img


def roxie(pose="idle", variant="Blueberry"):
    v = base.VARIANTS[variant]
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_back(d, v)
    draw_face(d)
    draw_brows(d)
    left, right = EYES[pose], EYES[pose]
    if pose == "happy":
        left = "open"  # a wink
    draw_eye(img, 38, 52, False, left, v)
    draw_eye(img, 58, 52, True, right, v)
    draw_mouth(d, MOUTHS[pose])
    draw_bangs(d)
    draw_hat(d, v)
    return outline(img)


def sheet(path):
    """Roxie on her own: every pose, the candy colours, and real bubble size."""
    scale, cw, ch, gap = 3, 300, 330, 22
    poses = list(EYES)
    width = len(poses) * cw + (len(poses) + 1) * gap
    height = 150 + 2 * ch + 30 + 40
    img = base.gradient(width, height, (255, 228, 240), (214, 228, 255)).convert("RGBA")
    d = ImageDraw.Draw(img)
    title = base.ImageFont.truetype(base.PIXEL_FONT, 64)
    label = base.ImageFont.truetype(base.FONT_DIR + "LiberationSans-Bold.ttf", 26)
    small = base.ImageFont.truetype(base.FONT_DIR + "LiberationSans-Regular.ttf", 22)
    base.centered_text(d, width / 2, 24, "Roxie, glow-up", title)
    base.centered_text(d, width / 2, 98, "brown hair, blue eyes, cat-ear beanie · beanie, choker and top take your colour",
                       small, (120, 100, 160))

    def tile(x, y, pose, variant, caption):
        base.card(img, (x, y, x + cw, y + ch))
        spr = roxie(pose, variant).resize((SIZE * scale, SIZE * scale), Image.NEAREST)
        img.alpha_composite(spr, (x + (cw - spr.width) // 2, y + 4))
        base.centered_text(ImageDraw.Draw(img), x + cw / 2, y + ch - 40, caption, label)

    y = 150
    for i, p in enumerate(poses):
        tile(gap + i * (cw + gap), y, p, "Blueberry", "happy (wink)" if p == "happy" else p)
    y += ch + 30
    variants = list(base.VARIANTS)
    for i, v in enumerate(variants):
        tile(gap + i * (cw + gap), y, "idle", v, v + (" (default)" if i == 0 else ""))

    x0 = gap + len(variants) * (cw + gap)
    x1 = x0 + 2 * cw + gap
    base.card(img, (x0, y, x1, y + ch))
    base.centered_text(ImageDraw.Draw(img), (x0 + x1) / 2, y + ch - 40, "bubble size", label)
    for j, px in enumerate((96, 144)):
        disc = px + 16
        bx = x0 + 60 + j * 260
        by = y + 40 + (160 - disc) // 2
        ImageDraw.Draw(img).ellipse((bx, by, bx + disc, by + disc), fill=(255, 255, 255, 255),
                                    outline=(200, 190, 235), width=4)
        face = roxie("idle").resize((px, px), Image.NEAREST)
        img.alpha_composite(face, (bx + 8, by + 10))
    img.convert("RGB").save(path)


if __name__ == "__main__":
    sheet("/home/claw/assistant/outbox/roxie-glowup-sheet.png")
