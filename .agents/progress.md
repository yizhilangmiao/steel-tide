# 进度 · 钢铁潮汐（mod_l61phe）

更新时间：2026-11-05 17:30 · 会话 dsh（P5 自动化 + 目录收口）
工程：`E:\ioncreating\mod_l61phe` · ModMind 整合包「钢铁潮汐」· MC 1.21.1 / NeoForge 21.1.265 / JDK 21 · v1.1.3

> 本文件覆盖写，**MC 线的进度真源就是这里**（2026-11-05 起）。`E:\creatingmc` 降为方案与历史根。

## 目录收口（2026-11-05 17:30，只是登记，一个字节没动）

MC 相关目录共五个，从今天起只有**第一行**是现役；其余是历史资产，查资料可以进，**不许往里加新东西**，删之前必须问老大。

| 目录 | 定位 | 体量 / 内容 |
|---|---|---|
| `E:\ioncreating\mod_l61phe` | **现役工程**（唯一写入点） | 整合包「钢铁潮汐」，见下 |
| `E:\creatingmc` | 方案根：`整合包总方案-1.21.1-NeoForge.md`、`AGENTS.md`（原 `mod_12rr0lu` 已于 2026-11-05 删除，小文档归档在 `_archive\mod_12rr0lu-20261105\`） | 不进构建 |
| `E:\mcmc` | 老大自己玩的 PCL 便携根（**不属工程范围**） | 10.0 GB / 26802 文件：assets、libraries、versions、SkyExplorer、launcher_profiles.json |
| `E:\mcserve` | 服务端 jar 收藏 | 47 MB / 3 个：arclight-fabric-1.20.4、fabric-1.21.10、paper-1.19.4 |
| `E:\mcserver` | 旧服务端运行时 | 299 MB / 249 文件：cache、config、libraries、logs、plugins、versions |

> 待办：若某天要省空间，`E:\mcmc` 的 `assets` / `libraries` 与 `E:\mcserver` 的 `libraries` 是重下的东西，可清；`E:\mcserve` 三个 jar 留着当模板。**先问再动。**

---

## 目录地图

| 路径 | 是什么 |
|---|---|
| `.modmind\` | ModMind 工具状态（会话 / 快照 / 请求证据 / 构建缓存），**受保护，不手改** |
| `mods\` | 第三方模组 jar（不入库，靠 `modmind.pack.json` 清单重下） |
| `modules\` | 项目自身模块 |
| `overrides\` | 整合包覆盖文件（进包时替换） |
| `docs\` | 文档；`last-ai-change.json` / `last-ai-response.txt` 是 ModMind 自动生成 |
| `modmind.project.json` | 工程元数据（name / pack / loader 版本） |
| `modmind.pack.json` | 打包清单 |
| `modmind.modpack.lock.json` | 托管锁记录 —— **版本与哈希以它为准，不凭文档断言** |
| `.agents\` | 协同内核（本目录），env.json 是环境事实源 |
| `E:\creatingmc` | 方案根：`整合包总方案-1.21.1-NeoForge.md`、`ModMind接入DSH方案.md`、`AGENTS.md` |

---

## 当前任务

工作流收敛 P2/P3/P4（2026-11-05）：MC 线的治理已从 `E:\creatingmc` 搬到现役工程，工具链四件套全部落地并登记进 env.json —— PCL 直启（`pcl-link-mc.ps1`）、mod 版本矩阵（`mc-mods-lock.ps1`）、ModMind MCP 桥（`modmind-bridge.ps1`）、每周维护（`E:\_ai\scripts\weekly.ps1` 会代跑 doctor 与 mod 锁）。技能 selected 清单：`create-3d-model`、`bailian-protocol`；`doctor.ps1` 带 SKILL 段（清单漂移 + 体积 >50 MB 报警）。

## 启动方式（2026-11-05 起，实机待验）

**PCL 直接启动开发中的状态**，不用导出整包再导入：

```
pwsh -File E:\_ai\scripts\pcl-link-mc.ps1 -Project E:\ioncreating\mod_l61phe
```

它做四件事：补 `D:\mc\versions\1.21.1` 基座与缺的库 → 写 `D:\mc\versions\钢铁潮汐-开发\钢铁潮汐-开发.json`（`inheritsFrom 1.21.1`，mainClass 走 NeoForge）→ 把 `mods / config / defaultconfigs / kubejs / local / resourcepacks / schematics / saves / tacz / tlm_custom_pack` 十个目录 junction 到 `.modmind\minecraft\` → 体检。

日常流程：**DSH 改代码 → ModMind 点构建同步 → 跑一次 mods 锁 → PCL 选版本「钢铁潮汐-开发」启动**。PCL 版本设置里 Java 指到 `C:\Users\25571\AppData\Roaming\modmind\minecraft-runtime\java\java-runtime-delta\bin\java.exe`（21）。模式变了、`mods` 数量不对时跑 `-Check` 看，链接被 PCL 写成真目录时跑 `-Force` 修。

## ModMind MCP 桥（2026-11-05 实测打通）
> 桥按需开（2026-11-05 老大定）：只有在 DSH 里要驱动 ModMind 时才起桥；平时不开。`pwsh E:\_ai\scripts\scene.ps1 -Start -Scene mc` 会**先起桥、再确认本体**（桥启动时自带 `--mcp-bridge` 把本体拉起来；先单起本体不会开桥）。

外部 agent 走 ModMind 官方通道拿它的 104 个工具（构建、测试会话、截图、modpack、Blockbench、资产）：

```
pwsh -File E:\_ai\scripts\modmind-bridge.ps1 -Action on       # 开桥（幂等）
pwsh -File E:\_ai\scripts\modmind-bridge.ps1 -Action status   # 体检：读 bridge.json 探一次 mcp_probe
pwsh -File E:\_ai\scripts\modmind-bridge.ps1 -Action status -Json
pwsh -File E:\_ai\scripts\modmind-bridge.ps1 -Action off      # 不加 -Force 只提示（关桥 = 退出 ModMind 本体）
```

三个必须知道的事实：①**桥不常驻**，ModMind 平时不带 `--mcp-bridge` 启动，进程不监听端口、也不写 `bridge.json`，此时 `modmind-mcp-server.mjs` 第 5 行 `readFileSync` 直接 ENOENT；②**端口每次不同**（存在 `bridge.json` 的 `port`，本次 51038），不许写死，所以台账里这一项用 probe 判活而不是端口；③开桥必须在清掉 `ELECTRON_RUN_AS_NODE` 的环境里起，否则 ModMind 以 node 模式启动并报 `bad option: --mcp-bridge`。

实测：`mcp_probe` → `{"ok":true,"marker":"modmind-mcp-ready-v1"}`；`project_info` → 钢铁潮汐 / neoforge / 1.21.1 / 21.1.265 / java 21 / v1.1.3；MCP stdio `initialize` → `{name:"modmind", version:"1.4.26"}`，`tools/list` → **104 个工具**。被屏蔽的三个：`modmind_inspiration_submit` / `modmind_research` / `modmind_project_setup`。

DSH 侧 `cordis.patch.yml` 的 `mcp-modmind` 已经指向现役工程的 mjs，但 DSH 是在桥之前启动的 → 本次 `list_mcp_resources` 报 `server is disconnected`。**桥先开、DSH 后启**才有工具；重启 DSH 会断当前会话，所以留到下次重启时验。

## mod 版本矩阵（2026-11-05 起）

构建同步之后记一次账，下次构建前比一次 —— 版本冲突不报错，只在进游戏那一刻崩。

```
pwsh -File E:\_ai\scripts\mc-mods-lock.ps1 -Project E:\ioncreating\mod_l61phe          # 写 mods.lock.json
pwsh -File E:\_ai\scripts\mc-mods-lock.ps1 -Project E:\ioncreating\mod_l61phe -Check   # 只比不写，有漂移退出 1
pwsh -File E:\_ai\scripts\mc-mods-lock.ps1 -Project E:\ioncreating\mod_l61phe -List    # 看矩阵
```

锁里记：每个 jar 的 `modId` / `version` / `sha256_16` / `sizeMB`；`META-INF/neoforge.mods.toml` 的依赖（required 才校验）；`META-INF/jarjar/` 内嵌 jar 的 modId（否则 create→flywheel、jei→mezz_config 会误报悬空）。当前基线：14 mod / 152.9 MB / error 0 / warn 0。

## 上次进度（2026-11-03）

基座已换 MC 1.21.1 + NeoForge；6 份 1.20.1 旧文档已删；策略＝先单机验手感，再从结果反推服务端该装什么。

## 下一步

1. 单机实例装「最小基线 12 + 渲染三件套 + GD656Killicon + 近战手感 10」，跑总方案 §1.1 四步验收（光影重影 / 一刀多杀 / R 键 / 女仆持枪）。
2. 按总方案 §4 产出服务端白名单，做 2 人联机验收。
3. ~~建 `mods.lock.json`：mod 版本矩阵（版本 + 依赖 + 冲突）~~ —— 2026-11-05 已完成（`mc-mods-lock.ps1`，基线 14 mod / 152.9 MB）。往后每次构建同步后跑一次。
4. ~~`ModMind接入DSH方案.md` 落地验证：起一次 MCP、调一个工具~~ —— 2026-11-05 已完成：桥开、HTTP 200、stdio 104 工具、`modmind_mcp_probe` 返回 ready（详见上节）。剩 DSH 重启后确认 `mcp-modmind` 不再 disconnected。

## 卡点

- ~~两个整合包并存~~ 已定（2026-11-05 老大）：「霸机动力」= ModMind 旧工程名，与钢铁潮汐是同一个项目。`E:\creatingmc\mod_12rr0lu`（13.4 GB / 61672 文件）已删到回收站，小文档归档在 `E:\creatingmc\_archive\mod_12rr0lu-20261105\`；`E:\mcmc` 是他自己玩的，不属工程范围。
- MC 线只剩一个写入点 `E:\ioncreating\mod_l61phe`。
- ~~`env.json` 里 `path:temp / modmindApp / modmindProject` 三项让 doctor 红~~ 已重写（2026-11-05 18:00）：那三条指向 `E:\creatingmc` 的旧路径，现在 mod_l61phe 的 env.json 只登记真实存在的 5 条（`pcl_root / pcl_version / modmind_game_dir / modmind_runtime / java_base`），doctor EXIT=0。

## 铁律（MC 线）

- 用户自己进游戏测：只做编辑、安装、静态检查，**不启动客户端 / 服务器**。
- ModMind 应用与安装目录是受保护基础设施，不直接改 / 删 / 移动。
- 实际安装版本与哈希以 `modmind.modpack.lock.json` 为准，不凭文档断言。
- en / 中文资源漂移是已知项，不当故障处理。


## 2026-11-10 多方块成型材质切换（方案+教程）

- 产出：`docs/design/多方块成型材质切换-方案.md`、`docs/design/多方块成型材质切换-教程.md`、`modules/irontide/tools/gen_shell_textures.py` + 4 张 `shell_*.png`（andesite/brass/steel/copper）
- 路线：`CasingSpec.formed` 数据驱动换方块；原始状态存 `ShellBlockEntity`，控制器不新增存档字段；否决 BER 整体模型（NeoForge 1.21.1 无 obj loader + 作者不建模）
- 下一步：按教程 P0~P5 落地。最易漏处＝`StructureValidator` 的 `brassRatio` 统计必须同步接受成型态，否则成型后装甲/血量崩
- 卡点：无。本轮纯文档 + 资源，未改任何 Java 代码
