"""Read-only inspection of the canonical figure: channel orientation, straightness, and crops."""
from pathlib import Path
import sys

from PIL import Image
import numpy as np

SRC = Path(sys.argv[1] if len(sys.argv) > 1 else
           'build/hydro-reassessment/canonical-provider-current.png')
OUT = SRC.parent / 'inspect'
OUT.mkdir(exist_ok=True)

RIVER = (0x00, 0xE5, 0xD0)
LAKE = (0x4A, 0x90, 0xE2)
SEA = (0x1F, 0x4E, 0x79)

img = np.array(Image.open(SRC).convert('RGB'))
n = img.shape[0]
river = np.all(img == RIVER, axis=2)
lake = np.all(img == LAKE, axis=2)
sea = np.all(img == SEA, axis=2)
print(f'size={n}x{img.shape[1]} river={river.sum()} lake={lake.sum()} sea={sea.sum()}')

# Orientation of every river pixel against its 8 neighbours: a raster river should mix directions.
counts = {}
for dx, dz in ((0, 1), (1, 0), (1, 1), (1, -1)):
    a = river[:n - max(dx, 0), :n - max(dz, 0)] if dz >= 0 else river[abs(dz):, :n - dx]
    if dz >= 0:
        b = river[dx:, dz:]
    else:
        b = river[:n - abs(dz), dx:]
    counts[(dx, dz)] = int(np.sum(a & b))
total = sum(counts.values()) or 1
print('adjacent river pairs by direction:')
for key, value in counts.items():
    print(f'  d{key} = {value} ({100.0 * value / total:.1f}%)')
best = max(counts, key=counts.get)
print(f'dominant orientation d{best} share={100.0 * counts[best] / total:.1f}%')

# Straight runs: longest horizontal/vertical/diagonal run of river pixels.
def longest_run(mask, axis):
    best = 0
    for row in (mask if axis == 0 else mask.T):
        run = 0
        for value in row:
            run = run + 1 if value else 0
            best = max(best, run)
    return best

print('longest axis-aligned river runs: rows=%d cols=%d' % (longest_run(river, 0), longest_run(river, 1)))

for name, box in (('zoom-southwest', (0, 900, 480, 1401)),
                  ('zoom-parallel', (40, 880, 300, 1120)),
                  ('zoom-coast', (600, 620, 980, 1000))):
    left, top, right, bottom = box
    crop = Image.open(SRC).convert('RGB').crop(box)
    scale = max(1, int(720 / max(1, right - left)))
    crop = crop.resize(((right - left) * scale, (bottom - top) * scale), Image.NEAREST)
    path = OUT / f'canonical-{name}.png'
    crop.save(path)
    print(f'{path} {path.stat().st_size} bytes scale={scale}')
