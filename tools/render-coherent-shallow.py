#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Read-only S2 labels: python tools/render-coherent-shallow.py <root> <out>.

Java exports meta.json, before.u8, after.u8, changed.u8, sites.csv and optional
shared cells.i64be. Geometry matching belongs to Java, not this renderer.
No generation, internal-edge suppression (S3), transition weights or heights.
"""
import csv
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROI = (0, 0, 620, 660)
TITLES = ('前C成片600（宏1200）', '后S2浅海匹配（细胞600／宏1200不变）')
OUTPUTS = ('pair-types-full.png', 'pair-types-local.png', 'native-before.png',
           'native-after.png', 'changed-mask.png', 'changed-mask-local.png', 'metrics.json')


def need(condition, message):
    if not condition:
        raise ValueError(message)


def load_layout():
    path = Path(__file__).with_name('render-coherent-layout.py')
    spec = importlib.util.spec_from_file_location('gg_coherent_shallow_layout', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_sites(raw):
    reader = csv.DictReader(raw.decode('utf-8-sig').splitlines())
    fields = ('cell', 'cellX', 'cellZ', 'before', 'after', 'distance')
    need(set(fields).issubset(reader.fieldnames or []), 'sites.csv: missing required columns')
    sites = {}
    for row in reader:
        site = {k: int(row[k]) for k in fields[:-1]}
        distance = float(row['distance'])
        need(not np.isnan(distance) and distance >= 0, 'sites.csv: invalid distance')
        cell, x, z, before, after = (site[k] for k in fields[:-1])
        need(-(1 << 31) <= x < (1 << 31) and -(1 << 31) <= z < (1 << 31),
             'sites.csv: coordinates outside int32')
        need(cell == ((x << 32) ^ (z & 0xffffffff)) and cell not in sites,
             'sites.csv: duplicate / incorrectly packed cell')
        need(0 <= before <= 22 and 0 <= after <= 22, 'sites.csv: recipe outside 0..22')
        need(before == after or (before >= 18 and after == 22), 'sites.csv: non-S2 change')
        sites[cell] = site
    need(sites, 'sites.csv: no sites')
    return sites


def load_data(root, size):
    arrays, sha = {}, {}

    def read(name):
        raw = (root / name).read_bytes()
        sha[name] = hashlib.sha256(raw).hexdigest()
        return raw

    read('meta.json')
    for name, hi in (('before.u8', 22), ('after.u8', 22), ('changed.u8', 1)):
        raw = read(name)
        need(len(raw) == size * size, '%s: wrong byte count' % name)
        grid = np.frombuffer(raw, dtype='u1').reshape(size, size)
        need(int(grid.max()) <= hi, '%s: value outside 0..%d' % (name, hi))
        arrays[name] = grid
    sites = read_sites(read('sites.csv'))
    cells = None
    if (root / 'cells.i64be').exists():
        raw = read('cells.i64be')
        need(len(raw) == size * size * 8, 'cells.i64be: wrong byte count')
        cells = np.frombuffer(raw, dtype='>i8').reshape(size, size)
        ids, inverse = np.unique(cells, return_inverse=True)
        need(all(int(c) in sites for c in ids), 'raster cell missing from sites.csv')
        for arm in ('before', 'after'):
            recipes = np.array([sites[int(c)][arm] for c in ids], dtype='u1')
            need(np.array_equal(recipes[inverse.ravel()], arrays[arm + '.u8'].ravel()),
                 '%s raster disagrees with sites.csv' % arm)
    return arrays, sha, sites, cells is not None


def statistics(arrays, sites, has_cells):
    before, after, mask = (arrays[n + '.u8'] for n in ('before', 'after', 'changed'))
    diff = before != after
    land = before < 18
    need(np.array_equal(mask, diff.astype('u1')), 'changed.u8 does not equal recipe diff')
    need(np.all((before[diff] >= 18) & (after[diff] == 22)), 'changed pixel is not ocean -> 22')
    need(np.array_equal(before[land], after[land]), 'land recipe pixels changed')
    need(np.array_equal(land, after < 18), 'ocean/land label side changed')

    def counts(a, b):
        hist = {key: np.bincount(grid.ravel(), minlength=23).tolist()
                for key, grid in (('before', a), ('after', b))}
        return {'pixels': int(a.size), 'changedPixels': int(np.count_nonzero(a != b)),
                'recipeHist': hist, 'shelf22': {key: values[22] for key, values in hist.items()},
                'landPixels': int(np.count_nonzero(a < 18)),
                'oceanPixels': int(np.count_nonzero(a >= 18))}

    x0, y0, x1, y1 = ROI
    return {'full': counts(before, after),
            'local': counts(before[y0:y1, x0:x1], after[y0:y1, x0:x1]),
            'sites': {'records': len(sites),
                      'changedRecords': sum(s['before'] != s['after'] for s in sites.values()),
                      'rasterAgreementChecked': has_cells},
            'validation': {'changedMaskEqualsDiff': True, 'changesOnlyOceanToShelf22': True,
                           'allLandRecipesIdentical': True, 'oceanLandLabelSideIdentical': True},
            'geometryEvidence': {'sharedCellMapProvided': has_cells,
                'independentBeforeAfterGeometryCheck': 'not available: only one shared map allowed',
                'matching': 'Java exporter responsibility; not recomputed or certified here'}}


def render(arrays, meta, dest, layout, helper, uni, mor, pal, names):
    images = [Image.fromarray(np.uint8(np.clip(pal[arrays[arm + '.u8']], 0, 255)))
              for arm in ('before', 'after')]
    mask = Image.fromarray(arrays['changed.u8'] * np.uint8(255)).convert('RGB')
    lines = ['种子 %s｜1401²｜步长5块｜原点 %s｜原生像素1:1，不缩放'
             % (meta['seed'], meta['origin']),
             '前C成片600／后S2浅海匹配：细胞600、宏1200不变；仅海洋标签可改为既有浅海配方22。',
             '不做S3／不抑制内部边／不求高度；海色表示海洋配方，不是最终水面或水线。']
    spots = {}

    def save(name, panels, titles, heading, local=False, legend=False):
        panels = [image.crop(ROI) for image in panels] if local else panels
        details = lines + (['局部ROI [0,0,620,660)：同坐标原生裁剪（620×660），不缩放。'] if local else [])
        canvas, pasted = mor.grid_panel(panels, titles, heading, details,
                                        '只读Java导出的原始胜出标签；几何匹配由Java完成。')
        if legend:
            canvas = helper.append_band(canvas, uni.BG, 286,
                lambda d, w, t: layout.draw_raw_legend(d, w, t, pal, names, uni))
        canvas.save(dest / name, optimize=True)
        spots[name] = pasted

    for local in (False, True):
        suffix = 'local' if local else 'full'
        save('pair-types-%s.png' % suffix, images, TITLES,
             '前C成片600／后S2浅海匹配｜' + ('局部' if local else '全幅'), local, True)
    for i, arm in enumerate(('before', 'after')):
        save('native-%s.png' % arm, [images[i]], [TITLES[i]], TITLES[i] + '｜原生类型图', legend=True)
    for local in (False, True):
        save('changed-mask%s.png' % ('-local' if local else ''), [mask],
             ['白=标签变化（1），黑=未变（0）'], '前C／后S2标签变化掩膜｜' + ('局部' if local else '全幅'), local)
    return helper.verify(spots, dest)


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-coherent-shallow.py <root> <out>\n')
        return 2
    try:
        root, dest = (Path(p).resolve() for p in argv)
        need(dest != root and dest not in root.parents, 'output must not replace input root / ancestors')
        need(not any((dest / name).exists() for name in OUTPUTS), 'refusing to overwrite existing outputs')
        layout = load_layout()
        helper, uni, mor, pal, names = layout.load_tools()
        meta = layout.read_meta(root)
        arrays, sha, sites, has_cells = load_data(root, meta['size'])
        stats = statistics(arrays, sites, has_cells)
        payload = {'tool': 'render-coherent-shallow', 'geometry': meta,
                   'scope': 'S2 raw labels only; no S3 / internal-edge suppression / height',
                   'declaredFixedLayout': {'cellSpacing': 600, 'macroSpacing': 1200},
                   'recipeNames': names, 'rawSHA256': sha, **stats}
        dest.mkdir(parents=True, exist_ok=True)
        payload['figures'] = render(arrays, meta, dest, layout, helper, uni, mor, pal, names)
        (dest / 'metrics.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False)
                                          + '\n', encoding='utf-8')
    except Exception as exc:
        sys.stderr.write('%s: %s\n' % (type(exc).__name__, exc))
        return 1
    print('Wrote metrics and %d pixel-verified figures -> %s' % (len(payload['figures']), dest))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
