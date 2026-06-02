# GeoGenesis 真实地形模组 - 技术设计文档 v1.2

## 更新记录

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0 | 2026-05-11 | 初始版本 |
| 1.1 | 2026-05-12 | 新增气候系统、混合侵蚀、地质系统、水文系统 |
| 1.2 | 2026-05-12 | 噪声防条纹优化、5段海岸过渡、区块边界平滑、Tile-based架构 |

---

## 一、架构概述

### 1.1 核心设计哲学

**气候驱动一切**：温度、湿度、海拔决定地形类型、侵蚀强度和地质特性。

**混合侵蚀系统**：不是单一侵蚀类型，而是多种侵蚀的权重混合（主+副+...=1）。

**地质推断**：根据气候条件推断岩石类型（硬度、粘度、抗侵蚀性）。

**防噪声伪影**：Y轴动态偏移、非整数lacunarity、旋转坐标扰动，消除Perlin噪声条纹。

### 1.2 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│  Level 1: 气候系统 (ClimateSystem)                          │
│  - 温度采样（三级：大陆→域扭曲→海拔修正）                     │
│  - 湿度采样（海陆+季风+地形雨+温度）                         │
│  - 大陆性采样（海洋/内陆分布）                               │
│  - 混合侵蚀权重归一化（5种侵蚀）                             │
├─────────────────────────────────────────────────────────────┤
│  Level 2: 地质系统 (GeologySystem)                          │
│  - 根据气候推断9种岩石类型                                   │
│  - 计算硬度/粘度/孔隙度/抗侵蚀性                             │
│  - 风化模拟                                                  │
├─────────────────────────────────────────────────────────────┤
│  Level 3: 侵蚀系统 (ErosionEngine)                          │
│  - Pixie粒子水滴物理侵蚀模拟                                 │
│  - Tile-based：2×2区块合并为32×32 Tile运行完整侵蚀           │
├─────────────────────────────────────────────────────────────┤
│  Level 4: 水文系统 (HydrologySystem)                        │
│  - 降水模拟（湿度+地形雨+温度）                              │
│  - 河流强度+河谷深度+河谷宽度                                │
│  - 水文侵蚀                                                  │
├─────────────────────────────────────────────────────────────┤
│  Level 5: 地形生成管线 (TerrainCache + TileCache)           │
│  - NoiseEngine: 基础噪声（Perlin FBM + Ridge）               │
│  - TerrainCache: 18×18计算→3×3边界平滑→16×16输出            │
│  - TileCache: 2×2区块Tile管理，运行粒子侵蚀                  │
│  - 5段海岸渐进过渡（深海→浅海→沙滩→海岸→内陆）               │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 噪声防条纹优化

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| Perlin条纹 | Y轴固定为0，XZ平面相关性 | Y = 动态偏移 + 八度偏移 |
| 频率相干 | lacunarity=2.0整数倍 | lacunarity=2.1~2.2非整数 |
| Ridge方向性 | `1-abs(n)`产生网格对齐脊线 | `1-n*n`+120°旋转扰动 |
| 区块边界悬崖 | 16×16独立计算无跨区块连续 | 18×18计算→3×3边界平滑→16×16输出 |
| 海岸垂直悬崖 | 单段硬过渡 | 5段渐进过渡(smoothstep) |

---

## 二、气候系统 (ClimateSystem)

### 2.1 三级气候模拟

```
┌─────────────────────────────────────────────────────────────┐
│  Level 1: 大陆气候（超低频噪声）                             │
│  频率: 1/4096, 每个气候区宽 ~16384 格                        │
│  输出: 基础温度、基础湿度、大陆性                             │
├─────────────────────────────────────────────────────────────┤
│  Level 2: 域扭曲（自然边界）                                 │
│  扭曲强度: 2000格, Y偏移: 13.7/29.3                          │
│  效果: 气候边界自然蜿蜒，无生硬带状分布                       │
├─────────────────────────────────────────────────────────────┤
│  Level 3: 海拔修正 + 局部细节                                │
│  海拔递减率: 0.6°C/100格（雪线效应）                         │
│  局部噪声: lacunarity=2.1, ±0.1 微调                         │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 温度计算

```java
温度 = 基础温度(噪声) + 海拔修正 + 洋流影响 + 局部变化

