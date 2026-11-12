# 进度 · 钢铁潮汐（mod_l61phe）
  更新时间：2026-11-12 17:05 · 会话 codex（W2.5 信标 + W2.6 传输接线）
  工程：`E:\ioncreating\mod_l61phe` · ModMind 整合包「钢铁潮汐」· MC 1.21.1 / NeoForge 21.1.265 / JDK 21 · 69 Mod
  > 本文件覆盖写，**MC 线的进度真源就是这里**。稳定知识（目录地图/启动方式/MCP 桥/mod 版本矩阵/铁律）→ `文件\设计\项目手册.md`。

## 当前任务
  多方块「成型后整体换材质」已完工（W0/W1 用户实机验收通过）。
  当前推进 **W2：怪潮压力与核心状态**。

## 已完成（均已提交 + 托管构建 + 同步实例）
  - `17ada82` W0：`irontide:shell` 方块/物品（4 变体贴图）+ `ShellBlockEntity` + 攻核 DPS 上限 `attack.dpsCap`。
  - `d2a2b87` W1：成型整体换壳 / 拆解还原 / `materialSwap.enabled` 总开关 / ≤64 格每秒分批。
  - `4f4154a`：每秒自检 → `/setblock`、蓝图搭满也能自动成型。
  - `f7f800c`：W1 手动测试用例 T1~T10。
  - `deefca2`：**W1 还原漏格修复**（rot90 成型时按 rot0 字符表判定壳，漏 8 格；改为按控制器归属扫包围盒）+ T11 TNT 用例。**用户实测通过**。
  - `dfd7e37`（本轮）W2：
    1. 怪潮规模按核心档位算 GearMul（新增 `tierIndex()`，原来写死 T1）；
    2. `raids.json` 新增 `spawn.maxAlive`（默认 16，0=不限）——同时存活上限，到顶暂停刷怪；
    3. `raids.json` 新增 `spawn.chaseRadius`（默认 48）——远怪只做直线移动，每 60 tick 补一次真寻路兜底；
    4. 核心四态 `CoreStatus`（未成型/运转中/停机/已摧毁），面板与右键聊天共用 `statusText()`；
    5. 重写 `overrides/config/irontide/README.md` 配置契约；新增 `文件\测试\怪潮与状态-测试用例.md`（S1~S10）。
  - `ac1b55a` **W2 数值返修**：波次基数 14→**32 起步**（32/38/44/50/56/62/68/74），新增 `minWaveSize`（默认 32，兜底保证「一波不少于 32」）；新增 `spawn.glowWhenAliveBelow`（默认 10，`0`=关）——当前存活来袭怪少于阈值时每只挂发光，方便收尾找漏怪。测试用例补 S11~S14。
  - `dd16cdb` **W2 单向怪潮**：新增 `spawn.arcDegrees`（默认 60，0=一条线 / 360=四周随机）——每波开打抽一次主攻方位，整波落在该方位 ±一半张角的扇面里；出生即面朝核心；开战播报追加「来自 东/南/西/北 侧」。README 字段表与测试用例（新增 S15）同步。
  - 退出世界卡「保存世界中」排查：见 文件/诊断/退出卡死-排查.md。已证实世界存盘完整，卡点在 MinecraftServer.stopServer() 尾部（level close / resources close / storage close）；新增 client/StuckShutdownWatchdog 自动抓 Server thread 堆栈（20 秒触发，最多 3 次）。**尚未定位到具体 mod**。
  - 93d449c 燃料模块上限返修：updatePowerState 夹取/低燃料阈值、feedFuel 容量与余量、reportStatus 百分比三处都由 config.fuelCapacity()(12000) 改为 getFuelCapacity()(模块容量)。装 FUEL I 时原先燃料被钉在 12000 = 20% 且煤一直被吃；README 补燃料/模块对照表，用例补 S16。
  - **W2.5 信标接线**：`RaidSettlement` 读 `core.getBeaconReward()`，残骸 = round((波次×2+2)×performance×(1+reward))，结算播报附「信标 +N%」，日志加 `beacon=`。
  - **W2.6 传输接线**：`AndesiteCoreBlock#hasShaftTowards` 改为 自身轴线恒通 → 未装 TRANSFER 一律 false → I 级仅水平 / II 级以上全方向；BE 新增 `hasModule(ModuleType)`。两处均通过托管构建并同步实例（**未实机验证**）。

## 下一步（完整排期见 `文件\设计\排期表.md`）
  1. 用户自测：`文件\测试\怪潮与状态-测试用例.md`（重点 S15 同向来袭、S16 燃料模块、S17 信标、S18 传输）。
  2. W2 收尾遗留 D2：核心 BE 缺 `getUpdateTag`/`getUpdatePacket`，模块槽不同步到客户端（服务端传动正常）。
  3. W2.7 tier→壳材质；W3 前哨核心；W4 白名单/MSPT；W5 手册任务书 + tag。
## 卡点
  - **退出世界偶发卡在「保存世界中」（5 次里 2 次）**：世界已存盘，卡在集成服务器 stopServer() 尾部（level close / resources close / storage close），要等下一次复现的线程堆栈才能定位到具体 mod；卡住时强杀进程安全。
  - W2 只做到构建通过，**未实机验证**（用户自测）。
  - 玩家实例内存务必 ≥4G（资源重载阶段会 OOM）。
  - `createbigcannons.mixins.json:compat.frameblocked` 类缺失告警为噪声（包内无 FramedBlocks）。
  - 自检每秒对非 DESTROYED 核心全量校验一次；大结构多核心同屏若见卡顿，再上「上次完整布局索引」缓存。
