# GeoGenesis 长期记忆

## 用户方法论偏好
- 参考模组先读透再提方案；严格**先案后码**（复杂功能先出方案待确认再码）；模块化/单一职责
- 知识沉淀必须做；每完成一次任务/对话做记录；docs 新文档需更新 INDEX
- 关键决策点用提问确认（不打断则默认授权"你决定"）；微小细节可先用合理默认值并标注

## 地形引擎核心原则（钉死）
- **e(x,z) 单一连续场**：大陆性 `c∈[0,1]` 单一连续噪声，海陆仅条件切分；海岸 = e 场自然穿过 0 的等值线（smoothstep 过渡），不绑定 c 硬阈值锚点。
- **【铁律·c 绝不控制地形高度】**：c 只做两件事——(1) 决定 (x,z) 出现什么类型；(2) 决定海陆 mask（c<0 海洋域 / c>0 陆地域）。地形高度由类型各自的独立噪声全权决定。自查：若 `if c>X then e=f(c)` 或 `e+=g(c)` 即违规。
- **差异调制（v14，连续无断裂）**：`shared=computeSharedNoise` + `perTypeAvg=Σw_t·typeNoise_t/Σw_t` + `modulated=shared+0.4·(perTypeAvg-shared)`，零均值偏差保障 C0 连续。旧 v7-v13 用 per-type 独立噪声全量加权 → Δe 0.04-0.07 断裂。
- **类型过渡 = Voronoi 高斯距离权重**（2026-07-25）：`CELL_SPACING=400`，5×5 窗口 σ=150，边缘权重 ×1e-7 消除窗口跳变；任意类型可邻接任意类型。`cAffinityStrength` 乘法偏置叠加（默认 3.0，恢复山脉偏内陆语义）。
- **条件系统（2026-07-21）**：温度(5段)/湿度(4段)/大陆性(7段) 用 `ClimateSpline`(Cubic Hermite) 连续映射；雪线双曲线（2026-07-22）锚定 seaLevel~maxY。
- **oceanDepthFactor**（2026-07-22）：乘 eOcean，>1 更深 <1 更浅，默认 1.0 [0.5,3.0]。`e→Y` 必须非对称（e=0→seaLevel=63）。

## 配置同步铁律
- 增删字段须同步：`GeoGenesisConfig`(定义+BUILDER+buildParams+defaultParams) + `TerrainParams`(record+defaults) + `ParameterConfigPanel.addSpec` + toml；改默认值须同步已存在 toml。
- **`resetToDefault()` 已反射自动化**（遍历 ConfigValue 字段 set 默认），新增字段自动覆盖；但 `defaultParams()`/`buildParams()` 仍需随字段同步。
- **滑块 reset 铁律**：`ParamSlider.setDefaultValue` 必须填 `cfg.getDefault()`，**严禁填 `cfg.get()`**（否则重置拉到最低而非代码默认）。
- Forge 1.20.1：禁止 reobf jar 拷进 `run/mods/`；biome 注册表用 `registryAccess()` 禁静态缓存；叶子 CODEC 禁 `.stable()`。

## 重要重构历史
- 2026-07-16 类型系统取代省系统；07-20 统一嵌套样条；07-21 气候样条化；07-22 双曲线雪线；07-24 阶段2 语义亲和度接入 + 双轨死链删除（TerrainParams 删 10 个 `*Center/*HalfRange`，compileJava BUILD SUCCESSFUL）。
- 2026-07-25 类型过渡恢复 Voronoi 高斯权重（否决双线性）；v14 差异调制钉死 + c 铁律。
- 完全自定义架构（阶段3 待做）：TerrainTypeSpec(JSON)+Registry 加载 `config/geogenesis/terrain_types.json` 增删类型零代码；BiomeClassifier 数据驱动。

## 配置屏（2026-07-23 单屏 8 标签，常驻预览）
页签 0世界参数/1气候/2地形/3显示/4采样/5色带/6缓存/7群系。三按钮 [应用]（`SPEC.save()`+返回父屏）[保存]（命名预设→`user_presets.json`）[重置]（`resetToDefault()`）。`GeoGenesisConfig` 反射三件套：`resetToDefault/captureAllValues/applyNamedValues`。

