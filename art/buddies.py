"""Buddies: duck, cat, frog and bunny next to Roxie. 64x64, drawn from shapes like roxie_hd.py.

Every buddy wears a bow in the user's candy colour, so the colour picker works for all of them.
Poses only change the eyes and mouth, same seven as Roxie.
"""

from PIL import Image, ImageDraw

import roxie_hd
import sprite as base

SIZE = 64

INK = roxie_hd.PAL["outline"]
WHITE = (255, 255, 255)
BLUSH = (255, 156, 176)
MOUTH = (150, 60, 80)
TONGUE = (255, 128, 150)

PAL = {
    "duck": {"body": (255, 216, 96), "shade": (238, 176, 58), "light": (255, 240, 170),
             "beak": (255, 156, 64), "beak_shade": (222, 106, 46)},
    "cat": {"body": (255, 200, 146), "shade": (232, 150, 98), "light": (255, 232, 204),
            "inner": (255, 178, 198), "nose": (238, 112, 146)},
    "frog": {"body": (132, 212, 120), "shade": (86, 168, 92), "light": (196, 242, 172)},
    "bunny": {"body": (252, 248, 252), "shade": (222, 210, 236), "light": (255, 255, 255),
              "inner": (255, 186, 206), "nose": (238, 112, 146)},
}

EYES = roxie_hd.EYES
MOUTHS = roxie_hd.MOUTHS


def mirror(pts):
    return [(SIZE - 1 - x, y) for x, y in pts]


def head(d, box, p):
    """Body-colour ellipse with a shade crescent bottom-right and a highlight top-left."""
    x0, y0, x1, y1 = box
    d.ellipse(box, fill=p["shade"])
    d.ellipse((x0, y0, x1 - 2, y1 - 3), fill=p["body"])
    d.ellipse((x0 + 7, y0 + 5, x0 + 14, y0 + 10), fill=p["light"])


def eye(d, cx, cy, state, sclera=False):
    if state == "closed":
        d.line([(cx - 3, cy + 1), (cx + 3, cy + 1)], fill=INK, width=2)
    elif state == "happy":
        d.arc((cx - 4, cy - 2, cx + 4, cy + 6), 200, 340, fill=INK, width=2)
    elif state == "sleepy":
        d.arc((cx - 4, cy - 5, cx + 4, cy + 3), 20, 160, fill=INK, width=2)
    else:
        if sclera:
            d.ellipse((cx - 6, cy - 6, cx + 6, cy + 6), fill=WHITE, outline=INK)
        dx, dy = (1, -2) if state == "up" else (0, 0)
        x, y = cx + dx, cy + dy
        d.ellipse((x - 3, y - 4, x + 3, y + 4), fill=INK)
        d.rectangle((x - 2, y - 3, x - 1, y - 2), fill=WHITE)
        d.point((x + 1, y + 2), fill=WHITE)


def blush(d, y, left, right):
    for x in (left, right):
        d.ellipse((x, y, x + 6, y + 3), fill=BLUSH)


def bow(d, cx, cy, v):
    d.polygon([(cx, cy), (cx - 7, cy - 5), (cx - 7, cy + 5)], fill=v["hat"], outline=INK)
    d.polygon([(cx, cy), (cx + 7, cy - 5), (cx + 7, cy + 5)], fill=v["hat"], outline=INK)
    d.line([(cx - 5, cy - 2), (cx - 5, cy + 2)], fill=v["hat_light"])
    d.line([(cx + 5, cy - 2), (cx + 5, cy + 2)], fill=v["hat_shade"])
    d.rectangle((cx - 1, cy - 2, cx + 1, cy + 2), fill=v["hat_shade"], outline=INK)


