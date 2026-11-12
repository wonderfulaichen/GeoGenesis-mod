# `CellGenerator` 死对象处置清单（2026-11-12）

> **本文只出清单与判据，不改代码。** 每一项在动手前需用户确认。
>
> 背景：结构核查确认 `CellGenerator` 上挂着**一整套从未被采样的场**（详见
> [`GeoGenesis-模组结构核查-20261112.md`](GeoGenesis-模组结构核查-20261112.md) §1.2）。
> 危险不在于占内存，而在于**读代码的人会以为构造/地层/气候在参与地形**，
> 于是改错地方，或据此做出错误的设计判断。

---

## 〇、判定方法（可复现）

只认**调用点**，不认注释、不认字段名。对每个符号做全仓 `grep`：

```
Get-ChildItem -Recurse -Filter *.java | Select-String -Pattern "<符号>"
```

然后把命中分成四类：

| 类别 | 含义 | 处置 |
|---|---|---|
| **P** | 生产路径（`MorphCellAdapter` → `BaseWorldSampler` → 落块）真的在调 | 保留 |
| **D-探针** | 只被 `src/diagnostics` 引用 | 删前需改探针 |
| **D-注释** | 只出现在 javadoc / 注释字符串里 | 可直删（连带改注释） |
| **D-孤** | 全仓零命中（除自身声明） | 可直删 |

---

## 一、可直删（无任何外部引用）

判据：除自身声明/内部私有调用外，**全仓零命中**。

| # | 对象 | 位置 | 证据 |
|---|---|---|---|
| A1 | `SEABED_MARGIN` / `SEABED_SOFT_SAT` | `:51,53` | 只出现在声明行。海床护栏在生产中**不存在**（真正写它在死代码 `OceanFeatures:220-225`） |
| A2 | `POLAR_SNOW_TEMP` / `DEG_C_PER_E_UNIT` / `LAT_PRECIP_REF` | `:76,91,98` | 只出现在声明行。`PrecipField:64-65` 的 javadoc 仍以 `DEG_C_PER_E_UNIT` 为换算基准描述，实际已无人读 |
| A3 | `TECTONIC_ENABLED` / `STRATA_ENABLED` / `DEFORM_ENABLED` | `:45,62,71` | 三个 `true` 常量，**从未被读**。它们"保护"的分支早已不存在 |
| A4 | `eqEntries`（131072 槽 ≈2 MB） | `:344` | 全仓只在 `clearEqCache:351` 被清空，**无任何 `get/set`** |
| A5 | `eqHits` / `eqMisses` 的**语义**（`AtomicLong` 本身被探针读） | `:346,347` | 只被 `clearEqCache` 重置、被 `eqCacheHits()/eqCacheMisses()` 读出；**没有任何 `incrementAndGet`** ⇒ 恒 0 |
| A6 | `ditheredTerrain` | `:807-822` | 零调用 |
| A7 | `continentAt` | `:316-318` | 零调用（`waterTable` 那套 RTF 河网已删） |
| A8 | `sampleContinent` | `:939-941` | 零调用 |
| A9 | `regionTemperature` / `temperatureAt` / `regionMoisture` / `precipitationFrom` | `:827,835,841,788` | 整簇只在彼此之间调用，链路末端无生产入口（生产版在 `BaseWorldSampler:184,191,196,229`） |
| A10 | `applyTectonicWeights` | `:466-517` | **零调用（含探针）**。这是"构造决定地形类型"的核心实现，从未接线 |
| A11 | `processErosionEnabled` | `:237` | 生产零调用；`BInputBlockCacheRaceProbe` 用的是别的符号 |
| A12 | `classify(...)` | `:626-680` | **精确检索 `gen.classify(` / `generator.classify(` 零命中**（`classify(` 的其余命中全是 `BiomeClassifier` / `ClimateZone` / `WhittakerType` / `WorldBasin` 等无关方法）。它是唯一含 `SEAMOUNT` / `SUBMARINE_RIDGE` 细分的分类器，随 `landFeat` 恒 null 一并失效 |

