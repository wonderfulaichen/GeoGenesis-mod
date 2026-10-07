# model 链实机验收结果（2026-10-07）

> 依据：`runClient` 实机跑一次（seed=9139912035078620160，新建世界），
> `worldgenProfilerEnabled=true`。原始证据存档：
> `forge-1.20.1-47.4.10-mdk/build/ab-model-on/wgp.txt` 与 `regions.txt`。

## 一、结论：**接线可用，但当前成本不可接受；且暴露两个既有缺陷**

| 指标 | legacy 基线（HANDOFF） | model 链实机 | 判定 |
|---|---|---|---|
| 块均总耗时 | 30.02 ms | **63.01 ms** | ❌ 2.1× |
| 水文雕刻（占比） | 21.2% | **31.1%（234582 ms）** | ❌ 最大单项 |
| 水文-计算 均/P50 | — | 48.99 / 38.43 ms | ❌ |
| region 求解 | — | **14~18 秒/个**（离线探针 2216 ms） | ❌ 慢 6~8× |
| 方块铺设 | 8.40 ms | 8.83 ms | ✅ 无变化 |
| 洞穴雕刻 | — | 3.73 ms | ✅ |
| 原版装饰 | — | 3.01 ms | ✅ |

**model 链本身功能正确**（列语义、只下挖、水位自洽、纯函数、引擎开关三态
均 ALL PASS）——问题纯粹是成本，不是正确性。

## 二、★ 缺陷 A：地形单例在世界生成前被构造 4 次（全部白烧）

实机时间线（`latest.log` 原始行）：

```
11:42:26.965 [Render thread]  terrain init #1   ← 主菜单预览
11:42:27.753 [Render thread]  terrain init #2
11:42:27.760 [Render thread]  terrain init #3
11:42:27.762 [Render thread]  terrain init #4   ← 全在世界加载之前
11:42:31.973 [Server thread]  Starting integrated server
11:42:32.340 [Worker-Main-3]  terrain init #5   ← 世界生成实际用这个
11:42:32.379 [Server thread]  world seed set to ... (terrain singleton invalidated)
```

代码级根因：`GeoGenesisGenerator.sharedTerrain` 是 static 单例，
但 `setWorldSeed()` 会执行 `sharedTerrain = null`（第 662 行）——
而它发生在**世界生成开始时**，此时前 4 个实例已各自持有
`ModelHydrologyProvider` 缓存并可能已求解过 region。

⇒ 每个实例各解一遍同样的 region（见缺陷 B）。

## 三、★ 缺陷 B：同一 region 被求解两次，且两次结果不同（违反纯函数铁律）

```
region (0,0)  solved in  4933 ms (reaches=16,  lake=3,   channel=49)
region (0,0)  solved in 13355 ms (reaches=218, lake=7,   channel=2719)  ← 同一 region！
region (1,0)  solved in  3823 ms (reaches=94,  lake=0,   channel=763)
region (1,0)  solved in 18490 ms (reaches=774, lake=60,  channel=5553)  ← 同一 region！
region (-1,0) solved in  1406 ms (reaches=0,   lake=0,   channel=0)
region (-1,0) solved in 14085 ms (reaches=726, lake=305, channel=7817)  ← 同一 region！
```

逐个 region 计数：`(0,0)/(1,0)/(-1,0)/(0,1)/(0,-1)` **各 2 次**。

**同一 `(seed, region)` 两次产出不同** ⇒ 违反"纯函数 + 缓存只改时序不改内容"
（`ModelHydrologyProvider` 自己的 javadoc 断言）。
差异来源是**不同地形实例**（各自 CellGenerator 状态不同），
而非缓存 bug —— 但后果相同：一半算力白烧 + 产出不可复现。

## 四、★ 性能探针与实机的落差（口径警告）

`runPipelinePerfProbe` 报生产配置 **1.58~1.77 ms/chunk**（优于 legacy 6.8），
实机 region 求解 **14~18 秒**（离线探针 2216 ms）⇒ **差 6~8 倍**。

混杂因素（均已确认存在）：
1. 探针空载；实机同时有 tile 生成 / 装饰 / 洞穴争 CPU；
2. 探针 step=4 用 r=512；`ModelHydrologyProvider` 实际是 512+96 halo
   ⇒ 177×177 网格（比探针假设的大）；
3. 实机首解含冷 tile 生成（日志中 `[PERF] erosion tile` 与求解交错）。

⇒ **探针 ms/chunk 不能作为接线依据**。这正是方案 §P3 警告的口径问题，
   只是方向相反：实机比探针**更差**（原担心是"探针数字过于乐观"）。

## 五、未做（如实）

- 合成场景验证（方案 P2 明确要求：合成谷地 / Y 汇合 / 陡坡 / 平地 / 出口回流）；
- 多种子泛化验收（只跑了正典 seed）；
- 并发冷启动 / 峰值内存压测；
- `PreviewDisplay` 缓存 schema 与 model 链的同步（合同 5 的"世界生成数据与预览同源"
  仍不满足：`fillRiverDistance` 走 legacy 河网）。

## 六、建议下一步（待用户拍板，未擅自实施）

1. **先修缺陷 A**（可判定、收益明确）：让 4 个预览实例不创建 model provider，
   或让 `ModelHydrologyProvider` 缓存提升为 static/按 seed 键控
   ⇒ 预计直接省掉一半 region 求解。
2. **重标定 region 规模**：14~18 秒/个明显过大（可降 samples 或缩 region）。
3. 若修完仍不可接受 ⇒ **保持 `hydrologyModelPipelineEnabled=false`**，
   model 链作为研究线保留（代码已就位、开关可用、契约全绿）。
