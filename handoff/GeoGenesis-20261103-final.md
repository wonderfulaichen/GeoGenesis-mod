# GeoGenesis 2026-11-03 定稿交接

> **冷启动入口。** 本轮定稿＝「`morph-coastal` 完整地形数据 + 青色水面色带渲染器」。
> 生产代码（`src/main`）全程零改动；定稿物是**诊断沙箱**的稳定产物，不是已接入生产的生成器。
>
> 时间标记：本文件所有 SHA／计数都是 2026-11-03 检查点，引用前**现测**。

---

## 〇、一句话结论

定稿的是**一份可复现的地形高度数组**和**一套配色**，不是某个提交号：

| 定稿物 | 位置 | SHA-256 |
|---|---|---|
| 地形数据（1401²，步 5） | `forge-1.20.1-47.4.10-mdk/build/main-verify-restore/out/morph-coastal.f64be` | `7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238` |
| 定稿图（渲染成品） | `handoff/attachments/commit-selection-20261103/height-morph-coastal-d47de74.png` | `571698EE3E982894066277202E8B58C4E215D21055946BD5CFAE5EE44BC473C6` |
| 渲染器 | `tools/cmp-render-versions.py` | 见 §四 |

⚠ **这三个提交（722198b / c165e92 / d47de74）的 `morph-coastal` 输出逐位相同**
（同一 SHA `7BE04183…`）。所以"选提交"在形态上没有可选项，真正被定稿的是
**这套青色水面色带 + 这份地形数据**。这一点如果写错，会让接手方以为换提交能换出不同地形。

---

## 一、必须分开写的两个"水占比"

这是本轮最容易出错的地方，**两个数不能互相替代**：

| 口径 | 水域 | 陆地 | 采样 | 含义 |
|---|---|---|---|---|
| **完整高度 Y<63** | **35.8131%** | 64.1869% | 7000 块窗口 / 步 5 | 真实地形水面，定稿图上的蓝绿分界 |
| 基线侧标签（`+0.30`） | 66.9330% | 33.0670% | 56000 块 / 步 40 | 离散抽签结果，`heightEvaluated:false` |
| 基线侧标签（`+0.30`） | 63.4776% | 36.5224% | 7000 块 / 步 5 | 同上，近窗口 |
| 基线侧标签（零偏移） | 24.3005% | 75.6995% | 56000 块 / 步 40 | 对照 |

**为什么差这么多**：`+0.30` 的 `landThreshold` 只作用在**离散抽签层**
（`CoherentRegionDistribution.seaProbability`），决定"这个格抽成陆还是海"；
它**不进入** `OceanAssemblyCandidate` 的连续高度剖面。所以：

- 标签图看到 66.9% 是海；
- 实际地形图水面只有 35.8%；
- 交接文档里把这两个数混写 = 事实错误。

---

## 二、地形数据规格

```
文件      morph-coastal.f64be
格式      float64 大端（big-endian），行优先（z 为行，x 为列）
尺寸      1401 x 1401
窗口      7005 x 7005 块（step 5），中心 [-140, 137]
原点      [-3642.5, -3365.5]
种子      9139912035078620160
海平面    Y = 63
高度范围  5.1449 ~ 277.8583
中位/ P90 79.41 / 146.21
海陆      水 35.8131% / 陆 64.1869%
```

**臂含义**：`morph-coastal` = 18 配方形态过渡（`FullMorphLand`）接入完整海陆链
（`OceanAssemblyCandidate` + `CoastalDetail`），**无群岛**。
不加 `enableShallowDetail`，不加 `islands.delta`。

---

## 三、生产代码状态（重要）

```
git diff d47de74 -- forge-1.20.1-47.4.10-mdk/src/main   # 空
```

即：`91c239c → d47de74` 全程 **`src/main` 逐字节未变**。定稿地形完全位于
`src/diagnostics`，**没有任何生产生成器被接上**。

