---
name: phase-a-terrain-allocation-morphology
overview: 阶段 A：大陆感知的地形类型分配 + 类型专属形态生成器。三阶段中的 A-B 阶段，为空间殖民山脉（C）打下基础。
todos:
  - id: phase-a-climate-classifier
    content: 创建 ClimateClassifier 类：用温度(纬度)×湿度(大陆性)二维表分配 TerrainClass，替换 CellGenerator.classify()
    status: pending
  - id: phase-a-restructure-sample
    content: 重构 CellGenerator.sample()：先由 ClimateClassifier 产出类型 → 委托 LandShape.sampleByType → 合成 eLand
    status: pending
    dependencies:
      - phase-a-climate-classifier
  - id: phase-a-type-generator-interface
    content: 创建 TypeGenerator 接口 + PlainGenerator/HillsGenerator/PlateauGenerator/BasinGenerator 四个实现类
    status: pending
    dependencies:
      - phase-a-restructure-sample
  - id: phase-a-integrate-types
    content: LandShape.sampleByType(wx, wz, type, weights) 按类型委托对应的 TypeGenerator，在类型边界处平滑过渡插值
    status: pending
    dependencies:
      - phase-a-type-generator-interface
  - id: phase-a-verify
    content: 编译验证 + runPreview 目检大陆类型分配和形态差异
    status: pending
    dependencies:
      - phase-a-integrate-types
---

## 需求概述

### 现有问题

1. 地形类型不按大陆和气候来分布——全靠全局省权重硬阈值，不问温湿度条件
2. 不同地形类型的形态相同——都是同一套噪声混合，只是振幅缩放，PLAIN 与 MOUNTAINS 的视觉质感无区别
3. 山脉无骨架结构——noise-based 鼓包没有山脊线/分支感

### 用户需求

1. 每块大陆内，根据气候等条件（温度/湿度/大陆性）分布多种地形类型，各类型在大陆内占一定面积比例
2. 类型不同，地形形态质的不同——平原有平原的平坦低频起伏，高原有高原的台地陡崖，山脉有山脊线
3. 山脉采用空间殖民算法生成分支状山脊骨架（参考 B 站 BV1jK67BCER8 的思路，空间殖民→树枝状分支结构→山脊线→高度场挤出）

## 核心功能

- **大陆感知的气候驱动类型分配**：用温度（纬度梯度）和湿度（大陆性）决定每点所属地形类型，气候梯度自然产生不同大陆的不同类型组合
- **类型专属形态生成器**：每类型绑定独立噪声/形状函数（Pla inGenerator/HillsGenerator/PlateauGenerator/BasinGenerator），在类型边界处平滑过渡
- **山脉脊线骨架（空间殖民）**：先撒种子→生长分支骨架→沿骨架挤出高度场→叠加高频细节

## 技术栈

- Minecraft Forge 1.20.1 (Java 17+)
- 项目原有噪声系统（Simplex/Frequency/Warp/Noises 工具链）
- 空间殖民算法：纯 Java 实现（点/吸引子/节点迭代生长），无外部依赖

## 架构设计

### 整体架构

当前数据流（问题版）：

```
provinceWeights(wx,wz) → LandShape.sample(wx,wz,weights) → elevation/relief
                                                                     ↓
                                                              classify(e,relief,weights) → TerrainClass
```

新数据流：

```
climate(temp,hum) + continent(c) → classifyByClimate() → TerrainClass
                                                                |
                          ┌─────────────────────────────────────┤
                          ↓                                     ↓
                    TypeGenerator₀                       TypeGenerator₁
                    (PlainGen)                           (RidgeGen)
                          |                                     |
                          ↓                                     ↓
                    elevation/relief                      elevation/relief
                          |                                     |
                          └──────────── type-boundary-smooth ──┘
                                                                ↓
                                                         final eLand
```

### 模块划分

#### A. 气候驱动的类型分配（`ClimateClassifier`）

新增类，取代 `CellGenerator.classify()` 的省权重主导逻辑。

策略：用温度（纬度梯度）和湿度（大陆性）做二维气候表，直接产出的 TerrainClass。

```
              湿度
        干 ←──────────→ 湿
   寒  │  PLATEAU/SNOW    PLAIN/HILLS
    │  │  (冷干高原)     (冷湿平原)
温  │  │  BASIN          HILLS
度  │  │  (温干盆地)     (温湿丘陵)
   热  │  PLAIN/DESERT    PLAIN
      │  (热干平原)     (湿热平原)
```

省权重降为次要角色：只在边界附近做微调（如 wBelt>0.5→可能升级为 MOUNTAINS 子型）。

气候梯度在大陆内自然变化（沿海→内陆湿度下降，低纬→高纬温度下降）→ 每大陆内部分区的类型组合由气候决定。

