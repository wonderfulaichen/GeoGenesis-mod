#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S0/S1 raw winner layouts only: python tools/render-coherent-layout.py <root> <output>.

Reads main-export meta.json and arm-{a1200,b600,c600}/{type.u8,cells.i64be,
adjacency.csv,sites.csv}. Optional root/extended has the same contract.
Connectivity uses positive-length clipped world edges, never raster neighbours.
Areas are winner-pixel count * step² approximations; border-pixel censoring is approximate.
All sites, including geometric halo and zero-pixel sites, are DSU vertices.
No generation, label reassignment, internal-edge suppression, or height processing.
"""
import csv
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ARMS = ('arm-a1200', 'arm-b600', 'arm-c600')
TITLES = ('A1200｜原有原始胜出标签', 'B独立600', 'C成片600（宏1200）')
ROI = (0, 0, 620, 660)


def need(condition, message):
    if not condition:
        raise ValueError(message)


def load_tools():
    path = Path(__file__).with_name('render-cell-scale.py')
    spec = importlib.util.spec_from_file_location('gg_coherent_cell_scale', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    uni, mor = module.load_tools()
    pal, names = module.palette(uni, mor)
    return module, uni, mor, pal, names


def read_meta(root, extended=False):
    meta = json.loads((root / 'meta.json').read_text(encoding='utf-8-sig'))
    need(isinstance(meta, dict), '%s: metadata must be an object' % root)
    need(isinstance(meta.get('seed'), str), '%s: seed must be a string' % root)
    need(meta.get('size') == 1401, '%s: expected size 1401' % root)
    need(meta.get('step') == (10 if extended else 5), '%s: unexpected step' % root)
    origin = [-7142.5, -6865.5] if extended else [-3642.5, -3365.5]
    need(meta.get('origin') == origin, '%s: unexpected origin' % root)
    need(meta.get('roi') == list(ROI), '%s: unexpected ROI' % root)
    return meta


def csv_rows(path, fields, sha):
    raw = path.read_bytes()
    sha[path.name] = hashlib.sha256(raw).hexdigest()
    reader = csv.DictReader(raw.decode('utf-8-sig').splitlines())
    need(reader.fieldnames == fields, '%s: expected headers %s' % (path, fields))
    return [{key: int(row[key]) for key in fields} for row in reader]


def load_arm(folder, meta):
    size, sha, arrays = meta['size'], {}, {}
    for name, dtype in (('type.u8', 'u1'), ('cells.i64be', '>i8')):
        raw = (folder / name).read_bytes()
        need(len(raw) == size * size * np.dtype(dtype).itemsize, '%s: wrong byte count' % (folder / name))
        arrays[name] = np.frombuffer(raw, dtype=dtype).reshape(size, size)
        sha[name] = hashlib.sha256(raw).hexdigest()
    types, cells = arrays['type.u8'], arrays['cells.i64be']
    need(int(types.max()) <= 22, '%s: recipe outside 0..22' % folder)
    sites = csv_rows(folder / 'sites.csv', ['cell', 'cellX', 'cellZ', 'recipe', 'macroX', 'macroZ'], sha)
    lookup = {}
    for site in sites:
        cell, x, z, recipe = (site[k] for k in ('cell', 'cellX', 'cellZ', 'recipe'))
        need(-(1 << 31) <= x < (1 << 31) and -(1 << 31) <= z < (1 << 31), 'cell coordinate outside int32')
        need(cell == ((x << 32) ^ (z & 0xffffffff)), '%s: packed cell mismatch' % folder)
        need(cell not in lookup and 0 <= recipe <= 22, '%s: duplicate cell / invalid recipe' % folder)
        lookup[cell] = site
    edges = csv_rows(folder / 'adjacency.csv', ['cellA', 'cellB', 'recipeA', 'recipeB'], sha)
    ids, inverse, counts = np.unique(cells, return_inverse=True, return_counts=True)
    inverse = inverse.reshape(-1)
    need(all(int(c) in lookup for c in ids), '%s: raster cell missing from sites' % folder)
    expected = np.array([lookup[int(c)]['recipe'] for c in ids], dtype=np.uint8)
    need(np.array_equal(expected[inverse], types.ravel()), '%s: raster recipe disagrees with cell site' % folder)
    for edge in edges:
        a, b = edge['cellA'], edge['cellB']
        need(a != b and a in lookup and b in lookup, '%s: invalid adjacency endpoint' % folder)
        need(lookup[a]['recipe'] == edge['recipeA'] and lookup[b]['recipe'] == edge['recipeB'],
             '%s: adjacency recipe disagrees with sites' % folder)
    return {'types': types, 'cells': cells, 'ids': ids, 'counts': counts,
            'sites': lookup, 'edges': edges, 'sha256': sha}


class DSU:
    def __init__(self, cells):
        self.parent = {c: c for c in cells}

    def find(self, cell):
        while cell != self.parent[cell]:
            self.parent[cell] = self.parent[self.parent[cell]]
            cell = self.parent[cell]
        return cell

    def union(self, a, b):
        a, b = self.find(a), self.find(b)
        if a != b:
            self.parent[max(a, b)] = min(a, b)


def component_rows(arm, meta):
    # Include every geometric halo site; zero-pixel nodes may bridge real clipped edges.
    vertices = set(arm['sites'])
    for edge in arm['edges']:
        vertices.update((edge['cellA'], edge['cellB']))
    dsu = DSU(vertices)
    for edge in arm['edges']:
        if edge['recipeA'] == edge['recipeB']:
            dsu.union(edge['cellA'], edge['cellB'])
    pixels = dict(zip(map(int, arm['ids']), map(int, arm['counts'])))
    cells = arm['cells']
    border = set(map(int, np.concatenate((cells[0], cells[-1], cells[:, 0], cells[:, -1]))))
    groups = {}
    for cell in sorted(vertices):
        groups.setdefault(dsu.find(cell), []).append(cell)
    rows = []
    for key, members in sorted(groups.items()):
        count = sum(pixels.get(c, 0) for c in members)
        macros = sorted({(arm['sites'][c]['macroX'], arm['sites'][c]['macroZ']) for c in members})
        rows.append({'component': str(key), 'recipe': arm['sites'][key]['recipe'],
                     'cellCount': len(members), 'sampledCellCount': sum(c in pixels for c in members),
                     'pixels': count, 'areaApproxBlocks2': count * meta['step'] ** 2,
                     'censored': any(c in border for c in members),
                     'cells': '|'.join(map(str, members)),
                     'macroCells': '|'.join('%d,%d' % p for p in macros)})
    return rows


def summary(rows, is600):
    rows = [r for r in rows if r['pixels'] > 0]
    area = np.array([r['areaApproxBlocks2'] for r in rows], dtype=float)
    ordered = np.sort(area)
    total = float(area.sum())
    quantiles = dict(zip(('P50', 'P75'), map(float, np.percentile(area, [50, 75])))) if rows else None
    weighted = None
    if total > 0:
        cumulative = np.cumsum(ordered)
        weighted = {key: float(ordered[np.searchsorted(cumulative, q * total, side='left')])
                    for key, q in (('P50', 0.5), ('P75', 0.75))}
    single = [r for r in rows if r['cellCount'] == 1]
    cell_count = sum(r['cellCount'] for r in rows)
    return {'components': len(rows), 'cellCount': cell_count, 'areaApproxBlocks2': total,
            'zeroPixelComponents': sum(r['pixels'] == 0 for r in rows),
            'areaQuantilesApproxBlocks2': quantiles, 'areaWeightedQuantilesApproxBlocks2': weighted,
            'single600CellFractions': {'componentFraction': len(single) / len(rows) if rows else None,
                'cellFraction': len(single) / cell_count if cell_count else None,
                'areaFraction': sum(r['areaApproxBlocks2'] for r in single) / total if total else None}
            if is600 else None}


def populations(rows, is600):
    halo = [r for r in rows if r['pixels'] == 0]
    return {'allClipped': summary(rows, is600),
            'completeOnly': summary([r for r in rows if not r['censored']], is600),
            'censoredOnly': summary([r for r in rows if r['censored']], is600),
            'haloOnly': {'components': len(halo), 'cellCount': sum(r['cellCount'] for r in halo),
                         'componentIds': [r['component'] for r in halo]}}


def arm_metrics(arm, rows, names, is600):
    counts = np.bincount(arm['types'].ravel(), minlength=23)
    recipes = []
    for recipe, name in enumerate(names):
        selected = [r for r in rows if r['recipe'] == recipe]
        recipes.append({'recipe': recipe, 'name': name, 'pixels': int(counts[recipe]),
                        'share': float(counts[recipe] / arm['types'].size),
                        'missingInRaster': bool(counts[recipe] == 0),
                        'missingInGraph': not any(r['pixels'] > 0 for r in selected),
                        'componentCount': sum(r['pixels'] > 0 for r in selected),
                        **populations(selected, is600)})
    return {**populations(rows, is600), 'recipes': recipes,
            'missingRecipeIds': [i for i in range(23) if counts[i] == 0],
            'siteRecords': len(arm['sites']), 'graphVertices': sum(r['cellCount'] for r in rows),
            'adjacencyRows': len(arm['edges']), 'inputsSha256': arm['sha256']}


def dataset(root, meta, names):
    data, metrics, evidence = {}, {}, {}
    for i, stem in enumerate(ARMS):
        arm = load_arm(root / stem, meta)
        rows = component_rows(arm, meta)
        metrics[stem] = arm_metrics(arm, rows, names, i != 0)
        evidence[stem], data[stem] = rows, arm
    need(data[ARMS[1]]['sha256']['cells.i64be'] == data[ARMS[2]]['sha256']['cells.i64be']
         and np.array_equal(data[ARMS[1]]['cells'], data[ARMS[2]]['cells']),
         '%s: B600 / C600 cells.i64be must be bit-identical' % root)
    return data, {'geometry': meta, 'arms': metrics,
                  'validation': {'b600C600CellsBitIdentical': True}}, evidence


def write_components(dest, evidence, prefix=''):
    files = {}
    fields = ['component', 'recipe', 'cellCount', 'sampledCellCount', 'pixels',
              'areaApproxBlocks2', 'censored', 'cells', 'macroCells']
    for stem, rows in evidence.items():
        name = '%scomponents-%s.csv' % (prefix, stem[4:])
        with (dest / name).open('w', encoding='utf-8', newline='') as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)
        files[stem] = name
    return files


def draw_raw_legend(draw, width, top, pal, names, uni):
    rows, left = 8, 40
    col_w = (width - 2 * left) // 3
    for i, name in enumerate(names):
        x, y = left + (i // rows) * col_w, top + 10 + (i % rows) * 30
        draw.rectangle((x, y + 2, x + 22, y + 20), fill=tuple(int(v) for v in pal[i]))
        draw.text((x + 30, y), '%2d %s' % (i, name), font=uni.font(15), fill=uni.INK)
    text = '类型来自原始几何赢家：尚未求过渡权重或地形高度；海色表示海洋配方，不是最终水面。'
    draw.text((left, top + 254), text, font=uni.fit(text, width - left * 2, 16), fill=uni.DIM)


def boundary_image(arm, pal):
    cells = arm['cells']
    boundary = np.zeros(cells.shape, dtype=bool)
    boundary[:, 1:] |= cells[:, 1:] != cells[:, :-1]
    boundary[1:, :] |= cells[1:, :] != cells[:-1, :]
    rgb = np.uint8(np.clip(pal[arm['types']], 0, 255))
    rgb[boundary] = (35, 35, 35)
    return Image.fromarray(rgb)


def render(data, meta, dest, helper, uni, mor, pal, names):
    images = {s: Image.fromarray(np.uint8(np.clip(pal[data[s]['types']], 0, 255))) for s in ARMS}
    overlays = {s: boundary_image(data[s], pal) for s in (ARMS[0], ARMS[2])}
    spots = {}
    lines = ['S0/S1 原始胜出配方 0..22｜A1200 / B独立600 / C成片600（宏1200）',
             '种子 %s｜%d²｜步长 %g 块｜原点 %s｜原生像素 1:1，不缩放'
             % (meta['seed'], meta['size'], meta['step'], meta['origin'])]
    note = '仅展示原始胜出标签；连通性采用导出的窗口内正长度真边，不采用栅格邻接。'

    def save(name, panels, titles, heading, local=False, legend=False):
        panels = [p.crop(ROI) for p in panels] if local else panels
        details = lines + (['局部框 [0,0,620,660)：对应左上圈选区域的近似包围框，同坐标原生裁剪'] if local else [])
        if 'boundaries' in name:
            details += ['细线仅按相邻像素的微细胞编号不等绘制（单像素），仅供显示，不参与连通性统计。']
        canvas, pasted = mor.grid_panel(panels, titles, heading, details, note)
        if legend:
            canvas = helper.append_band(canvas, uni.BG, 286,
                                        lambda d, w, t: draw_raw_legend(d, w, t, pal, names, uni))
        canvas.save(dest / name, optimize=True)
        spots[name] = pasted

    for i, stem in enumerate(ARMS):
        save('native-types-%s.png' % stem[4:], [images[stem]], [TITLES[i]], '原生类型图｜原始胜出配方', legend=True)
    for local in (False, True):
        suffix = 'local' if local else 'full'
        save('pair-a-c-%s.png' % suffix, [images[ARMS[i]] for i in (0, 2)],
             [TITLES[i] for i in (0, 2)], 'A / C 原始胜出类型对照｜' + ('局部' if local else '全幅'), local, True)
        save('triptych-types-%s.png' % suffix, [images[s] for s in ARMS],
             list(TITLES), '三臂原始胜出类型对照｜' + ('局部' if local else '全幅'), local, True)
        name = 'pair-a-c-boundaries%s.png' % ('-local' if local else '')
        save(name, [overlays[s] for s in (ARMS[0], ARMS[2])], [TITLES[i] for i in (0, 2)],
             '区域边界叠加｜全部微细胞边仍保留，S3 未接入｜' + ('局部' if local else '全幅'), local)
    return helper.verify(spots, dest)


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-coherent-layout.py <root> <output>\n')
        return 2
    try:
        root, dest = (Path(p).resolve() for p in argv)
        protected = [root / stem for stem in ARMS] + [root / 'extended']
        need(dest != root and dest not in root.parents
             and all(dest != p and p not in dest.parents for p in protected),
             'output must not replace the input root or live inside an arm / extended input')
        helper, uni, mor, pal, names = load_tools()
        meta = read_meta(root)
        data, base, evidence = dataset(root, meta, names)
        payload = {'tool': 'render-coherent-layout', 'scope': 'S0/S1 raw winner labels only', 'main': base,
                   'recipeNames': names, 'extended': {'status': 'not provided; deferred to main'},
                   'method': {'connectivity': 'DSU same-recipe unions of exported positive-length clipped world edges',
                       'vertices': 'ALL sites including geometric halo plus all adjacency endpoints; zero-pixel nodes retained',
                       'area': 'APPROXIMATION: winner raster pixel count * step^2; zero-pixel graph vertices retained',
                       'censoring': 'APPROXIMATION: any member cell sampled on a raster border; may miss subpixel boundary contacts',
                       'populations': 'area statistics, component counts and recipe occurrence use pixels>0 groups ONLY; '
                           'haloOnly reports zero-pixel groups separately; CSV retains all groups; '
                           'completeOnly means not flagged by approximate raster-border censoring, not proven geometrically complete',
                       'quantiles': 'component-unweighted NumPy linear percentiles; area-weighted inverse area ECDF',
                       'single600': 'exactly one graph cell; fractions by components, cells, and approximate area; B/C only',
                       'judgement': 'descriptive only; no parameter or area PASS thresholds'}}
        extended_path = root / 'extended'
        extended_evidence = None
        if extended_path.exists():
            extended_meta = read_meta(extended_path, True)
            need(extended_meta['seed'] == meta['seed'], 'extended seed differs from main')
            _, extended, extended_evidence = dataset(extended_path, extended_meta, names)
            payload['extended'] = {'status': 'provided', **extended}
        dest.mkdir(parents=True, exist_ok=True)
        payload['main']['componentCsv'] = write_components(dest, evidence)
        if extended_evidence is not None:
            payload['extended']['componentCsv'] = write_components(dest, extended_evidence, 'extended-')
        payload['figures'] = render(data, meta, dest, helper, uni, mor, pal, names)
        (dest / 'metrics.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False)
                                          + '\n', encoding='utf-8')
    except Exception as exc:
        sys.stderr.write('%s: %s\n' % (type(exc).__name__, exc))
        return 1
    print('Wrote metrics, component evidence and %d pixel-verified figures -> %s' % (len(payload['figures']), dest))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
