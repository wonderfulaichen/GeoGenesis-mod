# GeoGenesis 长期记忆

## 用户方法论偏好
- 参考模组先读透再提方案；严格**先案后码**（复杂功能先出方案待确认再码）；模块化/单一职责
- 知识沉淀必须做；每完成一次任务/对话做记录；docs 新文档需更新 INDEX
- 关键决策点用提问确认（不打断则默认授权"你决定"）；微小细节可先用合理默认值并标注

## 地形引擎核心铁律（钉死）
- **e(x,z) 单一连续场**：大陆性 `c∈[0,1]` 单一连续噪声，海陆仅条件切分；海岸 = e 场自然穿过 0 的等值线（smoothstep），不绑定 c 硬阈值。
- **【c 绝不控制地形高度】**：c 只决定类型 + 海陆 mask；高度由类型各自独立噪声决定（自查 `if c>X then e=f(c)` 即违规）。
- **差异调制（v14）**：`shared + 0.4·(perTypeAvg-shared)` 零均值偏差保障 C0 连续。
- **类型过渡 = Voronoi 高斯距离权重**（CELL_SPACING=400，σ=200，SEARCH_RADIUS≥3 即 7×7）；`cAffinityStrength` 乘法偏置（默认 3.0）。
- **【窗口半径 ≥3】σ=200 时窗口半径必须 ≥3（7×7）**：3×3 进出 ring1 格点（跨界 600→权重 0.011）→ typeWeights 1 格突变 ~0.022 → argmax 翻转 → 1 格断裂线。**禁用边缘硬压制 ×1e-7**（σ=200 下格点滑出 ring1→ring2 权重骤降 1e7 倍反而造新跳变），正解 = 扩窗自然衰减。
- **条件系统**：温度/湿度/大陆性用 `ClimateSpline`(Cubic Hermite)；雪线双曲线锚定 seaLevel~maxY。
- **oceanDepthFactor**（乘 eOcean，默认 1.0 [0.5,3.0]）；`e→Y` 必须非对称（e=0→seaLevel=63）。

## 配置同步铁律
- 增删字段须同步：`GeoGenesisConfig`(定义+BUILDER) + `TerrainParams`(record+defaults) + `ParameterConfigPanel.addSpec` + toml。
- `resetToDefault()` 反射自动化；但 `defaultParams()`/`buildParams()` 仍需随字段同步。
- **滑块 reset 铁律**：`ParamSlider.setDefaultValue` 必须填 `cfg.getDefault()`，严禁填 `cfg.get()`。
- Forge 1.20.1：禁 reobf jar 进 `run/mods/`；biome 注册表用 `registryAccess()` 禁静态缓存；叶子 CODEC 禁 `.stable()`。

## 并发/队列铁律
- **computeIfAbsent 的 mapping 内严禁嵌套任何 CHM 的 computeIfAbsent**（跨 map 也死锁）；缓存写入统一 `get → 计算 → putIfAbsent`。
- **f.cancel(true) 只对"未开始"任务有效** → 取消须"batch.cancel() 标志 + 线程中断检查"双通道。
- **队列型架构必须设单次提交硬顶**（预览 TerrainQueue.MAX_CHUNKS_PER_SCAN=1024）；防抖只能限频不能限规模。
- **无上限 CHM 缓存必配容量兜底**（CellCache.MAX_ENTRIES=4096 + trimTo 按距中心淘汰）。

## 配置屏（单屏 8 标签，常驻预览）
页签 0世界参数/1气候/2地形/3显示/4采样/5色带/6缓存/7群系。按钮 [应用]SPEC.save()[保存]user_presets.json[重置]resetToDefault()。反射三件套：resetToDefault/captureAllValues/applyNamedValues。

## 侵蚀引擎（两级架构，已编译验证 2026-07-31）
- **Tile 架构**：spacing=4 粗采 + Catmull-Rom 双三次插值（1024 次 terrainE/tile）；每 tile 输出 3×3=9 chunk（ERODE_TILE_CHUNKS=3），边长 128，缓存 256 条目。
- `erosionEnabled` 默认 false（手动开，否则世界创建慢）；`riversEnabled` 默认 true。
- **粗级骨架 = 脊-谷条纹滤镜** `RidgeValleyErosion`（纯局部算子，无 tile 缝）：独立更密网格（RIDGE_SKELETON_SPACING=2）采样真实地形跑 `computeCoarseDelta` → bilinear 升采样。配置（GeoGenesisConfig 声明+defineInRange+toml，不在 TerrainParams）：erosionRidgeEnabled=true / Strength=0.08[0,0.5] / Scale=100[50,800] / CellScale=1.2[0.2,2.0] / Octaves=4[1,5] / GullyWeight=0.5[0,1] / landRef=0.15[0.02,0.5]。**配置同步三处一致：字段初始/BUILDER/fromConfig 回退**（漏回退曾致探针走旧默认）。
- **细级 = 液滴侵蚀** `ErosionEngine.runErosionOnFlat`：单轮 + 3-zone flat；默认强度 ~12%（erosionStrength=0.3, dropsMul=0.4, erodeMul=0.4）。
- **河流粒子状态机**：simulateDrop 双闸门 riverMix=smoothstep(lcLo,lcHi,lc)×smoothstep(disLo,disHi,dis)，按 riverMix 插值侵蚀/蒸发/速度。
- **tile 确定性铁律**：tile 只依赖自身世界坐标；提取期右/下缘 4 块渐变 blend（extractFromTile lx/lz≥12）是唯一跨 tile 平滑机制。已删邻居 postErosion 收敛链。
- **V 形河谷雕刻**：TF-style 距离场剖面（bedWidth=1.5/bankWidth=4，depth=0.02+(q/maxQ)×0.03）。**水面铁律：e 单位禁止加 Y 单位**（riverSurfaceY=heightFromE(cell.e+carve)+0.5）。
- **softCapLandE** 起点 0.97×maxLandHi（0.922）；峰尖自然分布不被压平。
- **否决方案**：① D8 流功率（tile 边界缝+剥皮）② 局部脊-谷锐化（不能造新脊线）。
- 诊断工具：`ErosionTileProbe`/`MountainFootprintProbe`/`MountainProfileProbe`/`SharpnessABProbe`。

## BiomeSource 性能
- 87s 世界创建慢根因是侵蚀 tile 管线；设 `erosionEnabled=false` 解决。BiomeSource 出生快速路径（terrain==null 返 plains）解首次初始化阻塞。

## 验证铁律（地形噪声/高度项修改必守）
- 改完只说 BUILD SUCCESSFUL 不够：用 A/B 探针实测 eLand 统计（固定区域、坐标稳定）确认方向正确。
- **[0,1] 高度项严禁直接平方**（除峰值外整体下移→变圆）；锐化用保端点对比曲线或加性零均值尖刺。
- **山脉去圆润实战定论**：频率加法（mountShape+1/70+1/35 等）A/B 全正（正确方向）；对比度重塑 A/B 全负。

## 待办
- [DONE] v14 修复；性能优化；侵蚀 tile 架构；两级侵蚀骨架整合；脊-谷骨架 A/B 验证（stdR/mgR≈1.7x，非剥皮）。
- [PENDING] `erosionEnabled=true` 后 runClient 验证；河流重接验证；V 形河谷无缝实测；cAffinityStrength 非零安全；BASIN 曲线；mixer 面板重绑新类型参数；toml→JSON 迁移（阶段3）；**地形参数存档级（per-save）方案待实施**。
