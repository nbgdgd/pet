# -*- coding: utf-8 -*-
"""Generate RuStore store assets that MATCH the installed app:
   - icon-512.png  : exact replica of the adaptive launcher icon (black bg + "A" flame)
   - ss1..3.png    : 1080x1920 (exactly 9:16) marketing screenshots, no cropping
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = os.path.dirname(os.path.abspath(__file__))
SS_DIR = os.path.join(os.path.dirname(OUT), "screenshots")

ORANGE = (255, 77, 0)
PURPLE = (122, 63, 255)

# ---------- fonts ----------
def font(size, bold=True):
    for name in (("segoeuib.ttf" if bold else "segoeui.ttf"), "arialbd.ttf", "arial.ttf"):
        p = os.path.join("C:/Windows/Fonts", name)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

# ---------- ICON: exact replica of the adaptive launcher icon ----------
def make_icon(size=512):
    SS = 4  # supersample for smooth edges
    S = size * SS
    img = Image.new("RGB", (S, S), (0, 0, 0))  # background = #000000 (matches values/colors.xml)
    d = ImageDraw.Draw(img)
    s = S / 108.0  # vector viewport is 108x108
    def P(pts):
        return [(x * s, y * s) for (x, y) in pts]
    # orange outer "A"
    d.polygon(P([(54,28),(70,80),(60,80),(56,68),(52,68),(48,80),(38,80)]), fill=ORANGE)
    # punch the counter (hole) back to background
    d.polygon(P([(54,46),(50,60),(58,60)]), fill=(0,0,0))
    # purple right-edge overlay (two-tone gradient look)
    d.polygon(P([(54,28),(70,80),(64,80),(54,46)]), fill=PURPLE)
    img = img.resize((size, size), Image.LANCZOS)
    img.save(os.path.join(OUT, "icon-512.png"))
    print("icon-512.png", img.size)

# ---------- backgrounds ----------
def gradient_bg(w, h):
    top = (24, 13, 42)      # dark purple
    bot = (0, 0, 0)
    base = Image.new("RGB", (w, h))
    px = base.load()
    for y in range(h):
        t = y / (h - 1)
        r = int(top[0] * (1 - t) + bot[0] * t)
        g = int(top[1] * (1 - t) + bot[1] * t)
        b = int(top[2] * (1 - t) + bot[2] * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    # soft brand glows
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([w*0.10, h*0.30, w*0.90, h*0.78], fill=(90, 28, 0))      # orange glow
    gd.ellipse([w*0.05, h*0.02, w*0.70, h*0.40], fill=(40, 18, 70))     # purple glow top
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    return Image.blend(base, ImageChops_add(base, glow), 1.0)

def ImageChops_add(a, b):
    from PIL import ImageChops
    return ImageChops.add(a, b)

def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0], img.size[1]], radius=radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out

# ---------- SCREENSHOTS: 1080x1920 (9:16), no cropping ----------
def make_screenshot(src, caption, out_name):
    W, H = 1080, 1920
    bg = gradient_bg(W, H).convert("RGBA")

    shot = Image.open(src).convert("RGB")
    card_h = 1430
    scale = card_h / shot.size[1]
    card_w = int(shot.size[0] * scale)
    shot = shot.resize((card_w, card_h), Image.LANCZOS)
    shot = rounded(shot, 34)

    x = (W - card_w) // 2
    y = 330

    # drop shadow
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle([x-6, y+14, x+card_w+6, y+card_h+22], radius=42, fill=(0, 0, 0, 170))
    shadow = shadow.filter(ImageFilter.GaussianBlur(30))
    bg.alpha_composite(shadow)

    # thin orange frame behind the card
    frame = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([x-3, y-3, x+card_w+3, y+card_h+3],
                                            radius=37, outline=ORANGE + (180,), width=3)
    bg.alpha_composite(frame)
    bg.alpha_composite(shot, (x, y))

    d = ImageDraw.Draw(bg)
    # caption (wrap to <= ~22 chars per line)
    cf = font(60, bold=True)
    lines = wrap(caption, 24)
    cy = 120
    for ln in lines:
        bb = d.textbbox((0, 0), ln, font=cf)
        d.text(((W - (bb[2]-bb[0]))//2, cy), ln, font=cf, fill=(255, 255, 255))
        cy += (bb[3]-bb[1]) + 18

    # AniBlaze wordmark bottom (orange→purple)
    wf = font(50, bold=True)
    word = "AniBlaze"
    bb = d.textbbox((0, 0), word, font=wf)
    ww = bb[2]-bb[0]
    wx = (W - ww)//2
    wy = 1800
    draw_gradient_text(bg, word, wf, wx, wy, ORANGE, PURPLE)

    bg.convert("RGB").save(os.path.join(OUT, out_name))
    print(out_name, bg.size)

def wrap(text, n):
    words = text.split()
    lines, cur = [], ""
    for w in words:
        if len(cur) + len(w) + 1 <= n:
            cur = (cur + " " + w).strip()
        else:
            lines.append(cur); cur = w
    if cur:
        lines.append(cur)
    return lines

def draw_gradient_text(img, text, fnt, x, y, c1, c2):
    bb = fnt.getbbox(text)
    tw, th = bb[2]-bb[0], bb[3]-bb[1]
    mask = Image.new("L", (tw+4, th+12), 0)
    ImageDraw.Draw(mask).text((-bb[0], -bb[1]), text, font=fnt, fill=255)
    grad = Image.new("RGB", (tw+4, th+12))
    gp = grad.load()
    for gx in range(grad.size[0]):
        t = gx / max(1, grad.size[0]-1)
        gp_col = (int(c1[0]*(1-t)+c2[0]*t), int(c1[1]*(1-t)+c2[1]*t), int(c1[2]*(1-t)+c2[2]*t))
        for gy in range(grad.size[1]):
            gp[gx, gy] = gp_col
    img.paste(grad, (x, y), mask)

if __name__ == "__main__":
    make_icon(512)
    make_screenshot(os.path.join(SS_DIR, "screen1_home.jpg"),
                    "Тысячи аниме в одном месте", "ss1.png")
    make_screenshot(os.path.join(SS_DIR, "screen2_favorites.jpg"),
                    "Избранное и история под рукой", "ss2.png")
    make_screenshot(os.path.join(SS_DIR, "screen3_search.jpg"),
                    "Умный поиск и каталог", "ss3.png")
    print("DONE")
