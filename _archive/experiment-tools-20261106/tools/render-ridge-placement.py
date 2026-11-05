"""Render control/A/B from real Morph evaluation and the shared RidgeValley point operator."""
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('palette', ROOT / 'tools/cmp-render-versions.py')
palette = importlib.util.module_from_spec(spec)
spec.loader.exec_module(palette)
NAMES = ['control', 'recipe', 'post']
LABELS = ['Control: current base', 'A-proxy: bias shell', 'B: post morph']


def profiles(arrays, step, out):
    n = arrays[0].shape[0]
    canvas = Image.new('RGB', (1500, 1000), 'white')
    d = ImageDraw.Draw(canvas)
    d.text((60, 8), 'Control=black; A=red; B=blue. Identical filter settings, HS=2; no particle/hydrology.', fill='black')
    for k, row in enumerate([n//4, n//2, 3*n//4]):
        top, bottom = 65+k*310, 285+k*310
        d.text((65, top-24), 'Section row=%d; distance=%.0f wu / %.0f MC blocks' % (row, (n-1)*step, (n-1)*step*2), fill='black')
        for y in [0, 63, 150, 250, 320]:
            py = bottom-(y+64)/384*220
            d.line((65, py, 1465, py), fill=(180, 205, 220) if y == 63 else (225, 225, 225))
            d.text((25, py-5), str(y), fill='black')
        for a, color in zip(arrays, ['black', (200, 40, 40), (30, 70, 220)]):
            d.line([(65+i/(n-1)*1400, bottom-(float(v)+64)/384*220) for i, v in enumerate(a[row])], fill=color)
        d.text((65, bottom+10), 'max |A-control| %.4f; |B-control| %.4f; |A-B| %.4f blocks' % (
            np.abs(arrays[1][row]-arrays[0][row]).max(), np.abs(arrays[2][row]-arrays[0][row]).max(),
            np.abs(arrays[1][row]-arrays[2][row]).max()), fill='black')
    canvas.save(out)


def metrics(base, a):
    diff = a-base
    return {'changed': int((diff != 0).sum()), 'minDeltaY': float(diff.min()), 'maxDeltaY': float(diff.max()),
            'meanAbsDeltaY': float(np.abs(diff).mean()), 'p99AbsDeltaY': float(np.quantile(np.abs(diff), .99)),
            'seaLandFlips': int(((a < 63) != (base < 63)).sum()), 'minY': float(a.min()), 'maxY': float(a.max())}


def render(folder):
    window = json.loads((folder / 'window.json').read_text())
    n, step = window['size'], window['stepWu']
    arrays = [palette.read_f64(folder / (name+'.f64be'), n) for name in NAMES]
    if not all(np.isfinite(a).all() for a in arrays):
        raise ValueError('nonfinite terrain')
    # Y is already MC-mapped height; horizontal physical distance is stepWu * HS.
    # Divide both Y and X by HS for wu-equivalent slopes, not Y over stepWu alone.
    palette.WINDOW['step'] = step * window['hs']
    images = [palette.shade(a) for a in arrays]
    for name, image in zip(NAMES, images):
        Image.fromarray(image).save(folder / (name+'.png'))
    palette.panel(images, LABELS, folder / 'comparison.png')
    profiles(arrays, step, folder / 'three-profiles.png')
    stats = {name: metrics(arrays[0], a) for name, a in zip(NAMES, arrays)}
    stats['A-vs-B'] = metrics(arrays[2], arrays[1])
    stats['sha256'] = {name: hashlib.sha256((folder/(name+'.f64be')).read_bytes()).hexdigest() for name in NAMES}
    stats['window'] = window
    delta_max = max(float(np.abs(arrays[1]-arrays[0]).max()), float(np.abs(arrays[2]-arrays[0]).max()), 1e-9)
    for name, a in zip(NAMES[1:], arrays[1:]):
        v = np.clip((a-arrays[0])/delta_max, -1, 1)
        rgb = np.full((n, n, 3), 245.0)
        rgb[..., 1] -= np.abs(v)*220
        rgb[..., 2] -= np.maximum(v, 0)*220
        rgb[..., 0] -= np.maximum(-v, 0)*220
        Image.fromarray(rgb.astype('uint8')).save(folder / (name+'-delta.png'))
    stats['deltaColorScaleBlocks'] = delta_max
    (folder/'metrics.json').write_text(json.dumps(stats, indent=2), encoding='utf-8')
    print(folder.name, json.dumps(stats, indent=2))
    return arrays, images


def main():
    root = Path(sys.argv[1])
    if len(sys.argv) > 2 and sys.argv[2] == 'small':
        for folder in sorted(root.iterdir()):
            if (folder/'window.json').exists():
                render(folder)
        return
    wide, images = render(root/'wide')
    assert hashlib.sha256((root/'wide/control.f64be').read_bytes()).hexdigest() == 'bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965'
    render(root/'local')
    # Same native samples, no resampling or new terrain: preserve attribution of large differences.
    change = np.abs(wide[1]-wide[2])
    j, i = np.unravel_index(np.argmax(change), change.shape)
    x0, z0 = max(0, min(1000, i-200)), max(0, min(1000, j-200))
    palette.panel([im[z0:z0+401, x0:x0+401] for im in images], LABELS, root/'largest-placement-difference.png')
    (root/'largest-placement-difference.json').write_text(json.dumps({'pixelOrigin':[x0,z0], 'size':401,
        'wuOrigin':[-3642.5+x0*5,-3365.5+z0*5], 'stepWu':5, 'selection':'maximum absolute A-B in wide image'},indent=2),encoding='utf-8')


if __name__ == '__main__':
    main()
