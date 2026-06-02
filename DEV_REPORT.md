# GeoGenesis 地形模组 - 开发报告

## 一、项目概述

**GeoGenesis** 是一个 Minecraft Forge 1.20.1 模组，目标是生成真实感地形替代原版地形生成。核心架构为**噪声驱动 → 板块构造 → 多层地形合成 → 粒子侵蚀** 的分层管线。

---

## 二、代码结构

```
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/
├── GeoGenesisMod.java                    ← 模组主入口
├── config/GeoGenesisConfig.java          ← 可配置参数（高度/海平面/侵蚀强度等）
├── client/GeoGenesisConfigScreen.java    ← 配置界面 + 交互式预览
└── worldgen/
    ├── GeoGenesisGenerator.java          ← ChunkGenerator 实现，地形生成入口
    ├── GeoLevels.java                    ← 世界高度配置
    ├── NoiseEngine.java                  ← 噪声引擎（16 层噪声源）
    ├── HeightmapPreview.java             ← 独立高度图预览工具（可运行 main）
    ├── MaterialMapper.java               ← 方块材质映射
    ├── biome/ClimateBiomeMapper.java     ← 气候→生物群系映射
    ├── climate/ClimateSystem.java        ← 气候系统（温度/湿度模拟）
    ├── geology/
    │   ├── GeologySystem.java            ← 地质系统（9 种岩石类型）
    │   └── PlateTectonics.java           ← 板块构造系统（Voronoi 板块 + 边界抬升）
    ├── erosion/
    │   └── ErosionEngine.java            ← 粒子侵蚀引擎（水滴物理模拟）
    └── hydrology/
        └── HydrologySystem.java          ← 水文系统（河流/河谷）
```

独立测试工具：
```
erosion-test-tool/src/com/erosiontest/
├── Noise.java                  ← 离线噪声引擎（复刻模组噪声逻辑）
├── Erosion.java                ← 旧版粒子侵蚀（效果验证基准）
├── ErosionV2.java              ← V2 三层粒子侵蚀（最终方案原型）
├── ModErosionEngine.java       ← 模组 pyramidErosion 移植版
├── RidgeVisualTest.java        ← 脊线效果可视化对比
├── RunModErosion.java          ← 模组侵蚀效果测试
├── TestErosionV2.java          ← V2 侵蚀对比测试（含 zoom 放大功能）
├── DiagErosion.java            ← 侵蚀数值诊断
└── ImprovedNoise.java          ← Java 版 Perlin 噪声实现
```

---

## 三、改进记录

### 0. 修复 chunk 边界断裂（已解决）

**问题**：`sampleGullyErosion()` 调用了 `sampleGullies()`，后者内部使用 `hash2D(ix, iz)` 基于整数网格的确定性哈希，不是 per-point 连续函数，跨越整数坐标边界时产生跳变。

**修复**：将 `sampleGullyErosion()` 改为纯 per-point 噪声生成——使用 `ImprovedNoise.noise(x, y, z)` 的 FBM 组合，天然连续无缝。

**影响文件**：`NoiseEngine.java`
- `sampleGullyErosion()` 重写为连续 FBM 噪声

---

### 1. 恢复多层地形公式（已解决）

**问题**：技术文档 v1.3 设计了 ridge + cell + hills + gully 四层地形公式，但 `NoiseEngine` 中对应方法全是假的。

**修复**：实现了 4 个真正的噪声层方法：

| 方法 | 频率 | 说明 |
|------|------|------|
| `sampleRidge()` → `sampleRidgeFBM` | 0.005Hz, 5oct | FBM 脊线骨架 |
| `sampleCellNoise()` | 0.02Hz, 3oct | 网络状山脊线 |
| `sampleTerrainHills()` | 0.004Hz, 4oct | 独立丘陵起伏 |
| `sampleGullyErosion()` | 0.04→0.08Hz | 坡面沟壑纹理 |

**影响文件**：`NoiseEngine.java`

---

### 2. 改进 `computeHeight` 地形合成（已解决）

**问题**：`computeHeight()` 只用 `terrainBase × relief` 简单相乘产生地形，且高度范围被压缩，山脉最高只到 Y144。

**修复**：

| 改动 | 旧值 | 新值 | 效果 |
|------|------|------|------|
| 基础地形公式 | `terrain^0.6 × ...` | `terrain×0.5 + detail×0.5` | 更平坦，山体宽阔 |
| detail 层 | 无 | `ridge×0.50 + cell×0.28 + hills×0.14 + gully×0.08` | 多尺度结构 |
| 幅度系数 | `0.35 + relief×0.65` | `min(1, 0.04 + relief²×1.2)` | 平原低、山脉高 |
| 自适应配置 | 硬编码 | 统一使用 `genMaxY` 参数 | 任意高度配置 |

