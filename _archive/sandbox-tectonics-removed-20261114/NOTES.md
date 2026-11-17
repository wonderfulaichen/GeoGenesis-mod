# 已删除的沙箱文件（2026-11-14 清理）

用户裁定"板块只以 `planet-simulation` 为借鉴对象"后，把上一批参考（已全部删除）
留下的**死代码 / 错代码**清出沙箱。这里只记**删了什么、为什么删、被谁取代**——
代码本身未提交过（`src/diagnostics` 当时是 untracked），保留全文没有价值。

| 删除文件 | 为什么删 | 取代物 |
|---|---|---|
| `SandBoundaryStats.java` | 它标定的是"速度场 `div` 与单配对 `direct` 的符号一致率"。四案例构造响应改写后，生产路径改用**逐配对 `conv`**，`div` 只剩诊断用途 ⇒ 这探针测的量**不再驱动任何东西**，结论会误导 | `SandPairProbe`（逐对打印 `dEff/conv/own/oth/u/权重`） |
| `SandTectonicProbe.java` | 旧渲染器，读 `Plate.shell` / `Plate.baseElev` / `Parts.oceanDepth` 等**已删除字段**；它出的 `height/plates/btype/depth` 图反映的是"板块=大陆"的旧模型 | `SandSurfaceProbe`（`surface/raw/eroded/grad/sections`） |
| `SandStepCheck.java` | 沿一条横线测 1wu 跳变，单条线不足为证（用户早前指出图上仍有网格状边线） | `SandContinuityCheck`（全网格双向 + 分量归因） |
| `SandErosion.java` | 液滴侵蚀，源码自己写着"实测 ×0.95 几乎无效"（7×7 笔刷把移除量稀释 49 倍）；且只有 `iters>0` 才走到，默认不走 | `SandLandscape`（LEM：优先队列填洼 + D8 + 汇水面积 + 水流功率 + 坡面扩散）——**目前也未接线**，等"侵蚀阶段"再启用 |

同时清掉的**死成员**（都在 `SandTerrain` / `SandPlateField` 里）：

- `Agg` / `aggregate()` / `weightedBaseElev()` —— "邻域板块 baseElev/landness 加权聚合"，是"板块=大陆"模型的遗留。
- `interiorRelief()` 及其 5 个噪声常量 —— 被参考的第 ⑤ 段细节公式取代。
- `Parts` 的 `oceanDepth/arc/backArc/rift/ridgeAxial/transform` 六个字段 —— 四案例改写后**恒为 0**，却让每个探针打一整列 0。
- `Plate.shell` / `Plate.baseElev` + `LAND_PLATE_FRAC` / `LAND_FIELD_THRESHOLD` / `CONT_CLUSTER` / `SALT_LANDFIELD` / `SALT_SHELL` / `SALT_ELEV` —— **每板一个陆/洋标志**的旧做法。它产出"平顶板块 + 台阶状海岸"，正是用户指出的问题；现在陆/洋由独立大陆层 `SandContinent` 决定。
- `SUB_CONT_DIP` / `SUB_CONT_DIP_W` —— 陆侧俯冲的前弧凹，改写后零引用。
- `sigmoid()`、`CRUST_LAND`、`SandContinent.shellAt()` —— 零调用。

**教训**：这些都是"能编译、能被探针读到、但语义已经错"的代码。它们比纯死代码更危险——
探针会拿它们算出好看的数，让人以为在验证生产路径。删之前先确认**生产入口**
（`SandTerrain.partsOf`）到底读哪些字段，只留那条链上的。