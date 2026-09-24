from PIL import Image, ImageDraw, ImageFont
import os

SRC = r"C:\Users\xuial\Downloads\Telegram Desktop"
DST = r"D:\AndroidProjects\AniBlaze\screenshots"
os.makedirs(DST, exist_ok=True)

BG = (12, 11, 18)       # OledBlack app background
SURFACE = (21, 20, 29)  # Surface2
WHITE = (255, 255, 255)
ORANGE = (255, 106, 61)
GRAY = (160, 160, 160)

def load_font(size):
    for path in [
        "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/tahoma.ttf",
    ]:
        try:
            return ImageFont.truetype(path, size)
        except:
            pass
    return ImageFont.load_default()

def rect(draw, x, y, w, h, color):
    draw.rectangle([x, y, x+w, y+h], fill=color)

def text(draw, s, x, y, font, color=WHITE, anchor="lt"):
    draw.text((x, y), s, font=font, fill=color, anchor=anchor)

# ── Photo 1: Home screen ──────────────────────────────────────────────────────
img = Image.open(f"{SRC}/photo_1_2026-06-26_16-09-12.jpg")
W, H = img.size
draw = ImageDraw.Draw(img)
sx = W / 591.0  # scale factors based on reference width
sy = H / 1280.0

f_section = load_font(int(34 * sy))
f_nav     = load_font(int(22 * sy))
f_time    = load_font(int(26 * sy))

# Cover "Continue Watching" → "Продолжить просмотр"
cx, cy, cw, ch = int(20*sx), int(185*sy), int(400*sx), int(48*sy)
rect(draw, cx, cy, cw, ch, BG)
text(draw, "Продолжить просмотр", cx, cy+6, f_section)

# Cover purple floating X button (bottom-right area)
bx, by, bw, bh = int(435*sx), int(975*sy), int(120*sx), int(110*sy)
rect(draw, bx, by, bw, bh, BG)

# Cover bottom nav labels → Russian
nav_y = int(1195*sy)
nav_h = int(55*sy)
rect(draw, 0, nav_y, W, nav_h, SURFACE)
labels = ["Главная", "Поиск", "Избранное", "История", "Настройки"]
positions = [0.1, 0.3, 0.5, 0.7, 0.9]
for label, pos in zip(labels, positions):
    nx = int(pos * W)
    color = ORANGE if pos == 0.1 else GRAY
    text(draw, label, nx, nav_y + int(12*sy), f_nav, color=color, anchor="mt")

img.save(f"{DST}/screen1_home.jpg", quality=95)
print("screen1 done", W, H)

# ── Photo 2: Favorites ────────────────────────────────────────────────────────
img = Image.open(f"{SRC}/photo_2_2026-06-26_16-09-12.jpg")
W, H = img.size
draw = ImageDraw.Draw(img)
sx = W / 591.0
sy = H / 1280.0

f_header = load_font(int(44 * sy))
f_nav    = load_font(int(22 * sy))

# Cover "Favorites" header → "Избранное"
rect(draw, int(20*sx), int(85*sy), int(300*sx), int(55*sy), BG)
text(draw, "Избранное", int(54*sx), int(90*sy), f_header)

# Bottom nav
nav_y = int(1195*sy)
nav_h = int(55*sy)
rect(draw, 0, nav_y, W, nav_h, SURFACE)
labels = ["Главная", "Поиск", "Избранное", "История", "Настройки"]
positions = [0.1, 0.3, 0.5, 0.7, 0.9]
for label, pos in zip(labels, positions):
    nx = int(pos * W)
    color = ORANGE if pos == 0.5 else GRAY
    text(draw, label, nx, nav_y + int(12*sy), f_nav, color=color, anchor="mt")

img.save(f"{DST}/screen2_favorites.jpg", quality=95)
print("screen2 done", W, H)

# ── Photo 3: Search ───────────────────────────────────────────────────────────
img = Image.open(f"{SRC}/photo_3_2026-06-26_16-09-12.jpg")
W, H = img.size
draw = ImageDraw.Draw(img)
sx = W / 591.0
sy = H / 1280.0

f_placeholder = load_font(int(28 * sy))
f_nav         = load_font(int(22 * sy))

# Cover "Search anime..." placeholder → "Поиск аниме..."
rect(draw, int(100*sx), int(60*sy), int(380*sx), int(50*sy), (30, 30, 38))
text(draw, "Поиск аниме...", int(108*sx), int(72*sy), f_placeholder, color=(120, 120, 130))

# Bottom nav
nav_y = int(1195*sy)
nav_h = int(55*sy)
rect(draw, 0, nav_y, W, nav_h, SURFACE)
labels = ["Главная", "Поиск", "Избранное", "История", "Настройки"]
positions = [0.1, 0.3, 0.5, 0.7, 0.9]
for label, pos in zip(labels, positions):
    nx = int(pos * W)
    color = ORANGE if pos == 0.3 else GRAY
    text(draw, label, nx, nav_y + int(12*sy), f_nav, color=color, anchor="mt")

img.save(f"{DST}/screen3_search.jpg", quality=95)
print("screen3 done", W, H)

print("\nГотово! Скриншоты в:", DST)
