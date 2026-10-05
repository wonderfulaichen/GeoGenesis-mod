# 环境速查（接手前必读）

> **先读 [`DOCS-STATUS.md`](DOCS-STATUS.md)** —— 本项目有两条并行水文代码线，
> 文档互相矛盾。本文描述**当前代码线**（`fix/port-lake-p2-2`）。
>
> ⚠ **§2 的正典半径 700 是定稿值，勿改。**
> 2026-10-05 曾被误改为 1000（"用户要求放大"是误判），用户当即纠正"怎么又被改面积了"，
> 已回退。改它会让新旧图不可比，也让照本文档传参的人对不上。

> **为什么单独成文**：环境信息（哪个分支、worktree 在哪、跑哪个命令、带什么开关）
> 是最容易被遗忘、又最容易导致"白跑一轮"的东西 —— 历史已发生多次：
> 只查当前分支就误判"探针从未存在"、传错半径把 1401×1401 画成 601×601。
> ⇒ 开工前先读本文，再读 `HANDOFF.md`（成果与待办）。

## 0. 仓库 / 分支拓扑（★ 最易忘）

仓库根 = `D:\Office software\Development Project\GeoGenesis-mod`（**不是** mdk 目录）。
git 路径参数要带 `forge-1.20.1-47.4.10-mdk/` 前缀。

| 分支 | 内容 | 备注 |
|---|---|---|
| `fix/port-lake-p2-2` | **主工作区当前分支**（湖泊 P2-2 移植 + 河道只下挖铁律） | 有 `worldgen/hydrology/` 生产代码 |
| `preserve/before-rollback` (`abdc7c1`) | **本轮物理水文模型的全部产物**（72 个 `model/` 文件） | 回退 `c12f76e` 前的保全点 |
| `fix/continuous-river-coherence` (`040069b`) | 在 preserve 之上：河道连续性修复 + 诊断 | **本轮成果** |
| `main` | 仅 5 个 `model/` 文件（`DiffusiveWaveModel` 等 P1 原型） | |
| `legacy-hydrology-archive` | 阶段 D 删除的旧水文链归档 | |
| `rollback/c12f76e` | 回退目标点 | |

> ⚠ **全历史无任何"删除 `model/` 目录"的提交** —— 它只是不在 `main`/`fix/port-lake-p2-2` 上。
> 若某处"找不到"，用 `git ls-tree -r --name-only <分支> -- ':/forge-.../hydrology/model'` 查，
> **不要**只在当前分支找。

## 1. 本轮工作区（worktree）

物理水文模型的改动**不在主工作区**，在独立 worktree：

```
D:\Office software\Development Project\GeoGenesis-mod\_model_wt\forge-1.20.1-47.4.10-mdk
分支 fix/continuous-river-coherence   HEAD = 040069b（未推送）
```

创建/进入：
```powershell
cd "D:\Office software\Development Project\GeoGenesis-mod\_model_wt\forge-1.20.1-47.4.10-mdk"
```
删除（不再需要时）：`git worktree remove "<上面那个 _model_wt 路径>"`

> ⚠ **worktree 是 detached HEAD 的常见来源**：`git worktree add <path> <branch>` 会 checkout。
> 若传的是 commit（`abdc7c1`）⇒ detached ⇒ **提交前必须先 `git checkout -b <新分支>`**，
> 否则提交可能随 worktree 删除而丢失（本轮已发生一次，侥幸未丢）。

## 2. 主渲染探针：连续河流网络

```powershell
cd "D:\Office software\Development Project\GeoGenesis-mod\_model_wt\forge-1.20.1-47.4.10-mdk"
$env:JAVA_TOOL_OPTIONS="-Dstdout.encoding=UTF-8"      # 中文判据不乱码（必需）

# ★ 正典规格：半径 700 ⇒ 1401×1401，1 像素 = 1 方块
.\gradlew.bat runCanonicalViewProbe "-PprobeArgs=9139912035078620160 -140 137 700 0.004 continuous" --console=plain --offline

# 快速小窗（r=300 ⇒ 601×601，约 45 秒）
.\gradlew.bat runCanonicalViewProbe "-PprobeArgs=9139912035078620160 -140 137 300 0.004 continuous 400000" --console=plain --offline
```

`probeArgs` 位置参数（顺序固定，**别改**）：
`seed cx cz radius precip riverModel streamSamples`

