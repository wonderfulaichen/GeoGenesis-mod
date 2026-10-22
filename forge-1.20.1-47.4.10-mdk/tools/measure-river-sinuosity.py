"""Measure how natural the river paths look: straightness, parallel runs, and tributary joins.

Straight parallel lines and comb-like spacing are the two defects the user pointed at.
This quantifies them so a fix can be judged instead of eyeballed.
"""
from pathlib import Path
import sys

import numpy as np
from PIL import Image

RIVER = np.array([0x00, 0xE5, 0xD0], dtype=np.uint8)
LAKE = np.array([0x4A, 0x90, 0xE2], dtype=np.uint8)
SEA = np.array([0x1F, 0x4E, 0x79], dtype=np.uint8)


def load(path):
    return np.array(Image.open(path).convert('RGB'))


def longest_run(mask, axis):
    best = 0
    rows = mask if axis == 0 else mask.T
    for row in rows:
        run = 0
        for value in row:
            run = run + 1 if value else 0
            if run > best:
                best = run
    return best


def report(name, path):
    img = load(path)
    river = np.all(img == RIVER, axis=2)
    lake = np.all(img == LAKE, axis=2)
    sea = np.all(img == SEA, axis=2)
    total = river.sum()
    print(f'== {name} ==')
    print(f'  river={total} lake={lake.sum()} sea={sea.sum()}')
    if total == 0:
        return
    # Orientation census of adjacent river pixel pairs.
    pairs = {}
    for dy, dx in ((0, 1), (1, 0), (1, 1), (1, -1)):
        a = river[:river.shape[0] - dy, :river.shape[1] - dx] if dx >= 0 else river[:river.shape[0] - dy, -dx:]
        b = river[dy:, dx:] if dx >= 0 else river[dy:, :river.shape[1] + dx]
        pairs[(dy, dx)] = int(np.sum(a & b))
    tot = sum(pairs.values()) or 1
    for key, value in sorted(pairs.items()):
        print(f'  adjacent d{key} = {value} ({100.0 * value / tot:.1f}%)')
    print(f'  longest straight run: rows={longest_run(river, 0)}px cols={longest_run(river, 1)}px')
    # Comb test: count river pixels touching the sea, and how many distinct columns they occupy.
    # A comb has many evenly spaced, isolated contact points.
    sea_touch = np.zeros_like(river)
    for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0)):
        shifted = np.roll(np.roll(sea, dy, axis=0), dx, axis=1)
        sea_touch |= shifted
    contact = river & sea_touch
    cols = np.unique(np.nonzero(contact)[1])
    print(f'  river-sea contact pixels={int(contact.sum())} distinct columns={len(cols)}')
    if len(cols) > 1:
        gaps = np.diff(np.sort(cols))
        print(f'  contact column spacing: mean={gaps.mean():.1f} stdev={gaps.std():.1f}')
    # Tributary test: river pixels having 3+ river neighbours indicate branching/joins.
    neigh = np.zeros_like(river, dtype=int)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dy == 0 and dx == 0:
                continue
            neigh += np.roll(np.roll(river, dy, axis=0), dx, axis=1).astype(int)
    junction = river & (neigh >= 3)
    print(f'  junction pixels (3+ river neighbours)={int(junction.sum())} '
          f'({100.0 * junction.sum() / total:.2f}% of river)')


if __name__ == '__main__':
    paths = sys.argv[1:]
    if not paths:
        sys.exit('usage: measure-river-sinuosity.py <png> [<png> ...]')
    for path in paths:
        report(Path(path).stem, path)