## 侵蚀引擎（2026-07-28 Tile Context Chain）

### 引擎特性（不变）
- **本地确定性液滴引擎**：3 尺度（R_C=12/R_M=6/R_F=3），SH 原版参数对齐
- erf 放电反馈、per-step cascade、片流、水下延伸、BASELINE_ERODE=0.0012、双平滑

### 超分辨率 tile 架构（2026-07-27 晚重写，仿 6 月备份 70cd037）
- **采样改为 spacing=4 粗采 + Catmull-Rom 双三次插值**：32×32=1024 次 `terrainE()` 替代原来的 6400 次
- **侵蚀跑在插值场上**（不是真实 `terrainE`），插值场保大尺度特征即可，侵蚀自己雕刻细节
- **每 tile 输出 3×3=9 个 chunk**（`ERODE_TILE_CHUNKS=3`），替代原 1 chunk/tile
- **缓存 256 条目**，无 LRU 驱逐，出生区域 tiles 常驻
- tile 边长 128（`3*16 + 40*2`），center 3 chunk 分别提取，offset 兼容

### Tile Context Chain（2026-07-28 新增，Phase 1）
- **根因**：flat 缓冲区边缘镜像填充导致液滴在 tile 边界附近轨迹不连续 → 侵蚀结果断裂
- **方案**：相邻 tile 共享侵蚀后全高度场作为 flat 缓冲区上下文
- **ErosionEngine**：新增 `runErosionOnFlat(flat, flatPre, bufSize, ...)` 公共方法（接受外部预填充 flat，跳过内部镜像填充）
- **ErosionTileResult**：每个 tile 缓存存储 `delta`（叠加量）+ `postErosion[128][128]`（侵蚀后全高度）+ tile 元数据
- **三区制 flat 初始化**：
  - 区 1（本 tile 内）：插值场直接拷贝
  - 区 2a（左邻域 worldX < originX）：读左邻居 postErosion
  - 区 2b（上邻域 worldZ < originZ）：读上邻居 postErosion
  - 区 3（右/下/角隅无邻居）：terrainE() 直接采样回退
- **依赖顺序**：`getErosionTile` 递归调用 `ensureErosionTile` 保证左/上邻居先完成（游程: 左→右, 上→下）
- 缓存类型从 `ConcurrentHashMap<Long, float[][]>` → `ConcurrentHashMap<Long, ErosionTileResult>`
- `getErosionTile` 返回 `result.delta` 保持 `extractFromTile` 向后兼容

### 效率对比
- 旧架构（无超分辨率）：900 chunk × 6400 terrainE = 5.76M 次 `terrainE()`
- 新架构（spacing=4）：900 chunk / 9 × 1024 = 0.1M 次 `terrainE()`
- 减少了约 **57 倍**的 `terrainE()` 调用量

### 状态
- 代码已合并到 `main`，`erosionEnabled` 默认 false（需手动开）
- 全局对齐的双三次插值保证相邻 tile 连续（共用低分辨率控制点网格）
- Tile Context Chain Phase 1 编译通过 BUILD SUCCESSFUL

### 河流系统开关（2026-07-28 新增）
- `CellGenerator.riversEnabled` 字段（默认 true），构造时从 `GeoGenesisConfig.riversEnabled` 读取
- `computeRivers` 与 `extractFromTile` 守卫：关闭时跳过河网计算/读取
- `GeoGenesisConfig` 加 `riversEnabled` 配置项；`run/config/geogenesis-common.toml` 设 `false` 即可在游戏里关闭河流
- 用于隔离侵蚀测试：之前用户看到的"树状沙纹"是河流 bug，不是侵蚀

