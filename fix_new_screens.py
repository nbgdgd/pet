from PIL import Image, ImageDraw, ImageFont
import os

SRC = r"C:\Users\xuial\Downloads\Telegram Desktop"
DST = r"D:\AndroidProjects\AniBlaze\screenshots"
os.makedirs(DST, exist_ok=True)

files = [
    ("IMG_20260626_152015.jpg", "screen_home.jpg"),
    ("IMG_20260626_151902.jpg", "screen_fav.jpg"),
    ("IMG_20260626_151927.jpg", "screen_search.jpg"),
]

BG      = (12, 11, 18)
SURFACE = (21, 20, 29)
WHITE   = (255, 255, 255)
ORANGE  = (255, 106, 61)
GRAY    = (160, 160, 160)

def load_font(size):
    for p in ["C:/Windows/Fonts/arial.ttf","C:/Windows/Fonts/segoeui.ttf"]:
        try: return ImageFont.truetype(p, size)
        except: pass
    return ImageFont.load_default()

def crop_916(img):
    W, H = img.size
    target_h = int(W * 16 / 9)
    if target_h > H:
        target_w = int(H * 9 / 16)
        left = (W - target_w) // 2
        return img.crop((left, 0, left + target_w, H))
    else:
        top = (H - target_h) // 2
        return img.crop((0, top, W, top + target_h))

def fix_nav(draw, W, H, active_idx):
    sx, sy = W / 472, H / 839
    f = load_font(int(19 * sy))
    nav_y = int(H * 0.915)
    nav_h = H - nav_y
    draw.rectangle([0, nav_y, W, H], fill=SURFACE)
    labels = ["Главная", "Поиск", "Избранное", "История", "Настройки"]
    positions = [0.1, 0.3, 0.5, 0.7, 0.9]
    for i, (label, pos) in enumerate(zip(labels, positions)):
        color = ORANGE if i == active_idx else GRAY
        draw.text((int(pos * W), nav_y + int(10 * sy)), label,
                  font=f, fill=color, anchor="mt")

# ── Screen 1: Home ────────────────────────────────────────────────────────────
img = crop_916(Image.open(f"{SRC}/IMG_20260626_152015.jpg"))
W, H = img.size
draw = ImageDraw.Draw(img)
sy = H / 839.0

# Cover English section header
f_sec = load_font(int(28 * sy))
draw.rectangle([int(15/472*W), int(155*sy), int(340/472*W), int(195*sy)], fill=BG)
draw.text((int(50/472*W), int(158*sy)), "Продолжить просмотр", font=f_sec, fill=WHITE)

# Remove floating purple X button
draw.rectangle([int(355/472*W), int(750*sy), W, int(840*sy)], fill=BG)

fix_nav(draw, W, H, active_idx=0)
img.save(f"{DST}/screen_home.jpg", quality=95)

# ── Screen 2: Favorites ───────────────────────────────────────────────────────
img = crop_916(Image.open(f"{SRC}/IMG_20260626_151902.jpg"))
W, H = img.size
draw = ImageDraw.Draw(img)
sy = H / 839.0

f_hdr = load_font(int(36 * sy))
draw.rectangle([int(15/472*W), int(65*sy), int(280/472*W), int(108*sy)], fill=BG)
draw.text((int(44/472*W), int(68*sy)), "Избранное", font=f_hdr, fill=WHITE)

fix_nav(draw, W, H, active_idx=2)
img.save(f"{DST}/screen_fav.jpg", quality=95)

# ── Screen 3: Search ──────────────────────────────────────────────────────────
img = crop_916(Image.open(f"{SRC}/IMG_20260626_151927.jpg"))
W, H = img.size
draw = ImageDraw.Draw(img)
sy = H / 839.0

f_ph = load_font(int(24 * sy))
draw.rectangle([int(80/472*W), int(42*sy), int(420/472*W), int(80*sy)], fill=(28, 28, 36))
draw.text((int(90/472*W), int(50*sy)), "Поиск аниме...", font=f_ph, fill=(120, 120, 130))

fix_nav(draw, W, H, active_idx=1)
img.save(f"{DST}/screen_search.jpg", quality=95)

print("Done!")
for name in ["screen_home.jpg","screen_fav.jpg","screen_search.jpg"]:
    img = Image.open(f"{DST}/{name}")
    print(f"  {name}: {img.size}")
