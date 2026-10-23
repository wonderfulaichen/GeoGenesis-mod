# M2 实施记录：接力起点容错（2026-10-22）

> 承接 [GeoGenesis-legacy-vs-model-crossregion-20261022.md](GeoGenesis-legacy-vs-model-crossregion-20261022.md)
> 的 M2 项：恢复 legacy `bestHandoffStart` 的"种子落局部极小 → 1 格窗口改选"能力。

## 1. 一句话结论

**代码已实现且判据全绿，但在当前默认样例上没有触发**（12 次接力全部原本就有下坡）。
**不能声称"跨区因此改善"**，它是为特定失败形态准备的容错，不是断流主因。

## 2. 实现

`DeterministicSkeleton.bestHandoffStart(start, bed, nx, nz, covered)`：

1. 种子格本身有严格可下降邻格 ⇒ **原样返回**（不乱挪）。
2. 否则在 1 格窗口（8 邻）内改选：候选必须自身有可下降邻格、下坡目标**不是坑底自己**、
   未被 `covered` 占用；先取**抬升最小**者（≤ `geogenesis.handoffMaxRise`，默认 4.0），
   抬升相同再按坡降率取更顺者。
3. 找不到 ⇒ 如实返回原格，由既有规则判死；**不伪造终点**。

接线：`build()` 的 incoming pass 内，仅对 `fromIncoming` 生效；改选后**重走**
`covered` / 湖 / 海 三道门，避免把起点挪进别人的格或水体。

开关：`-Dgeogenesis.handoffStart=false` 关闭（逐位回到旧行为，供 A/B）。
参数已登记 `EXPERIMENT_KEYS`；`CACHE_SCHEMA_VERSION` 103→104。

## 3. 过程中被自写判据抓到的两个错（都对应用户可见后果）

探针 `HandoffStartProbe` 的合成坑底用例先失败两次，两次都是**我的实现**错：

| 版本 | 错误 | 后果 |
|---|---|---|
| v1 | 要求候选 `bed` 必须低于种子 | 种子通常就是局部最低点 ⇒ 永远选不出 ⇒ 等于没修 |
| v2 | 只按坡降率择优 | 选到坑外远处陡坡（探针实测选到距坑 4 格、高 80 的崖边）⇒ 让河为找出路先翻山 |
| v3 | 未排除"下坡目标就是坑底自己" | 原地打转，白起一条链 |

⇒ 最终版本三条约束都在，`runHandoffStartProbe` 8 项 **ALL PASS**
（含"有下坡不许乱挪"、"平台救不出就返回原格"、"避让已占用"、"角落不越界"、
"同输入两次一致"、`bed=null` 不 NPE）。

## 4. 实测：本样例未触发

`owner(-1,0)`、seed 9139912035078620160：

```
[HYDRO-SKELETON] traces=55 ... incomingStarts=0 ...
```

全量日志（`-Dgeogenesis.lifecycleDiag=true`，29 个 origin）统计：

| 项 | 值 |
|---|---|
| 接力尝试 | 12 |
| 其中 `JOIN` | 5 |
| 其中 `MOUTH_LAKE` | 5 |
| 其中 `SELF_CROSS`（被回滚） | 2 |
| `mouth == source`（刚落地即死） | 2 |
| `incomingStarts` (owner -1,0) | **0** |

⇒ 12 次接力的种子**全部原本就有下坡**，`bestHandoffStart` 无机会介入。
这不是"修好了"，而是"该失败形态在本样例不存在"。

### 但同批数据暴露了更该修的问题

那 2 条 `mouth == source` 的接力链说明：**种子落地后立刻被自交守卫判死**，
与"无下坡"无关。这与 §〇r 的 JOIN 自汇（108 次）属同类——
`ownSegments` / `selfApproach` / `covered` 的自链判定过严或过松，仍需单独处理。

## 5. 全图 A/B（诚实记录）

同一 seed / 中心 / 1401²，仅切 `handoffStart`：

| 项 | off | on |
|---|---|---|
| PNG SHA256 | `3789EB9E…` | `AF32864E…` |
| 湖格 | 110182 | 110182 |
| 河道格 | 38322 | **38326** (+4) |
| 4 连通分量 | 191 | 192 |
| chunks/未覆盖 | 8281 / 0 | 8281 / 0 |

逐像素差异 **10 px**，集中在两处：
`(-413..-412,-220..-216)` 5 px 旱地→河；`(-290..-288,-99..-97)` 5 px 河流位置平移
（2 px 新增、3 px 移除）。湖面零变化。

⇒ 严格单变量、影响极小、可回退（开关关掉即逐位恢复）。**不构成形态改善。**

## 6. 复跑

```powershell
# 判据
.\gradlew.bat -I tools/multiphysics-contract.gradle runHandoffStartProbe --offline --console=plain
# A/B（关 vs 开）
java -Xmx12g -Dfile.encoding=UTF-8 "-Dgeogenesis.handoffStart=false" -cp $cp `
  com.geogenesis.worldgen.hydrology.model.ProductionViewProbe 9139912035078620160 -140 137
```

同批回归（本次实测）：`runHandoffStartProbe` ALL PASS、`runSeamBandBoundaryProbe` ALL PASS、
`runOutsidePathOrderProbe` PASS、`runMultiphysicsContractProbe` 2815+18 PASS、
main/diagnostics 编译成功。

## 7. 未实测 / 边界

- **未跑 Forge 实机、未跑完整 WorldgenGate、未提交/未推送。**
- 未在多 seed（除 12345 之外的更广样本）上验证 M2 是否触发；本样例 0 次不代表全局。
- M1（接力载荷带水面/准入）与 M3（邻区查询边带）**未动**。
- 湖/海生命周期闭合、JOIN 自汇仍未解决（见 HYDROLOGY.md 〇r）。
- 默认开关为 **on**；若后续要在"未触发但仍有极小影响"的前提下保守上线，
  可先置 off，等出现真实触发样本再评估。
