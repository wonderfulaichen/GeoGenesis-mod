# GeoGenesis 交接文档（2026-08-27 更新）

> 会话转移。**先 `git status` 看清未提交改动再动手**——工作区有大量未提交改动（核心：河流 RTF 范式整体重写 + 2026-08-27 整块漏雕修复），且 `AGENTS.md` / `ARCHITECTURE.md` / `README.md` 也已 modified。
>
> **主参考**：`AGENTS.md` 的「当前工作焦点（2026-08-26）」已记录河流 RTF 重写的完整来龙去脉与历史轮次，本文件只给交接速览与接手步骤。

## 〇、当前 git 状态（最重要）

- 工作区**未提交**改动极多：`forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/...` 下河流/地形/预览/配置大量文件，外加 `AGENTS.md` / `ARCHITECTURE.md` / `README.md` / `build.gradle`。
- **河流系统已整体重写为 RTF 范式（2026-08-26 第四轮），旧 D8 全套已删除**——别再找 `FlowField`/`FlowRiverBuilder`/`StreamTracer`/`LakeBuilder`/`ProfileSmoother` 等（都已删）。
- 提交时机由用户决定（未主动提交）。

## 一、河流系统现状（RTF 范式，2026-08-26 第四轮 + 2026-08-27 修复）

**为什么要换**：用户"河流完完全全有问题、破坏性切地形、小溪不按现实物理走" → 放弃 D8 全追踪，按 FreeTerraForged（RTF）**几何河网 + 河谷雕刻**范式重写。

**核心架构（确定性纯函数，跨 chunk/region 无缝）**：
- `RTFRiverGenerator`：几何河网。`root` 沿随机角度走 e 场到海岸（主河必到海）；`fork` depth≤2 树状分叉 + 源点高于汇合点门控（100% 下坡、树状汇流）。region(512wu) 确定性（region 坐标 + seed id）。
- `ValleyRiverCarver`：RTF Zone1-4 平滑谷雕刻，`finalHeight = min(carved, origHeight)` 只下挖（根治垂直崖/悬河）。`fadeRAct = fadeR*lakeMult + h.offset` 已补偿 warp 位移。
- `RiverNetwork`：采样门面。`sampleRiver(wx,wz)` 遍历全部缓存 plate（3×3 预热 + 全 plate 遍历，无 region 轴对齐接缝）；纯函数水面 `waterTable(cAt 9 点模糊)` → 汇口零落差、无 jitter。
- `RiverWarp`：种子化 simplex 蜿蜒（端点淡出，段衔接连续）。`River`/`Network`/`Rivermap`：几何线段 / 河网树 / region 容器。
- 游戏路径：`GeoGenesisTerrain.generateChunk` → `applyRiverValley` 把河谷**回写 `cell.height`**（预览/采样/落块一致）；`fillFromNoise` 只按水面灌水。

**坐标体系（极易踩坑）**：
- `toWu(block) = block / hs`（`GeoGenesisTerrain`）；`applyRiverValley` 用 `wx = (baseX+lx) * invHs` 也是 `block/hs` —— **两者一致，hs=2.0 时皆为 block/2**。
- 河流在 **wu 空间**生成（`REGION_SIZE=512`wu）。`horizontalScale`（hs）默认 2.0。

**★ 2026-08-27 整块漏雕（柱子）根治（本次会话最后完成）**：
- 现象：河上出现一整块原地形柱子、两侧有河。
- 根因：① `getChunkCells` 的河网预热在 `generateChunk`/`applyRiverValley` **之后** → 远离河源 chunk 的 `sampleRiver` 在 carver 入缓存前调用 → 长河 carver 位于 chunk region 的 ±2~3 未预热 → 全列 NONE；② `prunePlates` 原在 `plateForRegion` 内、每次 `sampleRiver` 调 2304 次，迭代 `plates.values()` 前就把刚预热的 plate 驱逐 → 同理漏刻。
- 修复：`getChunkCells` 预热**移到 `generateChunk` 前** + 半径提至 3（7×7 region）；`prunePlates` 从 `plateForRegion` 移除、改 `sampleRiver` 遍历结束统一驱逐；`PLATE_CACHE_MAX` 512→4096。
- 验证：用户 `runClient` 实测**无柱子**；诊断 `UNCARVED(real)`（收紧后 `riverCrossesChunk` 用点到河中心线真实距离 ~90wu，取代原 550+120wu 粗 bbox）全部 `centerInChannel=false` → **确证零真漏雕**。

**已知遗留**：
- 浅支流源头 1~2 块干槽（dryRatio≈3%，物理自然）。
- `Network.overlaps` / `RiverConfig.length` 等少量未用 API。
- **`runClient` 实机目检（河口喇叭 / 谷壁平滑 / 无悬水）待做** —— 这是当前最高优先的下一步。

## 二、其他已完成（侵蚀无缝化等，保留）

- 液滴行程 ≤ tile 缓冲余量（LIFE_C=20、spdCap=1.0）→ 侵蚀无缝。
- 世界坐标连续播种 + 平滑概率闸门 → 消除坡度卡阈值跳变。
- hs 参数化从 `TerrainParams` 传入（探针 HS=2 复现游戏）。
- XS 微侵蚀调优（圆化笔刷 + 平衡 + 开关）。
- 详见旧 HANDOFF 与 `AGENTS.md`。

## 三、关键坑 / 铁律（接手必读）

- **探针铁律**：改侵蚀/河流必须跑 `ErosionPeriodProbe`（数值）+ `ChunkBorderProbe`（hs=2 + toml 参数复现游戏）+ 河流相关探针。
- **`getErosionTile` 会 fire-and-forget 生成 8 邻居 tile** → 探针慢到数分钟；必须用 `getErosionTileResultForProbe`（单 tile 直取）。
- **toml 用户配置 ≠ 探针默认**：探针要解析 `run/config/geogenesis-common.toml`。
- 游戏崩溃 OOM = 系统内存耗尽（非 Java 异常），跑游戏关本地 AI。
- Windows 中文提交：`git commit -F file.txt`（UTF-8）。
- 河流诊断日志：`[RIVER] UNCARVED(real)` 只在 chunk 真挨着河却全未雕刻时报；`centerInChannel=false` 即河岸外侧正常 chunk（非漏雕）。

## 四、接手步骤（新会话）

1. `git status` + 读 `AGENTS.md`「当前工作焦点（2026-08-26）」确认上下文。
2. **优先**：`runClient` 实机目检河流外观（河口喇叭 / 谷壁平滑 / 无悬水 / 无漏雕柱）——这是 2026-08-27 修复后的首次系统性目检。
3. 若目检 OK：清理死代码（未用 API）+ 由用户决定提交时机。
4. 若目检有问题：定位后按 RTF 范式修（别退回 D8/几何线旧思路——已论证不适合本"预计算水面+灌水柱"架构）。

## 五、本会话未提交改动清单（参考）

- `GeoGenesisTerrain.java`：预热前移 + 诊断收紧（`riverCrossesChunk` 调用）。
- `RiverNetwork.java`：`prunePlates` 位置修正 + `riverCrossesChunk` 真实距离判断 + `PLATE_CACHE_MAX` 调大。
- `ValleyRiverCarver` / `RTFRiverGenerator` / `River` / `RiverWarp` / `Network` / `Rivermap` / `RiverCarver` / `RiverSample` / `RiverPlate`：RTF 重写。
- `AGENTS.md`：工作焦点记录 2026-08-26/27 + 修 REGION 笔误。
