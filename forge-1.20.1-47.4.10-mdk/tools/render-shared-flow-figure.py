"""Render the diagnostic figure from exported grids and probe CSVs. Read-only: never writes source."""
from pathlib import Path
import sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import LightSource
import numpy as np

BASE = Path('build/local-flow-reassessment')
FIG = BASE / 'figure'
RUN = BASE / 'shared-final3'
NODATA = -9999.0


def grid(name):
    path = FIG / (name + '.csv')
    data = np.loadtxt(path, delimiter=',', skiprows=1)
    xs, zs, hs = data[:, 0], data[:, 1], data[:, 2]
    ux, uz = np.unique(xs), np.unique(zs)
    if len(ux) * len(uz) != len(hs):
        raise SystemExit(f'{name}: grid is not a full rectangle')
    return ux, uz, hs.reshape(len(ux), len(uz)).T


def shade(ax, ux, uz, z, title, cmap='gist_earth'):
    dx = float(ux[1] - ux[0]) if len(ux) > 1 else 1.0
    dz = float(uz[1] - uz[0]) if len(uz) > 1 else 1.0
    ls = LightSource(azdeg=315, altdeg=45)
    rgb = ls.shade(z, cmap=plt.get_cmap(cmap), blend_mode='overlay',
                   vert_exag=2.0, dx=dx, dy=dz)
    ax.imshow(rgb, origin='lower',
              extent=(ux[0] - dx / 2, ux[-1] + dx / 2, uz[0] - dz / 2, uz[-1] + dz / 2),
              interpolation='nearest')
    ax.set_title(title, fontsize=9)
    ax.set_xlabel('block X', fontsize=8)
    ax.set_ylabel('block Z', fontsize=8)
    ax.tick_params(labelsize=7)


def read(name, columns=None):
    """CSV rows as strings. Free-text trailing columns may contain commas, so the split is bounded."""
    path = RUN / name
    if not path.exists():
        return None
    lines = [line for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]
    if not lines:
        return None
    body = lines[1:]
    if columns is None:
        columns = max(len(line.split(',')) for line in body)
    rows = []
    for line in body:
        parts = line.split(',', columns - 1)
        parts += [''] * (columns - len(parts))
        rows.append(parts[:columns])
    return np.array(rows, dtype=object)


def birth_keys(traj):
    """Composite (birthCellX, birthCellZ) identity, as plain strings so numpy never coerces a dtype."""
    return [f'{a}|{b}' for a, b in zip(traj[:, 0], traj[:, 1])]


def overlay_paths(ax, lw=0.9):
    shared = read('receiver/real-shared-path.csv')
    if shared is not None and len(shared):
        ax.plot(shared[:, 0].astype(float), shared[:, 1].astype(float), color='#1f6feb', lw=lw + 0.6,
                label='shared receiver walk')
    traj = read('rolling/real-trajectories.csv')
    if traj is not None and len(traj):
        keys = birth_keys(traj)
        first = None
        for birth in sorted(set(keys)):
            rows = traj[[key == birth for key in keys]]
            rows = rows[np.argsort(rows[:, 3].astype(int))]
            ax.plot(rows[:, 4].astype(float), rows[:, 5].astype(float), color='#f0883e',
                    lw=lw, alpha=0.85, label='G42 raw trajectories' if first is None else None)
            first = birth


def fig_overview():
    ux, uz, z = grid('combined')
    fig, ax = plt.subplots(figsize=(9.5, 8.2), dpi=150)
    shade(ax, ux, uz, z, 'GeoGenesis erodedHeight (diagnostic) + this round\'s flow evidence')
    overlay_paths(ax)
    ax.plot([-428], [-94], marker='*', ms=13, color='#d62728', mec='k', mew=0.6,
            label='block basin floor (-428,-94)')
    cert = read('block-handoff/block-certificate.csv')
    if cert is not None:
        ax.plot(cert[:, 1].astype(float), cert[:, 2].astype(float), 's', ms=5,
                mfc='none', mec='#00e5ff', mew=1.2, label='block certificate members (9)')
    ax.legend(loc='lower left', fontsize=7, framealpha=0.85)
    ax.text(0.99, 0.01, 'DIAGNOSTIC GEOMETRY - not a provider lake/river image',
            transform=ax.transAxes, ha='right', fontsize=6.5, color='w')
    fig.tight_layout()
    out = FIG / 'FIG1-overview.png'
    fig.savefig(out)
    plt.close(fig)
    return out


