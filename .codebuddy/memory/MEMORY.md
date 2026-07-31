# GeoGenesis 长期记忆

## 用户方法论偏好
- 参考模组先读透再提方案；严格**先案后码**（复杂功能先出方案待确认再码）；模块化/单一职责
- 知识沉淀必须做；每完成一次任务/对话做记录；docs 新文档需更新 INDEX
- 关键决策点用提问确认（不打断则默认授权"你决定"）；微小细节可先用合理默认值并标注

## 地形引擎核心原则（钉死）
- **e(x,z) 单一连续场**：大陆性 `c∈[0,1]` 单一连续噪声，海陆仅条件切分；海岸 = e 场自然穿过 0 的等值线（smoothstep 过渡），不绑定 c 硬阈值锚点。
- **【铁律·c 绝不控制地形高度】**：c 只决定类型 + 海陆 mask。地形高度由类型各自独立噪声决定。自查：若 `if c>X then e=f(c)` 或 `e+=g(c)` 即违规。
- **差异调制（v14，连续无断裂）**：`shared + 0.4·(perTypeAvg-shared)` 零均值偏差保障 C0 连续。
- **类型过渡 = Voronoi 高斯距离权重**（CELL_SPACING=400，σ=150，边缘 ×1e-7）；`cAffinityStrength` 乘法偏置（默认 3.0）。
- **条件系统**：温度/湿度/大陆性用 `ClimateSpline`(Cubic Hermite)；雪线双曲线锚定 seaLevel~maxY。
- **oceanDepthFactor**（乘 eOcean，默认 1.0 [0.5,3.0]）；`e→Y` 必须非对称（e=0→seaLevel=63）。

## 配置同步铁律
- 增删字段须同步：`GeoGenesisConfig`(定义+BUILDER) + `TerrainParams`(record+defaults) + `ParameterConfigPanel.addSpec` + toml。
- `resetToDefault()` 反射自动化（遍历 ConfigValue 字段 set 默认）；但 `defaultParams()`/`buildParams()` 仍需随字段同步。
- **滑块 reset 铁律**：`ParamSlider.setDefaultValue` 必须填 `cfg.getDefault()`，**严禁填 `cfg.get()`**。
- Forge 1.20.1：禁 reobf jar 进 `run/mods/`；biome 注册表用 `registryAccess()` 禁静态缓存；叶子 CODEC 禁 `.stable()`。

## 重要重构历史（摘要）
- 07-16 类型系统取代省系统；07-20 统一嵌套样条；07-21 气候样条化；07-22 双曲线雪线；07-24 语义亲和度 + 删 10 个 `*Center/*HalfRange`；07-25 Voronoi 高斯权重 + v14 差异调制钉死。
- 完全自定义架构（阶段3 待做）：`config/geogenesis/terrain_types.json` 增删类型零代码；BiomeClassifier 数据驱动。

## 配置屏（单屏 8 标签，常驻预览）
页签 0世界参数/1气候/2地形/3显示/4采样/5色带/6缓存/7群系。按钮 [应用]SPEC.save()[保存]user_presets.json[重置]resetToDefault()。反射三件套：resetToDefault/captureAllValues/applyNamedValues。

## 侵蚀引擎（两级架构，2026-07-31 拍板 + 已实现）
### Tile 架构（超分辨率，仿 6 月备份 70cd037）
- 采样 spacing=4 粗采 + Catmull-Rom 双三次插值（1024 次 terrainE/tile vs 旧 6400，约 57× 提速）。
- 每 tile 输出 3×3=9 chunk（ERODE_TILE_CHUNKS=3）；边长 128；缓存 256 条目。
- **Tile Context Chain**：`runErosionOnFlat(flat,flatPre,bufSize)` 接受外部预填充 flat；三区制 flat（本 tile 插值场 / 左·上邻居 postErosion / 右·下·角隅 terrainE 回退）；递归保证左→上邻居先完成。`ErosionTileResult` 存 delta+postErosion[128][128]。
- `erosionEnabled` 默认 false（游戏内手动开，否则世界创建慢）；`riversEnabled` 默认 true（隔离侵蚀测试可关）。

