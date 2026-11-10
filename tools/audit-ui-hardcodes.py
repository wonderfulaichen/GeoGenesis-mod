# -*- coding: utf-8 -*-
"""UI 硬编码审计：找出所有"绕过 UiKit 自己写视觉参数"的地方。

用户问题：「为什么换地方就变了？是不是没完成统一和模块化？」
本脚本回答它 —— 逐条列出仍在写死尺寸/圆角/颜色的位置。
"""
import io
import os
import re
import sys

ROOT = 'forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client'
SKIP = {'UiKit.java'}

# 关注的可视参数（行高、控件高宽、圆角）
PATTERNS = [
    ('行高', re.compile(r'(?:private|public|protected)\s+static\s+final\s+int\s+(ROW_H|SLIDER_ROW_H|SCALE_ROW_H|SEED_ROW_H|ENTRY_H)\s*=\s*(\d+)')),
    ('圆角', re.compile(r'roundRect\w*\([^;]*?,\s*(\d+)\s*,\s*0x[0-9A-Fa-f]{8}\)')),
    ('控件高', re.compile(r'(?:new ParamSlider\([^)]*\)|\bsuper\([^)]*?,\s*(\d{2})\s*,)')),
]

findings = []
for dirpath, _, files in os.walk(ROOT):
    for fn in sorted(files):
        if not fn.endswith('.java') or fn in SKIP:
            continue
        p = os.path.join(dirpath, fn)
        rel = os.path.relpath(p, ROOT).replace('\\', '/')
        s = io.open(p, encoding='utf-8').read()
        for lineno, line in enumerate(s.splitlines(), 1):
            code = line.split('//')[0]
            if not code.strip():
                continue
            for kind, pat in PATTERNS:
                m = pat.search(code)
                if m:
                    findings.append((rel, lineno, kind, line.strip()[:95]))
                    break

if findings:
    print('%-34s %5s %-6s %s' % ('file', 'line', 'kind', 'code'))
    print('-' * 110)
    for rel, ln, kind, code in findings:
        print('%-34s %5d %-6s %s' % (rel, ln, kind, code))
    print('-' * 110)
print('%d hardcoded visual parameter(s) outside UiKit' % len(findings))
sys.exit(1 if findings else 0)
