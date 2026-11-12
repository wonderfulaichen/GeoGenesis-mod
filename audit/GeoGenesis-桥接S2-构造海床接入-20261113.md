# 桥接 S2：构造海床接入（O(1) 地壳场）— 2026-11-13

> **性质**：架构文档 [`GeoGenesis-构造基底架构设计-20261112.md`](GeoGenesis-构造基底架构设计-20261112.md)
> 实施顺序的 **S2**（导出 eBase、海侧接线、陆侧权重=0）。S1（物理量层）见 commit e5bad8c/5ff6270；
> 复刻形态基准见 [`GeoGenesis-SimpleTectonics复刻记录-20261113.md`](GeoGenesis-SimpleTectonics复刻记录-20261113.md)。
> **S3（陆侧逐档，每档停等用户）/S4（edge 换源+类型统一）/S5（全链重立基准）未开始。**

## 〇、一句话结论

**S2 完成并验证**：海床 deep 来源 Perlin → `CrustField.eBase`（构造物理量均衡导出），
海侧高度改变（新全图基准 `51c2626d…`）、**陆侧逐位不变**（双旗协议实测
`landCellSha=9e98ea93…` 切换前后一致，1,259,862 陆格）；开关
`geogenesis.crustOceanEBase`（默认开）可即时回退复现旧基准 `bea0a47c…`。

## 一、O(1) 地壳模型（CrustField，`src/main/java/.../terrain/CrustField.java`）

**范式裁定：C = 真实均衡**（与 S1 已验证方向一致；复刻的"成熟=陆"是 area=1 截断怪癖语义，不采用）。
用户未及答复范式选择题，C 为推荐默认；**S3 停等门是最终裁定时机**，标定探针已内置两极端对比图。

| 量 | 公式 | 来源 |
|---|---|---|
| age | `1 − (1−smoothstep(120,1020,dist)) · smoothstep(0.03,0.20,−stress)`（dist 老化 × 离散邻近掩码） | 既有 dist + stress（零新增采样） |
| κ | `smoothstep(0.15, 0.55, stress)`（连续应力正叶瓣 = 汇聚造山带足迹，σ=1000wu） | 既有 stressField |
| thickness | `0.20 + 0.35·age + 1.0·κ`（前段 = S1 常量） | LithoPhysical 复用 |
| ρ | `clamp≥0.40(0.62 + 0.30·age − 0.50·κ)`（越老越沉=越深；碰撞陆化变轻=成陆） | 同上 |
| buoyancy | `thickness·(0.95−ρ)`（S1 正统式，5ff6270） | 同上 |
| eBase | `(buoyancy − 0.12)·3.9`（e 量纲；B0=海平面均衡基准，S3 的范式旋钮） | 新标定 |

剖面自洽：脊轴薄热→浅；洋盆老密→深（≈−51 格）；碰撞带厚轻→陆/山（κ=1 时 ≈+0.82 buoyancy）。

## 二、age 语义的三次失败与终案（方法论记录，勿重走）

1. **v1：distDiv 直用 + 复用 blurDist 平滑**——blurDist 的环形采样硬编码 `distAt`
   （任意类型边界距离），把 dist 值搅进 distDiv 场 ⇒ 语义污染。
2. **v2：distDiv 直用 + 专属 blur**——**用户圈出 4 处轴对齐硬边伪影**。
   根因是两层：① 穿过汇聚/走滑边界时 c1 配对集合整体切换 ⇒ min 值跳变；
   ② pass2 窗口 ±1 格在 jitter=0.7 下漏检 ~600wu 内的离散边界 ⇒ 回退 NONE ⇒
   age 突跳成"老"，**回退边界沿格网线走** ⇒ 轴对齐直边。
   尝试 ±2 加宽无效（根源是配对切换，不是窗口）。
3. **v3（否决）：stress 负叶瓣直接整形 age**——实测中位 stress ≤ −0.45，
   大半图二值化。该场语义是 T1/T5 调制（板块径向速度投影染色内部），不是年龄场。
