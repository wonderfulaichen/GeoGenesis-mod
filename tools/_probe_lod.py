import io

p = r'forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/preview/SandboxLodProbe.java'
s = io.open(p, encoding='utf-8').read()

# 全部换成新 API（不再需要 viewport）
for a, b in [
    ('SandboxLod.gridFor(zoom, VIEWPORT)', 'SandboxLod.gridFor(zoom)'),
    ('SandboxLod.gridFor(zoom, viewportPx)', 'SandboxLod.gridFor(zoom)'),
    ('SandboxLod.windowFor(zoom, VIEWPORT)', 'SandboxLod.windowFor(zoom)'),
    ('SandboxLod.effectiveStep(zoom, VIEWPORT)', 'SandboxLod.effectiveStep(zoom)'),
    ('SandboxLod.pillarsFor(VIEWPORT)', 'SandboxLod.pillarsFor(zoom)'),
    ('SandboxLod.pillarsFor(viewportPx)', 'SandboxLod.pillarsFor(zoom)'),
    ('SandboxLod.perColumnFor(zoom)', 'SandboxLod.perColumnFor(zoom)'),
    ('SandboxLod.usesCoarseWater(zoom, VIEWPORT)', 'SandboxLod.usesCoarseWater(zoom)'),
    ('SandboxLod.scaleLabel(z, VIEWPORT)', 'SandboxLod.scaleLabel(z)'),
]:
    s = s.replace(a, b)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('probe updated')
