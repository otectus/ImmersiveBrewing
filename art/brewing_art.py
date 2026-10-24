"""Immersive Brewing pixel art: every texture is drawn here from code (original work, GPL-3.0 with the mod).

Vanilla-compatible 16x16 pixel art in gray stone, warm wood, iron, oxidised copper and clear glass (spec 24.1).
Deterministic: the same code always yields the same pixels, so tools/generate_textures.py --check can compare bytes.
"""
from PIL import Image

# ---- palette ----
IRON = [(46, 47, 51), (62, 64, 69), (82, 84, 90), (104, 106, 112), (128, 130, 136)]
COPPER = [(110, 52, 30), (150, 74, 40), (184, 107, 58), (214, 140, 82), (236, 176, 120)]
PATINA = [(62, 124, 108), (84, 152, 132), (112, 180, 160)]
BRICK = [(96, 42, 34), (128, 58, 44), (150, 74, 58), (172, 96, 78)]
MORTAR = [(150, 146, 138), (170, 166, 158)]
STONE = [(88, 88, 92), (108, 108, 112), (128, 128, 132), (148, 148, 152), (168, 168, 172)]
WOOD = [(84, 56, 32), (110, 76, 44), (136, 98, 58), (162, 120, 74), (184, 142, 92)]
GLASS = [(200, 230, 240, 90), (220, 244, 250, 120), (255, 255, 255, 170)]
CLOTH = [(120, 96, 64), (148, 120, 82), (176, 146, 104), (204, 176, 130)]
WAX = [(140, 28, 32), (180, 44, 44), (214, 80, 70)]
PAPER = [(214, 204, 176), (232, 224, 200), (246, 240, 222)]
LEATHER = [(84, 46, 28), (108, 62, 36), (134, 82, 48)]
SALT = [(120, 120, 124), (150, 150, 154), (182, 182, 186), (214, 214, 218)]
FIRE = [(180, 60, 20), (232, 110, 30), (255, 180, 60), (255, 230, 140)]
OUTLINE = (24, 22, 20)


def hash2(x, y, seed):
    h = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    return (h ^ (h >> 16)) & 0xFFFF


def blank(w=16, h=16):
    return Image.new('RGBA', (w, h), (0, 0, 0, 0))


def put(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), c if len(c) == 4 else (*c, 255))


def noise_fill(img, palette, seed, x0=0, y0=0, x1=None, y1=None, bias=0):
    x1 = img.width if x1 is None else x1
    y1 = img.height if y1 is None else y1
    for y in range(y0, y1):
        for x in range(x0, x1):
            i = (hash2(x, y, seed) % (len(palette) + 1)) + bias
            put(img, x, y, palette[max(0, min(len(palette) - 1, i - 1 if i > 0 else 0))])


