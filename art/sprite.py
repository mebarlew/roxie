"""Roxie sprite: one 40x40 indexed grid, poses as overrides, colour variants as palette swaps.

Retired Roxie v1 art. The drawing helpers (gradient, card, glyph, fonts) and the candy VARIANTS are
still shared by the other art scripts.
"""

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# Left half of the face; the right half is mirrored.
HALF = """
....................
.....OO.............
.....OHO............
....OHHHO...........
....OHEHHO..........
....OHEEHHO....OOOOO
....OHEEEHHO.OOHHHHH
...OHHEEEEHHOHHHHHHH
...OHHEEEEEHHHHHHHHH
...OHHHHHHHHHHHHHHHH
...OHHHHHHHHHHHHHHHH
...Ohhhhhhhhhhhhhhhh
..OLHLHLHLHLHLHLHLHL
..OLHLHLHLHLHLHLHLHL
..OLHLHLHLHLHLHLHLHL
...Ohhhhhhhhhhhhhhhh
...Orrrrrrrrrrrrrrrr
...ORlllRRRRllRRRRRR
...ORRRRRRRRRRRRrrRR
...ORRRRrRrrRRRrsSrR
...ORRRRrrsSSrrrSSSs
...ORRRRrsSKKKKSSSSS
...ORRRRrSSSKWWKKSSS
...ORRRRrSSSKWWKKSSS
...ORRRRrSSSKKKKKSSS
...ORRRrSSSSKIIJKSSS
...ORRRrSSSSSKKKSSSS
....ORRrSBBBBSSSSSSS
....ORRrSSBBSSSSSSMS
.....ORrSSSSSSSSSSSM
......OroSSSSSSSSSSS
.......O.ooooSSSSSSS
.............ooooooo
..........OOOOOOOsss
........OOCCCCCOAOOO
......OOTOCCCCCOAAAa
.....OTTTOCCCCcOAOOO
.....OTTTTOCCcOOOTTT
....OTTTTTTOOOTTTTTT
....OTTTTTTTTTTTTTTT
"""

SIZE = 40
EYE_X, EYE_Y = 11, 21  # top-left of the left eye's 6x6 box; the right eye mirrors it

ROLES = {
    "O": "outline", "H": "hat", "h": "hat_shade", "L": "hat_light", "E": "ear_inner",
    "R": "hair", "r": "hair_shade", "l": "hair_light", "S": "skin", "s": "skin_shade",
    "o": "jaw", "K": "eye", "W": "eye_light", "I": "iris", "J": "iris_light",
    "B": "blush", "M": "mouth", "N": "tongue", "T": "outfit", "C": "collar",
    "c": "collar_shade", "A": "bow", "a": "bow_shade",
}

BASE = {
    "outline": (52, 40, 92), "hair": (140, 86, 66), "hair_shade": (100, 58, 52),
    "hair_light": (190, 130, 96), "skin": (255, 226, 208), "skin_shade": (246, 192, 174),
    "jaw": (236, 168, 156), "eye": (40, 32, 72), "eye_light": (255, 255, 255),
    "blush": (255, 160, 176), "mouth": (180, 70, 90), "tongue": (255, 128, 150),
    "collar": (255, 255, 255), "collar_shade": (205, 212, 236),
}

VARIANTS = {
    "Blueberry": {
        "hat": (110, 160, 240), "hat_shade": (78, 118, 206), "hat_light": (170, 205, 255),
        "ear_inner": (255, 170, 200), "outfit": (110, 160, 240), "iris": (90, 110, 220),
        "iris_light": (140, 180, 255), "bow": (255, 120, 160), "bow_shade": (214, 76, 128),
    },
    "Strawberry": {
        "hat": (255, 128, 160), "hat_shade": (222, 86, 126), "hat_light": (255, 196, 212),
        "ear_inner": (255, 226, 234), "outfit": (255, 128, 160), "iris": (200, 70, 110),
        "iris_light": (255, 150, 180), "bow": (110, 200, 120), "bow_shade": (64, 150, 90),
    },
    "Mint": {
        "hat": (112, 214, 178), "hat_shade": (62, 164, 136), "hat_light": (192, 246, 222),
        "ear_inner": (255, 176, 204), "outfit": (112, 214, 178), "iris": (40, 150, 130),
        "iris_light": (120, 220, 190), "bow": (255, 120, 160), "bow_shade": (214, 76, 128),
    },
    "Lavender": {
        "hat": (178, 148, 240), "hat_shade": (134, 104, 206), "hat_light": (222, 206, 255),
        "ear_inner": (255, 180, 214), "outfit": (178, 148, 240), "iris": (120, 80, 200),
        "iris_light": (190, 160, 255), "bow": (255, 210, 100), "bow_shade": (226, 164, 60),
    },
    "Lemon": {
        "hat": (255, 216, 88), "hat_shade": (228, 172, 48), "hat_light": (255, 240, 170),
        "ear_inner": (255, 170, 190), "outfit": (255, 216, 88), "iris": (200, 130, 40),
        "iris_light": (250, 196, 90), "bow": (110, 160, 240), "bow_shade": (78, 118, 206),
    },
}