> ⚠ A10 删之前要把 `:447-464` 那段"构造决定地形类型"的**领域知识**搬进仍在用的方法或文档，
> 否则会重犯 AGENTS.md 里"删死代码连带删掉知识"的老错。
>
> ⚠ A12 删之前要把 `:662-680` 的海洋细化语义（海山/洋中脊判定条件）搬进
> `audit/GeoGenesis-海洋海岸需求基线-20261112.md`，那是未来 O2/O3 重建的参考。

---

## 二、删前必须先改探针

判据：生产零调用，但 `src/diagnostics` 有引用。**直接删会让整个 diagnostics 目录编译失败**（整体失败制：一个坏文件全红）。

| # | 对象 | 位置 | 探针引用 | 改法 |
|---|---|---|---|---|
| B1 | `landE(wx,wz)` | `:520` | `LandEConformityProbe`、`CoherentMorphCheck`、`LandRegionContextCheck`、`RegionScaleCheck`、`OceanAssemblyCandidate`、`RegionalMorphWorld` | 探针改调 `baseWorld.sampleTerrain(x,z).landE`（等价） |
| B2 | `typeWeightsAt(wx,wz)` | `:523` | `LandEConformityProbe`、`TectonicProbe`、`TerrainStripePngProbe`、`TerrainUpgradeProbe`、`TerrainUpgradeRender` | 同上，改调 `baseWorld.sampleTerrain(x,z).typeWeights` |
| B4 | `eqCacheHits()` / `eqCacheMisses()` | `:373,376` | `TerrainUpgradeProbe:1061-1062,1166`（写 CSV 列） | 连同 `TerrainUpgradeProbe` 的两个 CSV 列一起删 |
| B5 | `erodedHeight(...)` | `:605` | **15 个文件**（水文 model 探针为主，`ChannelMergeFeasibilityProbe` 等） | 这批探针改走 `input.processHeightAtBlock`（生产同源）；成本中高 |
| B6 | `sampleWu(...)` | `:594` | **48 个文件**（水文/侵蚀/地形诊断主干） | **建议保留**：它是探针唯一能拿"含侵蚀地形"的入口，删了要改 48 处，收益为零 |
| B7 | `peekErosionDeltaE(...)` | `:548` | `HydrologyExperimentEngine` | 改调 `erosionDeltaE` |

**结论**：B1/B2/B4/B7 成本低可做；**B5 成本中高、B6 建议保留**（在注释里标"仅诊断"）。
`classify(...)`（`:626-680`）经精确检索 `gen.classify(` / `generator.classify(` **零命中** ⇒ 归 A 类。

---

## 三、必须保留（生产在用，不要因为"看起来像旧代码"就删）

| 对象 | 位置 | 生产调用点 |
|---|---|---|
| `baseWorld` | `:108` | `:299,303,384,554,589` |
| `processRuntime` / `processContext` | `:195` 起 | `:216,240,611`；经 `Runtime.context()` 对外 |
| `applyCachedTileDelta` | `:559` | `GeoGenesisTerrain:243` |
| `extractFromTile` | `:528` | `GeoGenesisTerrain:515` |
| `erosionDeltaE` | `:543` | 生产唯一侵蚀增量入口 |
| `heightCurve` | `:39` | `:262,265,269` |
| `landFactorAt` | `:416` | **仅 `LandFeatProbe:180`** ⇒ 归 B 类，不是 A 类 |
| `lfEntries` | `:368` | 仅 `landFactorAt` 内部用 ⇒ **随 B 类一起处置** |

---

## 四、死字段（构造 + 播种，但从不采样）

这些不是"方法"，是**字段**。删它们要连带删 `seed()` 里的播种行与构造行。

