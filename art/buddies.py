"""Buddies: Sudo the duck, cat, frog and bunny. 64x64, drawn from shapes so every frame stays consistent.

A frame is a set of part states (eyes, mouth, ears) plus whole-body motion (bob, squash) and an
effect (Zzz, sparkles, thought dots, sound waves, sweat). Animations are lists of 100 ms frames.
The user's candy colour goes on the ring around the floating bubble, not on the buddies.
"""

from dataclasses import dataclass
from functools import lru_cache

from PIL import Image, ImageDraw

import roxie_hd
import sprite as base

SIZE = 64
STEP_MS = 100

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


@dataclass(frozen=True)
class Frame:
    eyes: str = "open"  # open, closed, happy, sleepy, up, left, right, squint
    mouth: str = "smile"  # smile, flat, o, talk1, talk2, open_smile
    ears: str = "rest"  # rest, perk, wiggleL, wiggleR, droop (tuft, ears or eye bumps)
    dy: int = 0  # bob in sprite pixels, negative is up
    squash: float = 0.0  # >0 squashed flat, <0 stretched tall
    fx: str = ""  # zzz0-5, sparkle0-1, dots1-3, waves0-1, sweat


def mirror(pts):
    return [(SIZE - 1 - x, y) for x, y in pts]


def head(d, box, p):
    """Body-colour ellipse with a shade crescent bottom-right and a highlight top-left."""
    x0, y0, x1, y1 = box
    d.ellipse(box, fill=p["shade"])
    d.ellipse((x0, y0, x1 - 2, y1 - 3), fill=p["body"])
    d.ellipse((x0 + 7, y0 + 5, x0 + 14, y0 + 10), fill=p["light"])


def eye(d, cx, cy, state, side, sclera=False):
    """side is -1 for the left eye and 1 for the right."""
    if state == "closed":
        d.line([(cx - 3, cy + 1), (cx + 3, cy + 1)], fill=INK, width=2)
    elif state == "happy":
        d.arc((cx - 4, cy - 2, cx + 4, cy + 6), 200, 340, fill=INK, width=2)
    elif state == "sleepy":
        d.arc((cx - 4, cy - 5, cx + 4, cy + 3), 20, 160, fill=INK, width=2)
    elif state == "squint":
        tip, back = cx - 2 * side, cx + 2 * side
        d.line([(back, cy - 3), (tip, cy), (back, cy + 3)], fill=INK, width=2)
    else:
        r = 6 if sclera else 4
        if sclera:
            d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=WHITE, outline=INK)
        dx, dy = {"up": (1, -2), "left": (-2, 0), "right": (2, 0)}.get(state, (0, 0))
        x, y = cx + dx, cy + dy
        d.ellipse((x - 3, y - 4, x + 3, y + 4), fill=INK)
        d.rectangle((x - 2, y - 3, x - 1, y - 2), fill=WHITE)
        d.point((x + 1, y + 2), fill=WHITE)


def blush(d, y, left, right):
    for x in (left, right):
        d.ellipse((x, y, x + 6, y + 3), fill=BLUSH)


def eyes(d, left, right, y, state):
    eye(d, left, y, state, -1)
    eye(d, right, y, state, 1)


TUFT = {"rest": [(24, 5), (33, 2), (42, 6)], "perk": [(24, 3), (33, 0), (42, 4)],
        "wiggleL": [(21, 6), (30, 2), (39, 5)], "wiggleR": [(27, 5), (36, 2), (45, 6)],
        "droop": [(19, 11), (33, 7), (47, 11)]}