| 参数 | 正典值 | 说明 |
|---|---|---|
| seed | `9139912035078620160` | |
| 中心 | `-140 137` | `ViewSpec.CENTER_X/Z` |
| **radius** | **700** | `ViewSpec.RADIUS` ⇒ 1401×1401。**传 300 会让图小 5.4 倍** |
| precip | `0.004` | 传 `1.0` 会让 channel 涨到 73%（全青） |
| riverModel | `continuous` | `d8` = 对照基线 |
| streamSamples | 省略 = 600000 | 小窗可给 400000 提速 |

输出：`build/hydro-reassessment/canonical-<时间戳>/canonical.png` + `metrics.txt`

### 开关（`-D` 传给探针 JVM）

| 开关 | 默认 | 作用 |
|---|---|---|
| `hydro.hillshade=true` | **关** | 底图切回 NW 光源 hillshade。**默认关是对的** —— 见下 |
| `hydro.channelUseRawFlux=true` | 关 | 成河判定用原始流量（默认 3×3 平滑，实测更好） |

> ⚠ **`JAVA_TOOL_OPTIONS` 会被 gradle 的 fork JVM 继承**，直接 `$env:` 设置即可生效。
> 若探针行为"改了没生效"，先怀疑这里。

## 3. ★★ 渲染陷阱（本项目已误判 3 次）

**hillshade 会把谷画成脊。** `ViewSpec.shade()` 是 NW 光源山体阴影，
亮/暗取决于**朝向**，与高低无关。因此：
- 看到"河流走在山坡上"⇒ **是光照错觉**，河实际在谷底；
- 本项目曾据此误判"河流走在山脊上"（实为 99.9% 在谷）。

**⇒ 判定形态必须用无光照等高线**（`ViewSpec.contour`，本轮新增，默认底图）。

其它渲染纪律：
- **半径用正典 700**，别随手传 300（`ViewSpec` 存在的唯一目的就是防各探针各写尺寸）。
- 三图对比时**必须共用同一色标**（本轮 `RiverBasisCompareViewProbe` 的做法）。
- 项目铁律：**渲染图是伪影/形态的最终依据**；但若渲染方式本身有系统性误导
  （hillshade），该依据失效 ⇒ 必须先换渲染方式。

## 4. 诊断开关（在 `model/` 类里，A/B 用）

| 开关 | 所在类 | 默认 | 作用 |
|---|---|---|---|
| `channelUseRawFlux` | `ContinuousRiverNetwork` | false | 成河判定：原始流量 vs 3×3 平滑 |
| `networkErodedBasis` | — | — | 未保留（试验失败已撤，见 commit 说明） |

## 5. 本轮三次"我自己的假设被实测证伪"（勿重犯）

| # | 假设 | 实测结论 |
|---|---|---|
| 1 | "3×3 平滑把窄河腰斩"（据 gap 局部读数） | **反了**：平滑 159 连通分量 vs 原始 486，平滑好 3.1× |
| 2 | "加样本数能消除虚线" | **无效**：样本 ×10，GAPS 31→33 不变 ⇒ 是顺序依赖不是噪声 |
| 3 | "流线死在未决高台是第三个缺陷" | **不成立**：死亡细分 100% 是内流盆地（物理正确），坡面缺陷 0 |

⇒ **教训：局部证据（几个 gap 格）不能推全局；下结论前先做同参 A/B。**

## 6. 主工作区（生产代码）常用命令

```powershell
cd "D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk"
.\gradlew.bat compileJava --console=plain --offline
.\gradlew.bat runWorldgenGate --console=plain --offline        # 真门禁（有退出码）
.\gradlew.bat build --console=plain --offline
```

⚠ 探针 task 传参：`probeArgs` 含空格/负数时，**必须整体带引号**：
`"-PprobeArgs=seed -140 137 300"`。
用 `Start-Process -ArgumentList` 会把 `-140` 拆成独立参数 ⇒ `Unknown command-line option '-1'`（已踩过）。

## 7. 提交规范

- 中文提交信息**用文件**：`git commit -F <file>`（避免 PowerShell 转码）。
- worktree 里提交前**先建分支**（见 §1）。
- 未经用户批准**不要 `git push`**。
- `docs/` 与 `参考/` 被 `.gitignore` 忽略 ⇒ 新文档放**仓库根**。

---
*环境速查。成果与待办见 `HANDOFF.md`；项目铁律见 `AGENTS.md`。*
