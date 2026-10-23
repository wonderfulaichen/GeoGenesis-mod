# 修复 A（自汇不当汇流）— 实施与回退记录（2026-10-22）

## 1. 结论

**实现完成、判据全绿、反向验证有牙齿，但单变量 A/B 显示效果变差 ⇒ 默认关闭。**

不能删掉代码：它是唯一能证明"自汇确实是缺陷"的可执行证据，也是后续修 B 之后的复评入口。

## 2. 实现

三处 `End.JOIN` 产生点全部加守卫（仅当目标格属于本链时才介入）：

| 位置 | 原行为 | 修复后（开关开） |
|---|---|---|
| `covered[p]`（沿 parent 走到已占格） | 记 `JOIN` | 若 `selfStamp[p]==stamp` ⇒ 记 `DEAD_END`，**不 `path.add(p)`** |
| `nearbyJoin()` 命中 | 记 `JOIN` | 同上（该函数本已排除自己，此处是防退化二道闩） |
| `outletEscape()` 命中已占格 | 记 `JOIN` | 同上 |

开关：`-Dgeogenesis.selfJoinFix=true`（**默认关**）。已登记 `EXPERIMENT_KEYS`。

## 3. 判据（`SelfJoinFixProbe`）

| 场景 | 断言 | 结果 |
|---|---|---|
| 支流并入干流（负控） | 仍记 `JOIN`，不许误杀合法汇流 | PASS |
| 链 `d→b` 自环 | 不得出现 `JOIN` | PASS（开关开时） |
| 默认值 | 默认关闭 | PASS |

**反向验证**：把开关显式设为 `false` 后跑同一探针 ⇒ `[FAIL] 绕回自己…` + `[FAIL] 默认…`，
进程 exit=1。⇒ 判据确实能捕获旧行为，不是假通过。

## 4. 单变量 A/B（1401²，同 seed/中心/配置）

| 项 | 关闭（现状） | 开启（修复） |
|---|---|---|
| PNG SHA256 | `AF32864E…` | `B0B0…`（不同） |
| 湖格 | 110182 | 110182 |
| 河道格 | 38326 | **39959（+1633）** |
| 4 连通分量 | 192 | 194 |
| **水平长直 ≥30 块** | **26 条** | **32 条（+6）** |
| 垂直长直 ≥30 块 | 83 条 | 86 条 |
| 最长水平长直 | 403 块 @ `z=608` | **403 块 @ `z=608`（未变）** |

开启后**新增**的水平长直：

| 长度 | 位置 |
|---|---|
| 141 块 | `z=96` |
| 93 块 | `z=-416` |
| 75 块 | `z=96` |
| 60 块 | `z=-88` |

而用户圈出的 `z=608` 那两条（403 块、225 块）**一格未变**。

## 5. 为什么变差（机理）

自汇原本会**提前截短**一条链：链绕回自己时立即终止，不再继续抄近路穿过别的河段。
改成 `DEAD_END` 后，该链沿后续路径继续走，在**别处**与横向河段相汇
⇒ 把"一处自环"转化成"多处横向串接"。

⇒ 自汇在本实现里起到了**意外的终止保护**作用。它不是无害的记账错误，
而是与 `nearbyJoin` 的相对距离判据耦合在一起的稳定机制。**单点删除会把问题转移到别处。**

## 6. 与 `z=608` 水平长直的关系（重要）

修复 A **完全没有**影响 `z=608`。该处属于**缺陷 B**：边框上的 `NEARBY` 假汇流
（南框 67 条 JOIN 中 44 条 `NEARBY`、21 条 `SELF`）。

⇒ **必须先修 B（跨区契约），再回来评估 A**。顺序反了会白做一轮。

## 7. 当前状态

- 默认产出**逐位不变**：关闭态 PNG SHA256 与修复前基准完全一致（`AF32864E…`）。
- `CACHE_SCHEMA_VERSION` 105（条目已注明"产出未变"）。
- 回归：`SelfJoinFixProbe` / `HandoffStartProbe` / `SeamBandBoundaryProbe` /
  `MultiphysicsContractProbe`(2815+18) 全 PASS；main/diagnostics 编译通过。
- 未提交、未跑 Forge 实机、未跑完整 WorldgenGate。

## 8. 复跑

```powershell
# 判据（默认关闭态）
.\gradlew.bat -I tools/multiphysics-contract.gradle runSelfJoinFixProbe --offline --console=plain
# 反向验证（必须有牙齿）
java -Dgeogenesis.selfJoinFix=false -cp $cp ...SelfJoinFixProbe   # 期望 exit=1
# A/B
java -Xmx12g "-Dgeogenesis.selfJoinFix=true" -cp $cp ...ProductionViewProbe 9139912035078620160 -140 137
```

## 9. 未做

- 没有尝试"自汇时改走次优分支"（而非直接终止）——那是更深的路由改动，需先修 B。
- 没有验证多 seed（除 12345）。
- 没有评估 A 与 B 同时开启的交互。