**影响文件**：`GeoGenesisGenerator.java`

---

### 3. 新增板块构造系统（已解决）

**问题**：大陆形状由模糊噪声决定，山脉不沿边界。

**架构**：Voronoi 板块网格 + 确定性哈希，`PLATE_GRID=300` 格/板块。

**集成**：
- `transition = smoothstep(crust - 0.3) / 0.2` 取代大陆 Sigmoid
- `shaped += plate.uplift()` → 山脉沿板块边界
- 地壳厚度混合消除"大正方形"问题

**影响文件**：新增 `PlateTectonics.java`，修改 `GeoGenesisGenerator.java`

---

### 4. 自适应高度配置（已解决）

审计所有代码路径，确认全部使用 `genMaxY` / `cfgMaxY` 参数，不硬编码：

| 路径 | 参数来源 |
|------|---------|
| `fillFromNoise` | `cfgMaxY` → `computeHeight(..., genMaxY)` |
| `getBaseHeight` | `cfgMaxY` → `computeHeight(..., genMaxY)` |
| `getBaseColumn` | `cfgMaxY` → `computeHeight(..., genMaxY)` |
| `addDebugScreenInfo` | `getGenDepth()` → `computeHeight(..., genMaxY)` |
| `computeHeight` | `genMaxY - levels.minY()` 算 worldHeight |

---

### 5. 海岸线真实性改进（已解决）

**问题**：大陆海岸线太平滑，不自然。

**修复**：
- 3 层 FBM 大陆性噪声（代替单层）
- 三级级联域扭曲（strength 120→300→500）
- 海岸线脊状细节：`(ridge - 0.5) × coastWeight × sign × 0.12`

**影响文件**：`NoiseEngine.java` — `sampleContinentRaw()` 重写

---

### 6. 交互式预览界面（已完成）

**问题**：配置屏幕预览卡顿。

**方案**：GPU 纹理渲染 + 低分辨率采样 + 双线性上采样
- 64×64 低分辨率采样 → 双线性上采样到 256×256
- 支持拖拽平移 + 滚轮缩放
- 四种预览模式（地形/温度/湿度/生物群系）
- 统一 UIColors 色彩系统 + 图例表

**影响文件**：`GeoGenesisConfigScreen.java`

---

### 7. V2 多层粒子侵蚀（当前 - 已完成）

**问题**：金字塔侵蚀中所有层的沉积速度都太低（0.015~0.04），细层把粗层挖的河谷也继续挖，磨平了地形细节。测试对比显示新方案地形过于平滑。

**根因**：四层金字塔分工不明确，每层都在做同样的"挖掘"工作，缺乏"大笔刷挖骨架→中笔刷细化→小笔刷纹理"的分层设计。

**修复**：基于测试工具 `ErosionV2` 验证的参数，重写金字塔侵蚀参数：

| 层级 | 分辨率 | 笔刷 | 滴数 | 蚀刻速度 | **沉积速度** | 分工 |
|------|--------|------|------|---------|---------|------|
| 0（最粗） | size/8 | r=**8** | 5000 | 0.30 | **0.08**（低） | 大笔刷深挖河谷骨架 |
| 1 | size/4 | r=**4** | 3000 | 0.20 | **0.30**（中） | 中笔刷细化沟壑 |
| 2 | size/2 | r=**3** | 1500 | 0.12 | **0.40**（高） | 小笔刷表面纹理 |
| 3（最细） | size | r=**1** | 500 | 0.06 | **0.50**（高） | 微笔刷轻触细节 |

**关键设计**：沉积速度**递进增加**，保证：
- 粗层挖的河谷不会被后续层填平
- 细层高沉积 → 很快放下携带物 → 不会继续侵蚀已有特征

**验证方式**：独立测试程序 `TestErosionV2` 可生成 4 列对比图：
1. 原始地形
2. 旧版 Erosion（图一，效果基准）
3. V2 三层（新方案）
4. V2 r=8 单层参考

数值对比（200×200 区域）：
```
旧版Erosion: maxΔ=0.0132  avgΔ=0.0062
ErosionV2:   maxΔ=0.0301  avgΔ=0.0079
```

**影响文件**：`ErosionEngine.java` — `applyErosionNormalized()` 参数更新

