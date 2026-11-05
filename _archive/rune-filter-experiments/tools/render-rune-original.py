#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Independent re-plot of the two ORIGINAL (upstream Rune) erosion arms.

INPUTS (written by the Java side into <outputdir>, never modified here)
    base.f32be      height BEFORE erosion   -> arm 1 "original baseline"
    eroded.f32be    height AFTER  erosion   -> arm 2 "original eroded"
Both are big-endian float32, size*size samples, row index = z, column index = x;
the sample at (iz, ix) sits at the CENTRE of its cell:
    x = minx + (ix + 0.5) * span / size
    z = minz + (iz + 0.5) * span / size

WHAT THIS SCRIPT IS
    A replot only. It does not import the mod, does not run erosion, does not
    read any game data, and contains no morph. It reads the two arrays and draws
    them. Output (all in <outputdir>):
        base.png        arm 1, one pixel per sample, label band on top
        eroded.png      arm 2, same window / same ramp / same light
        comparison.png  arm 1 | arm 2 side by side, one shared colorbar
        profiles.png    three cuts perpendicular to z at 25% / 50% / 75%,
                        both arms, identical X and Y limits in every panel
        stats.json      grid, height ranges, |diff| mean/max, sha256 of every file

WHAT THIS SCRIPT IS NOT
    Not a Minecraft / game render. Plain heightfield rasterization: no water, no
    trees, no vanilla 3D block materials, no screenshot. This is an independent,
    approximate visualization of the exported numbers, not the original tool's
    own renderer (different tone ramp, different framing, no upstream UI).

FAIRNESS RULES (why the two panels are comparable)
    * same window, same sampling grid, same output size
    * same hypsometric ramp over a range SHARED by both arms (vmin/vmax of both)
    * same light vector and same tone ramp for both arms
    * same vertical exaggeration for both arms: the 90th percentile of arm 1's
      per-sample gradient magnitude is drawn as a 45 degree slope, and that one
      reference is reused for arm 2
    => the panels can only differ where the height fields differ.

LIGHT
    LIGHT_XYZ_UP = (-1.0, 0.4, 0.05), taken from the Rune reference renderer
    (Common/Image). Read here in a Y-up frame (X right, Y up, Z forward): the
    light comes from -X, 0.4 above the surface normal's up axis and 0.05 toward
    +Z, i.e. from the left and near the horizon. The surface normal is
    (-gx, 1, -gz), so a slope rising toward +x (the left flank of a bump,
    facing -x) is lit. Gradients are per SAMPLE (height per pixel). Both axes
    have known shader coordinates; the display deliberately exaggerates relief
    relative to the baseline rather than claiming shader-space slopes.

HEIGHT
    Dimensionless, as exported. No world Y, no sea level, no 63 line: nothing
    here knows what a "water level" would be, so no water is drawn.

SIZE
    One output pixel per input sample, never rescaled or downsampled. Labels get
    their own band on top of base.png / eroded.png; when size < 1024 that band is
    widened to 1024 px (the raster stays size x size, centred) so nothing is
    clipped. comparison.png keeps both panels at exactly size x size pixels.

OUTPUT SAFETY
    Refuses (exit code 3) if any of the five output files already exists, and
    never writes to base.f32be / eroded.f32be. Pre-flight happens before any
    pixel is computed.

USAGE
    python -B forge-1.20.1-47.4.10-mdk/tools/render-rune-original.py \
        <outputdir> <size> <minx> <minz> <span>

EXIT CODES
    0 ok | 2 bad arguments / bad input | 3 would overwrite an existing output

