# SimpleTectonics 纯 Java 复刻记录（形态基准）— 2026-11-13

> **性质**：`src/diagnostics` 下的独立复刻工具（形态基准），**非生产代码、不进 `runWorldgenGate`、
> 不升 `CACHE_SCHEMA_VERSION`、零 `src/main` 改动**。
> 上游依据：交接包 `handoff/GeoGenesis-20261112-deadcode-cleanup.md` §7.2（用户原话裁定方向）。
> 参考项目：`参考/SimpleTectonics/`（MIT，C++/OpenGL，从 `%TEMP%` 抢救入库）。

## 〇、一句话结论

**复刻完成并验证**：同种子双跑 SHA 逐位一致；5000 步形态与参考截图同构
（土黄陆块 + 大红薄壳裂谷域 + 碰撞山链 + 绿大陆蓝海洋）。复刻过程最重要的发现：
**参考项目两个"怪癖"（area 的 int 截断、热场 8-bit 量化）是动力学的承重墙**，
按现代直觉"修正"它们会把形态毁掉——已回退为忠实行为。

## 一、交付物

| 文件 | 行数 | 职责 |
|---|---|---|
| `tectreplica/TectRng.java` | 47 | SplitMix64 单流（接管 srand/rand + mt19937 全部随机点） |
| `tectreplica/PoissonDisk.java` | 99 | poisson.h Bridson 变体（域 256²、目标 8192 点、r≈2.257px） |
| `tectreplica/TectSegment.java` | 56 | Litho 段（mass/thickness/area/density/height + buoyancy） |
| `tectreplica/TectPlate.java` | 261 | Plate 刚体四阶段：碰撞俯冲/造山扩散/生长/对流积分 |
| `tectreplica/TectCluster.java` | 119 | **CPU Voronoi**（空间哈希 3×3 邻格，替代 GPU 深度缓冲）+ 面积 EMA |
| `tectreplica/TectWorld.java` | 333 | World：热场初始化/subductHeat/updateWorld/间隙增生/高度图/SHA |
| `tectreplica/TectRender.java` | 176 | 出图（配色照抄 scene.h）：板块/地形/热场/Voronoi，各有 hillshade 变体 |
| `tectreplica/TectReplicaProbe.java` | 150 | main：`<outDir> [steps] [seed]`，跑 N 步 + 出图 + SHA + 统计 |
| `build.gradle` | +17 | `runTectReplicaProbe` 任务（样板 runP1CellRenderProbe；不进门禁） |

## 二、复现命令

```bash
# gradle（本机 ZCode 沙箱下 JavaExec 可能静默挂死 → 用下面的直调路线）
gradlew.bat runTectReplicaProbe [-PprobeArgs="<outDir> <steps> <seed>"]

# 直调（先编译！build/classes 旧字节码会跑出假结果——2026-11-13 实测踩过）
gradlew.bat --offline compileDiagnosticsJava
java @<argfile>   # -cp = diagnostics 类目录 + main.runtimeClasspath（全部正斜杠）
                  # 主类 com.geogenesis.worldgen.terrain.tectreplica.TectReplicaProbe
                  # 参数：<outDir> 5000
```

**正典口径（2026-11-13 定）**：seed=9139912035078620160、steps=**5000**。
产出 SHA：`stateSha256=f585313de12b8e0c35c4df7aa08ae51e48100d7f93b50666184cd199356f8d73`
（同口径双跑逐位一致已验证；500 步口径 SHA=`77885618…`，双跑一致已验证）。

## 三、形态基准结论（对拍 `参考/SimpleTectonics/screenshots/`）

5000 步（seed 9139912035078620160，`build/tectreplica-final/`）：

- `plates_5000_shaded.png`：土黄厚壳陆块 + 大红薄壳洋域（锯齿边界）+ 边界山链
  ⇔ 对照 `plates0/1.png`、`banner*.png` 左图。同构。
- `terrain_5000_shaded.png`：绿大陆 + 蓝海洋 + 白色山斑 + 半岛/岛弧
  ⇔ 对照 `terrain0/1.png`。同构。
