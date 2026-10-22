"""Quantify how far the model-chain river network sits from the physical flow-accumulation valley lines.

The flow field (CellGenerator's erosion discharge) is the independent ground truth for
"where water should run". This script measures the model-chain river pixels' distance to
the nearest high-flow pixel, plus orientation statistics, without any eyeballing.
"""
from pathlib import Path
import sys

import numpy as np
from PIL import Image

ROOT = Path('build/waterview')


def load(zoom=1):
    flow = np.array(Image.open(ROOT / 'flow.png').convert('L')).astype(float)
    view = Image.open(ROOT / 'water_view.png').convert('RGB')
    if view.size[0] != flow.shape[1]:
        # water_view may be nearest-neighbour zoomed; bring it back to 1px==1block
        if view.size[0] % flow.shape[1] != 0:
            sys.exit(f'size mismatch: view={view.size} flow={flow.shape}')
        factor = view.size[0] // flow.shape[1]
        view = view.resize(flow.shape[::-1], Image.NEAREST)
        print(f'water_view downscaled by {factor}x to match flow grid')
    return flow, np.array(view)


def river_mask(view):
    # water_view uses exact colours: river 0xC07020, lake 0x1E78D2, ocean 0x1040A0.
    # Exact match only - a colour RANGE also catches the warm hillshade tones.
    return np.all(view == np.array([0xC0, 0x70, 0x20], dtype=np.uint8), axis=2)


def main():
    flow, view = load()
    river = river_mask(view)
    print('flow map:', flow.shape, 'river pixels:', int(river.sum()))
    if river.sum() == 0:
        sys.exit('no river pixels detected - colour assumption wrong')
    hi = flow >= np.percentile(flow, 99.0)
    print('high-flow pixels (p99+):', int(hi.sum()))
    # Distance from each river pixel to the nearest high-flow pixel (brute force, small map).
    ys, xs = np.nonzero(river)
    hy, hx = np.nonzero(hi)
    if len(hy) == 0:
        sys.exit('no high-flow pixels')
    chunk = 4000
    distances = []
    for start in range(0, len(ys), chunk):
        block_y = ys[start:start + chunk]
        block_x = xs[start:start + chunk]
        diff = (block_y[:, None] - hy[None, :]) ** 2 + (block_x[:, None] - hx[None, :]) ** 2
        distances.append(np.sqrt(diff.min(axis=1)))
    distances = np.concatenate(distances)
    print('river->nearest-high-flow distance (px):')
    for percentile in (50, 75, 90, 95, 99):
        print(f'  p{percentile} = {np.percentile(distances, percentile):.2f}')
    print(f'  max = {distances.max():.2f}  mean = {distances.mean():.2f}')
    within = float(np.mean(distances <= 3.0))
    print(f'  fraction of river pixels within 3px of a high-flow line: {within:.3f}')
    # Orientation of river runs: long axis-aligned runs indicate staircase artefacts.
    for axis, name in ((0, 'rows(x)'), (1, 'cols(z)')):
        matrix = river if axis == 0 else river.T
        best = 0
        for row in matrix:
            run = 0
            for value in row:
                run = run + 1 if value else 0
                best = max(best, run)
        print(f'  longest axis-aligned river run {name} = {best}px')
    # Diagonal runs (the D8 staircase direction).
    best = 0
    for shift in range(-flow.shape[0], flow.shape[1]):
        diagonal = np.diagonal(river, offset=shift)
        run = 0
        for value in diagonal:
            run = run + 1 if value else 0
            best = max(best, run)
    print(f'  longest diagonal river run = {best}px')


if __name__ == '__main__':
    main()