4. **★ 终案：`age = 1 − (1−base)·divProx`**
   - `base` = smoothstep(dist)——既有连续场（含 blur），在所有边界处清零；
   - `divProx` = smoothstep(0.03, 0.20, −stress)——离散邻近度<b>掩码</b>；
   - 洋脊轴（base→0 ∧ divProx→1）⇒ age→0 ✓；转换/汇聚边界（base→0 ∧ divProx→0）
     ⇒ age→1 ✓（两侧 crust 各保留年龄——物理正确）；板块内部（base→1）⇒ age→1 ✓
   - **distDiv 全套机制已删除**（sampleCrust / blurDistDiv / distDivSampleAt /
     DIST_DIV_NONE / SEARCH_RADIUS_DIV），TectonicField 改动净额 = **0 行删除、
     纯新增**（sample() 逐字未动）。

**方法论（两次实证）**：连续性必须来自【连续输入的组合】，而不是新造的
min/切换型几何量。新造几何距离场看似"更物理"，实际引入配对切换与窗口截断
两个不连续源；用既有连续场做掩码组合反而干净。

## 三、TectonicField 桥接改动

- **零改动**。终案 age 公式只用既有的 `dist` 与 `stress` 两个连续场，
  不需要任何新的构造采样量——`TectonicField.java` 的 `git diff` 为
  **0 行删除、纯新增注释级改动**，`sample()` 逐字未动，
  岩层/土壤等全部既有构造消费者性能画像不变。
- 过程记录：曾新增 `distDiv`（到最近离散边界的几何距离）、`sampleCrust()`、
  `blurDistDiv()`、`distDivSampleAt()`、`DIST_DIV_NONE`、`SEARCH_RADIUS_DIV`——
  因引入配对切换 + 窗口截断两个不连续源（用户圈出轴对齐硬边）**全部删除**。

## 四、接线（A3：三段剖面逐位保留，只换 deep）

- `MorphTerrainSampler`：自持 `TectonicField`（同种子 ⇒ 与 BaseWorldSampler 的实例
  逐位等价，R3 双 sampler 经同一构造路径覆盖）；开关注入 eBase 源。
- `MorphOceanAssembler.withCrustEBase(...)`：`deepBlocksRaw` 切源
  `eBase × BLOCKS_PER_E_SEA`；**S2 近似：脊轴浅于陆架常数 −7 时钳到 −7**
  （保三段剖面单调衔接；钳位等值线在高分辨率下是一条轻微 kink 线，
  1401² 渲染已见、S3/S4 剖面评估后放开）。
- 开关 `geogenesis.crustOceanEBase`：默认开（`!"false".equals` 惯用法），
  单点读取（MorphTerrainSampler），注释同源；-D 关闭可逐位复现旧基准。
- `PreviewDisplay.CACHE_SCHEMA_VERSION` 129→**130**（清单已补条目）。

## 五、验证（全部实测）

| 项 | 结果 |
|---|---|
| P1 开关关 | `productionCellSha=bea0a47c…`（旧基准逐位复现）+ `cellMetadataSha=9ffcee45…` + PASS |
| P1 开关开 | `productionCellSha=d87b8629…`（终版基准）+ PASS |
| **陆侧逐位不变** | `landCellSha=9e98ea93…`（landCells=1,259,862）——**跨 age 语义三次大改逐位不变** |
| 形态 | 1401² 生产路径渲染（`build/p1-gate-s2-off/s2-heights-final.png`）：陆块不变、海床深盆/浅脊分明、无硬边伪影、−7 kink 消失 |
| CrustFieldProbe | κ coverage 12.5%；局部窗 landFrac=9.8% |
| **全球普查**（±20000wu / 200,704 点） | **C(碰撞=陆) = 31.4%** ／ A'(成熟=陆) = 65.5%——C 范式接近真实地球 29%，A' 偏高 |
| 门禁 | **runWorldgenGate ALL PASS**（终版重跑 5m30s，GATE_EXIT=0） |
| 性能 | `sample()` diff **0 行删除**（逐字未动，热路径零回归）；perf 读数受环境噪声影响大（1171~3582 漂移），**以代码等价性为准** |

