"""沙盘 LOD 对比出图（★ 2026-11-08）。

复用 render_sandbox_offline.py 的渲染函数，按 LOD 档位导出不同窗口的图，
用来验证「推近能看见河流湖泊」。

用法：
    python tools/render_sandbox_lod.py <world_tsv_dir> <out_dir>

world_tsv_dir 需含 lod_<level>.tsv（由 Java 探针导出）。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import render_sandbox_offline as R


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    for name in sorted(os.listdir(src_dir)):
        if not (name.startswith('lod_') and name.endswith('.tsv')):
            continue
        level = name[4:-4]
        path = os.path.join(src_dir, name)
        h, e, biome, terr, snow, fields = R.load(path)
        out = os.path.join(out_dir, 'lod_%s.png' % level)
        R.render(h, e, biome, terr, snow, fields, None, out=out, layer='BIOME')
        print('LOD %s -> %s' % (level, out))


if __name__ == '__main__':
    main()