def duck(d, f):
    p = PAL["duck"]
    bases = [((29, 16), (33, 13)), ((31, 15), (36, 15)), ((35, 16), (38, 16))]
    for (a, b), tip in zip(bases, TUFT[f.ears]):
        d.polygon([a, tip, b], fill=p["body"])
    head(d, (9, 13, 55, 58), p)
    eyes(d, 23, 41, 33, f.eyes)
    blush(d, 40, 11, 46)
    open_by = {"talk2": 0, "open_smile": 0, "talk1": 1, "o": 2}
    if f.mouth in open_by:
        k = open_by[f.mouth]
        d.ellipse((27 + k, 40, 37 - k, 49 - k), fill=MOUTH)
        if f.mouth == "open_smile":
            d.ellipse((29, 44, 35, 48), fill=TONGUE)
        d.chord((25 + k, 42, 39 - k, 51 - k), 0, 180, fill=p["beak_shade"], outline=INK)
        d.ellipse((24, 35, 40, 43), fill=p["beak"], outline=INK)
    else:
        d.ellipse((24, 37, 40, 47), fill=p["beak"], outline=INK)
        d.line([(26, 42), (38, 42)] if f.mouth == "flat" else [(26, 41), (32, 43), (38, 41)], fill=p["beak_shade"])
    d.line([(28, 38), (31, 38)], fill=p["light"])


# Ear tip per state, left ear in left-side coordinates; the right ear mirrors its own entry.
CAT_TIP = {"rest": ((12, 5), (12, 5)), "perk": ((12, 2), (12, 2)), "wiggleL": ((8, 8), (12, 5)),
           "wiggleR": ((12, 5), (8, 8)), "droop": ((6, 14), (6, 14))}


def cat(d, f):
    p = PAL["cat"]
    for tip, flip in zip(CAT_TIP[f.ears], (False, True)):
        ear = [(10, 28), tip, (29, 17)]
        inner = [(14, 23), (tip[0] + 3, tip[1] + 6), (24, 18)]
        d.polygon(mirror(ear) if flip else ear, fill=p["body"])
        d.polygon(mirror(inner) if flip else inner, fill=p["inner"])
    head(d, (7, 14, 57, 56), p)
    for x0, x1 in ((27, 28), (32, 32), (37, 36)):
        d.line([(x0, 15), (x1, 20)], fill=p["shade"], width=2)
    eyes(d, 22, 42, 34, f.eyes)
    blush(d, 40, 11, 47)
    d.polygon([(30, 39), (34, 39), (32, 41)], fill=p["nose"])
    if f.mouth in ("talk2", "open_smile"):
        d.chord((27, 41, 37, 51), 0, 180, fill=MOUTH)
        d.ellipse((30, 45, 34, 49), fill=TONGUE)
    elif f.mouth == "talk1":
        d.chord((29, 41, 35, 48), 0, 180, fill=MOUTH)
    elif f.mouth == "o":
        d.ellipse((30, 43, 34, 47), fill=MOUTH)
    if f.mouth == "flat":
        d.line([(30, 43), (34, 43)], fill=INK)
    else:
        d.arc((27, 39, 32, 44), 20, 160, fill=INK)
        d.arc((32, 39, 37, 44), 20, 160, fill=INK)


def cat_whiskers(d):
    for y0, y1 in ((38, 36), (41, 42)):
        d.line([(12, y0), (4, y1)], fill=INK)
        d.line([(51, y0), (59, y1)], fill=INK)


# Eye bump lift (left, right) per state; negative is up.
FROG_LIFT = {"rest": (0, 0), "perk": (-2, -2), "wiggleL": (-2, 0), "wiggleR": (0, -2), "droop": (2, 2)}


def frog(d, f):
    p = PAL["frog"]
    sclera = f.eyes in ("open", "up", "left", "right")
    bumps = [(8, 7 + dy, 30, 29 + dy) if i == 0 else (33, 7 + dy, 55, 29 + dy)
             for i, dy in enumerate(FROG_LIFT[f.ears])]
    for box in bumps:
        d.ellipse(box, fill=p["body"])
    head(d, (5, 17, 58, 57), p)
    for box in bumps:
        d.chord(box, 180, 360, fill=p["body"])
    (l, r) = FROG_LIFT[f.ears]
    eye(d, 19, 19 + l, f.eyes, -1, sclera)
    eye(d, 44, 19 + r, f.eyes, 1, sclera)
    blush(d, 40, 9, 48)
    d.point((29, 35), fill=p["shade"])
    d.point((34, 35), fill=p["shade"])
    if f.mouth in ("talk2", "open_smile"):
        wide = 3 if f.mouth == "open_smile" else 0
        d.chord((20 - wide, 36, 43 + wide, 54), 0, 180, fill=MOUTH, outline=INK)
        d.ellipse((27, 46, 36, 52), fill=TONGUE)
    elif f.mouth == "talk1":
        d.chord((23, 38, 40, 49), 0, 180, fill=MOUTH, outline=INK)
    elif f.mouth == "o":
        d.ellipse((28, 41, 35, 48), fill=MOUTH, outline=INK)
    elif f.mouth == "flat":
        d.line([(24, 43), (39, 43)], fill=INK)
    else:
        d.arc((16, 30, 47, 46), 25, 155, fill=INK)


