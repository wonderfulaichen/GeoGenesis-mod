"""预览 ↔ 游戏 参数同源检查（★ 2026-11-08）。

为什么用静态检查而不是运行时探针
--------------------------------
`GeoGenesisGenerator` 继承 MC 的 `ChunkGenerator`，**类加载即触发
`BuiltInRegistries` 初始化** ⇒ 在无 Forge/MC 环境的探针进程里必然抛
`IllegalArgumentException: Not bootstrapped`（已实测）。

而"两侧是否同源"本质是**代码结构问题**（谁调用谁），不是运行期数值问题
⇒ 静态检查反而更准确、也更快。

检查项
------
1. 配置屏**不得**直接调 `GeoGenesisConfig.INSTANCE.buildParams()` 去建地形
   （必须走 `resolvePreviewParams`）；
2. 配置屏**不得**用 `worldCtx != null || level == null ||` 这类自定义 morphBase 判据
   （必须走 `resolvePreviewMorphBase`）；
3. `resolvePreviewParams` / `resolvePreviewMorphBase` 必须存在且被配置屏调用；
4. `resolvePreviewMorphBase()` 的实现必须返回 `currentWorldMorphBase`
   —— 与 `buildTerrain` 读的是同一个字段。

用法：python tools/verify-preview-source-parity.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MDK = os.path.join(ROOT, 'forge-1.20.1-47.4.10-mdk')
SCREEN = os.path.join(MDK, 'src', 'main', 'java', 'com', 'geogenesis',
                      'client', 'GeoGenesisConfigScreen.java')
GEN = os.path.join(MDK, 'src', 'main', 'java', 'com', 'geogenesis',
                   'worldgen', 'generator', 'GeoGenesisGenerator.java')
READER = os.path.join(MDK, 'src', 'main', 'java', 'com', 'geogenesis',
                      'client', 'preview', 'PreviewDisplay.java')


def read(path):
    with io.open(path, encoding='utf-8') as f:
        return f.read()


def main():
    screen = read(SCREEN)
    gen = read(GEN)
    ok = True

    print('=== 预览 <-> 游戏 参数同源检查（静态）===')

    # 1) 配置屏不得用 buildParams 建地形
    #    允许它出现在"变更检测"里（比较用户在编辑的 toml 值），但不允许用于 new CellGenerator
    bad_build = re.search(
        r'TerrainParams\s+\w+\s*=\s*GeoGenesisConfig\.INSTANCE\.buildParams\(\);'
        r'\s*(?://[^\n]*\n\s*)*'
        r'boolean\s+morphBase', screen)
    print('\n[1] 配置屏建地形是否走统一入口')
    if bad_build:
        print('    FAIL: 仍用 GeoGenesisConfig.INSTANCE.buildParams() + 自定义 morphBase 建地形')
        ok = False
    elif 'resolvePreviewParams' in screen:
        print('    OK: 走 resolvePreviewParams / resolvePreviewMorphBase')
    else:
        print('    FAIL: 未调用 resolvePreviewParams')
        ok = False

    # 2) 不得保留旧的自定义 morphBase 判据
    print('\n[2] 是否残留自定义 morphBase 判据')
    old_morph = 'worldCtx != null || Minecraft.getInstance().level == null'
    if old_morph in screen:
        print('    FAIL: 仍存在 `%s`（与进游戏的 currentWorldMorphBase 不同源）' % old_morph)
        ok = False
    else:
        print('    OK: 已移除')

    # 3) 两个统一入口必须存在
    print('\n[3] GeoGenesisGenerator 是否提供统一入口')
    for name in ('resolvePreviewParams', 'resolvePreviewMorphBase'):
        if ('public static' in gen and (' ' + name + '(') in gen):
            print('    OK: %s 存在' % name)
        else:
            print('    FAIL: %s 缺失' % name)
            ok = False

    # 4) resolvePreviewMorphBase 必须返回 currentWorldMorphBase
    print('\n[4] resolvePreviewMorphBase 是否返回 currentWorldMorphBase（与 buildTerrain 同字段）')
    m = re.search(
        r'public static boolean resolvePreviewMorphBase\(\)\s*\{\s*return\s+(\w+)\s*;',
        gen)
    if not m:
        print('    FAIL: 方法体不是 `return <字段>;`')
        ok = False
    elif m.group(1) != 'currentWorldMorphBase':
        print('    FAIL: 返回的是 %s，不是 currentWorldMorphBase' % m.group(1))
        ok = False
    else:
        # 再确认 buildTerrain 里也读同一字段
        bt = re.search(r'CellGenerator gen = new CellGenerator\([^;]*?currentWorldMorphBase\)',
                       gen, re.S)
        if bt:
            print('    OK: 与 buildTerrain 读同一字段 currentWorldMorphBase')
        else:
            print('    FAIL: buildTerrain 未使用 currentWorldMorphBase')
            ok = False

    # 5) 变更检测仍应用 toml（那是用户正在编辑的值）
    print('\n[5] 变更检测是否仍比较 toml 值')
    if 'rebuildPreviewIfChanged' in screen:
        blk = screen[screen.index('private void rebuildPreviewIfChanged'):]
        blk = blk[:blk.index('\n    }')]
        if 'buildParams()' in blk:
            print('    OK: 变更检测用 toml（用户编辑值），与生效 params 分离')
        else:
            print('    FAIL: 变更检测未比较 toml，拖滑条可能不触发重建')
            ok = False
    else:
        print('    SKIP: 未找到 rebuildPreviewIfChanged')

    # 6) ★ 共享实例的安全性：预览绝不能关闭共享的存档单例
    #
    #    这是本轮最危险的一处：`PreviewDisplay.setTerrain`(旧) 与 `close()` 都会
    #    `retired.closeProcessErosion()`。若共享了存档实例，玩家关掉配置屏就会
    #    关停游戏正在用的引擎（其进程池已 shutdown）⇒ 进游戏后地形全崩。
    print('\n[6] 共享实例的关闭保护（ownsTerrain）')
    disp = read(READER) if os.path.exists(READER) else ''
    if not disp:
        print('    FAIL: 找不到 PreviewDisplay.java')
        ok = False
    else:
        guard_close = 'if (ownsTerrain && terrain != null) terrain.closeProcessErosion();'
        guard_retire = 'if (retiredOwned && retired != null && retired != terrain) retired.closeProcessErosion();'
        if guard_close in disp and guard_retire in disp:
            print('    OK: close() 与 setTerrain() 都只在 ownsTerrain 时退役')
        else:
            print('    FAIL: 关闭路径缺少 ownsTerrain 保护（会关掉游戏正在用的引擎）')
            if guard_close not in disp:
                print('          - close() 未加保护')
            if guard_retire not in disp:
                print('          - setTerrain() 未加保护')
            ok = False

    # 7) 复用入口必须同时校验 seed 与 params
    print('\n[7] previewReusableTerrain 是否同时校验 seed 与 params')
    m2 = re.search(r'public static GeoGenesisTerrain previewReusableTerrain\((.*?)\)\s*\{',
                   gen, re.S)
    if not m2:
        print('    FAIL: 未见 previewReusableTerrain')
        ok = False
    else:
        body = gen[m2.end():]
        body = body[:body.index('\n    }')]
        checks_seed = 'WORLD_LIFECYCLE.seed() != desiredSeed' in body
        checks_params = 'currentWorldParams, desiredParams' in body
        checks_usable = 'isUsable()' in body
        if checks_seed and checks_params and checks_usable:
            print('    OK: 校验 seed / params / isUsable 三项')
        else:
            print('    FAIL: 缺少校验项（seed=%s params=%s isUsable=%s）'
                  % (checks_seed, checks_params, checks_usable))
            ok = False

    print()
    print('PASS' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
