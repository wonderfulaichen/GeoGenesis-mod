# GeoGenesis 长期记忆

## 用户方法论偏好
- 参考模组先读透再提方案；严格先案后码；模块化/单一职责
- 知识沉淀必须做；每完成一次任务/对话做记录

## 🚨 地形类型核心原则（2026-07-16 深夜更新 v6.0）

**域扭曲空间距离场 + 高斯权重 + 独立类型噪声 + 按 typeWeights 加权混合 eLand（非 lo/hi）= 无断裂无网格无悬崖**

### 六层消除机制
1. **域扭曲空间距离**（v4）
2. **高斯权重**（v4）：`w = exp(-d²/2σ²)`，σ=CELL_SPACING/4=100
3. **SEARCH_RADIUS=1**（v5.2，因 SIGMA=100 使边缘 cell 权重 0.0003 可忽略）
4. **连续类型权重**（v5）
5. **独立噪声配方**（v5，每类型专属噪声节点树）
6. **v6.0：按 typeWeights 加权混合各类型独立 eLand（非 lo/hi）** —— 根除 cell 边界 1 块高度离散跳变。每类型 `eLand_i = center + halfRange×(2×noise−1)`，按 typeWeights 加权混合。

### v5 诊断验证
- 创建 `DiscontinuityProbe.java`（13 层梯度诊断，512×512，纯 Java 无 MC 依赖）
- SEARCH_RADIUS=1→2 后所有连续层 max Δe 从 0.09→0.004（21× 改善）
- eLand max Δe = 0.0058（2.2 块），远低于阈值 0.01（3.8 块）

### 关键参数
| 参数 | 值 | 说明 |
|------|------|------|
| CELL_SPACING | 400 块 | cell 间距 |
| WARP_AMP（Voronoi） | 250 | 距离场域扭曲（> cell 间距/2 = 200）|
| WARP_AMP（共享噪声） | 300 | 共享噪声域扭曲 |
| SIGMA | 200 | 高斯权重 σ = cell 间距/2 |
| SEARCH_RADIUS | **2** | **5×5 = 25 cells（v5 修复）**|

### 各类型范围参数（v6.0 CENTER+RANGE 模式）
| 类型 | CENTER | HALF_RANGE | 输出 range | 说明 |
|------|--------|-----------|-----------|------|
| PLAIN | 0.0375 | 0.0225 | [0.015, 0.06] | 低平温和 |
| HILLS | 0.155 | 0.095 | [0.06, 0.25] | 圆润起伏（v6.0 双层Warp+Perlin×Billow^0.5）|
| MOUNTAINS | 0.70 | 0.25 | [0.45, 0.95] | 尖锐脊线 |
| PLATEAU | 0.325 | 0.125 | [0.20, 0.45] | v6.0 加 smoothstep 边缘渐变（shape mask）|
| BASIN | 0.0475 | 0.0325 | [0.015, 0.08] | 凹陷盆地 |

### 关键文件
- `VoronoiRegionField.java` — 域扭曲空间距离场 + 高斯权重 + 5×5 搜索 + typeWeights
- `TypeGenerators.java` — 类型范围常量 (lo/hi) + basinModulate（被 Voronoi 引用）
- **`TypeNoiseProvider.java`** （2026-07-16 新增）— **每种类型独立噪声配方**
- `TypeLandShape.java` — 编排层：typeWeights 加权混合各类型独立噪声 → eLand
- `DiscontinuityProbe.java` — 13 层梯度诊断工具

### 各类型独立噪声配方（v6.0 更新）

