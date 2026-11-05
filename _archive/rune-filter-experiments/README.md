# Rune 侵蚀滤镜：实验件归档（2026-11-06）

> 只读归档。**这里的代码不参与编译、不在任何 gradle task 里。**

## 先分清两件事（别把生产代码一起删了）

**仍在 `src/main` 里（会打进 jar），不要删**（不在本目录）：

```
src/main/java/com/geogenesis/worldgen/erosion/RuneErosionFilter.java
  └─ 被 src/main/java/com/geogenesis/worldgen/terrain/morph/MorphSingleShaper.java 使用
     └─ MorphSingleShaper 目前只被诊断探针构造（morph 算子尚未接生产链）
src/main/resources/META-INF/licenses/RUNE-ADVANCED-TERRAIN-EROSION-{MPL2,SOURCE}.txt
  └─ MPL-2.0 §3.1/§3.4 义务：RuneErosionFilter 是 Rune Skovbo Johansen
     "Advanced Terrain Erosion Filter" 的 Java 改写，按 MPL-2.0 分发
```

`RuneErosionFilter` 的合成核验 **仍在**：`src/diagnostics/java/com/geogenesis/worldgen/erosion/RuneErosionCheck.java`，
由 `tools/verify-base-core.gradle` 的 `verifyBaseCore` 任务执行（`RUNE-EROSION-CHECK PASS`）。

## 本目录是什么

RUNE 滤镜作为**形态实验**（预侵蚀 / 信号侧 / 受控对照）的一次性工具链。
它们服务于「预侵蚀作为粒子侵蚀低配替代品」那条线——该线已由
`handoff/GeoGenesis-20261104-preeorder.md` §3 证伪并终止：

- 骨架线本就是按最陡下降追的，与粒子侵蚀撞同一批沟（带内中位切深 +1.58Y，多出的覆盖 69% 在带外成碎条纹）
- L3「近零开销 0.047s」不含骨架距离场，生产侧 `fillRiverDistance` 恒 `+∞`、无折线数据源

| 文件 | 原位置 | 作用 |
|---|---|---|
| `diagnostics/PreErosionGuideProbe.java` | `src/diagnostics/.../erosion/` | 用 Rune 滤镜做预侵蚀引导高度 |
| `diagnostics/RuneControlledComparisonProbe.java` | `src/diagnostics/.../terrain/morph/` | 作者参数 vs 候选参数的受控对照 |
| `diagnostics/SignalSideRuneProbe.java` | 同上 | 信号侧 / 后置 Rune 的 O2 对照 |
| `diagnostics/preeorder-rune.py` / `-v2.py` | `src/diagnostics/` | 出图与形态指标（转折角 / 毛糙度） |
| `tools/render-rune-original.py` | `forge-.../tools/` | Rune 原版链路渲染 |
| `tools/run-rune-controlled.py` | 同上 | 编排 `RuneControlledComparisonProbe` |
| `tools/run-signal-side-rune.gradle` | 同上 | `-I` 注入式 task：`runSignalSideRuneProbe` |
| `tools/render-rune-controlled.py` | 仓库根 `tools/` | 受控对照出图 |

⚠ **恢复方法**：这些脚本里写死了原路径（`tools/...`、`src/diagnostics/...`）。
要用就得整体搬回原位置，不能只拷单个文件。

## 关联档案

- `_archive/ridge-skeleton/`：骨架滤镜本体（`RidgeValleyErosion` + 适配器 + 探针），2026-11-06 暂停
- `audit/GeoGenesis-旧侵蚀路径清理清单-20261106.md` §7：本轮清理规模与验证
- `build/preeorder-rune-20261104/`、`build/particle-preeorder-20261104/`：实验产出（`build/` 被 .gitignore 忽略，只在本机）