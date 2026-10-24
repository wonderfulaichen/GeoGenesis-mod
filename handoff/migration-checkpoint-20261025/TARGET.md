# 迁移检查点：目标效果（2026-10-25）

> **用途**：这份目录是「用户已确认的目标效果」的完整快照。
> 候选源码原本放在 `forge-1.20.1-47.4.10-mdk/build/` 下，而 `build` 被 `.gitignore` 忽略
> ⇒ 普通 git 提交**保护不了它们**。故把真正关键的三份源码复制到这里纳入版本控制。
>
> **目标**：让游戏（`src/main`）跑出与 `prod-1792862777733` **逐像素一致**的效果。

---

## 目标图的唯一指纹（验收标准）

| 项 | 值 |
|---|---|
| 产物目录 | `forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792862777733/` |
| **production.png SHA256** | `74E9DC001C650B91620C435726AC4A0154801DA8014A16A0FAF8F7A5B7B456E3` |
| **columnsSHA256**（列级判据，更严格） | `7f21a5cfa3e0a1055ddc807f64f245145b04e1623ddb353d9703f2ad7f61879e` |
| 图像 | 1401×1401，1 像素 = 1 方块，step=1 |
| seed | `9139912035078620160` |
| 中心 | `(-140, 137)` |
| 半径 | 700（正典值，不可改） |
| TOML SHA256 | `8dc968236445a74a75357d84122b61e1f1a54c07500b829276f9b48f42c223e3` |
| 配置指纹 | `-8622715032661991989` |
| chunk 数 | 8281，uncovered=0 |
| 像素统计 | dry=1367688 / sea=452661 / lake=105182 / river=37270 |

**验收口径**：迁移后用同一 seed/中心/半径/TOML 重新出图，
`production.png` 的 SHA256 与 `columnsSHA256` 必须与上表**完全相同**。
任一不同即视为迁移改变了产出，不得声称"等价"。

---

## 这张图实际加载的类（来自该目录 provenance.txt）

| 类 | 来源 | 说明 |
|---|---|---|
| `ModelHydrologyProvider` | `pruning/outlet-prefix-nearest-1792842919611/classes` | 隔离候选（含湖岸前缀 attach） |
| `DeterministicSkeleton` | `pruning/lake-shore-1792860057138/classes` | **本目录 DeterministicSkeleton.java** |
| `GeoGenesisTerrain` | `pruning/lake-shore-1792860057138/classes` | **本目录 GeoGenesisTerrain.java** |
| `CellGenerator` | `build/classes/java/main` | = `src/main`，未改 |
| `ProductionViewProbe` | `build/boundary-repair-v2/integrated-1792736719405609000/classes` | 出图工具（诊断，非生产） |

⇒ **要复现这张图，光有本目录两份源码不够**，还依赖 `outlet-prefix-nearest-1792842919611`
里的 `ModelHydrologyProvider`（以及它下面串联的 `outlet-candidate-1792821818176` /
`halo-candidate-1792785734981` 等下游候选）。迁移时必须一并纳入。

---

## 本目录文件

| 文件 | 作用 | 相对 `src/main` 的差异 |
|---|---|---|
| `DeterministicSkeleton.java` | 确定性骨架（含补丁 ②溢口短段下限、③末端延伸限制） | 比 `src/main` **多 1170 行**，含此前多轮实验（出口修复、halo 剪枝等） |
| `GeoGenesisTerrain.java` | 地形回写（含补丁 ①湖岸出水判据） | 相对 `src/main` 改动：湖域判据 `height < spill - 0.5` → `height < spill - 1e-9`，水块能否放下交给既有「水柱保护」分支 |
| `ShoreCriterionProbe.java` | 补丁①的判据探针（240274 项 + 2 个反控） | 新增文件，不属于生产 |

---

## 已实测的来源校验（勿重复踩坑）

- 三张对照图的真实加载来源均已用 `provenance.txt` 核实，**不是**靠记忆或推断。
- 基线图 `prod-1792855942489` 的 `GeoGenesisTerrain` 来自 `build/classes/java/main`（= `src/main`）。
- 首版湖岸补丁（直接 `floor(spill) > floor(height)`）**已被否证**：绕过水柱保护
  ⇒ −2508 格湖→干、孤立河 17→22。当前 `GeoGenesisTerrain.java` 是修正后的第二版。
- `SkeletonProbe` 基线类 vs 补丁类逐条断言行 diff = 0（22 PASS / 1 FAIL 两臂相同）；
  该 22 PASS/1 FAIL 是 HEAD **既有**红灯，非本轮引入。

---

## 迁移前必须补的验证（尚未完成）

1. 负坐标 / 下游先查 / 缓存淘汰 / 并发取消 的确定性矩阵
2. `runWorldgenGate` 完整门禁
3. **Forge 实机验收**（本轮所有数字均来自离线 `getChunkCells`，未跑过 MC）
4. 升 `PreviewDisplay.CACHE_SCHEMA_VERSION`（否则预览静默复用旧缓存，看起来像没生效）

---

## 仍未解决的缺陷（如实留痕，不要当成已修）

剩余 6 个孤立河分量（`verify4.json`）：3 个 `end=JOIN` 但整簇离水 154~185 格、
3 个 `end=MOUTH_LAKE` 但离湖 19~40 格。与已修三类**不是同一成因**。