def fig_basin():
    ux, uz, z = grid('basin-detail')
    fig, ax = plt.subplots(figsize=(8.4, 7.2), dpi=170)
    shade(ax, ux, uz, z, 'Basin handoff: block certificate, first outlet, backwater sink (step=1 block)')
    cert = read('block-handoff/block-certificate.csv')
    down = read('block-handoff/block-certificate-downstream.csv')
    trace = read('block-handoff/outlet-trace.csv')
    if cert is not None:
        ax.plot(cert[:, 1].astype(float), cert[:, 2].astype(float), 's', ms=9,
                mfc='none', mec='#00e5ff', mew=1.5, label='upstream basin members (escape 162.741)')
    if down is not None:
        ax.plot(down[:, 1].astype(float), down[:, 2].astype(float), '.', ms=2.0,
                color='#7c3aed', label='downstream basin members (170, escape 163.356)')
    if trace is not None:
        kinds = trace[:, 0]
        for kind, colour, style in (('descent', '#ffffff', '-'), ('downDescent', '#ffd400', '-')):
            rows = trace[kinds == kind]
            if len(rows):
                ax.plot(rows[:, 1].astype(float), rows[:, 2].astype(float), style, color=colour,
                        lw=2.0, marker='o', ms=3, label=f'{kind} (unit 4-neighbour)')
        sill = trace[kinds == 'sillCut']
        if len(sill):
            ax.plot(sill[:, 1].astype(float), sill[:, 2].astype(float), 'D', ms=10, mfc='none',
                    mec='#ff2d95', mew=2.0, label='unique at-sill outlet (-428,-96)')
        seed = trace[kinds == 'receiverSeed']
        if len(seed):
            ax.plot(seed[:, 1].astype(float), seed[:, 2].astype(float), 'P', ms=11,
                    color='#00ff88', mec='k', mew=0.6, label='receiving bed (-428,-97)')
        sinks = trace[kinds == 'downstreamSink']
        if len(sinks):
            ax.plot(sinks[:, 1].astype(float), sinks[:, 2].astype(float), 'X', ms=12,
                    color='#ff6b6b', mec='k', mew=0.6, label='downstream sink (-428,-98)')
    # The 9-column basin is sub-pixel at this extent, so the chain is repeated in a magnified inset.
    axins = ax.inset_axes([0.56, 0.05, 0.42, 0.40])
    shade(axins, ux, uz, z, '')
    if cert is not None:
        axins.plot(cert[:, 1].astype(float), cert[:, 2].astype(float), 's', ms=10, mfc='none',
                   mec='#00e5ff', mew=1.6)
    if trace is not None:
        kinds = trace[:, 0]
        for kind, colour in (('descent', '#ffffff'), ('downDescent', '#ffd400')):
            rows = trace[kinds == kind]
            if len(rows):
                axins.plot(rows[:, 1].astype(float), rows[:, 2].astype(float), '-', color=colour,
                           lw=2.2, marker='o', ms=4)
        for kind, colour, marker in (('sillCut', '#ff2d95', 'D'), ('receiverSeed', '#00ff88', 'P'),
                                     ('downstreamSink', '#ff6b6b', 'X')):
            rows = trace[kinds == kind]
            if len(rows):
                axins.plot(rows[:, 1].astype(float), rows[:, 2].astype(float), marker, ms=9,
                           mfc='none' if marker == 'D' else colour, mec=colour, mew=1.6)
    axins.set_xlim(-432, -423)
    axins.set_ylim(-101, -89)
    axins.tick_params(labelsize=6)
    axins.set_title('magnified: 9-column basin + outlet chain', fontsize=7)
    ax.legend(loc='upper right', fontsize=7, framealpha=0.82)
    ax.text(0.01, 0.01, 'members are block columns; escape/floor are heights, not a lake level',
            transform=ax.transAxes, fontsize=6.5, color='w')
    fig.tight_layout()
    out = FIG / 'FIG2-basin-handoff.png'
    fig.savefig(out)
    plt.close(fig)
    return out


