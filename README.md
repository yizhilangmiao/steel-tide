# 钢潮 IRON TIDE

Minecraft 1.21.1 + NeoForge 整合包。
核心循环：火药量产 → 基地核心 → 预警怪潮 → 探索回收部件。

- 设计真源：[docs/design/steel-tide-2026-11-03.md](docs/design/steel-tide-2026-11-03.md)
- 当前阶段：**M1 第一轮** —— 主要模组能进游戏、跑通流程
- 联机验收、怪潮完整闭环、组队系统：后续轮次

## 目录

| 路径 | 说明 | 进 git |
| --- | --- | --- |
| `mods/` | 第三方模组 jar（按清单重下，**不入库**） | ❌ |
| `overrides/` | config / kubejs / datapacks / resourcepacks | ✅ |
| `modules/` | 自研 Java 模组源码 | ✅ |
| `docs/` | 设计文档与验收脚本 | ✅ |
| `.modmind/` | ModMind 工具状态（会话 / 快照 / 缓存） | ❌ |

## 还原

1. `git clone <repo>` 到任意目录
2. 用 ModMind 打开该目录（整合包 / NeoForge 1.21.1）
3. 按 `modmind.pack.json` 的记录在 ModMind 内同步 `mods/`，不要手工从旧机器拷 jar
4. 启动客户端。`mods/` 只有 jar、`.modmind/` 为空都是正常状态

## 运行要求（实测）

- **堆内存 ≥ 4 GB**。本包已验证配置为 6144 MB；**1024 MB 会在模组加载阶段 `java.lang.OutOfMemoryError: Java heap space` 直接崩溃**（2026-11-04 实测）。
- JVM/加载器：NeoForge 21.1.265，Java 21（64-bit）。
- 已装模组共 13 个 jar；进主菜单后杂项列表显示 33 mods 属正常（含 jar-in-jar 嵌套库）。

## 提交约定

- 只有 `main`，常绿：每次提交至少能进主菜单
- 第三方 jar 永不入库；版本以 `modmind.pack.json` 为准
- tag = 可玩基线（例：`m1-round1`），tag 必须能由本仓库 + 清单还原
- 不提交 `.modmind/`、世界存档、日志、截图、构建产物

## 许可

本仓库代码与配置采用 **MIT** 许可证，全文见 [LICENSE](LICENSE)。

第三方模组、材质、光影包、音频版权归各自作者，本仓库**不包含**其二进制文件；`modmind.pack.json` 仅记录 modrinth 项目与版本标识。发布 `.mrpack` 等含下载信息的分发件前，需逐项复核目标模组的再分发条款（ARR 模组不得打包）。
