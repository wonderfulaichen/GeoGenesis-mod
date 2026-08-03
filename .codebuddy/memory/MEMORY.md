# GeoGenesis 长期记忆

## 用户方法论偏好
- 参考模组先读透再提方案；严格**先案后码**（复杂功能先出方案待确认再码）；模块化/单一职责
- 知识沉淀必须做；每完成一次任务/对话做记录；docs 新文档需更新 INDEX
- 关键决策点用提问确认（不打断则默认授权"你决定"）；微小细节可先用合理默认值并标注

## 地形引擎核心原则（钉死）
- **e(x,z) 单一连续场**：大陆性 `c∈[0,1]` 单一连续噪声，海陆仅条件切分；海岸 = e 场自然穿过 0 的等值线（smoothstep 过渡），不绑定 c 硬阈值锚点。
- **【铁律·c 绝不控制地形高度】**：c 只决定类型 + 海陆 mask。地形高度由类型各自独立噪声决定。自查：若 `if c>X then e=f(c)` 或 `e+=g(c)` 即违规。
- **差异调制（v14，连续无断裂）**：`shared + 0.4·(perTypeAvg-shared)` 零均值偏差保障 C0 连续。
- **类型过渡 = Voronoi 高斯距离权重**（CELL_SPACING=400，σ=200，SEARCH_RADIUS=3/7×7 窗口）；`cAffinityStrength` 乘法偏置（默认 3.0）。
- **【铁律·窗口半径 ≥3】σ=200 时窗口半径必须 ≥3（7×7）**：3×3 窗口进出的是 ring 1 格点（跨边界距离 600 → 权重 0.011 不可忽略）→ cell 边界 typeWeights 1 格突变 ~0.022 → argmax 翻转 → 1 格断裂线（2026-08-03 实锤）。**禁用边缘硬压制 ×1e-7**（仅小 σ 适用，σ=200 下同一格点滑出 ring 1→ring 2 权重骤降 1e7 倍反而制造新跳变），正解 = 扩窗让权重自然衰减。
- **条件系统**：温度/湿度/大陆性用 `ClimateSpline`(Cubic Hermite)；雪线双曲线锚定 seaLevel~maxY。
- **oceanDepthFactor**（乘 eOcean，默认 1.0 [0.5,3.0]）；`e→Y` 必须非对称（e=0→seaLevel=63）。

## 配置同步铁律
- 增删字段须同步：`GeoGenesisConfig`(定义+BUILDER) + `TerrainParams`(record+defaults) + `ParameterConfigPanel.addSpec` + toml。
- `resetToDefault()` 反射自动化（遍历 ConfigValue 字段 set 默认）；但 `defaultParams()`/`buildParams()` 仍需随字段同步。
- **滑块 reset 铁律**：`ParamSlider.setDefaultValue` 必须填 `cfg.getDefault()`，**严禁填 `cfg.get()`**。
- Forge 1.20.1：禁 reobf jar 进 `run/mods/`；biome 注册表用 `registryAccess()` 禁静态缓存；叶子 CODEC 禁 `.stable()`。