交接给朋友时必须说清：
- 这是一套**可复现的沙箱地形**，不是可直接替换 `ChunkGenerator` 的生产代码；
- 要接生产，另需一轮"沙箱→生产"的接线授权与验收（不在本轮范围）。

---

## 四、渲染器与光照（踩过的坑）

### 4.1 定稿渲染器

`tools/cmp-render-versions.py`，色带：

```
STOPS  = [-64, 0, 50, 62.999, 63, 95, 150, 220, 320]
COLORS = [(12,37,72) … (42,103,135), (90,175,182) ← 水下青色, 
          (73,123,69) 陆地绿, (119,150,85), (148,128,93), (153,150,140), (245,245,245)]
```

**青色 `(90,175,182)` 落在 Y=62.999**，是这套配色的识别特征（也是本定稿图的视觉签名）。

### 4.2 光照方向事故（已修复，有守卫）

本轮曾出现**渲染反向**：早期版本 `shade()` 写成 `(-gx - gz)`，梯度符号与项目
标准相反，导致**山脊被读成沟谷**。

正确方向（项目 `render-islands-big.py:19,34`）：
```python
LIGHT = np.array([-0.5, -0.35, 1.0]); LIGHT /= norm(LIGHT)
lit = clip((-gx*L[0] - gz*L[1] + L[2]) / sqrt(gx²+gz²+1), 0, 1)
```

**守卫**：`tools/verify-light-convention.py` 用"圆锥 + 坑"已知形状断言
（圆锥必须读作凸起、坑必须读作凹陷）。现有三个渲染器全部 PASS：

```
python -B tools/verify-light-convention.py    # exit 0 = PASS
```

⚠ 以后**新增任何 hillshade 必须加进这个守卫**，并且不要靠肉眼看截图判断方向。

### 4.3 为什么水面着色容易造成"凹陷错觉"

项目渲染器对水陆用不同明暗系数：`shade_w = 0.72 + 0.28*lit`（水面几乎不随地形变化）、
`shade_l = 0.28 + 0.80*lit`（陆地强烈跟随地形）。大片平坦亮色水面会像一块台地，
把陆地反衬成凹陷 —— **这是配色效应，不是光照反向**。判断方向只能靠 §4.2 的圆锥测试。

---

## 五、已被否定的路线（不要重走）

### 5.1 B1：把 `+0.30` 接到连续高度链（**实测 FAIL，已清理**）

用户曾要求"把 +0.30 真正接入完整高度链"。实现方式是在 `OceanAssemblyCandidate`
里加 `landBias`，令 `edge` 用的 `c` 减去 0.30。**实测结果推翻了假设**：

| 指标 | 结果 |
|---|---|
| 水占比 | 35.8177% → 61.1933% |
| 陆→海 | 498,073 px（方向正确） |
| 海→陆 | 0 |
| **仍露水上的陆地高度变化** | **317,467 px**，最深 **−179.03 格** |
| 按高度档平均下沉 | Y[100,150) −12.83；Y[150,200) −27.97 |

**根因**：`OceanAssemblyCandidate.coastalProfile` 里
```java
double t = clamp01((edge - cpShore()) / w);
return shoreShape(t, cliff, landB);      // 高度直接由 edge 决定
```
降 `edge` = 把整块陆地从"岸线"往"内陆"推移 ⇒ 陆地高度被整体压低。
**在 `edge` 上减偏置，数学上不可能只动海岸带、不动内陆。**

失败证据（保留，不要当通过）：
- `handoff/attachments/b1-wide-sea-failed-20261103/b1-experimental-arm.diff`
- `.../wide-sea-coastal.f64be`（15334 KB）
- 代码已 `git checkout HEAD --` 还原，`wide-sea` / `landBias` 全仓 0 处。

若将来仍要"更多海且不动陆地高度"，唯一可行方向是**只移动岸线、内陆保持原值**，
但那属于改地形形态，不再是"海陆判定单变量"，需重新方案与授权。

### 5.2 其它未通过实验

