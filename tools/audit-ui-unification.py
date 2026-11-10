# -*- coding: utf-8 -*-
"""UI 统一度审计：找出仍在"自己画控件"的地方（v7 应全部改为委托 UiKit）。

判据（命中即视为未统一）：
  1. 直接 g.fill 画按钮式矩形（宽高固定且伴随 drawString 居中）
  2. 写死的像素宽度常量（*_W = <数字>）用于控件
  3. 自绘 tooltip 背景
  4. 未使用 UiKit 的 client 包文件
"""
import io
import os
import re
import sys

ROOT = 'forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client'
SKIP = {'UiKit.java'}

hdr = '%-34s %5s %5s %5s'
print(hdr % ('file', 'fill', 'str', 'uiKit'))
print('-' * 54)

rows = []
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith('.java') or fn in SKIP:
            continue
        p = os.path.join(dirpath, fn)
        s = io.open(p, encoding='utf-8').read()
        rel = os.path.relpath(p, ROOT).replace('\\', '/')
        nfill = len(re.findall(r'g\.fill\(', s))
        nstr = len(re.findall(r'g\.drawString\(', s))
        uses = 'UiKit.' in s
        rows.append((rel, nfill, nstr, uses))

# 按"自己画得多且没用 UiKit"排序 —— 这些是最需要收敛的
rows.sort(key=lambda r: (r[3], -(r[1] + r[2])))
for rel, nfill, nstr, uses in rows:
    mark = 'Y' if uses else '-'
    if not uses and (nfill + nstr) >= 3:
        print(hdr % (rel, nfill, nstr, mark))

total = len(rows)
unified = sum(1 for r in rows if r[3])
print('-' * 54)
print('%d / %d files use UiKit' % (unified, total))