基础温度: Perlin噪声，频率0.00025，2八度，lacunarity=2.1
海拔修正: -0.06 * (海拔/100)
洋流影响: 沿海±0.15（暖流增温/寒流降温）
局部变化: Perlin噪声，频率0.01，lacunarity=2.1，±0.05
Y轴: 动态偏移 baseY + getYOffset() + 八度×3.7
```

### 2.3 湿度计算

```java
湿度 = 基础湿度 + 海洋影响 + 季风 + 地形雨 + 温度影响

基础湿度: Perlin噪声，频率0.0003，2八度，lacunarity=2.15
海洋影响: (1-大陆性) * 0.4
季风: 大陆东岸±0.2
地形雨: 海拔 * 0.2
温度影响: 温度 * 0.1
```

### 2.4 洋流系统

```java
float oceanCurrentEffect(float x, float z, float continentality) {
    if (continentality > 0.6) return 0;  // 内陆无影响
    float current = sampleFBM(oceanCurrentNoise, x*0.001, z*0.001, 2, 0.5, 2.1);
    float coastalFactor = 1 - continentality/0.6;
    return current * 0.15 * coastalFactor;
}
```

### 2.5 季风系统

```java
float monsoonEffect(float x, float z, float continentality) {
    if (continentality < 0.3 || continentality > 0.8) return 0;
    float monsoon = sampleFBM(monsoonNoise, x*0.0008, z*0.0008, 2, 0.5, 2.1);
    return monsoon * 0.2;
}
```

---

## 三、地质系统 (GeologySystem)

### 3.1 岩石类型

| 岩石类型 | 硬度 | 粘度 | 孔隙度 | 风化速率 | 典型气候 |
|---------|------|------|--------|----------|---------|
| **花岗岩** | 0.85 | 0.3 | 0.2 | 0.3 | 低温高湿 |
| **玄武岩** | 0.80 | 0.2 | 0.3 | 0.4 | 火山 |
| **砂岩** | 0.60 | 0.2 | 0.8 | 0.5 | 高温干旱 |
| **页岩** | 0.30 | 0.7 | 0.4 | 0.7 | 高温高湿 |
| **石灰岩** | 0.50 | 0.4 | 0.5 | 0.6 | 中等 |
| **粘土** | 0.20 | 0.9 | 0.3 | 0.8 | 高温高湿 |
| **石英岩** | 0.95 | 0.1 | 0.1 | 0.1 | 低温干旱 |
| **冻土** | 0.90 | 0.1 | 0.2 | 0.2 | 高海拔 |
| **火山岩** | 0.75 | 0.2 | 0.6 | 0.5 | 火山 |

### 3.2 气候-地质推断

```java
if (temperature > 0.6 && moisture > 0.6) {
    // 高温+高湿 → 化学风化强 → 软但粘
    rockType = (chemicalWeathering > 0.7) ? CLAY : SHALE;
} else if (temperature > 0.6 && moisture < 0.3) {
    // 高温+干旱 → 物理风化 → 硬但松
    rockType = SANDSTONE;
} else if (temperature < 0.3 && moisture > 0.5) {
    // 低温+高湿 → 冻融 → 硬且密
    rockType = (elevation > 0.7) ? PERMAFROST : GRANITE;
} else if (temperature < 0.3 && moisture < 0.3) {
    // 低温+干旱 → 极硬
    rockType = QUARTZITE;
} else {
    // 中等 → 石灰岩
    rockType = LIMESTONE;
}
```

### 3.3 抗侵蚀性计算

```java
resistance = hardness * 0.5 + viscosity * 0.3 - porosity * 0.2
```

### 3.4 风化模拟

```java
// 风化降低硬度，增加孔隙度和粘度
newHardness = hardness - weatheringRate * erosionStrength * time
newPorosity = porosity + weatheringRate * erosionStrength * time * 0.5
newViscosity = viscosity + weatheringRate * erosionStrength * time * 0.2
```

---

## 四、混合侵蚀系统 (ErosionSystem)

### 4.1 侵蚀类型

| 侵蚀类型 | 触发条件 | 效果 |
|---------|---------|------|
| **水力侵蚀** | 高温+高湿+高海拔 | 深谷、圆润山峰 |
| **风蚀** | 高温+干旱 | 风蚀凹地、沙丘 |
| **冰川侵蚀** | 低温+高海拔 | U形谷、冰斗 |
| **热侵蚀** | 昼夜温差大 | 风化剥落 |
| **海岸侵蚀** | 沿海地区 | 海蚀崖、浪蚀台 |

### 4.2 权重归一化

```java
ErosionResult result = calculateErosion(temp, moisture, elevation, continentality);
// hydraulicWeight + windWeight + glacialWeight + thermalWeight + coastalWeight = 1.0
```

### 4.3 粒子侵蚀引擎 (ErosionEngine)

基于TerraForged的Pixie粒子水滴侵蚀算法：

```
参数:
- dropletsPerChunk: 150 * globalStrength
- erodeSpeed: 0.5 * globalStrength
- depositSpeed: 0.5 * globalStrength
- maxDropletLifetime: 20 * globalStrength + 10
- erosionRadius: 4 (侵蚀笔刷半径)
- inertia: 0.005 (水滴惯性)
- gravity: 2.5 (重力加速)