- 远坐标 float 反 warp：正典 (1e6,1e6) 残差 7.1913 > 0.02，**未修**；
  不承诺无限世界稳定。
- S4 坐标矩阵：4 seed 688 次反解，204 次残差失败。
- 分形/多频大陆、配方级海岸线等多轮实验：均未达验收，详见
  `handoff/attachments/` 下各 `*-2026110X/` 目录的 `run-notes`。

---

## 六、四周皆海的大面积图（旁证）

`+0.30` 标签口径下，56000 块窗口里存在一块**四周被海完全包围**的陆块：

| 项 | 值 |
|---|---|
| 位置 | `handoff/attachments/surrounded-continent-20261103/surrounded-continent-tag-56000.png` |
| 陆块尺寸 | 18,000 × 17,680 块 |
| 是否触窗口边 | 否（`touches_window_edge=false`） |
| 窗口 | 27,600 块宽，步 40，1401² |

对照片（零偏移）：全图仅 5 个陆块、最大占陆 99.93% 且触边 = "世界几乎全是陆地"。

⚠ 这是**基底标签图**，不是 Y=63 水线；"四周是海"仅限**该采样窗口内**，不是无限世界证明。

---

## 七、复现步骤

```powershell
cd <仓库根>

# 1) 编译主工作区 diagnostics + 跑 7 臂导出（约 90 s）
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-main-gallery.ps1 `
  -Main FullMorphGallery -OutName clean-check

# 2) 一键验收定稿：数据 SHA + 重渲染 + 与定稿图逐像素比对（必须 exit 0）
python -B tools/verify-final-render.py

