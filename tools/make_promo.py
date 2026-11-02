# -*- coding: utf-8 -*-
"""
宣传图合成：AI 生成的发布会舞台背景 + 真机实测截图 + 中文排版。

用法：
    python tools/make_promo.py
产物（promo/）：
    hero-16x9.png       主视觉横版 1920x1080
    poster-3x4.png      竖版海报 1080x1440
    features-16x9.png   三屏功能图 1920x1080

背景图放在 vibe_images/ 下（前缀 promo-backdrop-*），界面截图全部来自 device-tests/ 的真实 adb 截屏。
挑图只挑画面里没有人脸、没有个人文件名的帧：这些图是要对外发布的。
"""
import glob
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "device-tests")
OUT = os.path.join(ROOT, "promo")

BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
REG = r"C:\Windows\Fonts\msyh.ttc"

WHITE = (255, 255, 255)
DIM = (168, 176, 189)
ACCENT = (98, 226, 197)
VIOLET = (150, 132, 255)


def font(path, size):
    return ImageFont.truetype(path, size)


def backdrop(prefix, w, h):
    """把生成的背景按 cover 方式裁到目标尺寸。"""
    hits = sorted(glob.glob(os.path.join(ROOT, "vibe_images", prefix + "*.png")))
    if not hits:
        raise SystemExit("找不到背景图：%s（先跑 ImageGen 生成到 vibe_images/）" % prefix)
    im = Image.open(hits[-1]).convert("RGB")
    scale = max(w / im.width, h / im.height)
    im = im.resize((int(im.width * scale + 0.5), int(im.height * scale + 0.5)), Image.LANCZOS)
    x = (im.width - w) // 2
    y = (im.height - h) // 2
    return im.crop((x, y, x + w, y + h)).convert("RGBA")


def rounded(mask_size, radius):
    m = Image.new("L", mask_size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, mask_size[0] - 1, mask_size[1] - 1], radius, fill=255)
    return m


