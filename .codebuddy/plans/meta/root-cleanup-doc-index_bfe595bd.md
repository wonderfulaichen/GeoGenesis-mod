---
name: root-cleanup-doc-index
overview: 中度整理项目根目录：将散落文档按类归入 docs/（design/plans/fixes/archived），废弃侵蚀测试工具归档 backups/，杂项日志归 logs/，新建根 README + docs/INDEX 索引。保留核心文档（AGENTS/ARCHITECTURE/DEV_REPORT/HANDOFF）与 net/ 不动。
todos:
  - id: create-dirs
    content: 创建 docs/ 子目录结构（design/plans/fixes/archived）与 backups/tools-archive/、logs/
    status: completed
  - id: move-design-docs
    content: 移动 7 个设计 md + 参考/决策文档 到 docs/design/
    status: completed
    dependencies:
      - create-dirs
  - id: move-plan-fixes-history
    content: 移动 PLAN.md → docs/plans/、FRACTURE_FIXES → docs/fixes/、EROSION_HISTORY/ROADMAP → docs/archived/
    status: completed
    dependencies:
      - create-dirs
  - id: archive-tools
    content: 归档侵蚀测试工具目录到 backups/tools-archive/，sca_smoke/ 到 backups/
    status: completed
    dependencies:
      - create-dirs
  - id: archive-misc
    content: 移动 zip/日志到 backups/ 与 logs/
    status: completed
    dependencies:
      - create-dirs
  - id: create-index
    content: 创建 docs/INDEX.md 文档分类索引
    status: completed
    dependencies:
      - move-design-docs
      - move-plan-fixes-history
      - archive-tools
      - archive-misc
  - id: create-readme
    content: 创建根 README.md 项目总览与目录地图
    status: completed
    dependencies:
      - create-index
  - id: verify
    content: 验证整理结果（git status、文件列表）
    status: completed
    dependencies:
      - create-readme
---

## 需求

根目录混乱，需要中度归类整理 + 建立索引。

### 整理原则（已确认）

1. **中度归类**：散落 md 按类移入 docs/ 子目录，杂项日志归 logs/，zip 归 backups/
2. **核心文档留根**：AGENTS.md / ARCHITECTURE.md / DEV_REPORT.md / HANDOFF.md 留根目录
3. **侵蚀测试工具**：erosion-test-tool/ 与 backup 保留不动（用户确认）
4. **net/minecraftforge/**：保留不动（构建依赖待确认）

### 涉及文件

- 根目录 15 个散落 md（7 设计 + 1 计划 + 1 修复 + 2 历史/过时 + 4 核心保留）
- 2 个杂项文件（zip、symcheck4.txt、preview_run.err）
- 参考/河流与侵蚀方案_决策文档.md（1 个散落在 参考/ 的设计文档）

### 工具目录（保留不动）

- erosion-test-tool/
- erosion-test-tool-backup-v5-river-fixed/

# Agent Extensions

无需要扩展。