def palette(variant):
    roles = {**BASE, **VARIANTS[variant]}
    return {ch: roles[role] for ch, role in ROLES.items()}


def base_grid():
    rows = HALF.strip("\n").split("\n")
    grid = [list(r + r[::-1]) for r in rows]
    # Both eye highlights sit top-left so the light direction stays consistent.
    for y, s in ((22, "KWWKK"), (23, "KWWKK"), (25, "KIIJK")):
        grid[y][23:28] = s
    return grid


def eyes(grid, block):
    """Paint a 6x6 eye block on the left eye and its mirror image on the right."""
    for dy, row in enumerate(block):
        for dx, ch in enumerate(row):
            grid[EYE_Y + dy][EYE_X + dx] = ch
            grid[EYE_Y + dy][SIZE - 1 - EYE_X - dx] = ch


def paint(grid, y, x, s):
    grid[y][x:x + len(s)] = s


BLINK = ["SSSSSS", "SSSSSS", "KSSSSS", "SKKKKS", "SSSSSS", "SSSSSS"]
HAPPY = ["SSSSSS", "SSSSSS", "SSKKSS", "SKSSKS", "KSSSSK", "SSSSSS"]
SLEEPY = ["SSSSSS", "SSSSSS", "SSSSSS", "KSSSSK", "SKKKKS", "SSSSSS"]
LOOK_UP = ["SKKKKS", "SKIIJK", "SKKWWK", "SKKWWK", "SKKKKK", "SSKKKS"]


def perk_ears(grid):
    """Stretch both ears one pixel taller."""
    for x in list(range(0, 13)) + list(range(27, SIZE)):
        for y in range(0, 8):
            grid[y][x] = grid[y + 1][x]


def pose(name):
    g = base_grid()
    if name == "blink":
        eyes(g, BLINK)
    elif name == "talking":
        paint(g, 28, 18, "SMMS")
        paint(g, 29, 18, "MNNM")
        paint(g, 30, 18, "SMMS")
    elif name == "listening":
        perk_ears(g)
        paint(g, 28, 18, "SMMS")
        paint(g, 29, 18, "SMMS")
    elif name == "thinking":
        eyes(g, LOOK_UP)
        paint(g, 28, 18, "SSSS")
        paint(g, 29, 18, "SMMM")
    elif name == "sleepy":
        eyes(g, SLEEPY)
        paint(g, 28, 18, "SSSS")
        paint(g, 29, 18, "SMMS")
    elif name == "happy":
        eyes(g, HAPPY)
        paint(g, 28, 17, "MMMMMM")
        paint(g, 29, 17, "SMNNMS")
        paint(g, 30, 17, "SSMMSS")
    return g


POSES = ["idle", "blink", "talking", "listening", "thinking", "sleepy", "happy"]


def sprite(name="idle", variant="Blueberry"):
    pal = palette(variant)
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    for y, row in enumerate(pose(name)):
        for x, ch in enumerate(row):
            if ch != ".":
                img.putpixel((x, y), pal[ch] + (255,))
    return img


# --- sheet -----------------------------------------------------------------

FONT_DIR = "/usr/share/fonts/truetype/liberation/"
PIXEL_FONT = "/usr/share/fonts/opentype/unifont/unifont.otf"
INK = (52, 40, 92)

THOUGHT = ["..OOOOOOO..", ".OWWWWWWWO.", "OWKWWKWWKWO", ".OWWWWWWWO.", "..OOOOOOO..",
           "......OWO..", ".......O..."]
ZZZ = ["......KKKK", "........K.", ".......K..", "......KKKK", "KKKKK.....", "...K......",
       "..K.......", ".K........", "KKKKK....."]


