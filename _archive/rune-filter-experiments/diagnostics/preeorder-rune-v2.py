"""Single v2 trial; frozen v1 helpers/inputs are read-only, no route or terrain resampling."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import time

import numpy as np

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('frozen_v1', Path(__file__).with_name('preeorder-rune.py'))
v1 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(v1)
SOURCE = v1.BASE_OUT / 'fresh-01'
OUT = v1.BASE_OUT / 'fresh-02'


def snapshot():
    locked = v1.snapshot()
    for path in v1.BASE_OUT.rglob('*'):
        if path.is_file() and OUT not in path.parents:
            locked[path.relative_to(v1.REPO).as_posix()] = v1.sha(path)
    return locked


def origin(spec):
    return np.array([spec['originX'], spec['originZ']])


def points_for(spec):
    x, z = np.meshgrid(spec['originX']+np.arange(spec['cols'])*spec['step'],
                       spec['originZ']+np.arange(spec['rows'])*spec['step'])
    return np.column_stack((x.ravel(), z.ravel()))


def original_slope(h, spec, points):
    """True H0 slope in Y/wu, not normalized filter slope or guide slope."""
    spacing, lower = spec['step'], origin(spec)
    upper = lower + np.array([spec['cols']-1, spec['rows']-1])*spacing
    derivatives = []
    for axis in (0, 1):
        left, right = points.copy(), points.copy()
        left[:, axis] = np.maximum(lower[axis], points[:, axis]-3.)
        right[:, axis] = np.minimum(upper[axis], points[:, axis]+3.)
        a = v1.bilinear(h, lower, spacing, left)
        b = v1.bilinear(h, lower, spacing, right)
        derivatives.append((b-a)*v1.YPROXY/(right[:, axis]-left[:, axis]))
    return np.hypot(*derivatives)


def prepare_config():
    config = json.loads((SOURCE / 'config.json').read_text(encoding='utf-8'))
    baseline = copy.deepcopy(config)
    for spec in config['rasters']+config['pointSets']:
        spec['out'] = str(OUT / 'data' / Path(spec['out']).name)
        if 'gradientRasterOut' in spec:
            spec['gradientRasterOut'] = str(OUT / 'data' / Path(spec['gradientRasterOut']).name)
    full = config['rasters'][0]
    h0 = np.fromfile(full['in'], dtype='>f8').astype(float).reshape(full['rows'], full['cols'])
    slope = original_slope(h0, full, points_for(full))
    s_ref = float(np.quantile(slope, .6))
    assert np.isfinite(s_ref) and s_ref > 0
    config['params'].update(scale=.30, octaves=3, guideDepthY=3., sRef=s_ref)
    changed = {k for k in baseline['params'] if baseline['params'][k] != config['params'][k]}
    assert changed == {'scale', 'octaves', 'guideDepthY'}
    assert config['density'] == baseline['density'] and config['ftRaster'] == baseline['ftRaster']
    v1.save_json(OUT / 'config.json', config)
    v1.save_raster(OUT / 'data/full-slope-python.f64be', slope)
    return config, h0, slope


def generated_probe():
    """Derive an auditable isolated probe; do not edit/compile into any old directory."""
    text = v1.JAVA.read_text(encoding='utf-8')
    text = text.replace('PreErosionGuideProbe', 'PreErosionGuideV2Probe')
    replacements = [
        ('double guideDepth, double gradStep, long seed,',
         'double guideDepth, double gradStep, double sRef, long seed,'),
        ('        double evaluate(Raster h, double x, double z, double h0) {', '''        double slope(Raster h, double x, double z) {
            double xl = Math.max(h.x(), x - gradStep);
            double xr = Math.min(h.x() + (h.cols() - 1) * h.step(), x + gradStep);
            double zl = Math.max(h.z(), z - gradStep);
            double zr = Math.min(h.z() + (h.rows() - 1) * h.step(), z + gradStep);
            double sx = (h.at(xr, z) - h.at(xl, z)) * yproxy / (xr - xl);
            double sz = (h.at(x, zr) - h.at(x, zl)) * yproxy / (zr - zl);
            return Math.hypot(sx, sz);
        }

        double amplitude(double slope) {
            double t = clamp01(slope / sRef);
            return t * t * (3.0 - 2.0 * t);
        }

        double evaluate(Raster h, double x, double z, double h0) {'''),
        ('vu * delta * (.25 + .75 * g)) / yproxy;',
         'vu * delta * (.25 + .75 * g) * amplitude(slope(h, x, z))) / yproxy;'),
        ('number(p, "Yproxy"), number(p, "guideDepthY"), number(p, "gradStepWu"),',
         'number(p, "Yproxy"), number(p, "guideDepthY"), number(p, "gradStepWu"), number(p, "sRef"),'),
        ('            double[] result = new double[h.values().length];',
         '            double[] result = new double[h.values().length];\n'
         '            double[] slopes = new double[result.length], amps = new double[result.length];'),
        ('                                               h.values()[i]);',
         '                                               h.values()[i]);\n'
         '                    slopes[i] = model.slope(h, h.x() + col * h.step(), h.z() + row * h.step());\n'
         '                    amps[i] = model.amplitude(slopes[i]);'),
        ('            rasters.put(text(spec, "out"), h);',
         '            write(Path.of(text(spec, "out") + ".slope.f64be"), slopes);\n'
         '            write(Path.of(text(spec, "out") + ".amp.f64be"), amps);\n'
         '            rasters.put(text(spec, "out"), h);'),
        ('            double[] result = new double[h0.length];',
         '            double[] result = new double[h0.length];\n'
         '            double[] slopes = new double[result.length], amps = new double[result.length];'),
        ('                result[i] = model.evaluate(gradient, xz[2 * i], xz[2 * i + 1], h0[i]);',
         '                result[i] = model.evaluate(gradient, xz[2 * i], xz[2 * i + 1], h0[i]);\n'
         '                slopes[i] = model.slope(gradient, xz[2 * i], xz[2 * i + 1]);\n'
         '                amps[i] = model.amplitude(slopes[i]);'),
        ('            System.out.println("section "',
         '            write(Path.of(text(spec, "out") + ".slope.f64be"), slopes);\n'
         '            write(Path.of(text(spec, "out") + ".amp.f64be"), amps);\n'
         '            System.out.println("section "')]
    for before, after in replacements:
        assert text.count(before) == 1, before
        text = text.replace(before, after)
    target = OUT / 'generated/PreErosionGuideV2Probe.java'
    target.parent.mkdir()
    target.write_text(text, encoding='utf-8')
    v1.save_json(OUT / 'probe-derivation.json', {'base': str(v1.JAVA), 'baseSha256': v1.sha(v1.JAVA),
                  'classRename': 'PreErosionGuideProbe -> PreErosionGuideV2Probe',
                  'replacements': [{'before': a, 'after': b} for a, b in replacements]})
    return target


def compile_run(java):
    classes = OUT / 'classes'
    classes.mkdir()
    commands = [
        ['javac', '--release', '17', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d',
         str(classes), str(v1.CORE), str(java)],
        ['java', '-cp', str(classes), 'com.geogenesis.worldgen.erosion.PreErosionGuideV2Probe',
         str(OUT / 'config.json')]]
    with (OUT / 'java.log').open('w', encoding='utf-8') as log:
        for command in commands:
            log.write(subprocess.list2cmdline(command)+'\n')
            log.flush()
            subprocess.run(command, cwd=v1.ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    return commands


def fine_samples(previous):
    items = []
    for name in ('NW', 'SE', 'section-1', 'section-2', 'section-3'):
        definition = copy.deepcopy(previous['statistics'][name]['definition'])
        with np.load(SOURCE / f'data/{name}.npz') as saved:
            definition.update({k: saved[k].copy() for k in ('xz', 'H0', 'strong')})
        items.append(definition)
    return items[:2], items[2:]


def widths(depth, threshold, step=.25):
    center = len(depth)//2
    result = []
    for side in (depth[center::-1], depth[center:]):
        hits = np.flatnonzero(side <= threshold)
        if not len(hits):
            result.append({'distanceWu': None, 'greaterThanWu': (len(side)-1)*step})
            continue
        i = int(hits[0])
        distance = 0. if i == 0 else (i-1+(side[i-1]-threshold)/(side[i-1]-side[i]))*step
        result.append({'distanceWu': float(distance), 'greaterThanWu': None})
    return result


def section_stats(section):
    result = {'centerXZwu': section['centerWu'], 'sampledHalfWidthWu': 20.,
              'definition': 'First outward threshold crossing from fixed skeleton center; linear interpolation. '
                            'Not an isolated channel width; subsequent branches may recut.'}
    for key in ('strong', 'candidate'):
        depth = (section['H0']-section[key])*v1.YPROXY
        center_depth = float(depth[len(depth)//2])
        result[key] = {'centerLoweringY': center_depth, 'endpointDepthY': [float(depth[0]), float(depth[-1])],
                       'zeroReturnHalfWidthLeftRight': widths(depth, 0.),
                       'within01YHalfWidthLeftRight': widths(depth, .1),
                       'halfCenterDepthHalfWidthLeftRight': widths(depth, center_depth*.5)
                       if center_depth > 0 else None}
    return result


def validate_amplitudes(config, h0, expected_slope, stats):
    errors = {}
    for name, spec in zip(('full', 'NW', 'SE', 'section-1', 'section-2', 'section-3'),
                          config['rasters']+config['pointSets']):
        slope = np.fromfile(spec['out']+'.slope.f64be', dtype='>f8').astype(float)
        amp = np.fromfile(spec['out']+'.amp.f64be', dtype='>f8').astype(float)
        assert np.isfinite(slope).all() and np.isfinite(amp).all() and ((amp >= 0) & (amp <= 1)).all()
        if 'rows' in spec:
            h = np.fromfile(spec['in'], dtype='>f8').astype(float).reshape(spec['rows'], spec['cols'])
            expected = original_slope(h, spec, points_for(spec))
        else:
            raster = next(r for r in config['rasters'] if r['out'] == spec['gradientRasterOut'])
            h = np.fromfile(raster['in'], dtype='>f8').astype(float).reshape(raster['rows'], raster['cols'])
            expected = original_slope(h, raster, np.fromfile(spec['xz'], dtype='>f8').reshape(-1, 2))
        errors[name] = float(np.max(np.abs(expected-slope)))
        assert np.allclose(expected, slope, rtol=1e-10, atol=1e-10), errors
        t = np.clip(slope/config['params']['sRef'], 0, 1)
        assert np.allclose(amp, t*t*(3-2*t), rtol=0, atol=1e-15)
        stats[name]['slopeYperWu'] = v1.quantiles(slope)
        stats[name]['amp'] = dict(v1.quantiles(amp), mean=float(amp.mean()),
                                zeroFraction=float((amp == 0).mean()), oneFraction=float((amp == 1).mean()),
                                below01Fraction=float((amp < .1).mean()), below05Fraction=float((amp < .5).mean()))
    return errors


def render(h0, strong, candidate, config, grids, sections):
    v1.TITLES = ['原始 H0', '旧强下切', 'Rune 预侵蚀 v2（固定一版）']
    v1.FOOTER = ('v2：scale=.30，octaves=3；Hguide=H0−3Y×g；滤镜振幅乘真实H0坡度smoothstep。\n'
                 '固定色带 e=.02+1.5H，范围[-.35,1.2]；HS=2，Yproxy=354.66；同光照，无逐图归一化。')
    v1.plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'DejaVu Sans']
    v1.plt.rcParams['axes.unicode_minus'] = False
    palette = np.loadtxt(v1.PALETTE, delimiter=',', skiprows=1)
    cmap = v1.ListedColormap(palette[:, 1:]/255.)
    routes = v1.load_routes()
    full = config['rasters'][0]
    v1.render_triplet(OUT, 'full', (h0, strong, candidate), full['step'], origin(full), routes, cmap, True)
    v1.render_details(OUT, grids, sections, routes, cmap)


def main():
    started = time.perf_counter()
    OUT.mkdir()  # Fail rather than overwrite fresh-02 or silently choose another version.
    (OUT / 'data').mkdir()
    locked = snapshot()
    v1.save_json(OUT / 'locked-inputs.json', locked)
    previous = json.loads((SOURCE / 'meta.json').read_text(encoding='utf-8'))
    assert all(v1.sha(SOURCE / f) == digest for f, digest in previous['outputHashes'].items())
    config, h0, slope = prepare_config()
    print('Fixed v2 output:', OUT, 's_ref P60 Y/wu=', config['params']['sRef'], flush=True)
    commands = compile_run(generated_probe())
    grids, sections = fine_samples(previous)
    strong = np.fromfile(v1.FROZEN / 'strong-height.f64be', dtype='>f8').astype(float).reshape(h0.shape)
    density = config['density']
    g = np.fromfile(density['path'], dtype='>f8').astype(float).reshape(density['rows'], density['cols'])
    full = config['rasters'][0]
    v1.PARAMS = config['params']  # In-memory helper context only; frozen file is untouched.
    candidate, stats = v1.collect_outputs(OUT, h0, strong, origin(full), full['step'], g,
                                           origin(density), grids, sections)
    errors = validate_amplitudes(config, h0, slope, stats)
    profiles = {s['group']: section_stats(s) for s in sections}
    v1.save_json(OUT / 'section-measurements.json', profiles)
    render(h0, strong, candidate, config, grids, sections)
    after = snapshot()
    changed = [k for k in set(locked) | set(after) if locked.get(k) != after.get(k)]
    checks = {'finite': True, 'protectedHashesUnchanged': not changed, 'changedProtectedFiles': changed,
              'mainFileCount': sum(p.is_file() for p in (v1.ROOT / 'src/main').rglob('*')),
              'slopeIndependentCheckMaxAbsoluteError': errors, 'ampFormulaChecked': True,
              'densityAndFadeReusedUnmodified': True, 'noResamplingOrRouteRebuild': True}
    v1.save_json(OUT / 'checks.json', checks)
    assert not changed and checks['mainFileCount'] == 222
    record = {'version': 2, 'scope': 'One fixed offline pre-erosion experiment; no tuning sweep or production',
              'params': config['params'], 'statistics': stats, 'sections': profiles, 'checks': checks,
              'protectedFileCount': len(locked), 'compileAndRun': commands, 'render': previous['render'],
              'fadeTarget': previous['fadeTarget'],
              'slopeReference': {'s_refYperWu': config['params']['sRef'], 'quantile': .60,
                                 'referenceGrid': 'full 769x769 H0', 'rule': 'numpy linear quantile',
                                 'derivative': '+/-3wu bilinear H0, actual clipped denominator at edges',
                                 'amp': 't=clamp(s/s_ref,0,1); amp=t*t*(3-2*t)',
                                 'allRegionsShareSameReference': True},
              'formula': 'HnewY=H0Y-3*g+120*delta*(.25+.75*g)*amp; Hnew=HnewY/354.66',
              'reuse': {'source': str(SOURCE), 'density': config['density'], 'ftRaster': config['ftRaster'],
                        'fineH0': 'v1 saved grids and sections; no terrain sampling'},
              'limitations': previous['limitations'] + [
                  'Only Rune delta is slope-gated; guide lowering 3*g remains even where H0 slope is zero.',
                  'scale also scales Rune amplitude internally; doubling scale is not wavelength-only.',
                  'Half-width is measured relative to unchanged H0, not a fitted physical bank.',
                  'No pointwise old-depth cap; no output blur or uplift clipping.'],
              'elapsedSeconds': time.perf_counter()-started,
              'outputHashes': {p.relative_to(OUT).as_posix(): v1.sha(p) for p in OUT.rglob('*') if p.is_file()}}
    v1.save_json(OUT / 'meta.json', record)
    print(json.dumps({'full': stats['full'], 'sections': profiles, 'checks': checks}, indent=2), flush=True)
    print('DONE', OUT, flush=True)


if __name__ == '__main__':
    main()
