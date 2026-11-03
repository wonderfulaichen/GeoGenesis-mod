"""Render P1 production Cell heights using the accepted palette; never synthesize terrain."""
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('accepted_palette', ROOT / 'tools/cmp-render-versions.py')
palette = importlib.util.module_from_spec(spec)
spec.loader.exec_module(palette)


def main():
    out = Path(sys.argv[1]).resolve()
    baseline = ROOT / 'forge-1.20.1-47.4.10-mdk/build/main-verify-restore/out/morph-coastal.f64be'
    candidate = out / 'p1-cell-heights.f64be'
    raw = candidate.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == 'bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965'
    assert hashlib.sha256(baseline.read_bytes()).hexdigest() == '7be0418369d097ce107eeef58b8d02c6776782f1d7fbc6ad6c5e0e5728d7a238'
    a = palette.read_f64(baseline, 1401)
    b = palette.read_f64(candidate, 1401)
    assert np.isfinite(b).all()
    rgb_a, rgb_b = palette.shade(a), palette.shade(b)
    Image.fromarray(rgb_b).save(out / 'p1-cell-height.png')
    palette.panel([rgb_a, rgb_b], ['Accepted original (7BE)', 'P1 production Cell (BEA0), HS=2'], out / 'p1-ab.png')
    delta = b - a
    gain = 1000.0
    diff = np.full((1401, 1401, 3), 245.0)
    strength = np.clip(np.abs(delta) * gain, 0, 220)
    diff[..., 1] -= strength
    diff[..., 2] -= np.where(delta >= 0, strength, 0)
    diff[..., 0] -= np.where(delta < 0, strength, 0)
    Image.fromarray(diff.astype('uint8')).save(out / 'p1-height-diff-x1000.png')
    draw_profiles(a, b, out)
    stats = {
        'path': 'GeoGenesisTerrain.sampleCellCoarse(block) -> CellGenerator.sample(block/HS)',
        'completeChunkCheck': '4 chunks / 1024 cells, actual getChunkCells; see cell.log',
        'seed': '9139912035078620160', 'HS': 2, 'size': 1401,
        'wuOrigin': [-3642.5, -3365.5], 'wuStep': 5,
        'blockOrigin': [-7285, -6731], 'blockStep': 10,
        'externalErosion': False, 'modelHydrology': False,
        'candidateSha256': hashlib.sha256(raw).hexdigest(),
        'p0HeightChanged': 0,
        'relativeToOriginal': {
            'maxAbsY': float(np.abs(delta).max()),
            'p99AbsY': float(np.quantile(np.abs(delta), .99)),
            'seaLandFlips': int(((a < 63) != (b < 63)).sum()),
            'changedShadedPixels': int(np.any(rgb_a != rgb_b, axis=2).sum()),
            'maxChannelDiff': int(np.abs(rgb_a.astype(int) - rgb_b.astype(int)).max())},
        'waterPercent': float((b < 63).mean() * 100),
        'minY': float(b.min()), 'maxY': float(b.max())}
    (out / 'metrics.json').write_text(json.dumps(stats, indent=2), encoding='utf-8')
    print(json.dumps(stats, indent=2))


def draw_profiles(a, b, out):
    canvas = Image.new('RGB', (1500, 990), 'white')
    d = ImageDraw.Draw(canvas)
    d.text((60, 12), 'P1: accepted original (black), production Cell (red); HS=2; no external erosion/hydrology', fill='black')
    for k, row in enumerate([350, 700, 1050]):
        top = 65 + k * 305
        bottom = top + 220
        d.text((65, top - 25), 'Section %d: block Z = %.0f (wu Z = %.1f)' % (k+1, (-3365.5+row*5)*2, -3365.5+row*5), fill='black')
        for y in [0, 63, 150, 250, 320]:
            py = bottom - (y + 64) / 384 * 220
            d.line((65, py, 1465, py), fill=(180, 205, 220) if y == 63 else (225, 225, 225))
            d.text((25, py-5), str(y), fill='black')
        for h, color in [(a[row], 'black'), (b[row], (210, 40, 40))]:
            pts = [(65+i, bottom-(float(v)+64)/384*220) for i, v in enumerate(h)]
            d.line(pts, fill=color, width=1)
        d.text((65, bottom+10), 'X blocks: -7285 ... 6715; max |delta Y| = %.8f blocks' % np.abs(b[row]-a[row]).max(), fill='black')
    canvas.save(out / 'p1-three-profiles.png')


if __name__ == '__main__':
    main()
