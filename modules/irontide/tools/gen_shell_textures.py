# -*- coding: utf-8 -*-
"""生成 16x16 的壳方块占位贴图（金属板 + 铆钉 + 接缝）。
可反复重跑：只覆盖 block/shell_*.png，不动别的文件。
"""
import random
from pathlib import Path

from PIL import Image

OUT = (Path(__file__).resolve().parents[1]
       / "src" / "main" / "resources" / "assets" / "irontide" / "textures" / "block")
OUT.mkdir(parents=True, exist_ok=True)

# 每种材质的基色（对齐 Create 的调色板）
PALETTE = {
    "andesite": (127, 127, 127),
    "brass": (201, 162, 74),
    "steel": (138, 147, 155),
    "copper": (192, 123, 82),
}

SIZE = 16
SEED = 20261110


def clamp(v):
    return max(0, min(255, int(v)))


def shade(c, f):
    return tuple(clamp(ch * f) for ch in c)


def make(base, seed):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (SIZE, SIZE), base + (255,))
    px = img.load()

    # 噪点：让平面有材质感
    for y in range(SIZE):
        for x in range(SIZE):
            n = rnd.randint(-9, 9)
            r, g, b, a = px[x, y]
            px[x, y] = (clamp(r + n), clamp(g + n), clamp(b + n), a)

    # 内凹接缝：内缩 1px 的深色框
    seam = shade(base, 0.70)
    for i in range(SIZE):
        px[i, 0] = seam + (255,)
        px[i, SIZE - 1] = seam + (255,)
        px[0, i] = seam + (255,)
        px[SIZE - 1, i] = seam + (255,)

    # 高光边：让边框看起来是凸起的
    hi = shade(base, 1.18)
    for i in range(1, SIZE - 1):
        px[i, 1] = hi + (255,)
        px[1, i] = hi + (255,)

    # 四角铆钉
    rivet = shade(base, 0.55)
    rivet_hi = shade(base, 1.35)
    for (cx, cy) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px[cx, cy] = rivet + (255,)
        px[cx + 1, cy] = rivet + (255,)
        px[cx, cy + 1] = rivet + (255,)
        px[cx + 1, cy + 1] = rivet_hi + (255,)

    return img


def main():
    for name, base in PALETTE.items():
        img = make(base, SEED + sum(base))
        path = OUT / f"shell_{name}.png"
        img.save(path)
        print(f"wrote {path.relative_to(OUT.parents[2])} ({path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