| # | 字段 | 声明 | 构造 | 播种 | 唯一读点 | 处置建议 |
|---|---|---|---|---|---|---|
| C1 | `continent` | `:38` | `:132` | `:248` | `landFactorAt:440`（无生产调用） | **随 B 类**：若 `landFactorAt` 保留则保留 |
| C2 | `tectonic` | `:56` | `:134` | `:250` | `applyTectonicWeights:469`（零调用） | **可删**（A10 一并删） |
| C3 | `strata` | `:65` | `:135` | `:251` | 无 | **可删**（生产在 `BaseWorldSampler:45`） |
| C4 | `deform` | `:74` | `:136` | `:252` | 无 | **可删**（褶皱/断层从未接入） |
| C5 | `precipField` | `:86` | `:150-153` | `:249` | 无 | **可删**，且**优先删**——构造时分配约 1 MB 数组 + 65536 条，纯浪费 |
| C6 | `climateRegion` | `:84` | `:144` | `:258` | 无 | **可删** |
| C7 | `tempWarp` | `:81` | `:142` | `:254` | 死簇 `temperatureAt:837` | **可删**（A9 一并删） |
| C8 | `humidityNoise` | `:82` | `:143` | `:255` | 死簇 `regionMoisture:842,844` | **可删** |
| C9 | `regionWarp` | `:102` | `:156` | `:256` | 死簇 `ditheredTerrain:814` | **可删** |
| C10 | `oasisNoise` | `:104` | `:158` | `:257` | **连读点都没有** | **可删**（最快见效） |
| C11 | `regionSize` | `:100` | `:145` | — | 无（`warpOffset` 已迁 `BaseWorldSampler:175-182`，用自己那份） | **可删** |

> ⚠ **删 C2/C3/C4 有一个副作用要接受**：删完之后，`CellGenerator` 就彻底没有任何"构造/地层"入口了。
> 这对未来"构造决定整体地形"是**好事**（不会有人再对着死字段改），
> 但要写进文档说明"构造现在只能从 `BaseWorldSampler` 进"。

---

## 五、建议的执行顺序（按风险从低到高）

| 步 | 做什么 | 风险 | 验证方式 |
|---|---|---|---|
| **S1** | 删 A1–A11（纯孤立） | 极低 | `compileJava` 通过 |
| **S2** | 删 C5/C10（`precipField`、`oasisNoise`） | 极低，**收益最大**（省约 1 MB/世界） | 同上 |
| **S3** | 删 C2/C3/C4/C6/C7/C8/C9/C11 + 对应播种行 | 低 | 同上 |
| **S4** | 删 A4（`eqEntries` 2 MB）+ B4（`eqCacheHits` 与探针 CSV 列） | 低 | 编译 + 探针能跑 |
| **S5** | 改 B1/B2/B7 的探针调用，再删包装方法 | 中（要改探针） | `compileDiagnosticsJava` |
| **S6** | 决定 B5/B6 去留 | —— | 建议保留，只补"仅诊断"注释 |

**每一步之后必须验证产出逐位不变**：

```
gradlew compileJava compileDiagnosticsJava
gradlew runP1CellRenderProbe        # 1401² SHA 对拍（bea0a47c…）
```

死对象不参与任何计算 ⇒ **产出必须逐位不变**。若 SHA 变了，说明删错了一个"其实在用"的东西——立即回退。

---

## 六、不在这份清单里的（另行处置）

| 项 | 为何单列 |
|---|---|
| `PrecipField.node()` 非原子读 | 是**在用**代码的并发缺陷，不是死代码 |
| `OreVeins.beginColumn` 静态无同步 | 同上 |
| `BiomeColumnCache` 键缺 y | 同上 |
| `landFactorAt` / `landE` / `typeWeightsAt` 的**语义去留** | 它们服务"火山/诊断"。火山的整体接线（`LandFeatures`）本身是待决策项，需先定火山做不做 |
| `TerrainClass` 10 个永不产出的值 | 属类型体系设计，与"构造决定地形"一并决策 |
| `MorphTerrainSampler` 的 6 个查询门面（`sea/mountainMask/landField/...`） | 全仓零调用，但属对外 API，删除前先确认无外部依赖 |

---

## 七、一句话结论

**死对象本身不改产出，但它们让"改哪里才有效"这个问题变得不可答。**
先删 A 类与 C 类（无引用、无风险、省约 3 MB/世界），再处理 B 类探针依赖，
最后才谈统一 `eBase` —— 这样每一步都能用"产出逐位不变"证明自己没搞错地方。

> ⚠ 本文未做任何代码改动。