### 两级侵蚀
- **粗级（骨架）= 脊-谷条纹滤镜** `RidgeValleyErosion`（纯局部算子，无 tile 边界缝）：在**独立更密网格（RIDGE_SKELETON_SPACING=2，区别于主 bicubic 的 spacing=4）**采样真实地形跑 `computeCoarseDelta`（gradient 对齐 PhacelleNoise 条纹，octave 累积 gullySlope，combiMask 堆叠淡出）→ bilinear 升采样 → `flat = terrainE + coarseDeltaUp`。陆地 mask（smoothstep around seaE）保护海洋深度。
  - 溯源：Rune Skovbo Johansen 2026-03 博客 + Luke Mitchell Burst C# `lpmitchell/AdvancedTerrainErosion`(MPL-2.0+MIT)。技术笔记 `docs/05-分析诊断/03-脊谷条纹侵蚀骨架-ridge-valley-erosion.md`。
  - 配置（GeoGenesisConfig，仅声明+defineInRange+toml，不在 TerrainParams）：erosionRidgeEnabled=true / **Strength=0.08[0,0.5]** / Scale=100[50,800] / **CellScale=1.2[0.2,2.0]** / **Octaves=4[1,5]** / GullyWeight=0.5[0,1]。
  - **【2026-07-31 关键根因+修复】山谷不明显根因**：`combiMask = easeOut(smoothStart(slopeLen*onset, rounding*onset))`，slopeLen 用真实梯度 Δe/16（spacing=4 稀释），远低于阈值 rounding*onset≈0.0125 → **combiMask 恒≈0.004 → 条纹被完全淡出**（fGx≈fadeTarget，仅微弱线性偏移）。**修复=骨架层独立 spacing=2 网格采样真实地形恢复坡度**（combiMask 在真实陡坡正常触发）。配套：fadeTarget 对称化 `(h-0.25)/0.25`（谷=-1、峰=+1，LAND_REF=0.25 匹配陆地中值→delta.mean≈0 非剥皮）；strength 0.04→0.08（≤0.09 避免 >maxDeltaPerCell=0.15 截断）；octaves 2→4；CellScale 0.6→1.2（更密谷）。**配置同步铁律**：字段初始 / GeoGenesisConfig BUILDER / fromConfig 回退 三处必须一致（漏 fromConfig 回退曾导致探针走旧默认 0.04）。
  - **A/B 验证（RidgeValleyABProbe，2026-07-31，spacing=2）**：seed12345 陆地 10.9% → stdH 0.118→0.198（**stdR=1.68x**）、mean|grad| 0.0060→0.0100（**mgR=1.67x**）、maxGrad +68%；delta(full-land) mean=+0.0089≈0（**非剥皮**✓）；B.meanH−A.meanH=+0.009（山抬升、谷下切对称）。极少数点 max=0.174 略超 0.15（<5% 截断，正侧脊略平，可接受）。结论：**脊-谷骨架真正成形，山谷明显**。
- **细级（打磨）= 液滴侵蚀** `ErosionEngine.runErosionOnFlat`：单轮 + 3-zone flat；调参约 12% 默认强度（erosionStrength=0.3, dropsMul=0.4, erodeMul=0.4）。只做细节，不造大骨架。
- **否决方案**：① D8 流功率（flow-accumulation 必致 tile 边界缝，旧 bug 复活；且 bicubic 平滑深谷→"剥皮"匀质下剥，已验证失败）；② 局部脊-谷锐化（曲率调制只能锐化已有起伏，不能在圆噪声造新脊线）。

### 诊断工具
- `ErosionTileProbe`（6 阶段，runErosionTileProbe）；`MountainFootprintProbe`/MountainProfileProbe（runProfile）；`SharpnessABProbe`（stddev/mean|grad|max|grad|）。

## BiomeSource 性能
- 87s 世界创建慢的根因是侵蚀 tile 管线（每区块 6400 次 terrainE）；设 `erosionEnabled=false` 解决。BiomeSource 出生快速路径（terrain==null 返 plains）解决首次初始化阻塞。

## 待办
- [DONE] v14 修复；性能优化；侵蚀 tile 架构重写；**两级侵蚀骨架整合（RidgeValleyErosion 编译通过 2026-07-31）**。
- [PENDING] A/B 验证粗层把圆包切成多峰（contrast↑/max|grad|↑/mean 不降=非剥皮），用 SharpnessABProbe 或扩 ErosionTileProbe。
- [PENDING] `erosionEnabled=true` 后 runClient 验证性能/效果；河流重接（待侵蚀确认）。
- [PENDING] 测试 cAffinityStrength 非零安全性；BASIN 曲线检查；mixer 面板重绑新类型参数；toml→JSON 迁移（阶段3）。

## 验证铁律（地形噪声/高度项修改必守）
- 改完只说 BUILD SUCCESSFUL 不够：用 A/B 探针实测 eLand 统计（固定区域、坐标稳定）确认方向正确。
- **[0,1] 高度项严禁直接平方**（除峰值外整体下移→变圆）。锐化用保端点对比曲线或加性零均值尖刺。
- **实战定论（2026-07-31，山脉去圆润）**：频率加法（mountShape+1/70+1/35 等）A/B 全正（山体格数↑3.4%、峰顶 max|grad|↑2.6%、未压矮）；对比度重塑 A/B 全负。**频率加法=正确方向**。
