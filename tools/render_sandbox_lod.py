"""沙盘 LOD 对比出图（★ 2026-11-08）。

按 LOD 档位导出不同窗口的图。每档传对应的 zoom，
使 render() 的相机 scale 随档推进（与 SandboxRenderer 一致）。

用法：
    python tools/render_sandbox_lod.py <world_tsv_dir> <out_dir>

world_tsv_dir 需含 lod_<step>.tsv（由 SandboxLodProbe 导出）。
step 0 = 默认档（zoom 1.0），每 +1 步 zoom ×2。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import render_sandbox_offline as R

# 与 SandboxLod.BASE_ZOOM 一致
BASE_ZOOM = 1.0


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    for name in sorted(os.listdir(src_dir)):
        if not (name.startswith('lod_') and name.endswith('.tsv')):
            continue
        step = int(name[4:-4])
        zoom = BASE_ZOOM * (2.0 ** step)
        path = os.path.join(src_dir, name)
        h, e, biome, terr, snow, fields = R.load(path)
        out = os.path.join(out_dir, 'lod_%d.png' % step)
        # ★ 传 zoom：相机 scale 随档推近，与生产一致
        R.render(h, e, biome, terr, snow, fields, None, out=out,
                 layer='BIOME', zoom=zoom)
        print('step %d (zoom %.1f, %d cols) -> %s' % (step, zoom, h.shape[0], out))


if __name__ == '__main__':
    main()