流程:
1. 在Tile(32×32)内随机生成粒子
2. 粒子沿地形梯度移动（带惯性）
3. 陡坡侵蚀→携带沉积物
4. 缓坡沉积→释放沉积物
5. 蒸发衰减→生命周期终止
```

### 4.4 混合侵蚀示例

| 环境 | 水力 | 风蚀 | 冰川 | 热蚀 | 海岸 |
|------|------|------|------|------|------|
| **热带雨林** | 0.50 | 0.05 | 0.00 | 0.30 | 0.15 |
| **沙漠** | 0.05 | 0.60 | 0.00 | 0.30 | 0.05 |
| **高山** | 0.25 | 0.10 | 0.50 | 0.10 | 0.05 |
| **海岸** | 0.30 | 0.10 | 0.00 | 0.20 | 0.40 |

---

## 五、水文系统 (HydrologySystem)

### 5.1 降水模拟

```java
降水 = 基础湿度 * 0.6 + 地形雨 * 0.2 + 温度 * 0.2
地形雨 = 海拔 * 0.3
```

### 5.2 河流模拟

```java
河流强度 = 降水 * 0.5 + 坡度 * 0.3 + (1-海拔) * 0.2
河谷深度 = 河流强度 * 侵蚀强度 * 0.5
河谷宽度 = 河流强度 * (1-海拔) * 0.3
```

### 5.3 水文侵蚀

```java
水文侵蚀 = 降水 * 0.4 + 河流强度 * 0.4 + 坡度 * 0.2
```

---

## 六、地形类型系统

### 6.1 地形类型判断

```java
TerrainType getTerrainType(temp, moisture, elevation, erosion) {
    if (elevation > 0.8) {
        if (temp < 0.2) return GLACIER; else return ALPINE;
    }
    if (elevation > 0.5) {
        if (moisture > 0.6) return FOREST_HILL;
        if (moisture < 0.3) return DRY_HILL;
        return HILL;
    }
    if (elevation > 0.2) {
        if (moisture > 0.7) return WETLAND;
        if (moisture < 0.2) return DESERT;
        if (temp > 0.7 && moisture > 0.5) return RAINFOREST;
        if (temp < 0.3) return TUNDRA;
        return PLAINS;
    }
    if (moisture > 0.5) return COASTAL;
    return BEACH;
}
```

### 6.2 地形类型列表

| 类型 | 条件 | 特征 |
|------|------|------|
| **冰川** | 高海拔+低温 | U形谷、冰斗、冰雪 |
| **高山** | 高海拔 | 尖锐山峰、陡峭坡面 |
| **森林丘陵** | 丘陵+湿润 | 树木覆盖、柔和起伏 |
| **干旱丘陵** | 丘陵+干旱 | 稀疏植被、岩石裸露 |
| **丘陵** | 中等海拔 | 起伏地形、混合植被 |
| **湿地** | 低海拔+高湿 | 沼泽、湖泊、芦苇 |
| **沙漠** | 低海拔+干旱 | 沙丘、风蚀凹地 |
| **雨林** | 低海拔+高温高湿 | 茂密植被、深谷 |
| **冻原** | 低海拔+低温 | 苔藓、冻土、无树 |
| **平原** | 低海拔+中等 | 平坦、农田、草原 |
| **海岸** | 沿海+湿润 | 沙滩、岩石海岸 |
| **沙滩** | 沿海+干旱 | 金色沙滩、缓坡 |

---

## 七、集成流程

### 7.1 地形生成流程

```java
// 1. 采样气候（ClimateSystem + NoiseEngine 三级架构）
float continentality = climate.sampleContinentality(x, z);
float elevation = climate.sampleElevation(x, z);
float temperature = climate.sampleTemperature(x, z, elevation);
float moisture = climate.sampleMoisture(x, z, continentality, elevation, temperature);

