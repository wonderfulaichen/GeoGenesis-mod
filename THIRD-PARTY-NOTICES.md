# THIRD-PARTY NOTICES

This project (GeoGenesis Mod) contains code **derived from** the third-party projects
listed below. Each entry states the upstream project, its copyright line, the license it
is distributed under, and which parts of this repository carry the derived material.

This file does **not** change the project's own license (All Rights Reserved).
It exists to satisfy the notice-retention condition of the licenses below, which requires
the copyright notice and permission notice to be included in all copies or substantial
portions of the Software.

The machine-checked status, open items and reproduction evidence live in
`audit/沙盘与预览参考项目许可核查-20261111.md`.

---

## 1. yardsite

- Copyright: `Copyright (c) 2026 CYun_CYer`
- License: MIT
- Upstream LICENSE SHA-256: `f17c65cdd76c59b0c574891777efb66b18931f3a615eae258d1e02755be6c76e`
- Derived portions in this repository (`forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/`):
  - `client/preview/ProgressiveSampler.java`, `SandboxShade.java`, `SandboxLod.java`,
    `TerrainFieldData.java`, `TerrainViewMode.java`, `ViewTransition.java`, `PreviewDisplay.java`
  - `client/preview/raster/SandboxRasterizer.java`
  - `client/preview/sandbox/SandboxGeometry.java`
  - `client/reveal/` (DescentPath, PerspectiveCamera, RevealCameraHook, RevealDataHolder,
    RevealSchedule, RevealScreen, RevealScreenHook, RevealWorldRenderer)
- Nature: the sandbox terrain view, the plane<->sandbox view transition and the reveal
  animation were ported from yardsite's `SiteMapSampler`, `SiteTerrainRenderer`,
  `SiteRevealWorldRenderer`, `SiteRevealScreen` and `YardSiteScreen`, with local changes.

### MIT License text (verbatim copy of yardsite/LICENSE)

MIT License

Copyright (c) 2026 CYun_CYER

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
