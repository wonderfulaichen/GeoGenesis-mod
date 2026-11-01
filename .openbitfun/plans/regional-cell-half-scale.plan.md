---
name: 区域细胞1200与600单变量对照
overview: 只比较区域格距减半对海陆分布引导精度、普通形态过渡及成本的影响；不同时修改海洋类型邻接规则，不改生产。
todos:
  - id: isolate-scale
    content: 获准后为独立沙箱实例提供600块区域尺度，保留1200基准及全部配方参数
    status: completed
  - id: compare-layout
    content: 导出同窗口类型与地形A/B、固定剖面，统计邻陆海型及纯内部区域变化
    status: completed
  - id: scoped-validation
    content: 做身份/权重/端点重点回归与成本实测，保留旧缺陷负控但不重复整套历史出图
    status: completed
---
# 区域细胞1200与600单变量对照

## 结论与授权边界

用户已授权按本方案实施1200/600单变量A/B；授权仅限沙箱实例、相关检查与同窗口出图。
不修改默认值、不接生产，也不引入近岸海型选择规则。

## 源码事实

- [RegionPartition.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionPartition.java#L25-L26) 当前区域格距1200块；这不是1024块缓存tile，也不是图片5块采样步长。
- [RegionalMorphWorld.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionalMorphWorld.java#L38-L51) 在站点world位置采c并选海陆；海洋内部类型由独立海槽抽取，与近岸关系无约束。减小区域不会自动禁止深海邻陆。
- [LocalBoundaryGeometry.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LocalBoundaryGeometry.java#L63-L66) 普通过渡总宽240块，单侧120块。

## 实验约束

1. A保留当前P3的1200格距；B只改为600。不要全局改常量而污染已认可旧臂，应从区域实例及其同源几何传递尺度。
2. 不改配方种子/幅度/频率/bias/票槽权重，不改c场、海陆概率或独立站点抽签机制，不改240过渡宽度与warp。不接特殊海岸或生产。
3. 分区尺度变化会改变站点world位置及cell身份对应范围，布局必然改变；不承诺父子区域保持同类型，不反调海陆面积。
4. 同面积站点约4倍；240/格距从20%变40%。报告纯内部区、过渡覆盖、多候选数量、几何构建和采样成本；不把4倍站点等同于4倍运行耗时。

## 交付与检查

- 同seed、同7005×7005窗口、每臂1401²原生类型图/地形图及固定全幅剖面；附用户圈定区域局部对比。
- 按真实区域邻接统计陆型直接邻接的各海型，不用最终高度分桶倒推分布正确；保留高度水边的独立统计。
- 重点验证600尺度下站点跨tile身份一致、240块权重带/候选完整性、纯配方端点和无非法数值。
- 不以断言总数作为质量指标；重点回归按职责分组。已有hash范围、极小海权重负控保留，宽域槽位审计不必每次重复；本实验不重跑无关历史7臂图链。
- 若600改善颗粒度但深海邻陆仍在，如实说明它是选型/邻接问题，不继续缩细或擅加浅海环带。

## 本轮实施与实测（2026-11-01）

### 产物

- [全幅高度A/B](../../handoff/attachments/cell-scale-ab-20261101/pair-full.png)、[全幅类型A/B](../../handoff/attachments/cell-scale-ab-20261101/pair-types.png)。每臂原生1401²，5块/像素；采样点跨度7000块，像素覆盖口径7005块。
- [左上局部高度](../../handoff/attachments/cell-scale-ab-20261101/pair-local-height.png)、[左上局部类型](../../handoff/attachments/cell-scale-ab-20261101/pair-local-types.png)：同坐标像素框[0,0,620,660)，这是圈定海域的包围框，不是逐笔配准。
- [六条固定全幅剖面](../../handoff/attachments/cell-scale-ab-20261101/profiles-full.png)、[三条固定局部剖面](../../handoff/attachments/cell-scale-ab-20261101/profiles-local.png)。两臂均从Java真实入口逐块采样，不由Python插值高度；全幅Y轴固定[-64,320]，局部共用纵轴。
- [图像与统计校验](../../handoff/attachments/cell-scale-ab-20261101/metrics.json)、[原始邻接/成本摘要](../../handoff/attachments/cell-scale-ab-20261101/comparison.json)、[检查记录](../../handoff/attachments/cell-scale-ab-20261101/check.txt)。

### 单变量实现

`RegionalMorphWorld(catalog, scale)`→`FullMorphLand`→`LocalBoundaryGeometry`→`RegionPartition.withScale`。只复制实例尺度与同源frequency；COHERENT格心世界坐标同步用实例尺度，原噪声对象/seed/游标/票槽共享且不改。旧构造仍1200，1200走原几何入口。既有源码相对本轮冻结只改这四个文件；另外增加实验导出/检查与渲染脚本。生产未改、缓存schema未改、默认未改。

seed=`9139912035078620160`，样点原点=(-3642.5,-3365.5)，步长5，size=1401。两臂JVM属性都只有`file.encoding=UTF-8`（无任何`geogenesis.*`属性）；`-Xmx6g`为导出进程资源选项。c场4000/6、warp、配方、240距离带及分布抽签完全冻结。没有特殊海岸加工，不是Minecraft实机图。

### 结果（不是效果定稿）

| 指标 | A1200 | B600 |
|---|---:|---:|
| 高度Y<63水面占比 | 29.571974% | 30.394166% |
| 主导类型出现数（总23） | 21 | 23 |
| 单配方内部占比（全图） | 64.872088% | 38.456726% |
| 多配方过渡占比（全图） | 35.127912% | 61.543274% |
| 活跃配方均值/最大 | 1.408618 / 4 | 1.819385 / 4 |
| 单站点内部占比（同50块网格19881点） | 63.980685% | 34.273930% |
| 活跃站点均值/最大（同网格） | 1.426588 / 4 | 1.938283 / 5 |
| 全窗口真实区域邻接对（去重） | 112 | 431 |
| 陆型↔海型邻接对 | 28 | 65 |
| 陆型↔深海平原邻接对 | 10 | 26 |

邻接来自LocalBoundaryGeometry实际world边折线，只计窗口内有非零片段的站点对，按cell pair去重，不按高度倒推。五海型邻陆对数（深海平原/洋中脊/海山群/峡谷/沙脊）A=10/6/2/3/7，B=26/9/14/5/11。因区域对数增加，不能拿原始绝对计数声称深海邻陆概率恶化；这里只证明减半**没有禁止**它。

**判断**：600细化类型分布与轮廓，但固定240带使过渡覆盖显著增加；它不是近岸海型约束，深海仍可直接邻陆。布局改变会把不同陆地配方放到不同坐标，不可把局部新增山地/劣地当成配方幅度变化。保留两臂供用户看图，未把600设默认。

### 成本与局限

同进程顺序A→B、同1962801点，单次观测：采样记账25.5725→29.8355秒（约+16.7%，不含tile建场）；含建场/写raw导出39.2018→56.9467秒（约+45.3%）。同9个固定tile几何buildMillis汇总1748→3273（约1.87倍），站点合计213→756、线段52462→94679。这里只是离线观测，含JIT/缓存/系统波动，非重复微基准，**不是实机区块或FPS承诺**。

A重算高度SHA=`7941b81484200c5d99b96b212b9c15b9a5dcb361ca2aedc763f379b04aca3803`，类型/active/sea-weight三项也与冻结P3逐位一致。600相关检查：200点tile权重与穷举geometry.query逐位相同；331个跨窗口独立逆序身份逐位相同；168个强制双tile同点max权重差3.672186e-6、max e差4.582107e-7（阈值均2e-3未改）；23端点/既有陆覆盖对拍max差0；240单次ease及实际两候选距离带、hash范围和极小海权重陷阱通过，旧错误分支负控能检出。

Gradle `compileDiagnosticsJava`及受改动FullMorphLand的既有910项回归通过。未跑无关水文/世界生成全门禁或历史7臂图链；warp单射未证明、固定Gallery seed限制仍在，不以本轮抽样冒充无限世界证明。构建类路径与raw在忽略的`build/cell-scale-ab`，摘要/CSV/剖面证据归档在上方attachments。

### 复现命令

在mdk目录运行（classpath-jars.txt及baseline/classes为已有本地基准依赖；缺失时需先按原基准工作流构建，不能只凭本节冷启动整个工程）：

```powershell
$java='D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$base='build/terrain-upgrade/baseline'; $r='build/cell-scale-ab'
$jars=(Get-Content "$base/classpath-jars.txt" -Raw).Trim()
$cp="$r/classes;$base/classes;$base/src-main/resources;$jars"
# compile.args已经固化本轮完整源码列表；javac参数文件规避Windows命令行长度限制。
& "$java/javac.exe" "@$r/compile.args"
if ($LASTEXITCODE) { throw 'compile failed' }
& "$java/javac.exe" --release 17 -encoding UTF-8 -cp $cp -d "$r/classes" src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionScaleCheck.java
if ($LASTEXITCODE) { throw 'check compile failed' }
& "$java/java.exe" -Xmx5g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.RegionScaleCheck "$r/check"
if ($LASTEXITCODE) { throw 'check failed' }
& "$java/java.exe" -Xmx6g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.RegionScaleGallery "$r/data"
if ($LASTEXITCODE) { throw 'gallery failed' }
```

仓库根渲染：

```powershell
python -B tools/render-cell-scale.py forge-1.20.1-47.4.10-mdk/build/cell-scale-ab/data handoff/attachments/cell-scale-ab-20261101
```
