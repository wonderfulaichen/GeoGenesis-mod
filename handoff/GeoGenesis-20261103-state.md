# GeoGenesis 2026-11-03 状态交接

> **冷启动入口。** 接手方默认是**有经验的工程师，且不能随时问原作者**。
> 本文只写可核实的事实，每条都能落到文件 / 命令输出上；未能核实的标 `未验证`。
>
> 已有两份更细的文档，本文不重复：
> - [GeoGenesis-20261103-final.md](GeoGenesis-20261103-final.md) —— 定稿结论、实验否决记录、复现步骤
> - [GeoGenesis-20261103-CSharp-port.md](GeoGenesis-20261103-CSharp-port.md) —— C# 移植说明（给下游开发者）
>
> 本文回答的是：**现在处于什么状态、怎么验、有哪些坑、接下来谁做什么。**

---

## 一、背景与目标

GeoGenesis 是 Minecraft Forge 1.20.1 的地形模组（自定义 `ChunkGenerator` + `BiomeSource`）。
2026-10-30 ~ 11-03 这一轮做的是**新版地形沙箱**：18 个陆地配方 + 5 个海洋配方、
形态过渡、海陆分布，目标是产出一版用户认可的地形，交给朋友用 C# 在自己的游戏里复现。

**本轮结论：定稿了一份沙箱地形数据 + 一套配色**，不是生产接入。

---

## 二、当前状态

### 2.1 已完成

| 项 | 状态 | 证据 |
|---|---|---|
| 定稿地形数据 | ✅ | `morph-coastal.f64be`，SHA `7be0418369d097ce…` |
| 定稿配色（青色水面色带） | ✅ | `tools/cmp-render-versions.py` |
| 定稿图 | ✅ | `handoff/attachments/commit-selection-20261103/height-morph-coastal-d47de74.png` |
| 一键验收脚本 | ✅ | `tools/verify-final-render.py`（exit 0 = 未破坏） |
| 光照方向守卫 | ✅ | `tools/verify-light-convention.py`（圆锥断言） |
| 仓库瘦身 | ✅ | `.git` 407 MB → **42.5 MB** |
| 交接文档 | ✅ | 本文件 + final + CSharp-port |

### 2.2 ★ 看起来完成但其实没做的（重点）

| 项 | 真实情况 |
|---|---|
| **生产接入** | ❌ **完全没有**。`src/main` 全程零改动，沙箱在 `src/diagnostics` |
| **Minecraft 实机验证** | ❌ 一次都没跑。所有结论来自**离屏渲染** |
| **无限世界** | ❌ 未证明。1401² 窗口内正确，不代表任意坐标 |
| **远坐标** | ❌ **已知失败**。`LocalBoundaryGeometry` 反 warp 在 1e6 块处残差 7.19 > 阈值 0.02 |
| **水文系统** | ⚠ 属另一条线，本轮未动。见 `HYDROLOGY.md`，但其结论早于本轮 |
| **`+0.30` 进高度链** | ❌ **实测失败**。会把陆地整体压低（中位 −12.3 格，最深 −179），见 final 文档 §五 |

### 2.3 定稿到底定了什么

三份文档反复强调的一件事，**这里再说一遍**：

```
722198b / c165e92 / d47de74 三个提交的 morph-coastal 输出逐字节相同
（同一 SHA 7BE04183…）
```

所以「选哪个提交」在地形形态上**没有可选项**。真正被定稿的是：
**这份高度数组 + 这套青色水面色带**。交接时若写成「选了某个提交」，是错的。

---

## 三、环境与依赖

| 项 | 值 |
|---|---|
| JDK | 21（`D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`），源码 target 17 |
| Gradle | `gradlew.bat`（工程自带 wrapper） |
| Python | 3.8（脚本用到 numpy / Pillow / scipy） |
| 工程根 | 仓库根；**真实 Forge 工程在 `forge-1.20.1-47.4.10-mdk/`** |
| Forge | 1.20.1 / 47.4.10 |

### ★ 不在仓库里的依赖（新克隆拿不到）

| 依赖 | 位置 | 说明 |
|---|---|---|
| `baseline/classes/`（517 个 .class） | `forge-1.20.1-47.4.10-mdk/build/terrain-upgrade/baseline/` | 编译产物，已从索引撤除 |
| `baseline/diagnostics/` | 同上 | 同上 |
| `classpath-jars.txt` | 同上 | **本机绝对路径**（`D:\.gradle\caches\...`），不可照抄 |

要重跑 Java 侧需自己重建：`gradlew.bat build` 编译 `src/main`，
再把 `build/classes/java/main` 指到 `-cp` 首位并补 Forge 依赖 jar。

⚠ **只想看图的话不需要这些**：`verify-final-render.py` 只读 f64be + PNG，纯 Python。

---

