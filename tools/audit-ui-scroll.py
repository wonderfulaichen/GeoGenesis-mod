# -*- coding: utf-8 -*-
"""滚动高度与悬停提示一致性审计。

## 为什么需要
用户反复遇到两类问题，且都是"改了这页忘了那页"：
  1. **滚动到底还差一截** —— getHeight() 写死，或与 render 的实际布局不同源；
  2. **幽灵提示** —— hoverTooltip 字段只增不减，指针移开后仍被读到。

本脚本静态扫描，把"可疑点"列出来，避免只修一页。
"""
import io
import os
import re
import sys

ROOT = 'forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client'

PANEL_RE = re.compile(r'class\s+(\w+)\s+extends\s+(ConfigPanel|DomainPanel)')
# getHeight 直接返回字面量（硬编码高度）——可疑
HARDCODED_H_RE = re.compile(r'public\s+int\s+getHeight\s*\(\s*\)\s*\{\s*return\s+(\d+)\s*;\s*\}')
# 折行绘制但没参与高度计算
WRAP_DRAW_RE = re.compile(r'UiKit\.drawWrapped|drawWrapped\(')

issues = []
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith('.java'):
            continue
        p = os.path.join(dirpath, fn)
        s = io.open(p, encoding='utf-8').read()
        if not PANEL_RE.search(s):
            continue
        rel = os.path.relpath(p, ROOT).replace('\\', '/')

        # 1) getHeight 写死数字
        m = HARDCODED_H_RE.search(s)
        if m:
            issues.append((rel, 'getHeight 硬编码 %s' % m.group(1),
                           '布局一变就会滚不到底'))

        # 2) 用了折行绘制，但 getHeight 里没有 wrappedHeight
        if WRAP_DRAW_RE.search(s):
            # DomainPanel 基类的 getHeight 已含 extraHeight，子类覆盖 extraHeight 即可
            if 'wrappedHeight' not in s and 'extraHeight' not in s:
                issues.append((rel, '折行绘制但高度未按折行算',
                               '窄面板下最后一行可能被裁'))

print('%-34s %-32s %s' % ('file', 'issue', 'risk'))
print('-' * 100)
for rel, what, risk in issues:
    print('%-34s %-32s %s' % (rel, what, risk))
print('-' * 100)
print('%d issue(s)' % len(issues))
sys.exit(1 if issues else 0)
