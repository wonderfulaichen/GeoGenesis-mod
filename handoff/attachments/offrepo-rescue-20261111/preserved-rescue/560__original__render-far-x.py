# Far-field figure for the current (sqrt-cull) candidate.
# Adapted from the merged arm's render-far-v2.py; the two things that had to be fixed here:
#   * the input path is a parameter, not a hard-wired "optimized" directory, so the figure cannot
#     silently keep showing an older product;
#   * identity is computed by hashing the actual input CSV and the actual sources at render time,
#     instead of reading a possibly stale identity-freeze.json (a stale hash on a figure is worse
#     than no hash: it claims provenance it does not have).
import csv
import hashlib
import sys
from pathlib import Path

import numpy as np
import matplotlib
import matplotlib.ticker
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import LightSource

p = Path(__file__).resolve().parent
stage = sys.argv[1] if len(sys.argv) > 1 else 'indexed'
grid_path = p / stage / 'far-grid.csv'
land_src = p / 'diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DoubleFullMorphLand.java'
geo_src = p / 'diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DoubleBoundaryGeometry.java'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()


rows = list(csv.DictReader(grid_path.open()))
assert len(rows) == 4096, f'expected 4096 samples, got {len(rows)}'
x = np.array([float(r['x']) for r in rows]).reshape(64, 64)
z = np.array([float(r['z']) for r in rows]).reshape(64, 64)
h = np.array([float(r['finalY']) for r in rows]).reshape(64, 64)
assert np.all(np.diff(x, axis=1) == 1) and np.all(np.diff(z, axis=0) == 1), 'grid not contiguous'
assert np.isfinite(h).all(), 'non-finite far-field height'

grid_sha = sha(grid_path)
land_sha = sha(land_src)
geo_sha = sha(geo_src)
# class files are deliberately NOT used as identity: javac output is not bit-reproducible across
# runs/JDK builds, so a class hash would look like evidence while proving nothing about the source.
tag = f'land-src {land_sha[:16]}... + geo-src {geo_sha[:16]}...'
disclaimer = (f'Input {stage}/far-grid.csv sha256 {grid_sha[:16]}...  '
              'Height A/B vs canonical 7BE04183: see compare/metrics.json. '
              'Comparison is NOT acceptance.')

ls = LightSource(azdeg=315, altdeg=45)
rgb = ls.shade(h, cmap=plt.get_cmap('terrain'), vert_exag=1, dx=1, dy=1, blend_mode='overlay')
plt.imsave(p / 'far-height-shade-native-64x64.png', rgb, origin='upper')

fig, ax = plt.subplots(figsize=(9, 9))
fig.subplots_adjust(left=.10, right=.97, top=.90, bottom=.16)
ax.imshow(rgb, origin='upper', interpolation='nearest',
          extent=(x[0, 0] - .5, x[0, -1] + .5, z[-1, 0] + .5, z[0, 0] - .5), aspect='equal')
for i, color in [(0, 'cyan'), (31, 'magenta'), (63, 'yellow')]:
    ax.axhline(z[i, 0], color=color, linewidth=.8, label=f'z={z[i,0]:.1f}')
ax.set_xlabel('world x (blocks)')
ax.set_ylabel('world z (blocks; increases downward)')
ax.xaxis.set_major_locator(matplotlib.ticker.MaxNLocator(6))
ax.yaxis.set_major_locator(matplotlib.ticker.MaxNLocator(8))
ax.tick_params(axis='x', labelrotation=20)
ax.ticklabel_format(useOffset=False, style='plain')
ax.legend(loc='upper right', framealpha=.9)
ax.set_title('Far field 64x64 block-center final height / hillshade (nearest neighbor)\n'
             'seed 9139912035078620160 | identity: ' + tag, fontsize=10)
fig.text(.02, .015, f'DoubleFullMorphLand.java SHA-256: {land_sha}\n'
                    f'DoubleBoundaryGeometry.java SHA-256: {geo_sha}\n'
                    f'{disclaimer}', fontsize=7, va='bottom')
fig.savefig(p / 'far-height-shade-annotated.png', dpi=180)
plt.close(fig)

fig, axes = plt.subplots(3, 1, figsize=(11, 9), sharex=True)
fig.subplots_adjust(left=.09, right=.97, top=.90, bottom=.14, hspace=.30)
for ax, i, color in zip(axes, [0, 31, 63], ['teal', 'purple', 'darkgoldenrod']):
    ax.plot(x[i, :], h[i, :], color=color, marker='.', markersize=3, linewidth=1)
    ax.set_title(f'world z = {z[i,0]:.1f}   |   64 actual samples, step 1 block', fontsize=9)
    ax.set_ylabel('final height Y')
    ax.grid(alpha=.3)
    ax.ticklabel_format(useOffset=False, style='plain')
axes[-1].xaxis.set_major_locator(matplotlib.ticker.MaxNLocator(8))
axes[-1].tick_params(axis='x', labelrotation=20)
axes[-1].set_xlabel('world x (block centers)')
fig.suptitle('Far-field actual height profiles | seed 9139912035078620160\nidentity: ' + tag, fontsize=9)
fig.text(.02, .012, disclaimer, fontsize=7, va='bottom')
fig.savefig(p / 'far-three-profiles.png', dpi=180)
plt.close(fig)

print('stage', stage)
print('far-grid.csv      ', grid_sha)
print('land source       ', land_sha)
print('geometry source   ', geo_sha)
for name in ['far-height-shade-native-64x64.png', 'far-height-shade-annotated.png',
             'far-three-profiles.png']:
    print(name, sha(p / name))