## 四、关键文件地图（改 X 要看 Y）

| 想改什么 | 看这些文件 |
|---|---|
| 定稿的地形形态 | `FullMorphLand.java`（706 行）、`RecipeMorphCatalog.java`（18 配方） |
| 定稿的海陆判定 | `OceanAssemblyCandidate.java`（`heightBlocks` / `coastalProfile`） |
| 形态过渡权重 | `RecipeBoundaryWeights.java`、`NodeMorphEngine.java` |
| 区域划分 / Voronoi | `RegionPartition.java`、`LocalBoundaryGeometry.java`（1146 行，最难） |
| 噪声原语 | `RefNoise.java`（722 行，FTF 派生，MIT） |
| **定稿出图入口** | `FullMorphGallery.java` ← 出图从这里进 |
| 定稿配色 / 光照 | `tools/cmp-render-versions.py`（`shade()`） |
| 跨语言移植常量表 | `handoff/GeoGenesis-20261103-CSharp-port.md` §2.3 |
| 生产生成器（未接） | `forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/generator/GeoGenesisGenerator.java`、`worldgen/terrain/CellGenerator.java` |

**依赖方向**：`FullMorphGallery` → `FullMorphLand` + `OceanAssemblyCandidate` → `RecipeJunctionMorph` → `NodeMorphEngine`。
`FullMorphGallery` **不经过** `CoherentMorphWorld`（那是另一条实验链）。

---

## 五、操作 runbook

### 5.1 只验收定稿（最快，纯 Python）

```bash
cd <仓库根>
python -B tools/verify-final-render.py
```

预期输出（exit 0）：
```
PASS: data identical
pixels        : 1962801
changed px    : 0
max channel d : 0
water  35.8131%  land 64.1869%  minY 5.1449  maxY 277.8583
RESULT: FINAL RENDER IDENTICAL - final map NOT broken
```

⚠ 它默认读 `forge-1.20.1-47.4.10-mdk/build/main-clean-check/out/morph-coastal.f64be`。
**新克隆没有这个目录**——先跑 5.2，或把脚本里的 `NEW_DATA` 指向现有数据。

### 5.2 重跑出图（需要 Java）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-main-gallery.ps1 `
  -Main FullMorphGallery -OutName clean-check