def duck(d, pose, v):
    p = PAL["duck"]
    for pts in ([(29, 16), (24, 5), (33, 13)], [(31, 15), (33, 2), (36, 15)], [(35, 16), (42, 6), (38, 16)]):
        d.polygon(pts, fill=p["body"])
    head(d, (9, 13, 55, 58), p)
    bow(d, 46, 19, v)
    eye(d, 23, 33, EYES[pose])
    eye(d, 41, 33, EYES[pose])
    blush(d, 40, 11, 46)
    mouth = MOUTHS[pose]
    if mouth in ("talk", "open_smile", "o"):
        small = mouth == "o"
        d.ellipse((27 + small * 2, 40, 37 - small * 2, 49 - small * 2), fill=MOUTH)
        if mouth == "open_smile":
            d.ellipse((29, 44, 35, 48), fill=TONGUE)
        d.chord((25 + small * 2, 42, 39 - small * 2, 51 - small * 2), 0, 180, fill=p["beak_shade"], outline=INK)
        d.ellipse((24, 35, 40, 43), fill=p["beak"], outline=INK)
    else:
        d.ellipse((24, 37, 40, 47), fill=p["beak"], outline=INK)
        d.line([(26, 42), (38, 42)] if mouth == "flat" else [(26, 41), (32, 43), (38, 41)], fill=p["beak_shade"])
    d.line([(28, 38), (31, 38)], fill=p["light"])


def cat(d, pose, v):
    p = PAL["cat"]
    ear = [(10, 28), (12, 5), (29, 17)]
    inner = [(14, 23), (15, 11), (24, 18)]
    for pts in (ear, mirror(ear)):
        d.polygon(pts, fill=p["body"])
    for pts in (inner, mirror(inner)):
        d.polygon(pts, fill=p["inner"])
    head(d, (7, 14, 57, 56), p)
    for x0, x1 in ((27, 28), (32, 32), (37, 36)):
        d.line([(x0, 15), (x1, 20)], fill=p["shade"], width=2)
    bow(d, 47, 16, v)
    eye(d, 22, 34, EYES[pose])
    eye(d, 42, 34, EYES[pose])
    blush(d, 40, 11, 47)
    d.polygon([(30, 39), (34, 39), (32, 41)], fill=p["nose"])
    mouth = MOUTHS[pose]
    if mouth in ("talk", "open_smile"):
        d.chord((27, 41, 37, 51), 0, 180, fill=MOUTH)
        d.ellipse((30, 45, 34, 49), fill=TONGUE)
    elif mouth == "o":
        d.ellipse((30, 43, 34, 47), fill=MOUTH)
    if mouth == "flat":
        d.line([(30, 43), (34, 43)], fill=INK)
    else:
        d.arc((27, 39, 32, 44), 20, 160, fill=INK)
        d.arc((32, 39, 37, 44), 20, 160, fill=INK)


def cat_whiskers(d):
    for y0, y1 in ((38, 36), (41, 42)):
        d.line([(12, y0), (4, y1)], fill=INK)
        d.line([(51, y0), (59, y1)], fill=INK)


def frog(d, pose, v):
    p = PAL["frog"]
    state = EYES[pose]
    open_eye = state in ("open", "up")
    for box in ((8, 7, 30, 29), (33, 7, 55, 29)):
        d.ellipse(box, fill=p["body"])
    head(d, (5, 17, 58, 57), p)
    for box in ((8, 7, 30, 29), (33, 7, 55, 29)):
        d.chord(box, 180, 360, fill=p["body"])
    bow(d, 31, 13, v)
    eye(d, 19, 19, state, sclera=open_eye)
    eye(d, 44, 19, state, sclera=open_eye)
    blush(d, 40, 9, 48)
    d.point((29, 35), fill=p["shade"])
    d.point((34, 35), fill=p["shade"])
    mouth = MOUTHS[pose]
    if mouth in ("talk", "open_smile"):
        wide = mouth == "open_smile"
        d.chord((20 - wide * 3, 36, 43 + wide * 3, 54), 0, 180, fill=MOUTH, outline=INK)
        d.ellipse((27, 46, 36, 52), fill=TONGUE)
    elif mouth == "o":
        d.ellipse((28, 41, 35, 48), fill=MOUTH, outline=INK)
    elif mouth == "flat":
        d.line([(24, 43), (39, 43)], fill=INK)
    else:
        d.arc((16, 30, 47, 46), 25, 155, fill=INK)


