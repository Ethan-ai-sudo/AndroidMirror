# -*- coding: utf-8 -*-
"""
从 APPlogo.png 生成 Android 各密度启动器图标。
用法: python generate-icons.py
依赖: Pillow
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGO = os.path.join(ROOT, "APPlogo.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# density -> launcher 图标像素尺寸
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# density -> adaptive icon 前景像素尺寸 (108dp)
FOREGROUND_DENSITIES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}


def circular_mask(size):
    """生成圆形 alpha 遮罩。"""
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def make_launcher_square(logo, size):
    """方形启动器图标：logo 拉伸填满。"""
    return logo.resize((size, size), Image.LANCZOS)


def make_launcher_round(logo, size):
    """圆形启动器图标：logo 填满后做圆形遮罩。"""
    base = logo.resize((size, size), Image.LANCZOS).convert("RGBA")
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), circular_mask(size))
    return out


def make_foreground(logo, size):
    """adaptive icon 前景：logo 缩放到 66% 居中放在透明画布上。"""
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * 0.66)
    scaled = logo.resize((inner, inner), Image.LANCZOS)
    offset = (size - inner) // 2
    fg.paste(scaled, (offset, offset, offset + inner, offset + inner), scaled)
    return fg


def main():
    if not os.path.exists(LOGO):
        raise SystemExit(f"找不到 logo: {LOGO}")
    logo = Image.open(LOGO).convert("RGBA")
    print(f"logo: {logo.size} {logo.mode}")

    deleted = 0
    created = 0
    for folder, size in DENSITIES.items():
        d = os.path.join(RES, folder)
        # 删除旧 webp 同名资源，避免冲突
        for name in ("ic_launcher.webp", "ic_launcher_round.webp"):
            p = os.path.join(d, name)
            if os.path.exists(p):
                os.remove(p)
                deleted += 1

        make_launcher_square(logo, size).save(
            os.path.join(d, "ic_launcher.png"), "PNG"
        )
        make_launcher_round(logo, size).save(
            os.path.join(d, "ic_launcher_round.png"), "PNG"
        )
        created += 2

    for folder, size in FOREGROUND_DENSITIES.items():
        d = os.path.join(RES, folder)
        p = os.path.join(d, "ic_launcher_foreground.webp")
        if os.path.exists(p):
            os.remove(p)
            deleted += 1
        make_foreground(logo, size).save(
            os.path.join(d, "ic_launcher_foreground.png"), "PNG"
        )
        created += 1

    print(f"删除旧 webp: {deleted} 个，生成新 png: {created} 个")


if __name__ == "__main__":
    main()
