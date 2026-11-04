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

## 提交约定

- 只有 `main`，常绿：每次提交至少能进主菜单
- 第三方 jar 永不入库；版本以 `modmind.pack.json` 为准
- tag = 可玩基线（例：`m1-round1`），tag 必须能由本仓库 + 清单还原
- 不提交 `.modmind/`、世界存档、日志、截图、构建产物

## 许可

本仓库暂未选择开源许可证（**待定**）。第三方模组、材质、光影包版权归各自作者，本仓库不包含其二进制文件。