# 3) 光照守卫（必须 exit 0）
python -B tools/verify-light-convention.py
```

`verify-final-render.py` 做三层校验：数据 SHA-256 必须等于 `7BE04183…`、
用交付着色器重渲染、与 `height-morph-coastal-d47de74.png` 逐像素比对
（要求 `changed px = 0`）。任一步不符即非零退出。
⚠ 它默认读 `build/main-clean-check/out`，与第 1 步的 `-OutName` 对应。

依赖：JDK 21（`D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot`）。

⚠ **基线 classpath 不在仓库里**（2026-11-03 从索引撤除）：
`baseline/classes/`（编译产物）、`baseline/diagnostics/`、`classpath-jars.txt`（本机绝对路径）
都**没有入库**。新克隆只有定稿数据 `morph-coastal.f64be` 和 `baseline/config-input.toml`。

要重跑 Java 侧，需自己重建基线：先用 `gradlew.bat build` 编译 `src/main`，
再把 `build/classes/java/main` 指到 `-cp` 首位，并补 Forge 依赖 jar。
`classpath-jars.txt` 里的路径形如 `D:\.gradle\caches\...`，是**本机专属**，不能照抄。

### 7.1 ★ 2026-11-03 历史重写（重新 clone 必读）

当天对仓库做了一次**历史重写**：把 `handoff/attachments` 下 591 个大实验图
（448.5 MB）从**全部历史**移除，仅保留 2026-11-03 的 14 张定稿图。
`.git` 由 407 MB 降至 **42.5 MB**。

| 项 | 值 |
|---|---|
| 清理文件数 | 591（`.png` + `.f64be` + `.f64be.raw`） |
| 释放体积 | 448.5 MB（工作区）/ 365 MB（.git） |
| 保留 | 14 张定稿图、全部 csv/json/txt/log/py 文本证据 |
| 方法 | `git filter-branch --index-filter`（分批 `git rm --cached`） |
| 未使用 | 未手工删除任何 pack 文件 |

**对已有克隆的影响**：旧的本地副本与远程已分叉，`git pull` 会失败。必须重新 clone：

```bash
git clone https://github.com/wonderfulaichen/GeoGenesis-mod.git
cd GeoGenesis-mod && git checkout feat/hydrology-target
```

**注意**：2026-11-03 之前的所有提交哈希均已改变，旧 SHA 无法解析；
被移除的 591 张图不在历史里了，需要时从旧克隆或备份取。
`main` 分支仍是旧的、**不含定稿**，必须检出 `feat/hydrology-target`。

---

## 八、七个臂的 SHA（还原后实测，全部匹配历史基准）

```
baseline-coastal        5A8BB721BA191C79
baseline-islands        94BA83F7359526DF
morph-coastal           7BE0418369D097CE   ← 定稿
morph-islands           955BA2CC02A6DEE7
plateau-only-coastal    D8B0802EAC41ECF2
plateau-only-islands    4B9D3B8B6C5F2396
shelf-coastal           2604533841943D21
```

`FullMorphGallery` 内置硬门禁：`baseline-coastal` / `baseline-islands` SHA 不符即
`AssertionError`；`shelf-coastal` 另有"只许改水下、不许改陆地"的单变量归因断言。

---

## 九、Git 状态

```
分支      feat/hydrology-target      ← 必须检出这条，main 不含定稿
HEAD      fb833613cae90c970547e2cd86e49cad9ff8759b（2026-11-03 历史重写后）
远端      https://github.com/wonderfulaichen/GeoGenesis-mod.git
已推送    是（重写后强推，远端 HEAD 与本地一致）
.git      42.5 MB（重写前 407 MB）
工作区    干净
```

⚠ **2026-11-03 做过历史重写**：所有旧提交哈希已变（例如原来的 `d47de74`
现在是 `b4d4410` 之后的新链），旧 SHA 无法解析。详情见 §7.1。

工作区已还原到 HEAD 的干净状态：`OceanAssemblyCandidate.java` /
`FullMorphGallery.java` / `CoherentMorphWorld.java` / `ShallowRegionMatcher.java`
均无未提交改动；M1a 阈值实验的 `M1ThresholdHeight*` 两个文件已删除
（它们依赖的重载不入库，删除后 diagnostics 可正常编译）。

---

## 十、未完成 / 不承诺

- 远坐标数值几何（float 反 warp 失败未修）
- 沙箱 → 生产的接线与验收
- 生产配置、默认值、`CACHE_SCHEMA_VERSION` 本轮**均未改动**
- 未启动 Minecraft 实机验证；本定稿是**离屏渲染**结论
- "四周皆海"仅为采样窗口内结论

---

## 十一、证据索引

| 内容 | 路径 |
|---|---|
| 定稿图 | `handoff/attachments/commit-selection-20261103/height-morph-coastal-d47de74.png` |
| 三提交同图对照 | `handoff/attachments/commit-selection-20261103/height-morph-coastal-panel.png` |
| 四周皆海（标签） | `handoff/attachments/surrounded-continent-20261103/` |
| 大面积海陆比 | `handoff/attachments/large-area-sea-land-20261103/` |
| 青/深蓝光照对比 | `handoff/attachments/light-convention-proof-20261103/cone-vs-pit.png` |
| B1 失败证据（diff） | `handoff/attachments/b1-wide-sea-failed-20261103/b1-experimental-arm.diff` |
| 验收工具 | `tools/verify-final-render.py`（定稿）、`tools/verify-light-convention.py`（光照） |

⚠ **以下已在 2026-11-03 历史重写中移除**（见 §7.1），不再存在于仓库：

| 已移除 | 说明 |
|---|---|
| `backup-20261103-pre-handoff/` | 工作区完整备份（备份对象本身） |
| `b1-wide-sea-failed-20261103/wide-sea-coastal.f64be` | 15 MB B1 失败臂原始数据（保留了 diff） |
| `large-area-sea-land-20261103/raw-smooth/` | 60 MB 中间数据（可由 M0 数据重算） |
| 其余 591 张大实验图 | 2026-10-30 ~ 11-02 各轮淘汰实验的 PNG |

定稿相关证据（上面表格里的 14 张图 + 全部 csv/json/txt 文本）**完整保留**。