def bunny_ear(d, box, p, flop):
    """flop folds the top of the ear over to the outside (-1 left, 1 right, 0 upright)."""
    x0, y0, x1, y1 = box
    if flop:
        y0 = 14
    d.ellipse((x0, y0, x1, y1), fill=p["body"])
    d.ellipse((x0 + 4, y0 + 5, x1 - 4, y1 - 5), fill=p["inner"])
    if flop:
        fx0 = x0 - 11 if flop < 0 else x1 - 5
        d.ellipse((fx0, 9, fx0 + 16, 19), fill=p["body"])
        d.ellipse((fx0 + 3, 12, fx0 + 13, 16), fill=p["inner"])


def bunny(d, f):
    p = PAL["bunny"]
    top = 0 if f.ears == "perk" else 3
    flop_l = f.ears in ("wiggleL", "droop")
    flop_r = f.ears in ("wiggleR", "droop")
    bunny_ear(d, (14, top, 27, 34), p, -1 if flop_l else 0)
    bunny_ear(d, (36, top, 49, 34), p, 1 if flop_r else 0)
    head(d, (10, 21, 54, 61), p)
    eyes(d, 23, 41, 40, f.eyes)
    blush(d, 46, 12, 45)
    d.ellipse((30, 45, 34, 48), fill=p["nose"])
    if f.mouth in ("talk2", "open_smile"):
        d.chord((28, 46, 36, 56), 0, 180, fill=MOUTH)
        d.rectangle((31, 51, 33, 53), fill=WHITE)
    elif f.mouth == "talk1":
        d.chord((29, 47, 35, 53), 0, 180, fill=MOUTH)
        d.rectangle((31, 50, 33, 51), fill=WHITE)
    elif f.mouth == "o":
        d.ellipse((30, 50, 34, 54), fill=MOUTH)
    elif f.mouth == "flat":
        d.line([(30, 51), (34, 51)], fill=INK)
    else:
        d.line([(32, 48), (32, 49)], fill=INK)
        d.arc((28, 47, 32, 52), 20, 160, fill=INK)
        d.arc((32, 47, 36, 52), 20, 160, fill=INK)


# Tail tip and wing lift per state for the rubber duck.
RUBBER_TAIL = {"rest": (6, 29), "perk": (5, 25), "wiggleL": (4, 23), "wiggleR": (7, 31), "droop": (4, 38)}
RUBBER_WING = {"rest": 0, "perk": -2, "wiggleL": 0, "wiggleR": -5, "droop": 3}


def rubber(d, f):
    """Classic side-view rubber duck facing right."""
    p = PAL["duck"]
    d.polygon([(9, 42), RUBBER_TAIL[f.ears], (20, 35)], fill=p["body"])
    d.ellipse((5, 30, 58, 61), fill=p["shade"])
    d.ellipse((5, 30, 56, 59), fill=p["body"])
    w = RUBBER_WING[f.ears]
    d.ellipse((16, 38 + w, 38, 50 + w), fill=p["shade"])
    d.arc((16, 38 + w, 38, 50 + w), 10, 170, fill=INK)
    d.line([(20, 40 + w), (30, 39 + w)], fill=p["light"])
    d.ellipse((28, 6, 56, 35), fill=p["body"])
    d.ellipse((33, 9, 41, 15), fill=p["light"])
    eye(d, 45, 18, f.eyes, 1)
    d.ellipse((37, 24, 43, 27), fill=BLUSH)
    open_by = {"talk2": 0, "open_smile": 0, "talk1": 2, "o": 3}
    if f.mouth in open_by:
        k = open_by[f.mouth]
        d.ellipse((52, 20, 61, 29 - k), fill=MOUTH)
        if f.mouth == "open_smile":
            d.ellipse((54, 24, 59, 27), fill=TONGUE)
        d.chord((51, 22 - k, 62, 31 - k), 0, 180, fill=p["beak_shade"], outline=INK)
        d.ellipse((50, 16, 63, 23), fill=p["beak"], outline=INK)
    else:
        d.ellipse((50, 18, 63, 28), fill=p["beak"], outline=INK)
        d.line([(52, 23), (62, 23)] if f.mouth == "flat" else [(52, 22), (57, 24), (62, 23)], fill=p["beak_shade"])


