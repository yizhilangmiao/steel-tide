# 进度 · 钢铁潮汐（mod_l61phe）
  更新时间：2026-11-12 17:40 · 会话 codex（W3 前哨网络 / W4 拆墙+MSPT / W5 文档）
  工程：`E:\ioncreating\mod_l61phe` · ModMind 整合包「钢铁潮汐」· MC 1.21.1 / NeoForge 21.1.265 / JDK 21 · 69 Mod
  > 本文件覆盖写，MC 线进度真源。稳定知识（目录/启动/MCP 桥/版本矩阵/铁律）→ `文件\设计\项目手册.md`。

## 当前任务
  W3/W4/W5 已全部落地（`ff16478` + `03246e9`），托管构建通过并同步实例；
  等用户按 `文件\测试\怪潮与状态-测试用例.md` 实机验收，通过后再打 tag `m1`。

## 本轮完成
  - **W3 前哨核心网络**：`CoreNetwork`（同维度 / ACTIVE / ≤ `network.radius` 96）＋ `network.shareDamage` 承伤均摊＋`network.settlementSplit` 结算拆份；主核失守时网络内核心接替锚点，怪潮继续。
  - **W4 拆墙白名单**：`RaidBreaker` 只拆 `raids.json → break.whitelist`（木板/原木/木制件/玻璃），`maxPerTick=2` 全局预算；石墙/铁块/核心壳不拆。
  - **W4 MSPT 采样**：`RaidPerf` 每 5 秒一行 `[IronTide] raid perf mspt/peak/raiders`，≥50ms warn；结算日志带 `peakMspt=`。
  - **W5 文档**：`overrides/config/irontide/README.md` 补 network / break / MSPT 契约；新增 FTB 任务章「核心与防御」（`core_defense.snbt` + lang）；手册与排期表同步；用例补 S22~S27。
  - 前序 W2.5/W2.6/W2.7/D2 见提交 `465e776` / `ab728fd`（信标、传输、多材质机壳、模块同步）。

## 待用户实测（自己进游戏，我不启动）
  - 旧：S15 同向来袭、S16 燃料（20% 卡死已修）、S17 信标、S18 传输、S19/S20 机壳、S21 模块同步。
  - 新：S22 前哨识别、S23/S23b 承伤分摊、S24 结算分摊、S25 拆墙白名单、S26 MSPT 日志、S27 主核失守接替。
  - 改 `core.json` / `raids.json` 必须重启客户端（启动只读一次）。

## 下一步
  1. 用户实机验收；回来后按反馈修。
  2. 验收通过 → `git tag m1`（**未打**，等确认）。
  3. 继续 M1 剩余：词缀系统、帕秋莉手册补怪潮/核心章节。

## 卡点 / 已知
  - D1 退出世界偶发卡「保存世界中」：世界已存盘，卡在 `MinecraftServer#stopServer()` 尾部；`StuckShutdownWatchdog` 等下次复现堆栈。
  - 拆墙对空中单位同样生效（`raiders` 内所有 Mob）；不想让飞行怪拆墙需按 `FlyingMob` 过滤。
  - `RaidPerf` 是自算窗口 MSPT，不含渲染/GC；真掉 TPS 以 warn 行为准。
  - 玩家实例内存 ≥4G（资源重载曾 OOM）。