def bunny(d, pose, v):
    p = PAL["bunny"]
    for box, inner in (((14, 0, 27, 34), (18, 5, 23, 29)), ((36, 0, 49, 34), (40, 5, 45, 29))):
        d.ellipse(box, fill=p["body"])
        d.ellipse(inner, fill=p["inner"])
    head(d, (10, 21, 54, 61), p)
    bow(d, 22, 25, v)
    eye(d, 23, 40, EYES[pose])
    eye(d, 41, 40, EYES[pose])
    blush(d, 46, 12, 45)
    d.ellipse((30, 45, 34, 48), fill=p["nose"])
    mouth = MOUTHS[pose]
    if mouth in ("talk", "open_smile"):
        d.chord((28, 46, 36, 56), 0, 180, fill=MOUTH)
        d.rectangle((31, 51, 33, 53), fill=WHITE)
    elif mouth == "o":
        d.ellipse((30, 50, 34, 54), fill=MOUTH)
    elif mouth == "flat":
        d.line([(30, 51), (34, 51)], fill=INK)
    else:
        d.line([(32, 48), (32, 49)], fill=INK)
        d.arc((28, 47, 32, 52), 20, 160, fill=INK)
        d.arc((32, 47, 36, 52), 20, 160, fill=INK)


DRAW = {"duck": duck, "cat": cat, "frog": frog, "bunny": bunny}
BUDDIES = ["duck", "cat", "frog", "bunny", "roxie"]


def buddy(name, pose="idle", variant="Blueberry"):
    if name == "roxie":
        return roxie_hd.roxie(pose, variant)
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    DRAW[name](d, pose, base.VARIANTS[variant])
    img = roxie_hd.outline(img)
    if name == "cat":
        cat_whiskers(ImageDraw.Draw(img))
    return img


def big(img, px=192):
    return img.resize((px, px), Image.NEAREST)


