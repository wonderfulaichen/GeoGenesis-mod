# MorphRoundedMountain：M1 圆化变体，未接线（2026-11-06 归档）

> 只读归档。**不参与编译、不在任何 gradle task 里、不进 jar。**

## 为什么在这里

`MorphRoundedMountain`（原 `src/main/.../terrain/morph/`）是 M1_GEO 山脊的圆化实验：
只把 `M1_GEO` 的 `PerlinRidge` 折痕用圆化绝对值处理，其余节点复用原实现。

```text
raw = Warped(Mul(PerlinRidge(q,1/610,4,2.35,1.15), AlphaMix(Perlin(q+1,1/180,4),0.30)),
             Perlin(q+2,1/350,1), Perlin(q+3,1/350,1), 150)
round(n) = (sqrt(n²+r²)-r) / (sqrt(1+r²)-r)
```

**编译期引用为 0**：`src/main` 与 `src/diagnostics` 都不再引用它。
它唯一的调用方是已归档的
[`_archive/ridge-skeleton/diagnostics/SingleShaperProbe.java`](../ridge-skeleton/diagnostics/SingleShaperProbe.java)
（4 处：`recipes()` / `guideRecipes()`）——那个探针本身也依赖 `MorphSingleShaper`。

## 许可证归属（不要丢）

原 `META-INF/licenses/MORPH-PACKAGE-NOTICE.txt` 里它这一条已随归档移除，
归属声明在此保留：

```text
    MorphRoundedMountain.java   M1-only rounded-abs variant of the FTF
                                PerlinRidge recurrence; same MIT lineage
```

即：属于 FreeTerraForged 派生链（MIT），与 `MorphNoise` / `MorphScale` 同源。
恢复该文件到 `src/main` 时，**必须把这两行同时加回 `MORPH-PACKAGE-NOTICE.txt`**，否则 jar 的归属声明不完整。

## 恢复方法

```text
_archive/morph-unused/MorphRoundedMountain.java
  → forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/morph/MorphRoundedMountain.java
```

它是包级私有类（`final class`），恢复后仍需 `SingleShaperProbe` 之类的诊断探针来驱动；
仅恢复它不会改变任何生产产出。