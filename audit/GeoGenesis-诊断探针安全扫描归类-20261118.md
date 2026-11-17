# 诊断探针安全扫描归类（Mimosa medium 标记 12 处）— 2026-11-18

> 背景：2026-11-18 提交 `79d11f6` 时 Mimosa 提交钩子报出 12 处 medium 级风险
> （9 个文件，全部位于 `src/diagnostics`）。经逐文件读码排查，归为三类，
> **结论：无一为真安全问题，代码不动**。本文为存档，后续扫描重复报出时
> 直接引用本文，勿再重复排查。

## 总结论

1. **零处进生产 jar**。`build.gradle:126` 注释明确 diagnostics 为独立源集，
   `jar` 任务只打包 `sourceSets.main`。这些类只在开发机跑 JavaExec 探针时加载。
2. **三类均为诊断工具的正当模式**，共同点：**没有任何不可信输入路径**
   （反射目标类名全部硬编码常量；文件路径来自开发者 CLI `args[0]`）。
3. 唯一真实代价是 A 类的**维护脆弱性**（非安全）：生产类重构后探针会崩。
   按本项目"探针带判据、崩了即签名变了"的哲学，这是预期行为。

## A 类：反射白盒访问（6 处 / 5 文件）

| 位置 | 访问目标 |
|---|---|
| `terrain/candidate/SandboxInventoryProbe.java:189` | 反射读 `LandCompositionGallery.SEED` 私有静态字段 |
| `hydrology/model/LakeConnectorOwnershipProbe.java:12`（import 起） | 反射构造 `ModelHydrologyProvider$ColumnPlan` 私有构造器、调私有 `publishColumn` |
| `hydrology/model/ModelProviderConcurrencyProbe.java:144` | 反射构造 `$CacheKey`、读私有 P1 缓存字段 |
| `hydrology/model/SegmentBucketsCheck.java:24` | 反射调 `DeterministicSkeleton.segmentCrossesAny` 私有方法 + `$SegmentBuckets` 私有构造器 |
| `terrain/candidate/DistanceFixWorldCheck.java:33-35` | 反射读 `UnifiedWorld.baseGrid/distField` 私有字段 + `bandAt` 私有方法 |
| `hydrology/model/RiverDepthViewProbe.java:251` | 最温和：仅 `Class.forName(…, false, cl)` 做类指纹，无 setAccessible |

**定性**：项目"无单元测试框架、探针即测试"模式下检验生产类私有内部的刻意手段
（语义契约、并发缓存行为、反射 immutability 等没有包级测试钩子就无法验证）。
安全上无注入面；维护上重构会崩探针 = 特性。

## B 类：自定义类加载器从目录加载类（2 处 / 2 文件）

| 位置 | 干什么 |
|---|---|
| `erosion/ErosionScratchProbe.java:1063` | `ChildFirstLoader` 从 build 目录加载候选侵蚀引擎类（A/B 对拍） |
| `erosion/GuideErosionBench.java:55` | `Loader` 从目录加载引擎类跑基准 |

**定性**："从目录加载类"理论上等于代码执行，但此处加载的是**本地构建产物**
（探针自己编译的候选引擎，目录由构建写入），非外部可控输入。本地基准的常见做法。

## C 类：跨文件污点标记（2 处 / 5 行，基本误报）

| 位置 | 被标记的行 |
|---|---|
| `terrain/candidate/DistanceFixWorldCheck.java:33-35` | **import 行**（`reflect.Field/Method`、`nio.Files`） |
| `terrain/candidate/M0DistributionGallery.java:23-24` | **import 行**（`BufferedOutputStream/DataOutputStream`） |

**定性**：污点追踪从 import 起点保守跨文件传播；实际代码是"二进制导出写到
CLI 指定输出目录"（开发者自传 `args[0]`，`M0DistributionGallery` 还显式拒绝
已存在目录）。无不可信输入。

## 后续处理建议

- **代码不动**（理由见上）。
- 若钩子噪声影响评审，可仿照 `tools/config-key-guard-allowlist.txt` 先例建白名单；
  ⚠ 但须先确认 Mimosa 支持仓内白名单文件——**未经确认不要创建死文件**
  （白名单若没人读就是永久豁免腐烂，违反本仓铁律）。
- 本文档本身即是"已知误报清单"：后续扫描报同一批文件时，引用本文直接放行；
  报**新文件/新位置**时才需要重新排查。
