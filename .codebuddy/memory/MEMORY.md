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
- **【海岸线圆弧实锤·2026-08-06】**：e=0 穿越点 `cont=-eOcean/(eLand-eOcean)` 恒 <0.1（eOcean 穿越点仅 -0.005~-0.02）→ 海岸线=c 低频等值线（c 主波长 4000 块）=完美圆弧。勿凭过渡带宽度推断 cont。
- **【海陆类型化铁律·2026-08-06 v8.4】**：用户定案"**海陆=2 个大地形类型**"——OCEAN/DEEP_OCEAN 入 Voronoi 类型场（细胞概率 `pOcean=clamp(0.5-c/0.66,0,1)` 按 c 调制），海陆边界=类型权重竞争（穿越点 oceanW≈0.5~0.9、dom 翻转），与类型过渡同构。**c 门控混合（cont=smoothstep(oceanFadeStart,landRampEnd,cEdge)）整体废弃**。e=Σw·lo+Σw·(hi-lo)·modulated 统一公式，海洋 lo=hi=深度样条+seabed。cell.blendCont 语义=oceanW。内陆湖自然涌现（BASIN+海洋权重 e<0）。c 等值线补丁路线（warp 加强/浅海加深/过渡带移动）用户已否决，勿回退。
- **【衔接陡缓铁律·2026-08-06 v8.4.1】**：用户"自然≠平滑，类型间根据自身斜率衔接"——**海洋权重独立锐化 OCEAN_SHARP=5.0**（陆地 BLEND_SHARPEN=1.5 不动）：过渡带 ~90 块，e 落差由类型自身高度差决定（山地入海陡/水下山脉、平原入海缓、PLATEAU 台地边缘保留）。OCEAN_SHARP 是代码常量不在 TerrainParams.hashCode → **改动必须 bump CACHE_SCHEMA_VERSION（当前=5）**。SHELF 占比 ≠ 过渡带宽度（SHELF 大部分由 c 样条深度决定）。
- **条件系统**：温度/湿度/大陆性用 `ClimateSpline`(Cubic Hermite)；雪线双曲线锚定 seaLevel~maxY。
- **oceanDepthFactor**（乘 eOcean，默认 1.0 [0.5,3.0]）；`e→Y` 必须非对称（e=0→seaLevel=63）。
- **【侵蚀骨架无类型限制·2026-08-08】**：侵蚀骨架（RidgeValleyErosion）只在海岸带 landMask 有保护，无类型限制、无平顶保护、无高度基准约束（用户否决 flatMask）。
- **【"参考 X 配方"铁律·2026-08-08】**：必须逐行对照 X 完整链路，不能只对齐主频凭记忆写（v7 教训）。
- **【代码常量配方 bump CACHE_SCHEMA_VERSION·2026-08-08】**：改任何代码常量配方必须 bump CACHE_SCHEMA_VERSION（不限于 TerrainParams 参数）。
- **【队列型架构硬顶·2026-08-08】**：队列型架构必须设单次提交硬顶（TerrainQueue.MAX_CHUNKS_PER_SCAN=1024）。
- **【compute mapping 禁嵌套 CHM·2026-08-08】**：computeIfAbsent 的 mapping 内严禁嵌套任何 CHM 的 computeIfAbsent（跨 map 也死锁）。
- **【自然≠平滑·2026-08-08】**：类型间根据自身斜率衔接（海洋锐化 OCEAN_SHARP=5.0 的依据）。
- **【高原 v8 同构丘陵·2026-08-08】**：高原彻底同构丘陵（foldHills → perTypeAvg → modulated → blendLo/blendHi×modulated），移除所有类型特殊覆盖层。

## 配置同步铁律
- 增删字段须同步：`GeoGenesisConfig`(定义+BUILDER) + `TerrainParams`(record+defaults) + `ParameterConfigPanel.addSpec` + toml。
- `resetToDefault()` 反射自动化；但 `defaultParams()`/`buildParams()` 仍需随字段同步。
- **滑块 reset 铁律**：`ParamSlider.setDefaultValue` 必须填 `cfg.getDefault()`，严禁填 `cfg.get()`。
- Forge 1.20.1：禁 reobf jar 进 `run/mods/`；biome 注册表用 `registryAccess()` 禁静态缓存；叶子 CODEC 禁 `.stable()`。

## Git 提交铁律
- **Windows PowerShell 中文提交信息**：避免使用 `git commit -m "中文"`，改用 `-F` 参数从 UTF-8 文件读取（如 `git commit -F message.txt`）。PowerShell 终端编码可能导致中文乱码。

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

## 预览河流渲染修复（2026-08-08）
- **根因**：`RIVER_NETWORK` 图层读 `Cell.riverNetDist`（默认 1.0=远），但 `CellGenerator` 从未设置该字段。
- **修复**：`CellGenerator` 同步设置 `riverNetDist = riverDistance`；`GeoPalette` RIVER_NETWORK 图层改用 `riverDistance`；`CACHE_SCHEMA_VERSION` 12→13。
- **教训**：预览图层读取的 Cell 字段必须在数据生产端（CellGenerator）有对应赋值。

## BiomeSource 性能
- 87s 世界创建慢根因是侵蚀 tile 管线；设 `erosionEnabled=false` 解决。BiomeSource 出生快速路径（terrain==null 返 plains）解首次初始化阻塞。

## 验证铁律（地形噪声/高度项修改必守）
- 改完只说 BUILD SUCCESSFUL 不够：用 A/B 探针实测 eLand 统计（固定区域、坐标稳定）确认方向正确。
- **[0,1] 高度项严禁直接平方**（除峰值外整体下移→变圆）；锐化用保端点对比曲线或加性零均值尖刺。
- **山脉去圆润实战定论**：频率加法（mountShape+1/70+1/35 等）A/B 全正（正确方向）；对比度重塑 A/B 全负。

## 待办
- [DONE] v14 修复；性能优化；侵蚀 tile 架构；两级侵蚀骨架整合；脊-谷骨架 A/B 验证（stdR/mgR≈1.7x，非剥皮）。
- [PENDING] `erosionEnabled=true` 后 runClient 验证；河流重接验证；V 形河谷无缝实测；cAffinityStrength 非零安全；BASIN 曲线；mixer 面板重绑新类型参数；toml→JSON 迁移（阶段3）；**地形参数存档级（per-save）方案待实施**。
- [NEW] 预览折痕 smoothClamp 修复目检确认（用户尚未确认）。
- [NEW] 交接总结待办7项（折痕目检、侵蚀验证、BASIN曲线、mixer重绑、toml迁移、存档方案等）。