def rect(img, x0, y0, x1, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(img, x, y, c)


# ---- blocks ----
def kettle_iron():
    img = blank()
    noise_fill(img, IRON[1:4], 11)
    for y in (0, 5, 10, 15):
        for x in range(16):
            put(img, x, y, IRON[0])
    for x, y in ((2, 2), (13, 2), (2, 7), (13, 7), (2, 12), (13, 12)):
        put(img, x, y, IRON[4])
        put(img, x + 1, y + 1, IRON[0])
    return img


def copper_sheet(seed):
    img = blank()
    rect(img, 0, 0, 16, 16, COPPER[2])
    for i in range(16):
        x, y = hash2(i, 0, seed) % 16, hash2(i, 1, seed) % 16
        w = 2 + hash2(i, 2, seed) % 3
        c = COPPER[1 if i % 3 == 0 else 3]
        # Wrap material clusters at tile boundaries; no random one-pixel glitter.
        for dy in range(2):
            for dx in range(w):
                put(img, (x + dx) % 16, (y + dy) % 16, c)
    return img


def kettle_copper():
    img = copper_sheet(12)
    # Small connected patches of oxidation, rather than isolated green specks.
    for x, y in ((2, 10), (3, 11), (11, 6)):
        rect(img, x, y, x + 2, y + 2, PATINA[0])
        put(img, x, y, PATINA[1])
    for x in range(16):
        put(img, x, 0, COPPER[4])
        put(img, x, 15, COPPER[0])
    return img


def kettle_burner():
    img = blank()
    for y in range(16):
        row = y // 4
        offset = 4 if row % 2 else 0
        for x in range(16):
            if y % 4 == 3 or (x + offset) % 8 == 7:
                put(img, x, y, MORTAR[hash2(x, y, 3) % 2])
            else:
                put(img, x, y, BRICK[hash2(x, y, 4) % 4])
    return img


def kettle_vent():
    img = kettle_burner()
    rect(img, 3, 5, 13, 13, IRON[0])
    for y in range(6, 12):
        for x in range(4, 12):
            put(img, x, y, FIRE[hash2(x, y, 9) % 4] if y > 8 else IRON[1])
    for x in range(4, 12, 2):
        for y in range(6, 12):
            put(img, x, y, IRON[2])
    return img


def planks(seed):
    img = blank()
    for y in range(16):
        band = y // 4
        for x in range(16):
            c = WOOD[1 + (hash2(x, band, seed) + hash2(x // 3, y, seed + 1)) % 3]
            put(img, x, y, c)
        if y % 4 == 3:
            for x in range(16):
                put(img, x, y, WOOD[0])
    for band in range(4):
        x = hash2(band, 0, seed + 2) % 16
        put(img, x, band * 4 + 1, WOOD[0])
    return img


def kettle_rack():
    return planks(21)


def mortar_stone():
    img = blank()
    rect(img, 0, 0, 16, 16, STONE[2])
    for i in range(18):
        x, y = hash2(i, 0, 31) % 16, hash2(i, 1, 31) % 16
        for dy in range(2):
            for dx in range(2 + i % 2):
                put(img, (x + dx) % 16, (y + dy) % 16, STONE[1 if i % 3 == 0 else 3])
    return img


def mortar_wood():
    return planks(41)


def mortar_pestle():
    img = blank()
    noise_fill(img, [(196, 188, 170), (214, 206, 188), (226, 220, 204)], 51)
    for y in range(16):
        put(img, 0, y, (150, 142, 126))
        put(img, 15, y, (150, 142, 126))
    return img


def alembic_copper():
    img = copper_sheet(61)
    # A restrained highlight along the left of the curved copper body.
    for y in range(16):
        put(img, 3, y, COPPER[4])
        put(img, 4, y, COPPER[3])
        put(img, 12, y, COPPER[1])
    return img


def alembic_brick():
    img = kettle_burner()
    return img


def alembic_glass():
    img = blank()
    for x in range(16):
        put(img, x, 0, (150, 170, 180, 255))
        put(img, x, 15, (150, 170, 180, 255))
        put(img, 0, x, (150, 170, 180, 255))
        put(img, 15, x, (150, 170, 180, 255))
    for y in range(1, 15):
        for x in range(1, 15):
            put(img, x, y, GLASS[0])
    for i in range(2, 7):
        put(img, i, i - 1, GLASS[2])
    put(img, 3, 1, GLASS[2])
    return img


# ---- items ----
def paddle(blade, handle, seed):
    img = blank()
    # handle: diagonal from bottom-left
    for i in range(9):
        x, y = 2 + i, 13 - i
        put(img, x, y, handle[1])
        put(img, x, y - 1, handle[2])
        put(img, x, y + 1, handle[0])
    # blade: top-right oval
    for y in range(1, 7):
        for x in range(9, 15):
            dx, dy = x - 11.5, y - 3.5
            if dx * dx / 9 + dy * dy / 7 <= 1.05:
                shade = len(blade) - 1 if x + y < 15 else max(1, len(blade) - 2) if x + y < 18 else 1
                put(img, x, y, blade[shade])
    for y in range(1, 7):
        for x in range(9, 15):
            dx, dy = x - 11.5, y - 3.5
            inside = dx * dx / 9 + dy * dy / 7 <= 1.05
            if inside and any(not (((x + ox) - 11.5) ** 2 / 9 + ((y + oy) - 3.5) ** 2 / 7 <= 1.05)
                              for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                put(img, x, y, blade[0])
    return img


def wooden_brewing_paddle():
    return paddle(WOOD, WOOD[0:3], 71)


def iron_brewing_paddle():
    return paddle(IRON[1:], WOOD[0:3], 72)


def prepared_reagent_kit():
    img = blank()
    # A closed, tied silhouette with room around every edge of the item tile.
    rows = {2: (6, 9), 3: (5, 10), 4: (6, 9), 5: (5, 10), 6: (4, 11),
            7: (3, 12), 8: (2, 13), 9: (1, 14), 10: (1, 14), 11: (1, 14),
            12: (2, 13), 13: (2, 13), 14: (3, 12)}
    for y, (left, right) in rows.items():
        for x in range(left, right + 1):
            edge = x in (left, right) or y == 14
            shade = 3 if x < 6 and y < 12 else 1 if x > 10 or y > 11 else 2
            put(img, x, y, CLOTH[0 if edge else shade])
    rect(img, 5, 5, 11, 6, WAX[0])
    put(img, 6, 5, WAX[2])
    put(img, 10, 6, WAX[1])
    rect(img, 8, 8, 12, 12, PAPER[1])
    rect(img, 8, 8, 12, 9, PAPER[2])
    put(img, 9, 10, CLOTH[0])
    put(img, 10, 11, CLOTH[0])
    return img


def recovery_batch():
    img = blank()
    for x, y in ((4, 2), (7, 1), (10, 2)):
        rect(img, x, y, x + 2, 5, (144, 180, 194))
        put(img, x, y, (220, 240, 250))
    rect(img, 1, 4, 15, 15, WOOD[0])
    rect(img, 2, 5, 14, 14, WOOD[2])
    for y in (5, 10):
        rect(img, 2, y, 14, y + 1, WOOD[3])
    rect(img, 2, 9, 14, 10, WOOD[1])
    rect(img, 13, 5, 14, 14, WOOD[1])
    rect(img, 6, 7, 10, 11, WAX[0])
    rect(img, 6, 7, 9, 10, WAX[1])
    put(img, 6, 7, WAX[2])
    return img


def batch_carrier():
    img = blank()
    for x in (2, 6, 10):
        rect(img, x + 1, 2, x + 2, 4, (144, 180, 194))
        put(img, x + 1, 2, (220, 240, 250))
        rect(img, x, 4, x + 3, 10, (66, 91, 150))
        rect(img, x, 5, x + 2, 9, (110, 140, 220))
        put(img, x, 4, (200, 226, 240))
        put(img, x, 5, (180, 200, 240))
    rect(img, 1, 9, 15, 15, WOOD[0])
    rect(img, 2, 10, 14, 14, WOOD[2])
    rect(img, 2, 10, 14, 11, WOOD[4])
    rect(img, 2, 13, 14, 14, WOOD[1])
    for x in (6, 10):
        rect(img, x, 11, x + 1, 14, WOOD[1])
    rect(img, 1, 1, 15, 2, WOOD[3])
    for y in range(2, 10):
        put(img, 1, y, WOOD[2])
        put(img, 14, y, WOOD[0])
    put(img, 1, 1, WOOD[1])
    put(img, 14, 1, WOOD[1])
    return img


def batch_carrier_strapped():
    """The carrier with a stitched leather carrying strap over its handle (Immersive Tanning recipe; presentation only)."""
    img = batch_carrier()
    rect(img, 1, 0, 15, 2, LEATHER[1])
    for x in range(1, 15):
        put(img, x, 0, LEATHER[2])
    for x in range(2, 14, 3):
        put(img, x, 1, CLOTH[3])
    for y in range(2, 9):
        put(img, 1, y, LEATHER[0])
        put(img, 14, y, LEATHER[0])
    return img


def brewers_notebook():
    img = blank()
    rect(img, 2, 1, 14, 15, LEATHER[0])
    rect(img, 3, 2, 13, 14, LEATHER[1])
    rect(img, 3, 2, 12, 3, LEATHER[2])
    rect(img, 3, 3, 4, 13, LEATHER[2])
    rect(img, 12, 3, 14, 13, PAPER[0])
    rect(img, 12, 3, 13, 12, PAPER[2])
    for y in (6, 10):
        put(img, 13, y, PAPER[1])
    # Inlaid flask: the glass and liquid share the other bottle items' light direction.
    rect(img, 7, 4, 9, 6, (144, 180, 194))
    put(img, 7, 4, (220, 240, 250))
    for y in range(6, 11):
        w = min(y - 5, 3)
        for x in range(8 - w, 8 + w):
            put(img, x, y, (90, 140, 200) if x >= 9 or y == 10 else (120, 180, 230))
    rect(img, 5, 11, 11, 12, (66, 91, 150))
    put(img, 6, 7, (200, 226, 240))
    return img


def spent_alchemical_salt():
    img = blank()
    # Irregular crystal heap, fully contained in the tile; broad facets survive distance mipmaps.
    rows = {6: (8, 9), 7: (5, 10), 8: (4, 11), 9: (4, 12), 10: (3, 13),
            11: (2, 13), 12: (1, 14), 13: (2, 13), 14: (4, 11)}
    for y, (left, right) in rows.items():
        for x in range(left, right + 1):
            edge = x in (left, right) or y == 14
            shade = 3 if (x + y // 2) % 5 < 2 and y < 12 else 2 if x < 11 and y < 13 else 1
            put(img, x, y, SALT[0 if edge else shade])
    for x, y in ((8, 6), (5, 7), (4, 9), (2, 12)):
        put(img, x, y, SALT[3])
    return img


# ---- logo ----
def logo():
    small = blank(32, 32)
    # kettle silhouette
    for y in range(12, 28):
        for x in range(6, 26):
            put(small, x, y, IRON[1 + hash2(x, y, 101) % 3])
    for x in range(5, 27):
        put(small, x, 11, COPPER[3])
        put(small, x, 12, COPPER[2])
    for y in range(13, 17):
        for x in range(8, 24):
            put(small, x, y, (90, 120, 220, 255) if y > 13 else (230, 240, 255, 255))
    for x in range(8, 24, 3):
        put(small, x, 26, FIRE[2])
        put(small, x + 1, 27, FIRE[1])
    for y in range(28, 31):
        for x in range(4, 28):
            put(small, x, y, BRICK[hash2(x, y, 102) % 4])
    for i in range(5):
        x = 10 + i * 3
        put(small, x, 8 - i % 2, (240, 240, 240, 160))
        put(small, x + 1, 6 - i % 3, (240, 240, 240, 120))
    return small.resize((128, 128), Image.NEAREST)


BLOCKS = {
    'kettle_iron': kettle_iron, 'kettle_copper': kettle_copper, 'kettle_burner': kettle_burner, 'kettle_vent': kettle_vent,
    'kettle_rack': kettle_rack, 'mortar_stone': mortar_stone, 'mortar_wood': mortar_wood, 'mortar_pestle': mortar_pestle,
    'alembic_copper': alembic_copper, 'alembic_brick': alembic_brick, 'alembic_glass': alembic_glass,
}
ITEMS = {
    'wooden_brewing_paddle': wooden_brewing_paddle, 'iron_brewing_paddle': iron_brewing_paddle,
    'prepared_reagent_kit': prepared_reagent_kit, 'recovery_batch': recovery_batch, 'batch_carrier': batch_carrier,
    'batch_carrier_strapped': batch_carrier_strapped,
    'brewers_notebook': brewers_notebook, 'spent_alchemical_salt': spent_alchemical_salt,
}


def textures():
    out = {}
    for name, fn in BLOCKS.items():
        out[f'assets/immersive_brewing/textures/block/{name}.png'] = fn()
    for name, fn in ITEMS.items():
        out[f'assets/immersive_brewing/textures/item/{name}.png'] = fn()
    out['logo.png'] = logo()
    return out
