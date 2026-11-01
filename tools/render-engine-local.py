"""Render Java-exported P2 weight sweeps; not a spatial world or coastline render."""
import csv
import sys
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

source, output = map(Path, sys.argv[1:3])
output.mkdir(parents=True, exist_ok=True)
plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'DejaVu Sans']
plt.rcParams['axes.unicode_minus'] = False
rows = list(csv.DictReader(source.open(encoding='utf-8')))
labels = {
    '20-22': '海山群 → 陆架沙脊', '19-21': '洋中脊 → 海底峡谷',
    '3-18': '丘陵1 → 深海平原', '15-22': '台地 → 陆架沙脊', '17-19': '山地 → 洋中脊',
}
fig, axes = plt.subplots(5, 1, figsize=(12, 13), sharex=True, sharey=True)
for ax, (pair, title) in zip(axes, labels.items()):
    group = [r for r in rows if r['pair'] == pair]
    t = [float(r['t']) for r in group]
    ax.plot(t, [float(r['e']) for r in group], label='同一节点内核', color='#195d83', lw=2)
    ax.plot(t, [float(r['node_finished_lerp']) for r in group], label='成品高度 lerp（仅作对照）', color='#b56942', ls='--')
    ax.axhline(0, color='#444444', lw=.7)
    ax.set_title(title, loc='left', fontsize=11)
    ax.set_ylabel('e（无量纲）')
    ax.grid(alpha=.2)
axes[0].legend(loc='upper right')
axes[-1].set_xlabel('第二配方权重 t（不是世界距离；没有接入空间分布）')
fig.suptitle('P2 局部节点权重剖面｜同一点 (-2000, -2175)\n仅验证求值内核，不含海岸加工，不是完整世界效果图', fontsize=14)
fig.tight_layout(rect=(0, 0, 1, .95))
fig.savefig(output / 'local-weight-profiles.png', dpi=140)
plt.close(fig)
print(output / 'local-weight-profiles.png')
