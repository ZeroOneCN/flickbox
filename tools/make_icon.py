"""生成启动图标：深色圆角方形 + 白色播放三角 + 上下箭头，输出调色板 PNG 以控制体积。"""
from PIL import Image, ImageDraw
import os

S = 96
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

d.rounded_rectangle([2, 2, S - 3, S - 3], radius=20, fill=(24, 26, 32, 255))
d.rounded_rectangle([2, 2, S - 3, S - 3], radius=20, outline=(70, 74, 88, 255), width=2)

cx = S // 2
d.polygon([(cx, 18), (cx - 11, 33), (cx + 11, 33)], fill=(150, 160, 180, 255))
d.polygon([(cx, 78), (cx - 11, 63), (cx + 11, 63)], fill=(150, 160, 180, 255))

d.polygon([(38, 36), (38, 60), (62, 48)], fill=(245, 246, 250, 255))

out_dir = os.path.join(os.path.dirname(__file__), "..", "res", "drawable")
os.makedirs(out_dir, exist_ok=True)
out = os.path.abspath(os.path.join(out_dir, "ic_launcher.png"))
img.quantize(colors=16, method=Image.Quantize.FASTOCTREE).save(out, optimize=True)
print(out, os.path.getsize(out), "bytes")