| 类型 | 噪声配方 | 视觉特征 | 参考来源 |
|------|---------|---------|---------|
| PLAIN | `Map(Warp(Simplex(1/800), 60), [−1,1]→[0.42,0.58])` | 极平坦，微小起伏 | TF: Scale(0.08~0.15)×Perlin |
| HILLS | `Clamp(Boost(Warp(Warp(Multiply(Perlin(1/500), Power(Billow(1/300),0.5)), 20, 1/30), 200, 1/333), 0.6)+0.02, 0, 1)` | 圆润不规则丘陵（v6.0 双层 warp） | TF hills_1.json: Perlin×Billow^0.5 + 双 warp |
| MOUNTAINS | `Warp(Ridge(1/400,p=1.2), 200) + 0.3×Ridge(1/120)` | 尖锐脊线+蜿蜒 | TF: Ridge+Valley+DomainWarp |
| PLATEAU | `3Simplex(1/500+1/200+1/60)→[0.30,0.70] × smoothstep(shape(1/1000), 0.15, 0.40)` | v6.0 边缘 smoothstep 渐变 + shape mask | TF: ramp_height=0.35 |
| BASIN | `Map(Invert(2Simplex(1/300+1/100)), [−1.5,1.5]→[0,0.6])` | 凹陷盆地 | TF: Invert(Perlin) |

### 新噪声流水线
```
TypeLandShape.sample(blend, wx, wz):
  for each LAND_TYPE with weight>0.001:
    noise += weight × TypeNoiseProvider.computeNoise(type, wx, wz)
  base = noise / totalWeight
  basinW > 0.01 → lerp(base, basinModulate(base), basinW)
  eLand = lo + (hi-lo) × base
```

## 配置同步铁律
增删字段须同步 **6 处**：
`GeoGenesisConfig`(定义+BUILDER+buildParams+defaultParams) + `TerrainParams`(record+defaults) + `GeoGenesisConfigScreen.buildParams` + `run/config/geogenesis-common.toml`
改默认值后必须同步已存在的 toml。

## 关键工程陷阱

### Forge 1.20.1
- dev 运行 = `sourceSets.main`（官方映射），**禁止**把 reobf jar 拷进 `run/mods/`
- `f_XXXX_`/`m_XXXX_` NoSuchFieldError → 第一反应删除 `run/mods/` 里的 jar
- `NoiseColumn` 在 `net.minecraft.world.level` 包（非 levelgen）
- biome 是 dynamic(datapack) 注册表，解析须用 `ServerLifecycleHooks.getCurrentServer().registryAccess()`；**禁止缓存 Registry<Biome> 到静态字段**
- 叶子 CODEC 禁止 `.stable()`/`.withLifecycle()`（会导致 world_preset 解析崩溃）

### 地形
- `e→Y` 映射必须非对称（e=0→seaLevel=63）
- 独立预览用 `GeoGenesisConfig.defaultParams()` 而非 `buildParams()`
- 离散图层颜色数组必须与枚举同序同数

### 诊断经验
- 用户报"地形没变"时，先查 class 文件时间是否比源文件新 + `run/mods/` 是否被手放 jar
- 编译后必须 `gradlew compileJava --rerun-tasks` 验证全量编译

## 2026-07-16 类型系统重构

### 核心变更
用地形类型系统取代省系统（craton/belt/plateau/basin），每个类型有自己的噪声配方，类型间用样条嵌入噪声对象过渡。

### 新文件
- `TypeGenerators.java` — 类型专属噪声生成器（8 个类型，独立噪声节点）
- `TypeLandShape.java` — 类型驱动地形形态生成器（baseElev + 样条嵌入噪声对象）

### 删除文件
- `LandShape.java` / `TypeMorphology.java` / `TerrainComposer.java` / `ClimateWeights.java`

### 编译验证
BUILD SUCCESSFUL in 13s（2026-07-16晚）

## 2026-07-19 海洋特征 & OceanFeatures 重构

### OceanFeatures.java 变更
- 移除 per-cell eOcean gate（旧 eOcean < -0.30 太严格）
- 新增 `seamountCenterDepthCheck`（DoubleBinaryOperator 回调）
- 海山中心水深校验：eOcean_at_center > -0.20 → 跳过
- 振幅 0.12-0.25 → 0.08-0.16
- `seamountCompute` 签名取消 eOcean 参数