def phone(shot, height, bezel=0.030, radius_ratio=0.13):
    """真机截图装进一台手机：黑色边框 + 圆角屏 + 顶部小挖孔。"""
    src = Image.open(os.path.join(SHOTS, shot)).convert("RGB")
    sw = int(height * src.width / src.height)
    screen = src.resize((sw, height), Image.LANCZOS)
    b = max(6, int(height * bezel))
    body_w, body_h = sw + b * 2, height + b * 2
    body_r = int(body_w * radius_ratio)
    screen_r = int(sw * radius_ratio * 0.72)

    out = Image.new("RGBA", (body_w, body_h), (0, 0, 0, 0))
    # 机身：外圈稍亮的金属边，内圈黑框
    ImageDraw.Draw(out).rounded_rectangle([0, 0, body_w - 1, body_h - 1], body_r, fill=(46, 50, 58, 255))
    ImageDraw.Draw(out).rounded_rectangle([2, 2, body_w - 3, body_h - 3], body_r - 2, fill=(12, 13, 16, 255))
    mask = rounded((sw, height), screen_r)
    out.paste(screen, (b, b), mask)
    # 挖孔只画在竖屏上：横屏时机身的左右哪边是「上」不确定，画错就成了坏点
    if sw >= height:
        return out
    d = ImageDraw.Draw(out)
    hole = int(sw * 0.055)
    d.ellipse([body_w // 2 - hole // 2, b + int(height * 0.012),
               body_w // 2 + hole // 2, b + int(height * 0.012) + hole], fill=(6, 6, 8, 255))
    return out


def paste_phone(canvas, ph, cx, top, reflect=0.0, reflect_len=0.22):
    """贴手机，脚下加一团柔光阴影，可选镜面倒影。

    倒影只取机身顶部 reflect_len 比例的截断，完整高度的镜像会一路铺到下面的文案上。
    """
    w, h = ph.size
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.ellipse([cx - int(w * 0.62), top + h - int(h * 0.045),
                cx + int(w * 0.62), top + h + int(h * 0.055)], fill=(0, 0, 0, 190))
    shadow = shadow.filter(ImageFilter.GaussianBlur(26))
    canvas.alpha_composite(shadow)
    if reflect > 0:
        mir = ph.transpose(Image.FLIP_TOP_BOTTOM)
        n = max(24, int(h * reflect_len))
        mir = mir.crop((0, 0, w, min(n, mir.height)))
        fade = Image.new("L", mir.size, 0)
        fd = ImageDraw.Draw(fade)
        for i in range(mir.height):
            fd.line([(0, i), (mir.width, i)], fill=int(255 * reflect * (1 - i / mir.height)))
        mir.putalpha(fade)
        canvas.alpha_composite(mir, (cx - w // 2, top + h))
    canvas.alpha_composite(ph, (cx - w // 2, top))


def text(canvas, xy, s, f, fill=WHITE, anchor=None, spacing=0):
    ImageDraw.Draw(canvas).text(xy, s, font=f, fill=fill, anchor=anchor, spacing=spacing)
    return canvas


def measure(s, f):
    return ImageDraw.Draw(Image.new("RGBA", (8, 8))).textbbox((0, 0), s, font=f, anchor="lt")[2:]


def rule(canvas, x, y, w, color=ACCENT, thick=4):
    ImageDraw.Draw(canvas).rounded_rectangle([x, y, x + w, y + thick], thick // 2, fill=color)


def footnote(canvas, w, h, note):
    text(canvas, (w - 28, h - 26), note, font(REG, 20), fill=(150, 158, 170), anchor="rb")


def chips(canvas, x, y, items, f, gap=14, pad=(20, 12), fg=WHITE, bg=(255, 255, 255, 26)):
    for s in items:
        tw, th = measure(s, f)
        w = tw + pad[0] * 2
        h = th + pad[1] * 2
        ImageDraw.Draw(canvas).rounded_rectangle([x, y, x + w, y + h], h // 2, outline=(255, 255, 255, 70), width=2)
        text(canvas, (x + w // 2, y + h // 2), s, f, fill=fg, anchor="mm")
        x += w + gap
    return x


def hero():
    W, H = 1920, 1080
    c = backdrop("promo-backdrop-wide", W, H)
    ph = phone("n33_single_loop_replay.png", 760)
    paste_phone(c, ph, int(W * 0.665), 96, reflect=0.26)

    x = 132
    text(c, (x, 150), "竖滑播放器  ·  FlickBox", font(REG, 30), fill=DIM)
    rule(c, x, 208, 96)
    big = font(BOLD, 200)
    text(c, (x, 250), "53", big, fill=WHITE)
    w53 = measure("53", big)[0]
    text(c, (x + w53 + 18, 340), "KB", font(BOLD, 74), fill=ACCENT)
    text(c, (x, 480), "一个 APK 的全部体积", font(BOLD, 54), fill=WHITE)
    text(c, (x, 566), "把相册里的视频，上下滑动刷完。", font(REG, 34), fill=DIM)

    lines = [
        "离线运行，清单里没有 INTERNET 权限",
        "零依赖：MediaPlayer + SurfaceView，手写竖向翻页",
        "双击 ±10 秒 · 搜索与四种排序 · 视频信息面板",
        "横竖屏都真全屏，画面只等比、永不裁切",
    ]
    f = font(REG, 30)
    y = 660
    for s in lines:
        ImageDraw.Draw(c).ellipse([x, y + 14, x + 12, y + 26], fill=ACCENT)
        text(c, (x + 30, y), s, f, fill=(226, 230, 236))
        y += 52

    chips(c, x, y + 18, ["minSdk 21", "Android 5.0 起", "无 Gradle 打包", "真机实测通过"], font(REG, 24))
    footnote(c, W, H, "背景由 AI 生成 · 手机内界面为 vivo 真机 adb 实测截图")
    c.convert("RGB").save(os.path.join(OUT, "hero-16x9.png"))


def poster():
    W, H = 1080, 1440
    c = backdrop("promo-backdrop-tall", W, H)
    text(c, (W // 2, 96), "竖滑播放器  ·  FlickBox", font(REG, 28), fill=DIM, anchor="ma")
    text(c, (W // 2, 140), "53 KB", font(BOLD, 128), fill=WHITE, anchor="ma")
    text(c, (W // 2, 352), "装下整个相册的滑动播放器", font(BOLD, 40), fill=ACCENT, anchor="ma")

    ph = phone("n33_single_loop_replay.png", 580)
    paste_phone(c, ph, W // 2, 420, reflect=0.24)

    y = 420 + ph.height + int(ph.height * 0.24) + 40
    f = font(REG, 27)
    for s in ["上下滑动换视频", "点按暂停 · 长按 2 倍速 · 双击 ±10 秒", "不联网 · 零依赖 · 不申请网络权限"]:
        text(c, (W // 2, y), s, f, fill=(226, 230, 236), anchor="ma")
        y += 44
    footnote(c, W, H, "背景由 AI 生成 · 手机内界面为 vivo 真机 adb 实测截图")
    c.convert("RGB").save(os.path.join(OUT, "poster-3x4.png"))


def features():
    W, H = 1920, 1080
    c = backdrop("promo-backdrop-wide", W, H)
    # 背景压暗一点，让三块屏更突出
    ImageDraw.Draw(c).rectangle([0, 0, W, H], fill=(4, 5, 8, 120))
    text(c, (132, 84), "一个 53 KB 的播放器，能做多少事", font(BOLD, 58), fill=WHITE)
    text(c, (132, 176), "全部跑在本地：竖滑播放、横屏真全屏、循环与续播", font(REG, 30), fill=DIM)
    rule(c, 132, 238, 96)

    cards = [
        ("n34_list_loop_wrap.png", "竖滑播放", 520,
         ["上下滑动换片、单击暂停", "长按 2.0x、双击左右半屏 ±10 秒"]),
        ("n24_landscape_playing.png", "横屏真全屏", 250,
         ["横屏占满整块屏，功能区自动收起", "竖屏视频按原比例，不拉伸不裁切"]),
        ("n33_single_loop_replay.png", "循环与续播", 520,
         ["单条 / 列表 / 不循环 三档", "退出再进记得上次位置"]),
    ]
    gap = 60
    rendered = [(phone(shot, h), title, lines) for shot, title, h, lines in cards]
    total_w = sum(r[0].width for r in rendered)
    x = (W - total_w - gap * (len(rendered) - 1)) // 2
    tallest = max(r[0].height for r in rendered)
    top = 270
    mid = top + tallest // 2
    f_title = font(BOLD, 34)
    f_desc = font(REG, 24)
    for p, title, lines in rendered:
        # 三块屏竖横不一，按同一条中线对齐，标题才排得齐
        paste_phone(c, p, x + p.width // 2, mid - p.height // 2, reflect=0.20, reflect_len=0.16)
        cx = x + p.width // 2
        text(c, (cx, top + tallest + 44), title, f_title, fill=WHITE, anchor="ma")
        y = top + tallest + 98
        for s in lines:
            text(c, (cx, y), s, f_desc, fill=DIM, anchor="ma")
            y += 38
        x += p.width + gap
    footnote(c, W, H, "背景由 AI 生成 · 三块屏全部是 vivo V2359A 真机 adb 实测截图")
    c.convert("RGB").save(os.path.join(OUT, "features-16x9.png"))


if __name__ == "__main__":
    if not os.path.isdir(OUT):
        os.mkdir(OUT)
    hero()
    poster()
    features()
    for f in sorted(os.listdir(OUT)):
        p = os.path.join(OUT, f)
        print(f, Image.open(p).size, "%d KB" % (os.path.getsize(p) // 1024))