ASCII-only source (Windows PowerShell 5.1 safe).
"""
import hashlib
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt                      # noqa: E402
from matplotlib.cm import ScalarMappable              # noqa: E402
from matplotlib.colors import LinearSegmentedColormap, Normalize   # noqa: E402

# ---------------------------------------------------------------------------
# constants
# ---------------------------------------------------------------------------
BASE_NAME = 'base.f32be'
ERODED_NAME = 'eroded.f32be'

OUT_BASE = 'base.png'
OUT_ERODED = 'eroded.png'
OUT_COMPARISON = 'comparison.png'
OUT_PROFILES = 'profiles.png'
OUT_STATS = 'stats.json'
OUTPUT_NAMES = (OUT_BASE, OUT_ERODED, OUT_COMPARISON, OUT_PROFILES, OUT_STATS)

ARM1 = 'ARM 1 / ORIGINAL BASELINE - base.f32be (height BEFORE erosion)'
ARM2 = 'ARM 2 / ORIGINAL ERODED - eroded.f32be (height AFTER erosion)'
ARM1_SHORT = 'ARM 1 original baseline (base.f32be)'
ARM2_SHORT = 'ARM 2 original eroded (eroded.f32be)'

# Same vector for BOTH arms. Do not re-derive its sign per call site.
LIGHT_XYZ_UP = (-1.0, 0.4, 0.05)
REF_SLOPE = 1.0     # exaggerated gradient drawn as a 45 degree slope (tan 45 = 1)
SHADE_MIN = 0.16    # floor of the tone ramp

RAMP_POS = (0.00, 0.18, 0.38, 0.55, 0.72, 0.86, 1.00)
RAMP_RGB = ((26, 35, 32), (46, 74, 60), (94, 118, 74), (150, 142, 96),
            (186, 168, 132), (216, 206, 186), (250, 250, 248))

DISCLAIMER = ('Plain heightfield rasterization of the exported arrays - NOT a '
              'Minecraft/game render: no water, no trees, no vanilla 3D materials')

FONT_CANDIDATES = ('C:/Windows/Fonts/consola.ttf', 'C:/Windows/Fonts/segoeui.ttf',
                   'C:/Windows/Fonts/arial.ttf')
MIN_CANVAS_W = 1024   # label band width floor; the raster itself is never rescaled
DPI = 100


# ---------------------------------------------------------------------------
# arguments and io
# ---------------------------------------------------------------------------
def die(msg, code=2):
    sys.stderr.write('render-rune-original: ' + msg + '\n')
    sys.exit(code)


def parse_args(argv):
    want = '<outputdir> <size> <minx> <minz> <span>'
    if len(argv) != 6:
        die('expected 5 arguments: %s\n  got %d: %s'
            % (want, len(argv) - 1, ' '.join(argv[1:])))
    outdir = Path(argv[1])
    try:
        size = int(argv[2])
    except ValueError:
        die('size must be an integer, got %r' % (argv[2],))
    try:
        minx, minz, span = float(argv[3]), float(argv[4]), float(argv[5])
    except ValueError:
        die('minx/minz/span must be numbers, got %r %r %r' % (argv[3], argv[4], argv[5]))
    if size < 8:
        die('size must be >= 8, got %d' % size)
    if not math.isfinite(span) or span <= 0.0:
        die('span must be finite and > 0, got %r' % (span,))
    if not math.isfinite(minx) or not math.isfinite(minz):
        die('minx/minz must be finite, got %r %r' % (minx, minz))
    if not outdir.is_dir():
        die('output directory does not exist: %s\n'
            '  it must already hold %s and %s' % (outdir, BASE_NAME, ERODED_NAME))
    return outdir, size, minx, minz, span


def preflight(outdir):
    """Fail before any computation if we would overwrite something."""
    existing = [n for n in OUTPUT_NAMES if (outdir / n).exists()]
    if existing:
        die('refusing to overwrite %d existing output(s) in %s:\n  %s\n'
            '  move or delete them first; inputs %s/%s are never touched'
            % (len(existing), outdir, ', '.join(existing), BASE_NAME, ERODED_NAME),
            code=3)


def sha256_of(path):
    digest = hashlib.sha256()
    with open(str(path), 'rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def load_field(path, size):
    need = size * size * 4
    got = path.stat().st_size
    if got != need:
        die('%s is %d bytes, expected %d = %d*%d*4 big-endian float32.\n'
            '  the <size> argument does not match the export'
            % (path.name, got, need, size, size))
    raw = np.fromfile(str(path), dtype='>f4')
    if raw.size != size * size:
        die('%s decoded to %d samples, expected %d' % (path.name, raw.size, size * size))
    field = raw.reshape(size, size).astype(np.float64)
    bad = int((~np.isfinite(field)).sum())
    if bad:
        die('%s holds %d non-finite samples; refusing to render' % (path.name, bad))
    return field


# ---------------------------------------------------------------------------
# shading (identical maths for both arms)
# ---------------------------------------------------------------------------
def unit_light():
    vec = np.array(LIGHT_XYZ_UP, dtype=np.float64)
    return vec / np.linalg.norm(vec)


def gradients_per_sample(field):
    """np.gradient returns [d/drow, d/dcol]; rows are z, columns are x."""
    gz, gx = np.gradient(field)
    return gx, gz


def reference_gradient(gx, gz):
    """One exaggeration reference, taken from arm 1 only, reused for arm 2."""
    ref = float(np.percentile(np.hypot(gx, gz), 90.0))
    if not (ref > 1e-12):
        return 1.0, True
    return ref, False


def lit_term(gx, gz, gref, light):
    """dot(n, L) with n = (-gx, +1, -gz) and L = (lx, up, lz) pointing at the light."""
    lx, lup, lz = light
    gxn, gzn = gx / gref, gz / gref
    norm = np.sqrt(gxn * gxn + gzn * gzn + 1.0)
    return (-gxn * lx + lup - gzn * lz) / norm


def lit_bounds(light, ref_slope=REF_SLOPE, samples=181):
    """Range of lit_term over every slope of magnitude ref_slope (a circle)."""
    ang = np.linspace(0.0, 2.0 * math.pi, samples)
    vals = lit_term(ref_slope * np.cos(ang), ref_slope * np.sin(ang), 1.0, light)
    return float(vals.min()), float(vals.max())


def ramp_rgb(field, vmin, vmax):
    span = vmax - vmin
    t = np.zeros(field.shape) if span <= 0 else np.clip((field - vmin) / span, 0.0, 1.0)
    out = np.empty(field.shape + (3,), dtype=np.float64)
    for k in range(3):
        out[..., k] = np.interp(t, RAMP_POS, [c[k] for c in RAMP_RGB])
    return out


def render_rgb(field, ctx):
    gx, gz = gradients_per_sample(field)
    lit = lit_term(gx, gz, ctx['gref'], ctx['light'])
    frac = np.clip((lit - ctx['lit_lo']) / (ctx['lit_hi'] - ctx['lit_lo']), 0.0, 1.0)
    shade = SHADE_MIN + (1.0 - SHADE_MIN) * frac
    rgb = ramp_rgb(field, ctx['vmin'], ctx['vmax']) * shade[..., None]
    return np.uint8(np.clip(rgb, 0.0, 255.0))


def ramp_cmap():
    stops = [(p, tuple(c / 255.0 for c in col)) for p, col in zip(RAMP_POS, RAMP_RGB)]
    return LinearSegmentedColormap.from_list('geo_heightfield', stops, N=256)


# ---------------------------------------------------------------------------
# single-arm images (Pillow: one pixel per sample, labels in a band on top)
# ---------------------------------------------------------------------------
def font(px):
    for name in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(name, px)
        except Exception:
            continue
    try:
        return ImageFont.load_default()
    except Exception:
        return None


def window_lines(ctx):
    return [
        ('window x [%.6g .. %.6g]  z [%.6g .. %.6g]  span %.6g  |  %dx%d samples,'
         % (ctx['minx'], ctx['minx'] + ctx['span'], ctx['minz'], ctx['minz'] + ctx['span'],
            ctx['span'], ctx['size'], ctx['size']), 13, (40, 40, 40)),
        ('sample (iz, ix) sits at min + (i + 0.5) * span / size, step = %.6g'
         '   (rows = z, cols = x)' % (ctx['step'],), 13, (40, 40, 40)),
        ('height (dimensionless, as exported) min %.6g max %.6g mean %.6g'
         % (ctx['arm_min'], ctx['arm_max'], ctx['arm_mean']), 13, (40, 40, 40)),
        ('shared ramp %.6g .. %.6g | light (-1, 0.4, 0.05) Rune Common/Image,'
         ' Y-up | 90th-pct slope of arm 1 = 45 deg'
         % (ctx['vmin'], ctx['vmax']), 13, (40, 40, 40)),
    ]


def single_lines(arm_label, ctx):
    head = [(arm_label, 18, (10, 10, 10))]
    return head + window_lines(ctx) + [(DISCLAIMER, 13, (150, 40, 40))]


def write_single(rgb, dest, lines):
    """One pixel per sample; the raster keeps its native size, labels get a band."""
    height, width = rgb.shape[:2]
    canvas_w = max(width, MIN_CANVAS_W)
    head = 12 + sum(px + 7 for _text, px, _col in lines)
    canvas = Image.new('RGB', (canvas_w, head + height), (255, 255, 255))
    canvas.paste(Image.fromarray(rgb), ((canvas_w - width) // 2, head))
    draw = ImageDraw.Draw(canvas)
    y = 8
    for text, px, col in lines:
        draw.text((12, y), text, fill=col, font=font(px))
        y += px + 7
    canvas.save(str(dest))
    return canvas.size


# ---------------------------------------------------------------------------
# comparison image (matplotlib: two exact size x size panels + shared colorbar)
# ---------------------------------------------------------------------------
def add_px_axes(fig, fig_px, x, y_top, ax_w, ax_h):
    total_w, total_h = fig_px
    return fig.add_axes([x / total_w, (total_h - y_top - ax_h) / total_h,
                         ax_w / total_w, ax_h / total_h])


def write_comparison(dest, rgb_base, rgb_eroded, ctx, diff):
    size = ctx['size']
    sc = min(1.0, max(0.5, size / 700.0))
    k = lambda v: int(round(v * sc))                      # noqa: E731
    pad_l, gap, cbar_w, pad_r = k(18), k(26), k(24), k(104)
    head, foot = k(122), k(116)
    fig_w = pad_l + 2 * size + 2 * gap + cbar_w + pad_r
    fig_h = head + size + foot
    fig = plt.figure(figsize=(fig_w / DPI, fig_h / DPI), dpi=DPI, facecolor='white')

    titles = (ARM1, ARM2)
    subs = ('min %.6g  max %.6g  mean %.6g' % (ctx['b_min'], ctx['b_max'], ctx['b_mean']),
            'min %.6g  max %.6g  mean %.6g' % (ctx['e_min'], ctx['e_max'], ctx['e_mean']))
    for i, rgb in enumerate((rgb_base, rgb_eroded)):
        left = pad_l + i * (size + gap)
        ax = add_px_axes(fig, (fig_w, fig_h), left, head, size, size)
        ax.imshow(rgb, extent=ctx['extent'], interpolation='nearest', aspect='auto')
        ax.set_xticks([])
        ax.set_yticks([])
        for spine in ax.spines.values():
            spine.set_linewidth(0.8)
            spine.set_edgecolor('#333333')
        fig.text(left / fig_w, 1 - k(78) / fig_h, titles[i], ha='left', va='baseline',
                 fontsize=12.5 * sc, color='#0a0a0a')
        fig.text(left / fig_w, 1 - k(98) / fig_h, subs[i], ha='left', va='baseline',
                 fontsize=9.5 * sc, color='#333333')

    fig.text(k(12) / fig_w, 1 - k(12) / fig_h,
             'Two arms of the same exported window, same ramp and light (1 px per sample)',
             ha='left', va='baseline', fontsize=15 * sc, color='#0a0a0a')
    fig.text(k(12) / fig_w, 1 - k(34) / fig_h,
             'height dimensionless | window x [%.6g .. %.6g]  z [%.6g .. %.6g]  span %.6g'
             ' | %dx%d samples, step %.6g' % (ctx['minx'], ctx['minx'] + ctx['span'],
                                              ctx['minz'], ctx['minz'] + ctx['span'],
                                              ctx['span'], size, size, ctx['step']),
             ha='left', va='baseline', fontsize=10.5 * sc, color='#333333')
    fig.text(k(12) / fig_w, 1 - k(52) / fig_h,
             'Independent approximate visualization of a height field (%s)' % DISCLAIMER,
             ha='left', va='baseline', fontsize=10.5 * sc, color='#96281e')

    cax = add_px_axes(fig, (fig_w, fig_h), pad_l + 2 * size + 2 * gap, head, cbar_w, size)
    bar = fig.colorbar(ScalarMappable(norm=Normalize(ctx['vmin'], ctx['vmax']),
                                      cmap=ramp_cmap()), cax=cax)
    bar.set_label('height (dimensionless, as exported)', fontsize=9.5 * sc)
    bar.ax.tick_params(labelsize=8.5 * sc)
    bar.outline.set_linewidth(0.6)

    foot_rows = [
        ('shared ramp %.6g .. %.6g (both arms) | light (-1, 0.4, 0.05) Rune Common/Image,'
         ' Y-up | one exaggeration for both arms: 90th-pct slope of arm 1 = 45 deg'
         % (ctx['vmin'], ctx['vmax']), '#333333'),
        ('eroded - base: mean|d| %.6g  max|d| %.6g at x=%.6g z=%.6g  |  |d| > 1e-6 in %d of %d'
         ' samples (%.4f%%)' % (diff['mean_abs'], diff['max_abs'], diff['max_abs_at']['x'],
                                diff['max_abs_at']['z'], diff['changed_samples'],
                                size * size, 100.0 * diff['changed_fraction']), '#333333'),
        ('The two panels differ ONLY in the height field: identical window, grid, ramp, light'
         ' and exaggeration. NaN/inf are rejected, nothing is masked or blended', '#333333'),
        ('per-sample gradients (height is dimensionless, so no world-unit slope is implied)', '#666666'),
    ]
    y = head + size
    for i, (text, col) in enumerate(foot_rows):
        fig.text(k(12) / fig_w, 1 - (y + k(16) + i * k(20)) / fig_h, text,
                 ha='left', va='baseline', fontsize=9.5 * sc, color=col)

    fig.savefig(str(dest), dpi=DPI, facecolor='white')
    plt.close(fig)
    with Image.open(str(dest)) as probe:
        return probe.size


# ---------------------------------------------------------------------------
# profiles image (three horizontal cuts, unified X and Y axes)
# ---------------------------------------------------------------------------
def write_profiles(dest, h_base, h_eroded, ctx):
    size, step = ctx['size'], ctx['step']
    xs = ctx['minx'] + (np.arange(size) + 0.5) * step
    fracs = (0.25, 0.50, 0.75)
    fig, axes = plt.subplots(3, 1, figsize=(11.5, 8.6), sharex=True, sharey=True)
    for row_frac, ax in zip(fracs, axes):
        iz = int(round(row_frac * (size - 1)))
        z = ctx['minz'] + (iz + 0.5) * step
        delta = h_eroded[iz] - h_base[iz]
        ax.plot(xs, h_base[iz], color='#1f4f7a', lw=1.6, label=ARM1_SHORT)
        ax.plot(xs, h_eroded[iz], color='#b5511f', lw=1.2, ls='--', label=ARM2_SHORT)
        ax.set_title('z cut at %d%% of the window  ->  row iz=%d, z=%.6g  |  mean|d| %.4g,'
                     ' max|d| %.4g' % (int(round(row_frac * 100)), iz, z,
                                       float(np.abs(delta).mean()), float(np.abs(delta).max())),
                     loc='left', fontsize=9.5)
        ax.grid(alpha=0.25, lw=0.6)
        ax.set_ylabel('height (dimensionless)', fontsize=8.5)
    axes[0].legend(loc='upper right', fontsize=8.5, framealpha=0.9)
    pad = 0.04 * (ctx['vmax'] - ctx['vmin'] or 1.0)
    axes[0].set_xlim(ctx['minx'], ctx['minx'] + ctx['span'])
    axes[0].set_ylim(ctx['vmin'] - pad, ctx['vmax'] + pad)
    axes[-1].set_xlabel('x  (sample-centre coordinate, span %.6g, step %.6g)'
                        % (ctx['span'], step), fontsize=9.5)
    fig.suptitle('Horizontal height profiles: three cuts perpendicular to z at 25% / 50% / 75%',
                 fontsize=13)
    fig.text(0.01, 0.945,
             'Identical X and Y limits in every panel. Solid = %s; dashed = %s.'
             % (ARM1_SHORT, ARM2_SHORT), fontsize=9, color='#333333')
    fig.text(0.01, 0.922,
             'Height dimensionless, as exported. %s.' % DISCLAIMER,
             fontsize=9, color='#96281e')
    fig.tight_layout(rect=(0.0, 0.0, 1.0, 0.905))
    fig.savefig(str(dest), dpi=110, facecolor='white')
    plt.close(fig)
    with Image.open(str(dest)) as probe:
        return probe.size


# ---------------------------------------------------------------------------
# stats.json
# ---------------------------------------------------------------------------
def arm_stats(field):
    return {'min': float(field.min()), 'max': float(field.max()),
            'mean': float(field.mean()), 'std': float(field.std()),
            'median': float(np.median(field)), 'finite_samples': int(field.size)}


def diff_stats(h_base, h_eroded, ctx):
    delta = h_eroded - h_base
    adelta = np.abs(delta)
    iz, ix = divmod(int(np.argmax(adelta)), adelta.shape[1])
    mag = adelta.ravel()
    return {
        'definition': 'eroded - base, per sample, same grid',
        'mean_abs': float(adelta.mean()),
        'max_abs': float(adelta.max()),
        'rms': float(np.sqrt((delta * delta).mean())),
        'signed_mean': float(delta.mean()),
        'p50_abs': float(np.percentile(mag, 50)),
        'p95_abs': float(np.percentile(mag, 95)),
        'p99_abs': float(np.percentile(mag, 99)),
        'identical': bool(np.array_equal(h_base, h_eroded)),
        'changed_threshold': 1e-6,
        'changed_samples': int((adelta > 1e-6).sum()),
        'changed_fraction': float((adelta > 1e-6).mean()),
        'max_abs_at': {
            'iz': int(iz), 'ix': int(ix),
            'x': float(ctx['minx'] + (ix + 0.5) * ctx['step']),
            'z': float(ctx['minz'] + (iz + 0.5) * ctx['step']),
            'base': float(h_base[iz, ix]), 'eroded': float(h_eroded[iz, ix]),
        },
    }


def image_meta(path, note):
    with Image.open(str(path)) as probe:
        width, height = probe.size
    return {'path': str(path), 'note': note, 'sha256': sha256_of(path),
            'bytes': int(path.stat().st_size), 'width': int(width), 'height': int(height)}


def build_stats(outdir, size, sha_in, h_base, h_eroded, ctx, diff, images):
    step = ctx['step']
    outputs = dict(images)
    outputs['stats.json'] = {'path': str(outdir / OUT_STATS),
                             'note': 'this file; not hashed because it contains the hashes'}
    return {
        'tool': 'forge-1.20.1-47.4.10-mdk/tools/render-rune-original.py',
        'purpose': 'independent replot of the two exported ORIGINAL (upstream Rune) arms',
        'input_dir': str(outdir),
        'inputs': {
            BASE_NAME: {'path': str(outdir / BASE_NAME), 'bytes': int((outdir / BASE_NAME).stat().st_size),
                        'dtype': 'big-endian float32 (>f4)', 'sha256': sha_in[BASE_NAME]},
            ERODED_NAME: {'path': str(outdir / ERODED_NAME),
                          'bytes': int((outdir / ERODED_NAME).stat().st_size),
                          'dtype': 'big-endian float32 (>f4)', 'sha256': sha_in[ERODED_NAME]},
        },
        'grid': {
            'shape': [size, size], 'size': size,
            'row_axis': 'z', 'column_axis': 'x',
            'minx': ctx['minx'], 'minz': ctx['minz'], 'span': ctx['span'], 'step': step,
            'x_range': [ctx['minx'], ctx['minx'] + ctx['span']],
            'z_range': [ctx['minz'], ctx['minz'] + ctx['span']],
            'sample_position': 'value(iz, ix) at x = minx + (ix + 0.5) * step,'
                               ' z = minz + (iz + 0.5) * step (cell centres)',
            'pixels_per_sample': 1,
        },
        'arms': {'base': arm_stats(h_base), 'eroded': arm_stats(h_eroded),
                 'labels': {'base': ARM1, 'eroded': ARM2}},
        'height': {
            'units': 'dimensionless, as exported (no world Y, no sea level, no water drawn)',
            'shared_vmin': ctx['vmin'], 'shared_vmax': ctx['vmax'],
            'shared_range_rule': 'min/max over BOTH arms, so one ramp serves both',
        },
        'diff': diff,
        'render': {
            'light_xyz_up': list(LIGHT_XYZ_UP),
            'light_unit': [float(v) for v in ctx['light']],
            'light_source': 'Rune reference renderer, Common/Image; interpreted as X right,'
                            ' Y up, Z forward (approximate match, not a byte-exact port)',
            'surface_normal': 'n = (-gx, +1, -gz), gx/gz = per-sample gradients',
            'tone_ramp': 'shade = %.2f + %.2f * clip((lit - lo) / (hi - lo)), lo/hi from a'
                         ' %.1f-slope circle' % (SHADE_MIN, 1.0 - SHADE_MIN, REF_SLOPE),
            'lit_lo': ctx['lit_lo'], 'lit_hi': ctx['lit_hi'],
            'ramp_positions': list(RAMP_POS), 'ramp_colors': [list(c) for c in RAMP_RGB],
            'gradient_units': 'height per sample (height is dimensionless; no world-block'
                              ' slope is implied)',
            'vertical_exaggeration_ref': ctx['gref'],
            'vertical_exaggeration_rule': '90th percentile |grad| of ARM 1 drawn as 45 deg;'
                                          ' the same reference is reused for ARM 2',
            'base_field_flat': ctx['flat'],
            'same_light_both_arms': True, 'same_ramp_both_arms': True,
            'same_exaggeration_both_arms': True, 'runtime_upscaling': False,
            'renderer': 'numpy + Pillow + matplotlib (no game code, no mod import, no morph)',
        },
        'rendering_contract': {
            'base.png and eroded.png differ ONLY in the height field':
                'identical window, sampling grid, output size, hypsometric ramp, hillshade'
                ' light and vertical exaggeration; every other input to the two images is'
                ' bit-identical',
            'all five outputs are heightfield rasterizations':
                'plain colormapped+hillshaded plots of the exported float arrays',
            'not a game render':
                'no Minecraft/game screenshot, no water, no trees, no vanilla 3D block'
                ' materials, no in-game lighting or textures, no sea level',
            'approximation': 'an independent visualization, not the upstream tool\'s own'
                             ' renderer; tone ramp and framing are chosen here',
            'inputs_untouched': 'base.f32be / eroded.f32be are only read; no output'
                                ' overwrites them',
        },
        'outputs': outputs,
    }


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def main(argv):
    outdir, size, minx, minz, span = parse_args(argv)
    preflight(outdir)
    path_base, path_eroded = outdir / BASE_NAME, outdir / ERODED_NAME
    for path in (path_base, path_eroded):
        if not path.is_file():
            die('missing input: %s' % path)
    sha_in = {BASE_NAME: sha256_of(path_base), ERODED_NAME: sha256_of(path_eroded)}
    h_base = load_field(path_base, size)
    h_eroded = load_field(path_eroded, size)

    vmin = float(min(h_base.min(), h_eroded.min()))
    vmax = float(max(h_base.max(), h_eroded.max()))
    flat_range = not (vmax > vmin)
    if flat_range:
        vmax = vmin + 1.0
    gref, flat = reference_gradient(*gradients_per_sample(h_base))
    light = unit_light()
    lit_lo, lit_hi = lit_bounds(light)
    ctx = {'size': size, 'minx': minx, 'minz': minz, 'span': span, 'step': span / size,
           'vmin': vmin, 'vmax': vmax, 'gref': gref, 'flat': flat, 'light': light,
           'lit_lo': lit_lo, 'lit_hi': lit_hi,
           'extent': (minx, minx + span, minz + span, minz),
           'b_min': float(h_base.min()), 'b_max': float(h_base.max()),
           'b_mean': float(h_base.mean()),
           'e_min': float(h_eroded.min()), 'e_max': float(h_eroded.max()),
           'e_mean': float(h_eroded.mean())}

    rgb_base = render_rgb(h_base, ctx)
    rgb_eroded = render_rgb(h_eroded, ctx)
    ctx_base = dict(ctx, arm_min=ctx['b_min'], arm_max=ctx['b_max'], arm_mean=ctx['b_mean'])
    ctx_eroded = dict(ctx, arm_min=ctx['e_min'], arm_max=ctx['e_max'], arm_mean=ctx['e_mean'])
    size_base = write_single(rgb_base, outdir / OUT_BASE, single_lines(ARM1, ctx_base))
    size_eroded = write_single(rgb_eroded, outdir / OUT_ERODED, single_lines(ARM2, ctx_eroded))

    diff = diff_stats(h_base, h_eroded, ctx)
    size_cmp = write_comparison(outdir / OUT_COMPARISON, rgb_base, rgb_eroded, ctx, diff)
    size_prof = write_profiles(outdir / OUT_PROFILES, h_base, h_eroded, ctx)

    images = {
        OUT_BASE: image_meta(outdir / OUT_BASE, 'ARM 1, 1 px per sample, label band on top'),
        OUT_ERODED: image_meta(outdir / OUT_ERODED, 'ARM 2, same window/ramp/light as ARM 1'),
        OUT_COMPARISON: image_meta(outdir / OUT_COMPARISON,
                                   'two size x size panels side by side + shared colorbar'),
        OUT_PROFILES: image_meta(outdir / OUT_PROFILES,
                                 'three z cuts at 25/50/75%, unified X and Y limits'),
    }
    stats = build_stats(outdir, size, sha_in, h_base, h_eroded, ctx, diff, images)
    (outdir / OUT_STATS).write_text(json.dumps(stats, indent=2, ensure_ascii=True) + '\n',
                                    encoding='utf-8')

    print('render-rune-original: window x [%.6g .. %.6g] z [%.6g .. %.6g] span %.6g, %dx%d'
          % (minx, minx + span, minz, minz + span, span, size, size))
    for name, px in ((OUT_BASE, size_base), (OUT_ERODED, size_eroded),
                     (OUT_COMPARISON, size_cmp), (OUT_PROFILES, size_prof)):
        print('  %-15s %dx%d  %s  %s' % (name, px[0], px[1], images[name]['sha256'],
                                         outdir / name))
    print('  %-15s %s' % (OUT_STATS, outdir / OUT_STATS))
    print('  arm1 %.6g..%.6g | arm2 %.6g..%.6g | mean|d| %.6g max|d| %.6g%s'
          % (ctx['b_min'], ctx['b_max'], ctx['e_min'], ctx['e_max'], diff['mean_abs'],
             diff['max_abs'], '  (fields identical)' if diff['identical'] else ''))
    if flat:
        print('  note: arm 1 has no gradient (flat field), exaggeration reference forced to 1')
    if flat_range:
        print('  note: both arms are constant, ramp range widened by 1.0 to stay drawable')


if __name__ == '__main__':
    main(sys.argv)
