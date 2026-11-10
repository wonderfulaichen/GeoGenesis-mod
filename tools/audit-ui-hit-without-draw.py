# -*- coding: utf-8 -*-
"""审计「有命中区、但没有绘制」的幽灵控件。

背景（真实 bug）：v7 把 ParamSlider 的渲染整体换成 UiKit 时，
<b>删了重置按钮的绘制、却保留了 isHoveringReset()</b>，
于是 5 个面板仍在调它 —— 用户看到的是"重置键消失了"，
而代码里命中区还在，属于最难发现的一类不一致。

判据：
  若某文件里出现 `isHoveringXxx(` 的<b>公开命中方法定义</b>，
  则该文件内必须同时存在对应的绘制调用（roundRect / drawString 等）。
"""
import io
import os
import re
import sys

ROOT = 'forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client'

# 命中方法定义（public boolean isHoveringXxx / hitTest）
DEF_RE = re.compile(r'public\s+boolean\s+(isHovering\w+|hitTest)\s*\(')
# 绘制迹象
DRAW_RE = re.compile(r'(roundRect|drawString|valueChip|UiKit\.button|UiKit\.toggle|\.fill\()')

bad = []
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith('.java'):
            continue
        p = os.path.join(dirpath, fn)
        s = io.open(p, encoding='utf-8').read()
        defs = DEF_RE.findall(s)
        if not defs:
            continue
        rel = os.path.relpath(p, ROOT).replace('\\', '/')
        # 排除本身是"容器/基类"的文件（它们只声明，不绘制）
        if fn in ('ClickableRegion.java',):
            continue
        if not DRAW_RE.search(s):
            bad.append((rel, defs))

print('%-40s %s' % ('file', 'hit methods (no drawing found)'))
print('-' * 90)
if bad:
    for rel, defs in bad:
        print('%-40s %s' % (rel, ', '.join(defs)))
else:
    print('(none)')
print('-' * 90)
print('%d file(s) with hit-test but no drawing' % len(bad))
sys.exit(1 if bad else 0)