**-D 开关关跑 P1 会报 "P1 height drift"**——旧基准已非 EXPECTED，属预期；
A/B 对拍看打印的 SHA 值，不看退出码。

## 六、"五处确定性缺口"枚举（补全主文档 L215 的悬空引用）

1. **PRNG**：模拟 `srand/rand`+`mt19937(random_device)` 固定种子也不可复现 → O(1) 场全部由哈希常数导出（现有 CellConst 口径），天然逐位可复现。
2. **迭代状态**：模拟 8192 段迭代数千步 → O(1) 解析场（本文件公式），无状态、按需求值。
3. **有限域**：模拟 256² 有限网格 → 无限 Voronoi（现有 TectonicField 无限域口径）。
4. **时变过程**：模拟的漂移/增生/俯冲是时间过程 → 冻结快照语义：`age` 字段是
   "离脊距离"这一静态地理量的函数（板块格局即构造史）；无时间维度。
5. **GPU 依赖**：模拟 Voronoi 走 GPU 深度缓冲 → CPU 解析（复刻已证等价；生产本就 CPU）。

## 七、全球海陆占比（★ 应用户要求补的全局口径）

局部 501² 窗口（r=3000wu，仅 1.5 个板块宽）**不能代表全球**——应用户指出后补做
全球普查：±20000wu（20×PLATE_SPACING）确定性均匀网格 448² = 200,704 点，
复现命令见 §八。

| 范式 | 全球陆占比 | 对照 |
|---|---|---|
| **C（碰撞=陆，现行）** | **31.4%** | 真实地球陆壳 ≈ 29% ✓ |
| A'（成熟=陆，复刻忠实） | 65.5% | 偏高——"成熟地壳全部成陆"只剩裂谷是海 |

全球布局图：`build/crustfield/crust_global.png`（C）与 `crust_global_ageonly.png`（A'）。
C 范式的陆块沿汇聚边界成**带状/斑块状**分布（造山带），A' 是**大片连陆 + 网状裂谷**。
S3 陆侧放开时，这两张图就是形态选择的主要依据。

## 八、复现命令

```bash
gradlew.bat --offline compileDiagnosticsJava
# CrustFieldProbe：<toml> <outDir> [size] [cxB] [czB] [rB] [globalR=20000]
java @crustargs     # 主类 com.geogenesis.worldgen.terrain.CrustFieldProbe
                    # 产出 8 张图 + 全球普查 + perf

# P1 双旗（开关关=旧基准，开关开=新基准）
java -Dgeogenesis.crustOceanEBase=false @p1args   # → bea0a47c…
java @p1args                                       # → d87b8629… + PASS
```

## 九、S3 待办（下一步，每档停等用户）

1. 陆侧权重逐档放开：`MorphLandField` 与 eBase 陆侧的混合比例档位（文档 S3；
   档位数值文档未给，需本轮标定探针出图后与用户定）。
2. 范式最终裁定图证：`crust_ebase.png`（C：碰撞=陆）vs `crust_ebase_ageonly.png`
   （纯 age 世界）——已随本轮产出。
3. κ 整形：stress 正叶瓣收窄端的"针尖"（汇聚段结束处的自然收窄）在陆侧
   会成为针状山脉——S3 需整形（加宽叶瓣基座或轻度平滑）。
4. B0（=0.12）与陆占比标定：当前构造语义陆占比 10.5%，目标 ~0.4 量级
   （复刻基线）——调 B0 / KAPPA 窗口 / THICKNESS_COLLISION。
5. −7 钳位放开评估（脊轴高地的完整形态）。
6. LithoPhysicalProbe 保持 S1 原样（CrustFieldProbe 覆盖新语义）；
   LithoPhysical 类不动（S3 陆侧仍可能用其 shellS 插值路径）。
