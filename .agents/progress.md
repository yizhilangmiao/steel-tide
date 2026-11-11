# 进度 · 钢铁潮汐（mod_l61phe）

更新时间：2026-11-11 18:55 · 会话 dsh（E:\_ai 审计顺带收口）
工程：`E:\ioncreating\mod_l61phe` · ModMind 整合包「钢铁潮汐」· MC 1.21.1 / NeoForge 21.1.265 / JDK 21 · v1.1.3

> 本文件覆盖写，**MC 线的进度真源就是这里**（2026-11-05 起）。`E:\creatingmc` 降为方案与历史根。
> **稳定知识不在本文件**：目录收口 / 目录地图 / 启动方式 / MCP 桥 / mod 版本矩阵 / 铁律 → `文件\设计\项目手册.md`（2026-11-11 搬出，原样保留）。

## 当前任务

工作流收敛 P2/P3/P4（2026-11-05）：MC 线的治理已从 `E:\creatingmc` 搬到现役工程，工具链四件套全部落地并登记进 env.json —— PCL 直启（`pcl-link-mc.ps1`）、mod 版本矩阵（`mc-mods-lock.ps1`）、ModMind MCP 桥（`modmind-bridge.ps1`）、每周维护（`E:\_ai\scripts\weekly.ps1` 会代跑 doctor 与 mod 锁）。技能 selected 清单：`create-3d-model`、`bailian-protocol`；`doctor.ps1` 带 SKILL 段（清单漂移 + 体积 >50 MB 报警）。

## 上次进度（2026-11-03）

基座已换 MC 1.21.1 + NeoForge；6 份 1.20.1 旧文档已删；策略＝先单机验手感，再从结果反推服务端该装什么。

## 下一步

1. 单机实例装「最小基线 12 + 渲染三件套 + GD656Killicon + 近战手感 10」，跑总方案 §1.1 四步验收（光影重影 / 一刀多杀 / R 键 / 女仆持枪）。
2. 按总方案 §4 产出服务端白名单，做 2 人联机验收。
3. ~~建 `mods.lock.json`~~ —— 2026-11-05 已完成（`mc-mods-lock.ps1`，基线 14 mod / 152.9 MB）。往后每次构建同步后跑一次。
4. ~~`ModMind接入DSH方案.md` 落地验证~~ —— 2026-11-05 已完成：桥开、HTTP 200、stdio 104 工具、`modmind_mcp_probe` 返回 ready。**剩 DSH 重启后确认 `mcp-modmind` 不再 disconnected。**

## 卡点

- ~~两个整合包并存~~ 已定（2026-11-05 老大）：「霸机动力」= ModMind 旧工程名，与钢铁潮汐是同一个项目。`E:\creatingmc\mod_12rr0lu`（13.4 GB / 61672 文件）已删到回收站，小文档归档在 `E:\creatingmc\_archive\mod_12rr0lu-20261105\`；`E:\mcmc` 是他自己玩的，不属工程范围。
- MC 线只剩一个写入点 `E:\ioncreating\mod_l61phe`。
- ~~`env.json` 里 `path:temp / modmindApp / modmindProject` 三项让 doctor 红~~ 已重写（2026-11-05 18:00），doctor EXIT=0。

## 2026-11-10 多方块成型材质切换（方案+教程）

- 产出：`文件/设计/多方块成型材质切换-方案.md`、`文件/设计/多方块成型材质切换-教程.md`、`脚本/gen_shell_textures.py` + 4 张 `shell_*.png`（andesite/brass/steel/copper）
- 路线：`CasingSpec.formed` 数据驱动换方块；原始状态存 `ShellBlockEntity`，控制器不新增存档字段；否决 BER 整体模型（NeoForge 1.21.1 无 obj loader + 作者不建模）
- 下一步：按教程 P0~P5 落地。最易漏处＝`StructureValidator` 的 `brassRatio` 统计必须同步接受成型态，否则成型后装甲/血量崩
- 卡点：无。本轮纯文档 + 资源，未改任何 Java 代码

<!-- 写不出「下一步」就说明这轮白干。别把本轮详情写这里，那是 handoff.md 的事。 -->
