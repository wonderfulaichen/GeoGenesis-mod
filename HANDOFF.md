# GeoGenesis 交接文档（2026-08-13 深夜）

> 会话转移。上一交接点：git commit `cb27135`（main，已 push 同步）。本会话在 `cb27135` 之后做了大量**未提交**改动（侵蚀无缝化 + 河流重写），工作区有改动未提交，**先看清 git status 再动手**。

## 〇、当前 git 状态（最重要）

- HEAD = `cb27135`（XS 微侵蚀调优，已推送）
- 工作区**未提交**改动：`ErosionEngine.java` / `CellGenerator.java` / `StreamTracer.java` / `GeoGenesisGenerator.java` / `build.gradle` / `RiverProfileProbe.java` / `DischargeFieldProbe.java`（新增）/ `GeoGenesisForgeEvents.java` / `InGameSeamScanner.java`（新增）等
- **河流重写未定案**，改动是否保留待用户实机确认后决定

## 一、本会话完成并已验证的（侵蚀无缝化，推荐保留）

1. **液滴行程 ≤ tile 缓冲余量**（治侵蚀断裂根本）：`LIFE_C 60→20`、`spdCap 1.5→1.0`——行程 20wu ≤ 缓冲 40wu → 液滴不出界 → 双写一致 → 无缝
2. **世界坐标连续播种**（ErosionEngine 播种重写）：1wu 出生单元网格 + hash 密度 + 单元内连续偏移 → 任何 tile 看到同一区域 = 同一出生集合
3. **平滑概率闸门**：SLOPE 硬阈值 → smoothstep(0.5h,1.5h) 概率出生（hash 世界坐标）→ 消除坡度卡阈值处的 0/1 跳变
4. **hs 参数化**：引擎不再读 `GeoGenesisConfig.INSTANCE.horizontalScale`（探针进程无 Forge 环境恒 1.0 与游戏 2.0 不符，导致探针永远复现不了游戏），改从 `TerrainParams` 传入
5. **探针工具**：`ChunkBorderProbe`（toml 参数解析 + HS=2 + GeoGenesisTerrain 门面调用链，复现游戏环境）、`InGameSeamScanner`（B 键游戏内扫描，双剖面取证）、`ErosionTileProbe` 修 48wu 网格坐标
6. **XS 微侵蚀调优**：圆化笔刷（1-d²/2 权重）+ 侵蚀/沉积平衡 + `erosionXSEnabled` 开关（默认关，ParameterConfigPanel 有 toggle）

**验证**：ErosionPeriodProbe NaN=0、max=0.0000 零堆积、覆盖率 100%；ChunkBorderProbe 用户实测断裂处 z=-392..-385 从 3.7 → 0.1-0.3 块。

## 二、河流重写（本会话主战场，未定案）

### 历史（别重蹈覆辙）

- 用户反馈链：StreamTracer 几何追踪"太假" → 液滴 discharge 阈值切河道"自然但断流" → 连续播种+脊线检测"太卡" → 降密度
- **根因链**：侵蚀无缝需要液滴短行程（LIFE_C=20）→ 但流量累积需要长行程（到入海）→ 断流。用户点醒：粒子侵蚀无缝 ≠ 河流系统无缝；流量累积不该复用侵蚀液滴的短行程

### 当前实现（未定案）

- `StreamTracer` 源头改**连续播种**（FLOW_GRID=8、FLOW_DENSITY=0.4、FLOW_MARGIN=48）——液滴走到入海（出界用 `terrainEQuick` 世界坐标），dis = 集水面积连续不断流
- core 标记改**脊线检测**（dis 是面状场，阈值切不出线状河道）：Sobel 梯度（连续方向非 D8）→ 垂直流向采样两侧 dis → 中心高 = 脊。`ridgeThreshold = maxQ×0.08`
- 水面棘轮（`computeRiverSurfaces`）保留：discharge 降序 + 河口锚海平面 + 落差标瀑布 + BFS 岸坡传播
- 宽度/深度随流量渐变保留（bedWidth=1+3·qFrac²、bankWidth=3+5·qFrac、depth=0.015+0.05·qFrac^0.7）
- `GeoGenesisGenerator.fillFromNoise` 已恢复 `riverMask → fillRiverColumn` 填水

### 性能（用户"非常卡"已缓解）

- FLOW_GRID 4→8、MARGIN 96→48、密度 0.5→0.4 → 单 tile 河流追踪 ~0.5s
- **再卡的话优先降 FLOW_DENSITY**（0.4→0.3）

### 参考（用户点名）

- `参考/sources/SimpleHydrology`：**地形与河流一体**——液滴同时侵蚀 + 累积流量（maxAge=500、单 map 全局）。我们拆成"侵蚀液滴 + 流量液滴"两套是重复计算，**一体化大重构是可能的正确方向**（大工程，先确认用户意愿）
- `参考/Farseek-Mods`（Streams 1.0）：**单独河流**——chunk 级图搜索（Basin 网格），非长液滴。水面棘轮/谷肩/瀑布 V 形脊参考自 Streams 旧版（`参考/Streams`）

### 下一步（新会话）

1. **runClient 目检**：加载速度 + 河网形态（线状/支流/曲流/入海/边缘断裂）
2. 若形态 OK：清理死代码（CONFLUENCE_THRESHOLD/isLocalHigh/SOURCE_GRID 旧常量）+ 提交
3. 若形态差：评估 SimpleHydrology 式"一体液滴"大重构（侵蚀+流量合并）

## 三、其他记住的

- **探针铁律**：改侵蚀/河流必须跑 ErosionPeriodProbe（数值）+ ChunkBorderProbe（hs=2 + toml 参数，复现游戏）+ RiverProfileProbe（水面单调）
- **`getErosionTile` 会 fire-and-forget 生成 8 邻居 tile** → 探针慢到数分钟；必须用 `getErosionTileResultForProbe`（单 tile 直取）
- **toml 用户配置 ≠ 探针默认**：用户配置屏调过 `erosionRidgeStrength=0.75` 等，探针要解析 `run/config/geogenesis-common.toml`
- 游戏崩溃 OOM = 系统内存耗尽（hs_err 无 Java 异常），跑游戏关本地 AI
- Windows 中文提交：`git commit -F file.txt`（UTF-8）