### CellGenerator.java 变更
- seed() 中注入 depthChecker 回调（复用 continent / heightCurve / seaBed）

### TerrainClass 海洋类型
新增 CONTINENTAL_SHELF、SUBMARINE_RIDGE、SEAMOUNT（均位于 OCEAN/DEEP_OCEAN 之后）

### 海洋地形类型（共 7 水域类）
OCEAN、DEEP_OCEAN、CONTINENTAL_SHELF、SUBMARINE_RIDGE、SEAMOUNT、RIVER、LAKE

### 诊断清理
OceanFeatureProbe.java 已于 07-19 删除，probe_ocean_*.txt 已清理

## 2026-07-20 统一嵌套样条系统（Phase 1）

### 设计理念
对标 MC 原版 `offset.json`，整个地形系统是一个统一的嵌套样条树，海陆都在同一个样条内部。

### 三层嵌套结构（目标）
```
外层样条：大陆性 c（-1=深海, 0=海岸, 1=内陆）
├─ 中层样条：地形类型分布
│   └─ 内层样条：lo/hi 形状控制
```

### Phase 1 已完成（2 层嵌套）
- 外层样条：7 个大陆性 c 控制点（14 字段）
- 内层样条：5 个核心陆地类型的 lo/hi 样条（60 字段）
- **总计 74 字段**，封装在 `SplineConfig` record 中

### Phase 2 已完成（3 层嵌套）
- 中层样条：7 个外层节点 × 12 个类型 × 3 字段 = **252 字段**（实际 105，因陆地 5 类型 + 海洋 7 类型）
- 类型位置：PLAIN=0.0, HILLS=0.25, MOUNTAINS=0.5, PLATEAU=0.75, BASIN=1.0

### Phase 3 已完成（海洋/水域类型）
- 海洋/水域内层样条：7 类型 × 12 字段 = **84 字段**，封装在 `OceanSplineConfig` record 中

### 关键文件
- `UnifiedSpline.java` — 统一样条树（OuterNode + MidSpline + MidNode + InnerSpline + Spline）
- `SplineConfig.java` — 样条配置 record（74 字段 + OceanSplineConfig + MidSplineConfig）
- `MidSplineConfig.java` — 中层样条配置 record（105 字段）
- `OceanSplineConfig.java` — 海洋/水域内层样条配置 record（84 字段）
- `TypeGenerators.java` — 新增 `sampleFromSpline(c, typePosition, noiseValue)` 方法
- `TypeLandShape.java` — `sample()` 分为样条路径和旧路径 fallback

### 工程陷阱
- Java record 参数过多限制：74 字段使 TerrainParams 总参数 ~154，编译失败。解法：提取为 `SplineConfig` 独立 record
- SplineConfig 参数过多限制：84 海洋字段使 SplineConfig 总参数 ~159，编译失败。解法：提取为 `OceanSplineConfig` 独立 record
- GeoGenesisConfig 保留独立 ForgeConfigSpec 字段（TOML 需要），通过辅助方法组装为 SplineConfig
- MidSplineConfig/OceanSplineConfig 不暴露到 TOML 配置（太多字段），从 defaults 初始化

### 预览验证
- `runPreview --args=12345` 运行成功（BUILD SUCCESSFUL in 7m 40s）✅
- 地形生成正常，预览窗口已启动

### 待办
- UI：调音台面板改造支持样条编辑（较大的 UI 改造任务）
- runClient 目检游戏内地形
- BASIN 尚未集成到阈值链（需 moisture 二级分类支持，待后续迭代）
- 类型参数化：TypeLandShape 的阈值和 TypeGenerators 的噪声参数需从 TerrainParams 注入
- GeoGenesisConfigScreen 的省滑块需替换为类型滑块
- runClient 目检游戏内地形
- mixer 面板重绑到新类型参数