- 统计：segments=10801（增生>死亡，裂谷活跃）、plates=10、背景 21.6%（开放裂谷）、
  landFrac=0.42、nonFinite=0、max thickness 1.47。

## 四、关键教学：两个"怪癖"是承重墙（复刻方法论）

复刻中先后用"现代直觉"修掉参考项目的两个实现怪癖，形态**两次退化**，最终回退：

| 怪癖 | 表面看 | 实际作用 | 误修后果（实测） |
|---|---|---|---|
| `area` int 截断 EMA（cluster.h:73）：`floor(0.99a+0.01count)` | 精度损失 | 计数~8 时 `floor(1.07)=1` ⇒ **area 恒为 1** ⇒ 板块质量轻 ~7 倍 ⇒ 对流快 ~7 倍 ⇒ 大量碰撞/裂谷/边缘红壳 | 改 double(area=8) → 板块沉重、全图均匀厚壳、无海洋（landFrac=1.0） |
| 热场 8-bit 量化（RGBA8 回读×25 次/步） | 精度损失 | <1/255 的扩散变化被抹掉 ⇒ 热场块状滞留 ⇒ 持久梯度 ⇒ 持久推力 | 改 double 连续值 → 动力学偏软（与 area 问题叠加） |

**方法论**：移植"祖传公式"时，先把怪癖当feature跑基线，再做对照实验判定怪癖死活；
判定依据只能是渲染图 + 统计，不是直觉。

## 五、已记录偏差（与参考的全部有意差异）

1. **force() 未初始化 fx 置 0**（tectonics.h:233 的 UB：x∈[1,254] 且 y≤0∨y≥254 时读栈垃圾）——Java 无 UB。
2. **造山捐赠/溶解钳制下限 0.02**（TectPlate FLOOR）：原版允许 24 邻居透支捐赠 ⇒ thickness/mass 变负 ⇒ density 爆 ±∞（20000 步实测 max=4.4e36；C++ float 同炸）。钳制后 max 1.47，正常动态远低于钳制值。
3. **area EMA 钳 ≥1**：count=0 的段（圆盘被近邻全盖）会 EMA 到 0 ⇒ 除零 ⇒ inf 段（实测 nonFinite=2）。C++ int 同炸。
4. **libnoise Perlin3(z=SEED) → 项目自带 Simplex**（每倍频独立实例，ContinentField 先例）。8 倍频 f=2/p=0.5 保留。
5. **PRNG 统一为 SplitMix64(SEED)**（原版 srand/rand + mt19937(random_device)——后者固定种子也不可复现）。
6. **cascading.fs 沙堆沉积不做**（原作者裁定）：heightmap = 最近段高度（背景 0）+ 3 次 3×3 盒式模糊近似其 25×2 次级联。
7. 边缘扩散按 CLAMP_TO_EDGE（TinyEngine 纹理包装方式无法从本仓库核实，假设已记录）。

## 六、世界寿命（重要口径）

**该物理没有阻尼**：板块速度是纯积分（无摩擦），平均速度实测 500 步 ~0.8 → 4000 步 ~7 px/步
→ 万步级别全部板块冲出地图（无 wraparound，出界即死），世界死亡
（20000 步终态：背景 100%、热场冷透、平均速度冻结 12.3 px/步）。
参考项目的交互演示同样如此——它的截图全部是"生前"（几千步内）拍的。

⇒ **形态基准口径 = 2000~5000 步**；>万步的输出没有意义。接手方不要试图"修"这个寿命，
除非要做的是桥接设计（那本来就是下一个独立课题）。

## 七、下一步（桥接，未开始）

把有限模拟（256²、8192 段、迭代）转成"符合我们需求的确定性流式生成构造地形"
（用户原话）。本复刻只提供：形态目标图 + 公式全集 + 怪癖语义。
已知冲突（交接包 §7.3）：有限域 vs 无限、迭代 vs O(1)、随机游走 vs 逐位可复现。
架构约束见 `audit/GeoGenesis-构造基底架构设计-20261112.md`（A1–A4、S1–S5）。