// 2. 推断地质（GeologySystem）
GeologyProperties rock = geology.inferGeology(temperature, moisture, elevation);

// 3. 计算侵蚀权重（ClimateSystem）
ErosionResult erosion = climate.calculateErosion(temperature, moisture, elevation, continentality);

// 4. 计算水文（HydrologySystem）
float precipitation = hydrology.calculatePrecipitation(temperature, moisture, elevation);
float riverStrength = hydrology.calculateRiverStrength(precipitation, elevation, slope);

// 5. 计算抗侵蚀性（GeologySystem）
float resistance = geology.calculateErosionResistance(rock);

// 6. 生成18×18基础地形
float height = computeFinalHeight(continentality, elevation, temperature, moisture,
    ridge, erosion, resistance, valleyDepth, riverStrength, rockHardness);

// 7. 3×3边界平滑 → 16×16输出
TerrainData data = smoothBoundary(rawHeights);

// 8. Tile合并（2×2区块 → 32×32 Tile）
TileData tile = tileCache.get(chunkX, chunkZ);

// 9. Tile内运行粒子侵蚀（ErosionEngine）
erosionEngine.applyErosion(tile, seaLevel, minY);

// 10. 判断地形类型
TerrainType type = climate.getTerrainType(temperature, moisture, elevation, erosion.totalErosion);
```

### 7.2 海岸5段过渡

```
深海 → 浅海 → 沙滩 → 海岸 → 内陆

