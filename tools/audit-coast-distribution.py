#!/usr/bin/env python3
"""Independent reference distances for CoastDistributionAudit exports.

python -B tools/audit-coast-distribution.py <evidence-dir> <arm-parent> <output-json>
Requires numpy and scipy; never rewrites the input evidence or generator.
"""
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
from scipy.ndimage import distance_transform_edt, map_coordinates


def main():
    evidence, arms, output = map(Path, sys.argv[1:])
    meta = json.loads((evidence / 'grid-meta.json').read_text(encoding='utf-8'))
    n, cell = int(meta['n']), float(meta['cell'])
    grid = np.fromfile(evidence / 'base-grid.f64be', dtype='>f8').reshape(n, n)
    # Exact EDT to opposite-class grid nodes; output pixels use bilinear interpolation.
    to_sea = distance_transform_edt(grid > 0, sampling=cell)
    to_land = distance_transform_edt(grid <= 0, sampling=cell)
    ma = json.loads((arms / 'arm-BASELINE/meta.json').read_text(encoding='utf-8'))
    mc = json.loads((arms / 'arm-COAST_DIST/meta.json').read_text(encoding='utf-8'))
    for key in ('seed', 'size', 'step', 'sampleOrigin'):
        assert ma[key] == mc[key], key
    size, step = int(ma['size']), float(ma['step'])
    a_path = arms / 'arm-BASELINE/Y-world.f64be'
    c_path = arms / 'arm-COAST_DIST/Y-world.f64be'
    a = np.fromfile(a_path, dtype='>f8').reshape(size, size)
    c = np.fromfile(c_path, dtype='>f8').reshape(size, size)
    assert np.isfinite(a).all() and np.isfinite(c).all()
    zz, xx = np.mgrid[:size, :size]
    coords = np.array([
        (ma['sampleOrigin'][1] + zz * step - meta['z0']) / cell,
        (ma['sampleOrigin'][0] + xx * step - meta['x0']) / cell,
    ])
    land = map_coordinates(grid, coords, order=1) > 0
    sea_distance = map_coordinates(to_sea, coords, order=1)
    signed = np.where(land, sea_distance, -map_coordinates(to_land, coords, order=1))
    changed = a.view('>u8') != c.view('>u8')
    beyond = changed & (np.abs(signed) > 116)
    summary = dict(line.split('=', 1) for line in
                   (evidence / 'summary.txt').read_text(encoding='utf-8').splitlines())
    wx, wz = float(summary['worst_x']), float(summary['worst_z'])
    fx, fz = (wx - meta['x0']) / cell, (wz - meta['z0']) / cell
    sea_nodes = np.argwhere(grid <= 0)
    brute = np.hypot((sea_nodes[:, 1] - fx) * cell,
                     (sea_nodes[:, 0] - fz) * cell).min()
    reference = map_coordinates(to_sea, [[fz], [fx]], order=1)[0]
    assert abs(brute - reference) < cell * 2, 'independent nearest-point disagreement'
    result = {
        'reference': 'scipy.ndimage.distance_transform_edt; independent brute-force at worst point',
        'distance_definition': 'nearest opposite-class node on unchanged 8-block base grid; not exact continuous shoreline',
        'changed_pixels': int(changed.sum()),
        'changed_beyond_reference_116': int(beyond.sum()),
        'A_land_changed_beyond_reference_116': int((beyond & (a >= 63) & land).sum()),
        'max_reference_abs_d_on_changed': float(np.abs(signed[changed]).max()),
        'reference_symmetric_116_band_pixels': int((np.abs(signed) < 116).sum()),
        'worst_nearest_sea_node_distance_bruteforce': float(brute),
        'worst_reference_distance_bilinear': float(reference),
        'worst_old_distance': float(summary['worst_d']),
        'arm_A_sha256': hashlib.sha256(a_path.read_bytes()).hexdigest(),
        'arm_C_sha256': hashlib.sha256(c_path.read_bytes()).hexdigest(),
        'distance_criterion': 'FAIL: old distance is not Euclidean; no generator fix in this audit',
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