def fig_receiver():
    ux, uz, z = grid('receiver-detail')
    fig, axes = plt.subplots(1, 2, figsize=(12.4, 6.0), dpi=160)
    shade(axes[0], ux, uz, z, 'G42 raw trajectories from world-born events')
    traj = read('rolling/real-trajectories.csv')
    if traj is not None and len(traj):
        keys = birth_keys(traj)
        cmap = plt.get_cmap('tab20')
        for index, birth in enumerate(sorted(set(keys))):
            rows = traj[[key == birth for key in keys]]
            rows = rows[np.argsort(rows[:, 3].astype(int))]
            axes[0].plot(rows[:, 4].astype(float), rows[:, 5].astype(float),
                         color=cmap(index % 20), lw=0.9)
    shade(axes[1], ux, uz, z, 'Same terrain: one shared receiver per block cell')
    recv = read('receiver/real-receivers.csv')
    incoming = read('receiver/real-incoming.csv')
    if recv is not None and len(recv):
        for row in recv:
            if row[2] == '':
                axes[1].plot([float(row[0])], [float(row[1])], 'x', ms=7, color='#ff2d95',
                             mew=1.6)
                continue
            multi = False
            if incoming is not None:
                multi = int(np.sum((incoming[:, 0] == row[0]) & (incoming[:, 1] == row[1]))) > 1
            axes[1].annotate('', xy=(float(row[2]), float(row[3])), xytext=(float(row[0]), float(row[1])),
                             arrowprops=dict(arrowstyle='-|>', color='#1f6feb' if multi else '#9be9a8',
                                             lw=1.6 if multi else 0.8,
                                             shrinkA=0, shrinkB=0))
    axes[1].set_xlim(axes[0].get_xlim())
    axes[1].set_ylim(axes[0].get_ylim())
    axes[1].text(0.01, 0.01, 'blue = cell fed by more than one event; x = declared pending (not dry)',
                 transform=axes[1].transAxes, fontsize=6.5, color='w')
    fig.suptitle('Source momentum is dropped at the shared receiver: same cell, same next cell '
                 '(diagnostic candidate, not production)', fontsize=9)
    fig.tight_layout(rect=(0, 0, 1, 0.96))
    out = FIG / 'FIG3-receiver.png'
    fig.savefig(out)
    plt.close(fig)
    return out


def fig_profile():
    """A real longitudinal profile along the chain column: height against block Z, plus a zoom on the chain."""
    ux, uz, z = grid('basin-detail')
    chain = read('block-handoff/outlet-trace.csv')
    column_x = -428
    heights = z[:, np.searchsorted(ux, column_x)]
    fig, (ax, zoom) = plt.subplots(2, 1, figsize=(9.4, 7.0), dpi=160,
                                   gridspec_kw={'height_ratios': (1.0, 1.25)})
    marks = {'blockSink': ('floor 162.489', '#ff6b6b', 'v'),
             'sillCut': ('at-sill outlet 162.741', '#ff2d95', 'D'),
             'receiverSeed': ('receiving bed 162.592', '#00c853', 'P'),
             'downstreamSink': ('downstream sink 162.470', '#7c3aed', 'X')}
    for axis in (ax, zoom):
        axis.plot(uz, heights, color='#8b949e', lw=1.3,
                  label=f'erodedHeight along x={column_x} (1-block samples)')
        if chain is not None:
            seen = set()
            for row in chain:
                if row[0] not in marks:
                    continue
                label, colour, marker = marks[row[0]]
                axis.plot([float(row[2])], [float(row[3])], marker, ms=10, color=colour,
                          mec='k', mew=0.6, label=label if row[0] not in seen else None)
                seen.add(row[0])
            escape = [float(row[3]) for row in chain if row[0] == 'downstreamEscape']
            if escape:
                axis.axhline(escape[0], color='#ffd400', ls='--', lw=1.2,
                             label=f'downstream escape {escape[0]:.3f} (170-member merge)')
        axis.set_xlabel('block Z along the chain', fontsize=8)
        axis.set_ylabel('height (blocks)', fontsize=8)
        axis.tick_params(labelsize=7)
        axis.grid(alpha=0.25, lw=0.4)
    ax.set_title('Longitudinal profile through the outlet chain (full sampled window)', fontsize=9)
    zoom.set_xlim(-104, -88)
    zoom.set_ylim(162.2, 163.6)
    zoom.set_title('Zoom: floor 162.489 -> single at-sill 162.741 -> bed 162.592 -> sink 162.470, '
                   'downstream escape 163.356 above the upstream sill', fontsize=8.5)
    for row in chain if chain is not None else []:
        if row[0] not in marks:
            continue
        zoom.annotate(f'{row[0]}\nz={row[2]}', (float(row[2]), float(row[3])),
                      textcoords='offset points', xytext=(7, 8), fontsize=6.5, color=marks[row[0]][1])
    zoom.legend(loc='upper left', fontsize=7, framealpha=0.9)
    fig.tight_layout()
    out = FIG / 'FIG4-profile.png'
    fig.savefig(out)
    plt.close(fig)
    old = FIG / 'FIG4-chain.png'
    if old.exists():
        old.unlink()
    return out


def main():
    if not (FIG / 'combined.csv').exists():
        sys.exit('missing exported grids; run SharedFlowTerrainExport first')
    made = [fig_overview(), fig_basin(), fig_receiver(), fig_profile()]
    for path in made:
        print(f'{path} {path.stat().st_size} bytes')
    print("DIAGNOSTIC FIGURES ONLY: terrain + this round's evidence; no provider, no water, no acceptance")


if __name__ == '__main__':
    main()
