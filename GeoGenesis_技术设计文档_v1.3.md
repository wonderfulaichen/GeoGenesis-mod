# GeoGenesis 真实地形模组 - 技术设计文档 v1.3

## 更新记录

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0 | 2026-05-11 | 初始版本 |
| 1.1 | 2026-05-12 | 新增气候系统、混合侵蚀、地质系统、水文系统 |
| 1.2 | 2026-05-12 | 噪声防条纹优化、5段海岸过渡、Tile-based架构 |
| 1.3 | 2026-05-13 | 结构重构：全per-point噪声管线、向量沟壑、双频脊线、去TileCache |

---

## 一、架构概述

### 1.1 核心设计哲学

**职责分离**：噪声提供地形结构，粒子侵蚀提供表面纹理。不再互相混入。

**全 per-point 连续**：所有噪声层都是确定性连续函数，零网格后处理，天然跨区块无缝。

**噪声向量驱动沟壑**：用 terrainBase 的局部梯度估算坡向，Gullies 算法沿坡向生成正弦波纹沟壑。

### 1.2 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│  Level 1: 气候系统 (ClimateSystem)                          │
│  - 温度采样（三级：大陆→域扭曲→海拔修正）                     │
│  - 湿度采样（海陆+季风+地形雨+温度）                         │
│  - 混合侵蚀权重归一化（5种侵蚀）                             │
├─────────────────────────────────────────────────────────────┤
│  Level 2: 地质系统 (GeologySystem)                          │
│  - 气候推断9种岩石类型                                       │
│  - 硬度/粘度/孔隙度/抗侵蚀性                                 │
├─────────────────────────────────────────────────────────────┤
│  Level 3: 地形噪声层 (NoiseEngine, per-point)               │
│  - ridge: 1-|n×1.3| 山脊骨架 (~125格周期)                   │
│  - cellNoise: 1−(|a|+|b|)/2 翻转细胞脊线 (~50格周期)       │
│  - hills: FBM 中频丘陵 (~250格周期)                         │
│  - gullyErosion: terrainBase梯度→Gullies 沿坡沟壑纹理       │
├─────────────────────────────────────────────────────────────┤
│  Level 4: 粒子侵蚀抛光 (ErosionEngine, 28×28 grid)         │
│  - TerraForged Pixie 水滴物理模拟                            │
│  - padding=6, 边缘线性边界混合                               │
├─────────────────────────────────────────────────────────────┤
│  Level 5: 方块填充 (GeoGenesisGenerator + MaterialMapper)   │
│  - 材料映射（温度/湿度/海拔 → 草/沙/雪/泥/岩石）             │
│  - 5段海岸渐进过渡                                          │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 地形生成公式

```java
shape = ridge(1-|n×1.3|, 0.008Hz) * 0.42
      + cell(1−(|a|+|b|)/2, 0.02Hz) * 0.33
      + hills(FBM, 0.004Hz) * 0.15
      + gullyErosion * 0.06
    → 大陆性混合 → 28×28 grid → 粒子侵蚀 → blend → 16×16 输出
```

---

## 二、噪声引擎 (NoiseEngine)

### 2.1 Per-point 连续噪声层

| 方法 | 公式 | 频率 | oct | 作用 |
|------|------|------|-----|------|
| `sampleRidge` | `1-|n×1.3|` | 0.008 | 2 | 山脊骨架, V形峰, ~125格周期 |
| `sampleCellNoise` | `(|a|+|b|)/2` | 0.02 | 3 | 细胞结构(翻转后0→脊线), ~50格周期 |
| `sampleTerrainHills` | FBM | 0.004 | 4 | 中频丘陵起伏 |
| `sampleGullyErosion` | terrainBase±8格梯度→Gullies | 0.04→0.32 | 4 | 坡向沟壑纹理 |
| `sampleContinentRaw` | FBM | 0.001 | 2 | 海陆分割 [-1,1] |

### 2.2 Ridge 公式演进