---

### 8. 侵蚀参数暴露到配置文件（已完成）

**改动**：新增 4 个侵蚀配置参数替换原来的无用 `erosionRadius`：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `erosionDropsMul` | 1.0 | 金字塔每层滴数乘数 |
| `erosionErodeMul` | 1.0 | 蚀刻速度乘数 |
| `erosionDepositMul` | 1.0 | 沉积速度乘数 |
| `erosionBrushMul` | 1.0 | 笔刷半径乘数 |

**影响文件**：`GeoGenesisConfig.java`、`ErosionEngine.java`

---

### 9. 河流汇流网络（已完成）

**问题**：之前所有 `RiverNode.flow = 1`（恒值），导致所有河流宽度/深度相同，没有大/中/小三级分支。

**修复**：在 `findAndTraceRivers` 后添加 `computeFlowAccumulation()`：
- 初始化所有节点 `flow = 1`（源头流量）
- 对每对路径检测交汇：路径A的下游末端靠近路径B的节点时，A的流量累加到B的后续节点
- 重新计算各路径的 `totalFlow` 和 `maxDepth`
- 大河流（flow高）→ 宽/深，小河（flow低）→ 窄/浅

**影响文件**：`HydrologySystem.java`

---

### 10. 生物群系深度集成（已完成）

**问题**：`GeoGenesisBiomeSource.getNoiseBiome()` 调用 `selectBiome()` 时 riverDepth 硬编码为 0。

**修复**：
- `GeoGenesisBiomeSource` 新增 `hydrologySystem` 引用
- `GeoGenesisGenerator.ensureInit()` 传入 `hydrologySystem`
- `getNoiseBiome()` 调用 `hydrologySystem.getRiverDepthAt()` 获取真实河流深度
- 河流区域正确显示为 `RIVER`/`FROZEN_RIVER` 群系

**影响文件**：`GeoGenesisBiomeSource.java`、`GeoGenesisGenerator.java`

---

## 四、当前参数与预期效果

### 配置默认值

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `minY` | -64 | 世界最低高度 |
| `maxY` | 256 | 地形生成最大高度 |
| `seaLevel` | 63 | 海平面 |
| `oceanDepthMax` | 32 | 海洋最大深度 |
| `enableErosion` | true | 侵蚀开关 |
| `erosionStrength` | 0.5 | 侵蚀强度 |
| `erosionDropsMul` | 1.0 | 侵蚀滴数乘数 |
| `erosionErodeMul` | 1.0 | 侵蚀蚀刻乘数 |
| `erosionDepositMul` | 1.0 | 侵蚀沉积乘数 |
| `erosionBrushMul` | 1.0 | 侵蚀笔刷乘数 |
| `enableRivers` | true | 河流开关 |
| `riverDepth` | 0.5 | 河流深度 |
| `riverWidth` | 1.0 | 河流宽度乘数 |
| `precipThreshold` | 0.55 | 河流源头降水阈值 |
| `enableBiomeMapping` | true | 气候驱动群系映射 |

### 预期地形高度（maxY=256）

| 地形 | shaped 值 | 高度（Y） |
|------|----------|----------|
| 深海 | <0 | 30-50 |
| 海岸 | 0.05~0.10 | 64-68 |
| 平原 | 0.15~0.25 | 68-78 |
| 丘陵 | 0.35~0.55 | 98-140 |
| 山脉 | 0.65~0.85 | 162-220 |
| 最高峰 | 1.0 | ~227 |

### 大陆尺寸

| 项目 | 值 |
|------|-----|
| 板块网格 | 300×300 格 |
| 每个板块 | ~300-540 格宽 |
| 一个大陆 | 2-3 板块 = 600-900 格宽 |
| 山脉带宽 | ~60-120 格（沿板块边界） |

---

## 五、下一步建议

- [ ] **完善河流网络**（当前 hydrology 有河流噪声模拟 + 河谷雕刻，但无汇水网络逻辑）
- [ ] **生物群系集成**（`ClimateBiomeMapper` 已写入文件但未在生成管线中实际使用）
- [ ] 10 种地貌特征深化（当前仅高原/喀斯特/丹霞/冰川）
- [ ] **侵蚀参数暴露到配置文件**（当前仅 erosionStrength，笔刷/滴数/沉积等硬编码）
- [ ] 性能优化：区块生成多线程
- [ ] 检查 erosion 断裂：金字塔世界坐标粒子系统理论上无断裂，需要在游戏中用 `/tp` 跨越 chunk 边界验证