continentality < 0.40 : 纯海洋地形
0.40 - 0.55          : 海洋→沙滩平滑过渡
0.55 - 0.65          : 低地形→30%陆地
0.65 - 0.70          : 30%→100%陆地
> 0.70               : 完整陆地地形
```

---

## 八、性能优化

### 8.1 缓存策略

| 级别 | 容量 | 策略 |
|------|------|------|
| L1 TerrainCache | 500区块 | ConcurrentHashMap, 满时清空 |
| L2 TileCache | 250 Tile | ConcurrentHashMap, Tile=2×2区块 |
| L3 (未来) | 2048区块 | 预加载+序列化 |

### 8.2 LOD系统 (待实现)

| 距离 | 采样率 | 侵蚀 | 地貌 |
|------|--------|------|------|
| 0-4 chunks | 16x16 | 完整 | 完整 |
| 5-12 chunks | 8x8 | 简化 | 简化 |
| 13-32 chunks | 4x4 | 仅宏观 | 禁用 |
| >32 chunks | 2x2 | 禁用 | 禁用 |

---

## 九、文件结构

```
com.geogenesis.worldgen/
├── GeoGenesisGenerator.java    # ChunkGenerator入口，地形填充
├── GeoLevels.java              # 配置文件（高度/海平面等）
├── NoiseEngine.java            # 噪声引擎（防条纹优化版）
├── TerrainCache.java           # 地形缓存+系统管线
├── TileCache.java              # Tile管理器（2×2区块，运行侵蚀）
├── MaterialMapper.java         # 材料映射（温度/湿度→方块类型）
├── climate/
│   └── ClimateSystem.java      # 气候系统（三级模拟+侵蚀权重）
├── geology/
│   └── GeologySystem.java      # 地质系统（9种岩石+风化）
├── hydrology/
│   └── HydrologySystem.java    # 水文系统（降水+河流+河谷）
└── erosion/
    └── ErosionEngine.java      # 粒子侵蚀引擎（TerraForged风格）
```

---

## 十、开发状态

### 10.1 已完成

- [x] 气候系统（三级模拟：大陆→域扭曲→海拔修正）
- [x] 地质系统（9种岩石类型+风化模拟）
- [x] 混合侵蚀系统（5种侵蚀，权重归一化）
- [x] 水文系统（降水、河流、河谷）
- [x] 地形类型系统（12种地形枚举）
- [x] 噪声防条纹优化（Y轴偏移+非整数lacunarity+旋转扰动）
- [x] 5段海岸渐进过渡
- [x] 18×18区块边界平滑
- [x] 粒子侵蚀引擎（TerraForged风格Pixie算法）
- [x] Tile-based架构（2×2区块→32×32 Tile）

### 10.2 实现中

- [~] Tile粒子侵蚀管线（TileCache集成ErosionEngine）

### 10.3 待实现

- [ ] 河流网络生成（路径追踪）
- [ ] 10种地貌特征（丹霞、峡湾、喀斯特等）
- [ ] 生物群系集成
- [ ] UI配置界面
- [ ] LOD多级采样
- [ ] 二级缓存（RegionCache + DiskCache）

---

## 附录

### A. Tile-based架构详解

```
Tile = 2×2 相邻区块 = 32×32 格

Tile生命周期:
1. 4个区块到达 → 收集TerrainCache数据
2. 拼接为32×32临时高度图
3. 运行ErosionEngine粒子侵蚀
4. 拆分回4个16×16区块
5. 存入区块高度图
6. 释放Tile内存

Tile同步机制:
- 每个Tile管理4个区块的状态位
- 4个区块全部ready → 启动侵蚀
- 侵蚀完成 → 通知所有4个区块
```

### B. 参考项目

- [TerraForged](https://github.com/TerraForged/TerraForged) - 架构参考（噪声混合+海岸过渡）
- [Clean Terrain Erosion Filter](https://www.shadertoy.com/view/...) - 侵蚀算法（Pixie粒子法）

### C. 术语表

| 术语 | 说明 |
|------|------|
| ClimateSystem | 气候系统，三级模拟驱动地形生成 |
| GeologySystem | 地质系统，气候推断9种岩石类型 |
| ErosionEngine | 粒子侵蚀引擎，TerraForged风格Pixie算法 |
| HydrologySystem | 水文系统，模拟降水河流河谷 |
| NoiseEngine | 噪声引擎，多层Noise采样+防条纹 |
| TerrainCache | 地形缓存，集成全系统管线 |
| TileCache | Tile管理器，2×2区块合并运行侵蚀 |
| TerrainType | 地形类型（12种） |
| RockType | 岩石类型（9种） |
| DomainWarping | 域扭曲，让气候边界自然蜿蜒 |
| PixieErosion | 粒子水滴侵蚀算法 |

---

*本文档由 GeoGenesis 开发团队维护*
*版本: 1.2 | 日期: 2026-05-12*