## 并发/队列铁律（2026-08-03 预览 OOM 实锤）
- **computeIfAbsent 的 mapping 内严禁嵌套任何 CHM 的 computeIfAbsent**（跨 map 也会死锁）；缓存写入统一 `get → 计算 → putIfAbsent`。
- **f.cancel(true) 只对"未开始"任务有效**：正在运行的 Runnable 若不检查 `Thread.interrupted()` 会跑完整个任务 → 取消必须"batch.cancel() 标志 + 线程中断检查"双通道（TerrainPool 保存 batch 引用，cancelAll 先 b.cancel() 再 f.cancel(true)）。
- **队列型架构必须设单次提交硬顶**（预览 TerrainQueue.MAX_CHUNKS_PER_SCAN=1024：全视口扫描收集 + 洗牌后只提交前 N，其余下轮再扫）；防抖只能限频不能限规模。
- **无上限 ConcurrentHashMap 缓存必配容量兜底**（CellCache.MAX_ENTRIES=4096 + trimTo 按距中心距离淘汰）。

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
- **河流粒子状态机（2026-08-01）**：simulateDrop 内双闸门 `riverMix = smoothstep(lcLo,lcHi,lc) × smoothstep(disLo,disHi,dis[idx])`——lc=自身上升记忆（累计落差，资格）+ ld=路径放电量（确认）。按 riverMix 插值：侵蚀×(1+2S)、effD×(1−0.8S)、蒸发×(1−0.9S)、spd 上限×(1+0.5S 滞后一步)。`c_eq ×= (1+α·clamp(lc/0.5,0,1))` 兑现 erosionLocalChargeWeight（原失效参数）。配置：erosionRiverLcLo 0.05/LcHi 0.15/DisLo 3.0/DisHi 12.0/Strength 0.6（同步铁律三处：字段+BUILDER+toml，不进面板）。参数经 record RiverParams 传入。
- **侵蚀 tile 确定性铁律（2026-08-01）**：tile 生成只允许依赖自身世界坐标（flat 全源 terrainE+粗骨架双线性采样）。三区制 flat（邻居 postErosion）+ 滑窗收敛循环是脆弱设计——缓存淘汰后邻居丢失 → 重建结果不同 → chunk 边界断裂 + round 爆炸（1700+）。已删除：readFlatBorder/readNeighborHeight/ensureErosionTile/MAX_ROUNDS 收敛/coarseDeltaUp/bilinearUpsample。提取期右/下缘 4 块渐变 blend（extractFromTile lx/lz≥12）是唯一跨 tile 平滑机制。探针 Stage 7 世界缝真度量：X 3.1 块/Z 2.2 块 PASS。
- **骨架 landRef 配置化（2026-08-01）**：fadeTarget=(h-landRef)/0.25，`erosionRidgeLandRef` 默认 0.15 [0.02,0.5]（三处同步）。旧固定 0.25 在低地世界（eLand 中值 0.10~0.18）恒负 → 平坦区（combiMask≈0 时 fGx≈fadeTarget）全体下削 4~11 块（DIAG-EXT delta 全负）。AB 验证（seed 12345）：delta mean +0.0089→+0.0157、stdR/mgR 保持 1.7x/1.66x。**landRef 应匹配当前世界陆地中值，世界偏矮调小、偏高调大**。
- **河流系统关键修复（2026-08-01）**：① computeRivers 移到液滴侵蚀**之后**（tile 已是侵蚀后场，D8 标记与液滴刻蚀槽同源；确定性保证跨 tile 一致）；② riverMask 去掉高度限制 + `riverSurfaceY = max(seaLevel, cell.height+0.5)`（高山河道也灌水，低地连海）；③ riverDischargeThreshold 接线 `thr = max(8, maxQ×cfg)` 默认 0.02（调大=河网稀疏）。Cell 旧字段（isLake/lakeMask/riverIsWaterfall/riverSourceType/riverNet*）**非死字段**：预览三件套仍读取（无生产者），保留。
- **V 形河谷雕刻（2026-08-01 最终版 = TF-style 距离场剖面）**：移植 TerraForged RiverCarver 河道级公式——`nh = bedLevel + (base-bedLevel)×riverAlpha`，riverAlpha=(d-bedWidth)/(bankWidth-bedWidth)，bedWidth=1.5（河床平底水面 3 宽）/bankWidth=4（V 形斜坡），depth=0.02+(q/maxQ)×0.03（注意 maxQ 是全 tile 含超区最大流量 → q/maxQ 小 → 主河支流同深 ≈5-6 块）。**距离场**=BFS 从 riverCore（原始 mask 未膨胀）传播（队列容量 8×N×N，曾越界）。**无缝性铁律**：提取区（距 tile 边界 40 块）内 BFS 距离要么完整（中心线在 tile 内）要么一致缺失（中心线在 tile 外→距离≥40>valleyWidth→两侧都不雕）→ 提取区天然一致；双写区差异（探针 Stage 3/4）是固有指标不提取。fillRiverColumn 2026-08-01 重写：**仅真河床（水深>1）走河流填充**（基岩/石/SAND 床/水/水上全 AIR，峡谷壁由邻格普通填充）；无雕刻边缘格（水深≤1）委托 fillTerrainColumn（防 1 格水三明治）；**水下河道格（isWater）分流走 fillTerrainColumn**（海底正常水柱，防海面被沙/草替换）。**水面铁律：e 单位禁止直接加 Y 单位**——riverSurfaceY = heightFromE(cell.e + carve) + 0.5（e 域恢复原地面；曾因 e+0.021 直接加 Y 导致水深恒 1 格三明治，探针 avgDepth=1.02→5.82 验证）。D8 雕刻被删的历史教训前提（tile 边界流方向不一致）已被确定性修复消除。
- **softCapLandE 压缩窗口（2026-08-01）**：起点 0.92→0.97×maxLandHi（0.874→0.922）。原起点太贴上限：山峰自然分布 p99=0.8745 落窗口内 → 峰尖压平（"山脉碰顶"观感）。验证：压缩区 2.0%→0.4%，MOUNTAINS p99=0.897 自然尖峰，max 0.928 兜底。待办：MOUNTAINS 相邻格 38 块落差（骨架脊线垂直墙）另案。
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