DRAW = {"rubber": rubber, "duck": duck, "cat": cat, "frog": frog, "bunny": bunny}
BUDDIES = ["rubber", "duck", "cat", "frog", "bunny"]
NAMES = {"rubber": "Sudo", "duck": "Duckling", "cat": "Cat", "frog": "Frog", "bunny": "Bunny"}


@lru_cache(maxsize=None)
def parts(name, eyes_state, mouth, ears):
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    DRAW[name](d, Frame(eyes_state, mouth, ears))
    img = roxie_hd.outline(img)
    if name == "cat":
        cat_whiskers(ImageDraw.Draw(img))
    return img


def buddy(name, frame=Frame()):
    return parts(name, frame.eyes, frame.mouth, frame.ears)


POSES = {
    "idle": Frame(),
    "blink": Frame(eyes="closed"),
    "talking": Frame(mouth="talk2"),
    "listening": Frame(mouth="o", ears="perk"),
    "thinking": Frame(eyes="up", mouth="flat"),
    "sleepy": Frame(eyes="sleepy", mouth="flat", ears="droop"),
    "happy": Frame(eyes="happy", mouth="open_smile"),
}


def run(*parts_):
    """Expand (frame, steps) pairs into a list of 100 ms frames."""
    return [f for f, n in parts_ for _ in range(n)]


F = Frame
LISTEN = dict(mouth="o", ears="perk")
THINK = dict(mouth="flat")
HAPPY = dict(eyes="happy", mouth="open_smile")
SLEEP = dict(mouth="flat", ears="droop")