#### B. 类型专属生成器（`TypeGenerator` 接口 + 实现类）

```java
interface TypeGenerator {
    void sample(double wx, double wz, double[] weights, out LandSample);
}
```

实现类：

- **PlainGenerator**：低频 FBM（周期 800-1200），低振幅，柔和起伏
- **HillsGenerator**：中频 FBM（周期 300-500），中等振幅，圆润丘陵
- **PlateauGenerator**：flat-top 台阶函数 + 边缘陡崖噪声
- **BasinGenerator**：内凹 dome + 盆地边缘扰动
- **RidgeGenerator**（阶段 C）：空间殖民骨架 + 高度场挤出

`LandShape.sample` 改为按 `dominantType` 委托对应的生成器。

#### C. 空间殖民山脉骨架（阶段 C：`RidgeGenerator`）

算法流程：

1. 撒吸引子（Attractors）：在造山带区域随机分布 N 个点
2. 撒种子节点（Seed Nodes）：从大陆边缘/省边界撒起始点
3. 迭代生长：每轮每个节点向最近吸引子生长一步 → 分叉 → 移除被捕获的吸引子
4. 输出：树枝状山脊骨架（Node 列表，含父子关系）
5. 高度场挤出：对每个骨架点，计算脊线截面高度（高斯或帐篷函数衰减）
6. 叠加高频 Simplex 纹理

缓存：骨架在 chunk 生成时预计算，按世界坐标粗格缓存（类似 RiverField 的 LongCache）。

### 数据流重设计（Phase A+B）

```
CellGenerator.sample(wx, wz):
  1. continent.sample → c
  2. climate(temp, hum)
  3. classifyByClimate(c, temp, hum, elevation, relief, weights) → TerrainClass type
     （先用 province weights 的粗略值做初步类型判定，类型确定后再调用 type generator 算精确高度）
  [Phase A:]
  4. LandShape.sampleByType(wx, wz, type, weights) → LandSample (elevation, relief, eLand)
     -> 内部委托 type 对应的 TypeGenerator
  5. e = eOcean + eLand
  6. height = heightCurve.heightFromE(e)
```

### 海岸碎片小类型修复

合并到类型分配中：`e<0.03` 的陆地薄环仍判 BEACH，但气候条件可以决定 BEACH 是否显示为沙/雪/石海滩。这样细带宽度不变但颜色/质感按气候区分。

### 类型边界平滑过渡

两种类型边界处（如 PLAIN↔HILLS）做 `smoothstep` 插值，过渡宽度约 4-8 块，避免锐利分界线。

## 目录结构

```
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/
├── CellGenerator.java           # [MODIFY] sample() 重排：类型分配→类型生成器委托
├── TerrainClass.java            # [MODIFY] 可选：加气候关联信息
├── LandShape.java               # [MODIFY] 加 sampleByType 方法，保留旧 sample 做降级
├── classifier/
│   ├── ClimateClassifier.java   # [NEW] 气候→地形类型分配核心类
│   └── ClimateClassifier.md     # 文档
└── generators/
    ├── TypeGenerator.java       # [NEW] 接口
    ├── PlainGenerator.java      # [NEW] 平原专属生成器
    ├── HillsGenerator.java      # [NEW] 丘陵专属生成器
    ├── PlateauGenerator.java    # [NEW] 高原专属生成器
    ├── BasinGenerator.java      # [NEW] 盆地专属生成器
    └── RidgeGenerator.java      # [NEW] 山脉脊线骨架生成器（阶段 C）
```

## 性能

- 气候分类在 Cell 粒度（逐 block），只有温度公式（简单算术）+ 查表，O(1)
- TypeGenerator 当前每个几乎等价于一次 FBM 噪声采样（O(log n)），不比当前 `LandShape.sample` 重
- RidgeGenerator（阶段 C）预计算骨架并缓存：粗格每 64 块一个骨架节点，关键路径只做 BFS + 高度场采样
- 内存：骨架缓存约 64×64 世界块区域一个条目，开销可控（参照 RiverField 的 LongCache 模式）

## 预先验证

- `runPreview` 直接复用 `CellGenerator.sample` 新流程 → 无需改动预览代码
- `runDiag` 可快速看类型比例变化

## Agent Extensions 使用计划

### SubAgent

- [subagent:bmad-architect]
- 用途：在 Phase A 详细设计阶段，验证气候分类策略与类型生成器接口设计的合理性
- 预期产出：架构质量评分 + 潜在设计缺陷清单

- [subagent:code-explorer]
- 用途：在 Phase B 实施前，扫描 LandShape 所有噪声源声明，确保新增生成器不遗漏种子初始化和播种
- 预期产出：噪声源/种子清单，供 TypeGenerator 实现参考

### Skill

无需使用 Skills。