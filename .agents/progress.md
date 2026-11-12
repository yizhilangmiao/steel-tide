# 进度 · 钢铁潮汐（mod_l61phe）
  更新时间：2026-11-12 12:30 · 会话 codex（W1 还原漏格返修）
  工程：`E:\ioncreating\mod_l61phe` · ModMind 整合包「钢铁潮汐」· MC 1.21.1 / NeoForge 21.1.265 / JDK 21 · 69 Mod
  > 本文件覆盖写，**MC 线的进度真源就是这里**。稳定知识（目录地图/启动方式/MCP 桥/mod 版本矩阵/铁律）→ `文件\设计\项目手册.md`。

## 当前任务
  多方块「成型后整体换材质」（用户要的是：搭成了一眼看得出，不用点开核心看状态）。
  设计稿：`文件\设计\多方块成型材质切换-方案.md`（P0~P5）+ `-教程.md`。

## 已完成（均已提交 + 托管构建 + 同步实例）
  - `17ada82` W0：`irontide:shell` 方块/物品（4 变体贴图）+ `ShellBlockEntity`（记控制器与原方块、自愈）+ 攻核 DPS 上限 `attack.dpsCap`（默认 0=不限）。
  - `d2a2b87` W1：`CasingSpec.formed` → 成型时 23 格机壳变 `irontide:shell`，拆解整体还原；`StructureValidator` 接受壳态并按壳 type 统计 brassRatio；≤64 格/秒分批；`materialSwap.enabled` 总开关。
  - `4f4154a`：结构 INCOMPLETE 时每秒自检 → `/setblock`/蓝图搭满后自动成型，不必右键核心。
  - `f7f800c`：手动测试用例 `文件\测试\成型材质切换-测试用例.md`（T1~T10 + 日志判定）。
  - `deefca2`（本轮）：**W1 还原漏格修复**。用户 TNT 实测：炸壳后 5 秒还原，但控制器左边那格一直是壳。日志 `activated: layout=rot90 ... shell=23` → `unformed 13/15 cells`，还原恒少于成型 8 格。根因是 `fillUnformQueue` 用 rot0 字符表判定壳（结构不对称+成型朝向 rot90 → 错配成齿轮箱格）。改为：算 `layoutCells()` 包围盒逐格扫，只还原 `ShellBlockEntity.controllerPos()==自身` 的壳，原状态取 `shell.originalState()`；自检条件放宽到 `state != DESTROYED`。测试用例补 **T11 TNT 炸壳**。

## 下一步
  1. **用户自测**（用户明确自己测，勿启动游戏）：重启客户端后跑 `文件\测试\成型材质切换-测试用例.md`，重点 T2 / T5 / **T11**；判定线=`unformed N` 必须等于 `formed N`（23）。
  2. 按结果修：抖动（校验循环）/ 还原遗漏 / 掉落重复。
  3. W2：`status` 四态 + tier→壳材质 + `maxAlive` / 远怪不寻路（怪多不卡方案）。
  4. W3 前哨核心 + 主核无血；W4 破坏白名单/MSPT 实测；W5 手册与任务书同步 + 打 tag。

## 卡点
  - **换壳相关实机画面仍未验收**：本轮只做静态检查 + 托管构建通过，画面由用户自测。
  - 玩家实例内存务必 ≥4G（资源重载阶段会 OOM）。
  - `createbigcannons.mixins.json:compat.frameblocked` 类缺失告警为噪声（包内无 FramedBlocks）。
  - 自检现每秒对非 DESTROYED 核心全量校验一次；大结构（edge=9）多核心同屏时若见卡顿，再上「上次完整布局索引」缓存。