ANIMS = {
    "idle": run(
        (F(), 4), (F(dy=-1), 4), (F(ears="wiggleL"), 2), (F(ears="wiggleR"), 2), (F(), 2),
        (F(dy=-1), 2), (F(eyes="closed", dy=-1), 1), (F(dy=-1), 2), (F(eyes="left"), 3),
        (F(eyes="right"), 3), (F(), 2)),
    "listening": run(*[(F(fx=f"waves{i % 2}", dy=-(i % 2), **LISTEN), 3) for i in range(4)],
                     (F(eyes="closed", fx="waves0", **LISTEN), 1), (F(fx="waves1", **LISTEN), 3)),
    "thinking": run(
        (F(eyes="up", fx="dots1", **THINK), 4), (F(eyes="left", fx="dots2", dy=-1, **THINK), 4),
        (F(eyes="right", fx="dots3", dy=-1, ears="wiggleR", **THINK), 4),
        (F(eyes="up", fx="dots1", **THINK), 4), (F(eyes="left", fx="dots2", **THINK), 4),
        (F(eyes="up", fx="dots3", ears="wiggleL", **THINK), 4)),
    "talking": run(*[(F(mouth=m, dy=-(i // 4 % 2), eyes="closed" if i == 9 else "open"), 1)
                     for i, m in enumerate(["talk1", "talk2", "talk1", "smile", "talk2", "talk1", "talk2",
                                            "smile", "talk1", "talk2", "o", "talk2", "talk1", "smile",
                                            "talk2", "talk1", "talk2", "talk1", "smile", "smile"])]),
    "happy": run(*[x for _ in range(2) for x in (
        (F(squash=0.15, **HAPPY), 1), (F(squash=-0.1, dy=-3, fx="sparkle0", **HAPPY), 1),
        (F(dy=-5, fx="sparkle1", ears="perk", **HAPPY), 2), (F(dy=-3, fx="sparkle0", **HAPPY), 1),
        (F(squash=0.12, fx="sparkle1", **HAPPY), 1), (F(fx="sparkle0", **HAPPY), 2))]),
    "sleepy": run(
        (F(eyes="sleepy", fx="zzz0", **SLEEP), 3), (F(eyes="sleepy", dy=1, fx="zzz1", **SLEEP), 3),
        (F(eyes="closed", dy=2, fx="zzz2", **SLEEP), 3), (F(eyes="closed", dy=2, fx="zzz3", **SLEEP), 3),
        (F(eyes="closed", dy=1, fx="zzz4", **SLEEP), 3), (F(eyes="sleepy", fx="zzz5", **SLEEP), 3),
        (F(eyes="sleepy", fx="zzz0", **SLEEP), 3), (F(eyes="sleepy", dy=1, fx="zzz1", **SLEEP), 3)),
    "dragged": run(*[(F(eyes="squint", mouth="o", ears="droop" if i % 2 else "perk",
                        squash=(0.14 if i % 2 else -0.12), fx="sweat"), 2) for i in range(6)]),
}

CAPTIONS = {"idle": "just hanging out", "listening": "you tapped, all ears", "thinking": "waiting on the AI",
            "talking": "mouth follows the voice", "happy": "you said thanks", "sleepy": "late at night",
            "dragged": "you're moving it around"}

SPARKLE = ["..X..", "..X..", "XXXXX", "..X..", "..X.."]
DROP = ["..X..", "..X..", ".XXX.", ".XWX.", "XXWXX", "XXXXX", ".XXX."]


def effect(img, fx, x, y, scale):
    """Draw an effect around a sprite whose top-left screen position is (x, y)."""
    d = ImageDraw.Draw(img)
    s = SIZE * scale
    if fx.startswith("zzz"):
        n = int(fx[3:])
        g = base.glyph(base.ZZZ, 3, {"K": (120, 110, 200)})
        img.alpha_composite(g, (x + s - 18, y + 6 - n * 4))
    elif fx.startswith("sparkle"):
        spots = [(-10, 20), (s - 4, 6), (s + 2, s - 60), (-14, s - 70)]
        for i, (sx, sy) in enumerate(spots):
            if (i + int(fx[7:])) % 2 == 0:
                g = base.glyph(SPARKLE, 4, {"X": (255, 200, 80) if i % 2 else (255, 140, 190)})
                img.alpha_composite(g, (x + sx, y + sy))
    elif fx.startswith("dots"):
        for i in range(int(fx[4:])):
            cx, cy = x + s - 30 + i * 18, y + 4 - i * 8
            d.ellipse((cx - 6, cy - 6, cx + 6, cy + 6), fill=(236, 230, 255), outline=base.INK, width=2)
    elif fx.startswith("waves"):
        cx, cy = x + s - 10, y + s // 2
        for i in range(int(fx[5:]) + 1):
            r = 18 + i * 14
            d.arc((cx - r, cy - r, cx + r, cy + r), -45, 45, fill=(150, 130, 220), width=5)
    elif fx == "sweat":
        img.alpha_composite(base.glyph(DROP, 3, {"X": (120, 190, 255), "W": WHITE}), (x + s - 30, y + 8))


def place(img, spr, f, x, y, scale):
    """Composite a sprite at (x, y) with the frame's bob and squash, feet anchored."""
    s = SIZE * scale
    w, h = round(s * (1 + f.squash)), round(s * (1 - f.squash))
    big = spr.resize((w, h), Image.NEAREST)
    img.alpha_composite(big, (x + (s - w) // 2, y + s - h + f.dy * scale))
    if f.fx:
        effect(img, f.fx, x, y + f.dy * scale, scale)


def gif(path):
    scale, cw, ch, gap = 3, 240, 250, 16
    width = len(BUDDIES) * cw + (len(BUDDIES) + 1) * gap
    top = 110
    height = top + ch + 60
    bg = Image.new("RGBA", (width, height), (246, 238, 252, 255))
    d = ImageDraw.Draw(bg)
    label = base.ImageFont.truetype(base.FONT_DIR + "LiberationSans-Bold.ttf", 26)
    title = base.ImageFont.truetype(base.PIXEL_FONT, 48)
    small = base.ImageFont.truetype(base.FONT_DIR + "LiberationSans-Regular.ttf", 22)
    for i, name in enumerate(BUDDIES):
        x = gap + i * (cw + gap)
        d.rounded_rectangle((x, top, x + cw, top + ch), 28, fill=WHITE, outline=(226, 218, 240), width=2)
        base.centered_text(d, x + cw / 2, top + ch + 14, NAMES[name], label)
    frames = []
    for state, steps in ANIMS.items():
        for f in steps:
            img = bg.copy()
            d = ImageDraw.Draw(img)
            base.centered_text(d, width / 2, 14, state, title)
            base.centered_text(d, width / 2, 68, CAPTIONS[state], small, (120, 100, 160))
            for i, name in enumerate(BUDDIES):
                x = gap + i * (cw + gap) + (cw - SIZE * scale) // 2
                place(img, buddy(name, f), f, x, top + 40, scale)
            frames.append(img.convert("RGB"))
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=STEP_MS, loop=0)
    return len(frames)


def sheet(path):
    poses = list(POSES)
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
    base.centered_text(d, width / 2, 98, "Sudo the rubber duck (default) · duckling · cat · frog · bunny · your colour goes on the bubble ring",
                       small, (120, 100, 160))

    def col_x(i):
        return label_w + gap + i * (cw + gap)

    for i, p in enumerate(poses):
        base.centered_text(d, col_x(i) + cw / 2, rows_top - 44, p, label)

    for r, name in enumerate(BUDDIES):
        y = rows_top + r * (ch + gap)
        d.text((gap + 6, y + ch / 2 - 16), NAMES[name], font=label, fill=base.INK)
        if r == 0:
            d.text((gap + 6, y + ch / 2 + 16), "default", font=small, fill=(120, 100, 160))
        for i, p in enumerate(poses):
            x = col_x(i)
            base.card(img, (x, y, x + cw, y + ch))
            spr = buddy(name, POSES[p]).resize((192, 192), Image.NEAREST)
            img.alpha_composite(spr, (x + (cw - spr.width) // 2, y + 10))

    y = colour_top
    for i, variant in enumerate(base.VARIANTS):
        x = col_x(i)
        base.card(img, (x, y, x + cw, y + 260))
        ring = 180
        rx, ry = x + (cw - ring) // 2, y + 16
        ImageDraw.Draw(img).ellipse((rx, ry, rx + ring, ry + ring), fill=WHITE,
                                    outline=base.VARIANTS[variant]["hat"], width=10)
        spr = buddy("rubber", POSES["happy"]).resize((128, 128), Image.NEAREST)
        img.alpha_composite(spr, (rx + (ring - 128) // 2, ry + (ring - 128) // 2))
        base.centered_text(ImageDraw.Draw(img), x + cw / 2, y + 214, variant, label)

    x0, x1 = col_x(5), col_x(6) + cw
    base.card(img, (x0, y, x1, y + 260))
    base.centered_text(ImageDraw.Draw(img), (x0 + x1) / 2, y + 22, "bubble size", label)
    disc, step = 76, (x1 - x0 - 20) // len(BUDDIES)
    for i, name in enumerate(BUDDIES):
        bx = x0 + 10 + i * step + (step - disc) // 2
        by = y + 100
        ImageDraw.Draw(img).ellipse((bx, by, bx + disc, by + disc), fill=WHITE, outline=(200, 190, 235), width=3)
        img.alpha_composite(buddy(name), (bx + (disc - SIZE) // 2, by + (disc - SIZE) // 2 + 2))
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
    print(gif("/home/claw/assistant/outbox/buddies-animated.gif"), "frames")
    if len(sys.argv) > 1:
        icon(sys.argv[1])
