#!/usr/bin/env python3
"""Generate the PalGate launcher icon (rounded dark square + cyan barrier)."""
from PIL import Image, ImageDraw

S = 192
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# rounded dark background
d.rounded_rectangle([8, 8, S - 8, S - 8], radius=36, fill=(13, 27, 38, 255),
                    outline=(79, 195, 247, 255), width=5)

# gate posts
cy = S // 2
d.rounded_rectangle([38, cy - 52, 58, cy + 52], radius=6, fill=(79, 195, 247, 255))
d.rounded_rectangle([S - 58, cy - 52, S - 38, cy + 52], radius=6, fill=(79, 195, 247, 255))

# barrier bars (slightly rotated look via simple offsets)
for i, w in enumerate([14, 0, -14]):
    y = cy - 30 + i * 30
    d.rounded_rectangle([56, y + w // 3, S - 56, y + 12 + w // 3],
                        radius=6, fill=(105, 240, 174, 255) if i == 1 else (230, 241, 255, 255))

out = "/home/z/my-project/scripts/car-app/res/drawable/ic_launcher.png"
img.save(out)
print("wrote", out)
