"""把生成的三张图（睁眼闭嘴、闭眼、张嘴）做成可以叠着用的帧。

闭眼和张嘴的图是用改图接口出的，整体和原图几乎一样但不是逐像素相同，背景也不透明。
所以只取真正变了的那一块（眼睛、嘴）做成一张透明的补丁，叠在原图上，其余部分永远是原图，切换时不会抖。

用法：python3 scripts/role-frames.py <放原图的目录> public/roles
原图目录里每个角色三张 PNG：<名字>.png（透明底的全身像）、<名字>_blink.png、<名字>_talk.png。
名字和动物的对应关系在下面的 KINDS 里。需要 Pillow。
"""
import sys
from pathlib import Path
from PIL import Image, ImageChops, ImageFilter

src, dst = Path(sys.argv[1]), Path(sys.argv[2])
KINDS = {'zixuan': 'rabbit', 'ache': 'cat', 'xiaoman': 'bear'}
HEIGHT = 720

def patch(base: Image.Image, variant: Image.Image, region: tuple[float, float, float, float]):
    """region 是脸上要找变化的范围，用占整张图的比例表示（左、上、右、下）。"""
    w, h = base.size
    alpha = base.getchannel('A')
    diff = ImageChops.difference(base.convert('RGB'), variant.convert('RGB').resize((w, h))).convert('L')
    # 只看角色身体里面的像素，只看指定的范围。
    inside = alpha.point(lambda a: 255 if a > 250 else 0).filter(ImageFilter.MinFilter(9))
    diff = ImageChops.multiply(diff, inside)
    box = (int(region[0] * w), int(region[1] * h), int(region[2] * w), int(region[3] * h))
    focus = Image.new('L', (w, h), 0); focus.paste(255, box)
    diff = ImageChops.multiply(diff, focus)
    mask = diff.point(lambda v: 255 if v > 34 else 0).filter(ImageFilter.MedianFilter(7))
    bbox = mask.getbbox()
    if not bbox:
        return None, None
    # 把变了的地方连成片、往外扩一点、边缘羽化，补丁和原图的接缝就看不出来。
    mask = mask.filter(ImageFilter.MaxFilter(31)).filter(ImageFilter.GaussianBlur(9))
    layer = variant.convert('RGB').resize((w, h)).convert('RGBA')
    layer.putalpha(ImageChops.multiply(mask, alpha))
    coverage = mask.point(lambda v: 255 if v > 128 else 0).histogram()[255] / (w * h)
    return layer, (bbox, round(coverage, 4))

for role, kind in KINDS.items():
    base = Image.open(src / f'{role}.png').convert('RGBA')
    crop = base.getchannel('A').point(lambda a: 255 if a > 8 else 0).getbbox()
    scale = HEIGHT / (crop[3] - crop[1])
    size = (round((crop[2] - crop[0]) * scale), HEIGHT)
    def export(image, name):
        image.crop(crop).resize(size, Image.LANCZOS).save(dst / name, quality=88, method=6)
        return (dst / name).stat().st_size
    print(kind, 'base', size, export(base, f'{kind}.webp'))
    # 眼睛在脸的上半部，嘴在下半部：分开找，互不干扰。
    for variant, region in (('blink', (0.15, 0.22, 0.85, 0.52)), ('talk', (0.3, 0.42, 0.7, 0.62))):
        path = src / f'{role}_{variant}.png'
        if not path.exists():
            print(kind, variant, 'missing'); continue
        layer, info = patch(base, Image.open(path), region)
        if layer is None:
            print(kind, variant, 'no change found'); continue
        print(kind, variant, info, export(layer, f'{kind}-{variant}.webp'))
    # 头像：上半身的方形。
    w, h = crop[2] - crop[0], crop[3] - crop[1]
    side = round(h * 0.62)
    left = crop[0] + (w - side) // 2
    base.crop((left, crop[1], left + side, crop[1] + side)).resize((256, 256), Image.LANCZOS).save(dst / f'{kind}-face.webp', quality=88, method=6)
