# 跨 region 旧诊断稿（2026-10-25，关键归因已撤回）

> **状态：历史证据，不是当前实施方案。** 2026-10-26旧机制研究纠正了本文的多项推断：半宽不求和；round排序签名不证明逐点有序重复；degree==2变色不证明断裂；无向触水不证明有向全周期；HALO分布不证明根因。A1/A2/A3未实施、不要直接按本文删几何或重写发布规则。详见[旧机制研究§5](research/legacy-river-mechanism-20261026.md#5-必须撤回的上一轮判断)及[最新交接](GeoGenesis-handoff-20261026.md)。
>
> “src/main未改、与04bdc73一致”仅是下方旧稿自述，**不代表当前状态**。当前基线9de0e15，另有未完成River检查点；runWorldgenGate与SkeletonProbe也不是同一个任务。下方数字保留便于追溯，本次没有重跑这些测量。

## 一、用户可见症状

1. 「明明是一条河流结果被分好几段」（本轮核心）
2. 「宽度变成一节节的」/ 宽块（12 px = `maxWidth`）
3. 「河流汇入的bug」（同一几何被当成多条河汇进自己）

## 二、证据

数据：36 个 region（seed `9139912035078620160`、中心 `(-140,137)`、`REGION=512`、`STEP=2`、
`HALO` 默认、`-Dgeogenesis.boundaryPorts=true`），一次真实生产求解，导出到

```
forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/lake-edge-1792872387157/dump-out/
```

（`polys.csv` 亚格中心线、`traces.csv` 终点语义、`trace-river-map.csv` 归属、`water.csv` 水体）

### 2.1 几何完全重复

以「折线全部点坐标（round 到 0.1）排序后的元组」为几何签名：

| 项 | 值 |
|---|---|
| 几何完全重复的组数 | **239** |
| 涉及 trace 数 | **503 / 1720（29.2%）** |
| 涉及节点数 | **51741 / 227896（22.7%）** |
| 最大重复份数 | **4**（同一条河在 4 个 region 各解一遍） |

典型 4 份重复：

```
trace=41  river=25   region=-3_-3  end=MOUTH_LAKE  nodes=43
trace=186 river=135  region=-3_-2  end=MOUTH_LAKE  nodes=43
trace=483 river=365  region=-2_-3  end=MOUTH_LAKE  nodes=43
trace=594 river=443  region=-2_-2  end=MOUTH_LAKE  nodes=43
```

四份几何逐点相同，但 river id 分别是 25/135/365/443 ⇒ 被当成四条河。

### 2.2 重复发生在哪儿

所有重复组的 region 组合都是**相邻 region**（`(-2,-2)`+`(-2,-3)`、`(-1,-2)`+`(0,-2)`…），
且几何包围盒都**止于 region 的 HALO 边界**（如 `z=608 = originZ + HALO`）。
⇒ 这是 **HALO 区被多个 region 各自求解并各自发布**，不是接缝续流缺失。

### 2.3 对落块层的影响

`ModelHydrologyProvider.columnsFor` 的覆盖判据（约 4073 行）是：

```java
boolean covered = dist <= segHalf || segmentMeetsBlock(x0, z0, x1, z1, blockX, blockZ);
```

同一位置有 2~4 条**重合段**时，`half` 取「距离最近者」，但 `covered` 取**并集**
⇒ 视觉宽度被叠加加粗（§〇az 的 OverlayProbe 实测：`(513,79)` 覆盖 3 段、合计 half 2.991；
`(515,87)` 覆盖 4 段、合计 4.101，而每段半宽只有 ~1.0）。

### 2.4 为什么 `buildRivers` 只减少 22%（而不是根治）

`buildRivers` 的 `cellOwner` 只在**单个 region 的 trace 列表内**建立
⇒ 跨 region 的重复身份它看不见。实测：

| 口径 | `degree==2` 像素 | 两侧异 id | 占比 |
|---|---|---|---|
| trace | 94282 | 348 | 0.37% |
| River（`buildRivers`） | 94282 | 270 | 0.29% |

（度量方式：只在 `degree==2` 的骨架像素上判两侧 id 是否相同 —— 汇流点变色合法，不计入。）

## 三、已被否证的假设（不要重试）

| 假设 | 否证方式 | 结果 |
|---|---|---|
| 宽度律算错 | `TraceProbe` 逐段半宽 | 0.961→1.123 平滑，每段差 **0.002**，零突变 |
| JOIN 串不上（`next[]` 找不到下游） | 导出 `join-downstream.csv` | `DANGLING_JOIN = 0`，865 条 JOIN **全部**有有效下游 |
| 河不入湖海 | 36 region 并集几何判据（骨架 ∩ 水体掩码，8 连通） | **448 / 452 分量触水**；4 个不触水的全在并集最外圈 |
| 折线归属口径是主因 | `degree==2` 度量 | 只占 0.37%，量级与症状不匹配 |

## 四、可选方案（待用户拍板，未实施）

> 用户已选「A = 骨架内部重建，不做事后过滤器」。以下都属 A 的范畴，
> 区别在于**去重放在哪一步**与**保留谁的 id**。

### 方案 A1：骨架输出层几何去重（推荐）

在 `DeterministicSkeleton.build()` 产出 `traces`/`rivers` **之后、`shape()` 之前**，
按几何签名合并重复 trace：保留「拥有该几何面积的 region」那一份（用格坐标落在哪个
region 的 **core**（非 HALO）判定），其余丢弃。

- 改动面：`DeterministicSkeleton` 一处 + 可能的 `Result` 载荷字段
- 优点：一次解决身份重复；宽度/水面/断面自然只算一遍；`buildRivers` 自动受益
- 风险：**丢掉的那份可能是唯一带 `MOUTH_LAKE`/`BOUNDARY_EXIT` 语义的**
  （实测重复组内 end 语义**相同**，所以这个风险在样本上为 0，但需探针守门）
- 需要：`PreviewDisplay.CACHE_SCHEMA_VERSION` 升级

### 方案 A2：`columnsFor` 消费时去重（更保守）

不动骨架产出，只在 `SegmentIndex` 构建时，对**几何重合的段**只保留一份（按 core 优先）。

- 优点：不动 trace/river 身份，改动面最小，产出变化可控
- 缺点：`River` 身份仍是重复的（`buildRivers` 的 22% 上限不变），
  「一条河被分成几段」在**身份层**没修好，只是**渲染层**不再叠粗

### 方案 A3：从根本上不让 HALO 区发布（最彻底，改动最大）

`columnsFor` 的邻区借用逻辑（约 2198-2235 行）与 HALO 发布范围重新划分：
每个 region **只发布自己的 core**，HALO 只用于查询、不用于发布。

- 优点：从机制上消除重复，不需要"事后去重"
- 风险：会改变跨区接缝行为（§〇q 刚修好的缝合可能受影响），需要重跑接缝探针
- 这与 `-Dgeogenesis.boundaryPorts` 的接线有耦合，需一并评估

## 五、验收判据（无论选哪个方案）

1. **几何去重率**：239 组 → 0；涉及 trace 503 → 0
2. **`degree==2` 两侧异 id**：348 → 目标 **≤ 50**（当前 270）
3. **宽度不再叠加**：`OverlayProbe` 的「同一像素覆盖段数 ≥3」从当前有值 → 0（或显著下降）
4. **不回归**：
   - `runSkeletonProbe` / `ShoreCriterionProbe` 与基线逐条断言行 diff = 0
   - 36 region 并集**触水率不下降**（当前 448/452）
   - 1401² 生产图 湖/河/海 像素数需记录并与 `prod-1792862777733`（湖 105182 / 河 37270 / 海 452661）对比说明
5. **门禁**：`runWorldgenGate`（当前 HEAD 既有 22 PASS / 1 FAIL，不得新增红）

## 六、未做的事

- 未改任何生产源码（`src/main` 与提交 `04bdc73` 一致，本轮只在 `build/` 与 `HYDROLOGY.md` 留证据）
- 未跑 `runWorldgenGate`、未跑 Forge 实机、未出 1401² 生产图
- 未验证方案 A1/A2/A3 中的任何一个