```

预期：约 90 s，末尾 `FULL MORPH GALLERY PASS`。
产物：`forge-1.20.1-47.4.10-mdk/build/main-clean-check/out/`，含 7 个臂的 `.f64be`。

⚠ **必须先有 baseline classpath**（见 §三）。
⚠ 脚本会编译**整个** `src/diagnostics`；目录里若有坏文件会整体失败（历史上踩过）。

### 5.3 光照守卫

```bash
python -B tools/verify-light-convention.py     # 必须 exit 0
```

### 5.4 七个臂的 SHA（核对用）

```
baseline-coastal        5A8BB721BA191C79
baseline-islands        94BA83F7359526DF
morph-coastal           7BE0418369D097CE   ← 定稿
morph-islands           955BA2CC02A6DEE7
plateau-only-coastal    D8B0802EAC41ECF2
plateau-only-islands    4B9D3B8B6C5F2396
shelf-coastal           2604533841943D21
```

---

## 六、风险、坑、回滚

### 6.1 不可逆操作

| 操作 | 后果 |
|---|---|
| 强推 `feat/hydrology-target` | 会覆盖远端历史；本次已做过一次历史重写 |
| 删除 `.git/objects/pack/*` | **会让仓库对象库直接失效**（本次已踩，靠备份恢复） |

### 6.2 踩过的坑（按教训价值排序）

1. **`git filter-branch` 参数超长会静默失效**
   一次传 591 个路径 → `Argument list too long` → 但 filter-branch 仍「成功」结束，实际什么都没删。
   正解：分批 `git rm --cached`（每批 80 个）。

2. **`refs/original/*` 会让旧对象永远可达**
   filter-branch 自动把旧历史存成 `refs/original/...`，`gc` 回收不掉。
   必须显式删除这些 ref，之后 `gc --prune=now` 才真正释放。

3. **渲染光照符号写反 → 山脊读成沟谷**
   早期 `shade()` 写成 `(-gx - gz)`，与项目标准 `(-gx*L[0] - gz*L[1] + L[2])` 符号相反。
   肉眼很难发现（看着"也挺合理"）。正解：圆锥/坑形状断言，见 `verify-light-convention.py`。

4. **水面着色会造成"凹陷错觉"**
   水陆用不同明暗系数（水 0.28、陆 0.80），大片亮色水面像台地，把陆地反衬成坑。
   这是配色效应，**不是**光照反向。

5. **PowerShell 重定向会写 UTF-16**
   `git diff > x.patch` 得到 UTF-16LE，`git apply` 读不了（`No valid patches in input`）。
   正解：用 Python `write_bytes(text.encode('utf-8'))`。

6. **`.gitignore` 裸目录规则会屏蔽白名单**
   `build` 排除整个目录 ⇒ 其下所有 `!` 规则失效。必须写 `build/*` + 逐条 `!`。

7. **恢复 `.git` 时会被备份的 remote 污染**
   用 `git clone --mirror` 备份再恢复，`origin` 会指向备份目录。
   恢复后要 `git remote set-url origin <github>`。

### 6.3 回滚

当前**没有本地备份**（备份已按要求删除）。回滚手段只剩：

```bash
# 从远端重新拉（历史重写后的干净状态）
git clone https://github.com/wonderfulaichen/GeoGenesis-mod.git
```

被历史重写移除的 591 张大实验图**已无法从仓库找回**。

---

## 七、待办与决策点

### 7.1 卡在原作者（需业务决策）

| # | 待办 | 说明 |
|---|---|---|
| 1 | `main` 分支归属 | `origin/main` = `186af8c`（旧），`feat/hydrology-target` 领先 131 提交。用户提过「把 feat 改成 main 也行」，**未执行** |
| 2 | 沙箱 → 生产接入 | 需单独方案与验收，本轮未授权 |
| 3 | 远坐标修复 | float 反 warp 失败，需换 double 几何，未做 |
| 4 | 未跟踪探针处置 | `src/diagnostics` 有 **500 个**未跟踪 `.java`（289 个在 `candidate/`，另 211 个在其它包），本轮未提交也未删。它们会被 `run-main-gallery.ps1` 一起编译，**其中若有坏文件会导致整体编译失败**（历史踩过） |
| 5 | `handoff/attachments` 剩余 56.7 MB | 重写后仍保留全部 csv/json/txt 文本证据，是否进一步精简未定 |

### 7.2 可以立刻做（不需决策）

- 按 §5.1 验证定稿
- 按 `CSharp-port.md` 对接 C# 复现
- 读 `audit/新版地形第三方许可核查.md` 确认 MIT 义务

---

## 八、Git 与仓库现状

```
分支      feat/hydrology-target      ← 必须检出这条，main 不含定稿
HEAD      98d98f320e5cc1d852afeb845cd1375c7a964747
远端      https://github.com/wonderfulaichen/GeoGenesis-mod.git
已推送    是（与远端一致）
.git      42.5 MB
工作区    干净（无 M/A/D）
未跟踪    1935 个（多为 build/ 产物、早期探针、备份目录）
```

最近提交：

```
98d98f3  文档：补历史重写说明与重新 clone 指引
fb83361  工具：定稿出图一键验收 verify-final-render.py
b6cbb6b  文档：新版地形对接入口 + 第三方许可核查入库
b4d4410  仓库整理：从索引撤除 517 个 .class
ad1d698  地形定稿：morph-coastal 完整地形数据 + 青色水面色带渲染器
```

### 8.1 重新 clone 注意事项（给已有旧克隆的人）

⚠ 2026-11-03 做过历史重写，**旧克隆已与远程分叉，`git pull` 会失败**。

```bash
git clone https://github.com/wonderfulaichen/GeoGenesis-mod.git
cd GeoGenesis-mod
git checkout feat/hydrology-target      # 必须
```

- 2026-11-03 之前的所有提交哈希**全部改变**，旧 SHA 无法解析
- 被移除的 591 张大图不在历史里了
- `.git` 只有 42.5 MB 是正常的

---

## 九、术语与口径（避免误读）

| 说法 | 准确含义 |
|---|---|
| 「定稿」 | 指那份 f64be + 配色，**不是**某个提交号 |
| 「水域 35.81%」 | 完整高度 Y<63 的水面占比（定稿图上的蓝绿分界） |
| 「海 66.93%」 | **另一个口径**：`+0.30` 基线侧标签，`heightEvaluated:false`。**两个数不可混用** |
| 「M1a」 | `+0.30` 阈值注入 S3 链的实验，**不是定稿**，且实测会让陆地整体变低 |
| 「沙箱」 | `src/diagnostics` 下的实验代码，与 `src/main` 生产生成器无关 |
| 「morph-coastal」 | 定稿臂的名字：18 配方形态过渡 + coastal（无群岛） |

---

## 十、自检清单（接手第一天）

- [ ] `python -B tools/verify-final-render.py` → exit 0
- [ ] `python -B tools/verify-light-convention.py` → exit 0
- [ ] `git rev-parse HEAD` 是否 = `98d98f3`（或更新）
- [ ] `git status` 是否干净
- [ ] 能说清「定稿 = 数据 + 配色，不是提交号」
- [ ] 能说清「水域 35.81% ≠ 标签 66.93%」
- [ ] 知道 `src/main` **没被改过**，定稿不在生产链上