```
旧1: 1-n² (Perlin集群0→1-n²≈1常数→无效)
旧2: 1-n⁴ (Perlin集群0→1-n⁴≈0.99常数→无效)
旧3: 1-n⁴ + domain warp ±800 (拉伸到1000+格周期→完全平坦)
新:  1-|n×1.3| (n=0→1脊峰, n=±0.77→0谷底, 68%值∈[0,1]范围变化)
```

### 2.3 CellNoise 翻转原理

```
原 cellNoise = (|a|+|b|)/2  → 两噪声过零点=0(细胞壁), 峰值=1
翻转 1-cellNoise:
  两噪声过零点 → 1 → 山脊线
  两噪声同时峰值 → 0 → 谷底

Perlin过零点形成连续曲线, 两个Perlin过零交线
→ 天然网络状山脊结构 → 真实感提升
```

### 2.4 GullyErosion 坡度估算

```
预估梯度:
  采样 terrainBase(0.002Hz) 在 ±8格 邻居
  sx = (hE-hW)/16, sz = (hN-hS)/16
  mag = sqrt(sx²+sz²)
  归一化到 target=0.40

Gullies生成:
  sideDir = (-sz, sx) × 2π (垂直坡向)
  4x4 Voronoi-like 细胞, 钟形权重混合
  cos(waveInput) 正弦波纹沿坡下行
  4 octave 迭代 (freq 0.04→0.32)
```

### 2.5 噪声防条纹优化

| 问题 | 解决方案 |
|------|---------|
| Perlin条纹 | Y轴动态偏移 + 八度偏移 |
| 频率相干 | lacunarity=2.1 非整数 |
| 区块断裂 | 全 per-point 连续函数, 零网格后处理 |

---

## 三、粒子侵蚀引擎 (ErosionEngine)

### 3.1 参数 (TerraForged 精确对齐)

| 参数 | 值 | 说明 |
|------|-----|------|
| EROSION_RADIUS | 7 | 侵蚀笔刷半径 |
| INERTIA | 0.005 | 水滴惯性 |
| SEDIMENT_CAPACITY | 7 | 沉积物容量系数 |
| MIN_SEDIMENT | 0.008 | 最小容量 |
| EVAPORATE | 0.35 | 蒸发速率 |
| GRAVITY | 2.5 | 重力加速度 |
| HEIGHT_FALL_OFF | 0.4 | 高度衰减阈值 |

### 3.2 水滴物理模拟

```
每滴水滴:
1. 双线性梯度估算当前位置 (高度+坡度)
2. 方向更新: new = old×惯性 + 下坡×(1-惯性)
3. 归一化方向, 移动水滴
4. Δh = 新高度 - 旧高度
5. 容量 = max(|Δh|×速度×水量×7, 0.008)
6. 沉积物 > 容量 或 爬坡 → 沉积
7. 未饱和 → 侵蚀 (7格半径笔刷, 权重=1-d/radius)
8. 重力加速: 速度² = 旧速度² + Δh×2.5
9. 蒸发: 水量 *= (1-0.35)
```

### 3.3 边界线性混合

```
padding=6, grid=28×28:
  x<6: wx = (x+0.5)/6 → 0.08~0.92 渐变
  x≥22: wx = (27.5-x)/6 → 0.92~0.08 渐变
  中心 x∈[6,21]: wx = 1.0
  final_w = min(wx, wz)     ← 角区双向衰减

相邻区块重叠12格渐变 → delta 连续累积 → 无缝
```

---

## 四、填充管线 (GeoGenesisGenerator)

### 4.1 方块填充

```
fillChunk → 16×16:

每列填充:
  height = terrainData.getHeight(x,z)
  distFromSurface = height - currentY

  if currentY > height:
    seaLevel 以上 → 空气
    seaLevel 以下 → 水/冰(根据温度)
  elif distFromSurface == 0:
    材料映射(温度,湿度,海拔):
      高温干旱 → 沙
      高温高湿 → 苔藓
      低温 → 雪
      沿海 → 泥
      默认 → 草
  elif distFromSurface < 4:
    土/沙/砂土
  else:
    岩层 (花岗岩/砂岩/玄武岩/闪长岩/浮冰)
```

