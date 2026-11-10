# -*- coding: utf-8 -*-
"""逐面板 UI 一致性自检。

检查每个 ConfigPanel 子类是否：
  1. 不再调用 drawHeader（标题归 ConfigPanelHost）
  2. 不自己画 tooltip 背景（g.fill 黑色块 / roundRect 当 tooltip）
  3. 不写死行高/控件高（应引用 UiKit 常量）
  4. 不是"完全不用 UiKit"的孤立实现
"""
import io
import os
import re
import sys

ROOT = 'forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client'

PANEL_RE = re.compile(r'class\s+(\w+)\s+extends\s+(ConfigPanel|DomainPanel)')

CHECKS = [
    ('drawHeader 残留', re.compile(r'\bdrawHeader\s*\(')),
    ('自绘 tooltip 黑块', re.compile(r'g\.fill\([^)]*0x(?:EE|CC|88)[0-9A-Fa-f]{6}\)')),
    ('写死行高', re.compile(r'(?:static\s+final\s+int\s+(?:ROW_H|SLIDER_ROW_H|SCALE_ROW_H|BTN_H)\s*=\s*\d+;)')),
]

panels = []
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith('.java'):
            continue
        p = os.path.join(dirpath, fn)
        s = io.open(p, encoding='utf-8').read()
        if not PANEL_RE.search(s):
            continue
        rel = os.path.relpath(p, ROOT).replace('\\', '/')
        issues = []
        for lineno, line in enumerate(s.splitlines(), 1):
            code = line.split('//')[0]
            if not code.strip():
                continue
            for name, pat in CHECKS:
                if pat.search(code):
                    issues.append((name, lineno, line.strip()[:78]))
                    break
        if 'UiKit.' not in s:
            issues.append(('完全未使用 UiKit', 0, ''))
        panels.append((rel, issues))

print('%-30s %s' % ('panel', 'status'))
print('-' * 100)
bad = 0
for rel, issues in panels:
    if issues:
        bad += 1
        print('%-30s %d issue(s)' % (rel, len(issues)))
        for name, ln, code in issues:
            print('    L%-5d %-18s %s' % (ln, name, code))
    else:
        print('%-30s OK' % rel)
print('-' * 100)
print('%d panel(s), %d with issues' % (len(panels), bad))
sys.exit(1 if bad else 0)
