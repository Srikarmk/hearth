import sys
import numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080
rng = np.random.default_rng(7)
y, x = np.mgrid[0:H, 0:W].astype(np.float32)

base = np.zeros((H, W, 3), np.float32)
base[:] = (9, 7, 7)

def glow(cx, cy, rx, ry, color, power):
    d = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2
    return np.exp(-d)[..., None] ** power * np.array(color, np.float32)

img = base
img += glow(W * 0.30, H * 1.10, W * 0.55, H * 0.55, (64, 20, 6), 1.0)
img += glow(W * 0.72, H * 1.15, W * 0.45, H * 0.45, (52, 15, 5), 1.0)
img += glow(W * 0.50, H * 1.05, W * 0.30, H * 0.22, (60, 26, 8), 1.6)

sparks = np.zeros((H, W), np.float32)
for _ in range(140):
    sx = rng.uniform(0, W)
    sy = H - abs(rng.normal(0, H * 0.32))
    r = rng.uniform(1.0, 2.6)
    b = rng.uniform(0.25, 1.0) * (sy / H) ** 2
    m = ((x - sx) ** 2 + (y - sy) ** 2) < r * r
    sparks[m] = np.maximum(sparks[m], b)
spark_img = Image.fromarray((sparks * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2.2))
s = np.asarray(spark_img, np.float32)[..., None] / 255.0
img += s * np.array((255, 150, 70), np.float32) * 0.6

img += rng.normal(0, 1.6, img.shape).astype(np.float32)
Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(sys.argv[1], quality=90)