def glyph(rows, scale, colours):
    img = Image.new("RGBA", (len(rows[0]) * scale, len(rows) * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colours:
                d.rectangle([x * scale, y * scale, (x + 1) * scale - 1, (y + 1) * scale - 1],
                            fill=colours[ch])
    return img


def gradient(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return img


def card(sheet, box, radius=32):
    x0, y0, x1, y1 = box
    shadow = Image.new("RGBA", sheet.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((x0, y0 + 10, x1, y1 + 10), radius,
                                             fill=(90, 60, 140, 60))
    sheet.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14)))
    ImageDraw.Draw(sheet).rounded_rectangle(box, radius, fill=(255, 255, 255, 255))


def centered_text(d, cx, y, text, font, fill=INK):
    w = d.textlength(text, font=font)
    d.text((cx - w / 2, y), text, font=font, fill=fill)


def sheet(path):
    scale, cw, ch, gap = 6, 280, 320, 24
    width = 7 * cw + 8 * gap
    height = 1260
    img = gradient(width, height, (255, 228, 240), (214, 228, 255)).convert("RGBA")
    d = ImageDraw.Draw(img)
    title = ImageFont.truetype(PIXEL_FONT, 64)
    label = ImageFont.truetype(FONT_DIR + "LiberationSans-Bold.ttf", 26)
    small = ImageFont.truetype(FONT_DIR + "LiberationSans-Regular.ttf", 22)

    centered_text(d, width / 2, 28, "Roxie the Helper", title)
    centered_text(d, width / 2, 104, "poses · colours · real bubble size", small, (120, 100, 160))

    def tile(x, y, name, variant, caption):
        card(img, (x, y, x + cw, y + ch))
        spr = sprite(name, variant).resize((SIZE * scale, SIZE * scale), Image.NEAREST)
        img.alpha_composite(spr, (x + (cw - spr.width) // 2, y + 20))
        if name == "thinking":
            g = glyph(THOUGHT, 5, {"O": INK, "W": (236, 230, 255), "K": INK})
            img.alpha_composite(g, (x + cw - g.width - 4, y + 4))
        if name == "sleepy":
            g = glyph(ZZZ, 4, {"K": (120, 110, 200)})
            img.alpha_composite(g, (x + cw - g.width - 8, y + 6))
        centered_text(ImageDraw.Draw(img), x + cw / 2, y + ch - 50, caption, label)

    y = 160
    for i, name in enumerate(POSES):
        tile(gap + i * (cw + gap), y, name, "Blueberry", name)

    y += ch + 40
    x0 = (width - (5 * cw + 4 * gap)) // 2
    for i, variant in enumerate(VARIANTS):
        tile(x0 + i * (cw + gap), y, "idle", variant, variant + (" (default)" if i == 0 else ""))

    y += ch + 40
    bubble_preview(img, ((width - 1100) // 2, y, (width + 1100) // 2, y + 340), label, small)
    img.convert("RGB").save(path)


def bubble_preview(img, box, label, small):
    x0, y0, x1, y1 = box
    card(img, box)
    d = ImageDraw.Draw(img)
    # A fake busy chat app as the backdrop.
    screen = (x0 + 30, y0 + 30, x0 + 640, y1 - 30)
    d.rounded_rectangle(screen, 24, fill=(240, 242, 247))
    lines = [(0.55, (255, 255, 255)), (0.7, (220, 236, 255)), (0.45, (255, 255, 255)),
             (0.8, (220, 236, 255)), (0.5, (255, 255, 255))]
    for i, (frac, colour) in enumerate(lines):
        ly = screen[1] + 22 + i * 52
        w = (screen[2] - screen[0] - 60) * frac
        lx = screen[0] + 24 if i % 2 == 0 else screen[2] - 24 - w
        d.rounded_rectangle((lx, ly, lx + w, ly + 38), 19, fill=colour, outline=(225, 228, 238))

    spr = sprite()
    positions = [(screen[2] - 60, screen[1] + 40, 1), (screen[2] - 140, screen[1] + 120, 2),
                 (screen[0] + 40, screen[1] + 150, 3)]
    for bx, by, s in positions:
        bubble(img, bx, by, spr, s)

    tx = x0 + 680
    d.text((tx, y0 + 50), "Bubble size check", font=label, fill=INK)
    notes = ["1x = 40 px: still reads as a face", "2x = 80 px: about the size of a",
             "   real floating bubble on a phone", "3x = 120 px: the expanded state",
             "", "Colours are pure palette swaps,", "so any user colour works in-app."]
    for i, line in enumerate(notes):
        d.text((tx, y0 + 100 + i * 30), line, font=small, fill=(110, 96, 150))


def bubble(img, x, y, spr, s):
    size = SIZE * s + 8 * s
    disc = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    dd = ImageDraw.Draw(disc)
    dd.ellipse((0, 0, size - 1, size - 1), fill=(255, 255, 255, 255), outline=(200, 190, 235),
               width=max(2, s))
    face = spr.resize((SIZE * s, SIZE * s), Image.NEAREST)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((2 * s, 2 * s, size - 1 - 2 * s, size - 1 - 2 * s), fill=255)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer.alpha_composite(face, (4 * s, 6 * s))
    disc.paste(layer, (0, 0), Image.composite(layer, Image.new("RGBA", layer.size), mask))
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).ellipse((x, y + 4, x + size, y + size + 4), fill=(60, 40, 110, 70))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(6)))
    img.alpha_composite(disc, (x, y))


def icon(path, size=192):
    """Launcher icon: happy Roxie on a candy gradient disc."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    disc = gradient(size, size, (255, 214, 232), (190, 214, 255)).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    img.paste(disc, (0, 0), mask)
    face = sprite("happy").resize((SIZE * 4, SIZE * 4), Image.NEAREST)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer.alpha_composite(face, ((size - face.width) // 2, size - face.height))
    img.paste(layer, (0, 0), Image.composite(layer, Image.new("RGBA", layer.size), mask))
    img.save(path)


if __name__ == "__main__":
    import sys

    sheet("/home/claw/assistant/outbox/roxie-sheet.png")
    if len(sys.argv) > 1:
        icon(sys.argv[1])