### 4.2 5段海岸过渡

```
大陆性 ∈ [-1, 1]:
  < -0.8  → 深海
  < -0.12 → 浅海 (²平滑)
  -0.12~0.60 → smoothstep 海→陆
  ≥ 0.60  → 纯陆地
```

---

## 五、文件结构

```
com.geogenesis.worldgen/
├── GeoGenesisGenerator.java    # ChunkGenerator入口, fillChunk
├── GeoLevels.java              # 配置文件 (高度/海平面)
├── NoiseEngine.java            # 噪声引擎 (per-point连续噪声)
├── TerrainCache.java           # 地形管线 (噪声→网格→侵蚀→输出)
├── MaterialMapper.java         # 材料映射 (气候→方块)
├── climate/
│   └── ClimateSystem.java      # 气候系统 (三级模拟)
├── geology/
│   └── GeologySystem.java      # 地质系统 (9种岩石)
├── hydrology/
│   └── HydrologySystem.java    # 水文系统 (降水/河流)
└── erosion/
    └── ErosionEngine.java      # 粒子侵蚀引擎 (Pixie算法)
```

---

## 六、开发状态

### 6.1 已完成

- [x] 气候系统（三级模拟：大陆→域扭曲→海拔修正）
- [x] 地质系统（9种岩石类型+风化模拟）
- [x] 粒子侵蚀引擎（TerraForged Pixie 算法, 任意尺寸网格）
- [x] 水文系统（降水、河流、河谷）
- [x] 地形类型系统（12种地形枚举）
- [x] 材料映射（温度/湿度→方块类型）
- [x] 噪声防条纹优化（Y轴偏移+非整数lacunarity）
- [x] 全 per-point 连续噪声管线（零网格后处理, 天然无缝）
- [x] 双频脊线系统（1-|n×1.3| ridge + 1-cellNoise 翻转）
- [x] 坡向沟壑噪声层（terrainBase梯度→Gullies 4octave）
- [x] 5段海岸渐进过渡
- [x] 粒子侵蚀边界线性混合（padding=6, 28×28 grid）
- [x] TileCache 已废弃删除（被 per-point + 28×28 grid 替代）

### 6.2 待实现

- [ ] 河流网络路径追踪生成
- [ ] 10种地貌特征（丹霞、峡湾、喀斯特等）
- [ ] 生物群系集成
- [ ] UI配置界面
- [ ] LOD多级采样
- [ ] 二级缓存（RegionCache + DiskCache）

---

## 附录

### A. 性能特征

| 每区块操作 | 计数 | 说明 |
|-----------|------|------|
| computeBaseHeight | 28²=784 | 每点 4次 噪声采样 (ridge+cell+hills+gully→~15次FBM调用) |
| 粒子侵蚀 | ~590滴×20步 | 总~11800次双线性插值 |
| 方块填充 | 16²×320 | 材料映射+岩石层 |
| 总噪声调用 | ~3000 | 主导开销 |

### B. 参考项目

- [TerraForged](https://github.com/TerraForged/TerraForged) - 粒子侵蚀算法 (ErosionFilter)
- [Clean Terrain Erosion Filter](https://www.shadertoy.com/) - Gullies 沟壑算法 (bufferA)

### C. 术语表

| 术语 | 说明 |
|------|------|
| Per-point | 每坐标点独立计算的确定性连续噪声函数 |
| Gullies | Clean Terrain 的坡向正弦沟壑算法 |
| Pixie | 粒子水滴物理侵蚀模拟算法 |
| DomainWarping | 域扭曲，气候边界自然化 |
| Ridge | 1-|n×1.3| 山脊噪声公式 |
| CellNoise | (|noiseA|+|noiseB|)/2 翻转后产生脊线的噪声 |
| blend | 侵蚀 delta 的边界线性权重混合 |

---

*本文档由 GeoGenesis 开发团队维护*
*版本: 1.3 | 日期: 2026-05-13*