def sheet(path):
    poses = list(EYES)
    label_w, cw, ch, gap = 170, 210, 212, 18
    width = label_w + len(poses) * cw + (len(poses) + 1) * gap
    rows_top = 210
    colour_top = rows_top + len(BUDDIES) * (ch + gap) + 20
    height = colour_top + 260 + 40
    img = base.gradient(width, height, (255, 228, 240), (214, 228, 255)).convert("RGBA")
    d = ImageDraw.Draw(img)
    title = base.ImageFont.truetype(base.PIXEL_FONT, 64)
    label = base.ImageFont.truetype(base.FONT_DIR + "LiberationSans-Bold.ttf", 26)
    small = base.ImageFont.truetype(base.FONT_DIR + "LiberationSans-Regular.ttf", 22)
    base.centered_text(d, width / 2, 24, "Pick your buddy", title)
    base.centered_text(d, width / 2, 98, "duck (default) · cat · frog · bunny · Roxie · the bow takes your colour",
                       small, (120, 100, 160))

    def col_x(i):
        return label_w + gap + i * (cw + gap)

    for i, p in enumerate(poses):
        base.centered_text(d, col_x(i) + cw / 2, rows_top - 44, p, label)

    for r, name in enumerate(BUDDIES):
        y = rows_top + r * (ch + gap)
        d.text((gap + 6, y + ch / 2 - 16), name.capitalize(), font=label, fill=base.INK)
        if r == 0:
            d.text((gap + 6, y + ch / 2 + 16), "default", font=small, fill=(120, 100, 160))
        for i, p in enumerate(poses):
            x = col_x(i)
            base.card(img, (x, y, x + cw, y + ch))
            spr = big(buddy(name, p))
            img.alpha_composite(spr, (x + (cw - spr.width) // 2, y + 10))
            if p == "thinking":
                g = base.glyph(base.THOUGHT, 4, {"O": base.INK, "W": (236, 230, 255), "K": base.INK})
                img.alpha_composite(g, (x + cw - g.width - 6, y + 6))
            if p == "sleepy":
                g = base.glyph(base.ZZZ, 3, {"K": (120, 110, 200)})
                img.alpha_composite(g, (x + cw - g.width - 8, y + 8))

    y = colour_top
    for i, variant in enumerate(base.VARIANTS):
        x = col_x(i)
        base.card(img, (x, y, x + cw, y + 260))
        spr = big(buddy("duck", "happy", variant))
        img.alpha_composite(spr, (x + (cw - spr.width) // 2, y + 8))
        base.centered_text(ImageDraw.Draw(img), x + cw / 2, y + 214, variant, label)

    # Real bubble size: every buddy at 1x in a floating disc.
    x0 = col_x(5)
    x1 = col_x(6) + cw
    base.card(img, (x0, y, x1, y + 260))
    base.centered_text(ImageDraw.Draw(img), (x0 + x1) / 2, y + 22, "bubble size", label)
    disc, step = 76, (x1 - x0 - 20) // len(BUDDIES)
    for i, name in enumerate(BUDDIES):
        bx = x0 + 10 + i * step + (step - disc) // 2
        by = y + 100
        ImageDraw.Draw(img).ellipse((bx, by, bx + disc, by + disc), fill=WHITE, outline=(200, 190, 235), width=3)
        face = buddy(name)
        if face.width != SIZE:
            face = face.resize((SIZE, SIZE), Image.LANCZOS)
        img.alpha_composite(face, (bx + (disc - SIZE) // 2, by + (disc - SIZE) // 2 + 2))
    img.convert("RGB").save(path)


BRACE = ["...XXX", "..XX..", "..XX..", "..XX..", "..XX..", "XXX...",
         "..XX..", "..XX..", "..XX..", "..XX..", "...XXX"]


def rubber_duck():
    """Classic side-view rubber duck, 40x40, for the launcher icon."""
    p = PAL["duck"]
    img = Image.new("RGBA", (40, 40), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.polygon([(5, 25), (2, 14), (12, 22)], fill=p["body"])
    d.ellipse((4, 18, 37, 37), fill=p["shade"])
    d.ellipse((4, 18, 35, 35), fill=p["body"])
    d.ellipse((18, 4, 35, 22), fill=p["body"])
    d.ellipse((21, 6, 26, 10), fill=p["light"])
    d.chord((11, 22, 26, 32), 0, 180, fill=p["shade"])
    d.ellipse((31, 12, 39, 18), fill=p["beak"])
    d.line([(32, 15), (38, 15)], fill=p["beak_shade"])
    d.ellipse((27, 9, 30, 13), fill=INK)
    d.point((27, 10), fill=WHITE)
    d.ellipse((23, 14, 27, 16), fill=BLUSH)
    return roxie_hd.outline(img)


def icon(path, size=192):
    """Launcher icon: a rubber duck between code braces on a dark editor-style disc."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    disc = base.gradient(size, size, (58, 50, 104), (28, 26, 56)).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    img.paste(disc, (0, 0), mask)
    duck_img = rubber_duck().resize((120, 120), Image.NEAREST)
    img.alpha_composite(duck_img, ((size - 120) // 2, (size - 120) // 2 + 4))
    left = base.glyph(BRACE, 4, {"X": (255, 128, 176)})
    right = base.glyph([row[::-1] for row in BRACE], 4, {"X": (128, 178, 255)})
    y = (size - left.height) // 2 + 4
    img.alpha_composite(left, (10, y))
    img.alpha_composite(right, (size - 10 - right.width, y))
    img.save(path)


if __name__ == "__main__":
    import sys

    sheet("/home/claw/assistant/outbox/roxie-buddies-sheet.png")
    if len(sys.argv) > 1:
        icon(sys.argv[1])