### ErosionTileProbe 诊断（2026-07-28）
6 阶段诊断探针，输出到 `runErosionTileProbe`：
- Stage 1: terrainE 一致性
- Stage 2: bicubic base 连续性（修复后 0.000000）
- Stage 3/4: 侵蚀后高度 / delta 连续性
- Stage 5: 边界横截面
- Stage 6a: 液滴覆盖不对称；Stage 6b: delta vs 距离

### 隐式流功率侵蚀（2026-07-28，**未完成**）
- **参考论文**：Braun & Willett 2013 (O(n) 隐式流功率), Schott et al. 2024 SIGGRAPH (Analytical Terrains), Landlab FastscapeEroder
- **公式**：`h_new = (h_old + alpha*h_receiver)/(1+alpha)`，n=1 闭合解，无 Brent
- **ErosionEngine** 新增 `HeightQuery` 接口（`applyStreamPower(... HeightQuery, int[] origin)`），越界时读 `terrainE` 同源
- **多档尝试**：K=0.1/0.15/0.3/0.5/3.0 + nIters=3/10/12 + 暴力 sqrt(A) 公式 + 局部最低下蚀 (5×5)
- **结果**：连续性可达 0.009（13 块 PASS），但视觉上全是"剥皮"——所有 cell 同比例下剥
- **真根因**：bicubic 在 32×32→128×128 平滑掉了原始 sampleCore 的深谷 → dh 全近似 → 均匀下剥
- **真解法（未实施）**：放弃 bicubic，扩展 tile 为 5×5 chunk 区域直接用 sampleCore 全分辨率做 D8 流量累积+流功率

### 液滴模拟回退（v1.7.0，2026-07-28）
- 用户反馈"分析公式全是剥皮"后回退到液滴（`runErosionOnFlat`）
- 单轮（去多轮）+ 3-zone flat 缓冲区（left/top 邻居 postErosion + terrainE 回退）
- **配置调参（避免剥皮）**：`erosionStrength=0.3, erosionDropsMul=0.4, erosionErodeMul=0.4`（约 12% 默认强度）
- 性能：单轮 580K ops/tile（vs 多轮 1.7M，3× 加速）
- 已知问题：跨 tile 边界仍有轻微视觉阶梯（待全分辨率方案解决）

## BiomeSource 性能修正（2026-07-27 → 2026-07-27 末更正）
- **初始误判**：认为 87s 世界创建慢的原因是 `BiomeSource` + `CellGenerator.sample()` 全管线
- **实际根因**：**侵蚀 tile 管线**（`erosionEnabled=true` 默认运行）。每区块 6400 次 `terrainE()` (=全 `sampleCore()` 调用) = chunk 格点采样的 **26 倍工作量**
- **修复前优化尝试**：FeatureResult 去重（-6-10%）、SEARCH_RADIUS 2→1（-25-30%）均被侵蚀淹没，用户反馈"无效"
- **最终解决**：`run/config/geogenesis-common.toml` `erosionEnabled = false`
- **BiomeSource 出生区域快速路径**（terrain==null 时返 plains）保留有效，解决的是首次初始化阻塞，非 87s 主因

## 清理记录（2026-07-27）
- 已删除 `RiverNodeField.java` + `RiverCarver.java`（旧河流残留，编译错误链式遗存）
- 旧 `ErosionEngine.java` 备份式回滚文档已过时

## 待办
- [DONE] v14 修复（共享噪声公式 + cAffinity 默认 0.0，maxFe=0.010-0.021/4-8块）。
- [DONE] 性能优化（FeatureResult 去重 + SEARCH_RADIUS 2→1 + 侵蚀 tile 超分辨率采样 spacing=4）。
- [DONE] 侵蚀 tile 架构重写（仿 6 月备份 70cd037，spacing=4 粗采 + bicubic upsample + 3×3 chunk/tile）。
- [PENDING] **测试侵蚀可运行性**：设 `erosionEnabled=true` 后 `runClient` 验证性能和效果。
- [PENDING] 河流重接（待侵蚀确认可用后再做）。
- [PENDING] 测试 cAffinityStrength 设回非零值安全性；BASIN 曲线检查；mixer 面板重绑新类型参数；toml→JSON 迁移（阶段3）。
