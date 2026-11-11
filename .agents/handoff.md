# 交接 · 2026-11-11（晚）

**进度**：M1 约 85%。弹药组装已换实现：KubeJS 探针作废并删除，改为 Create 搅拌机多产物配方（10 口径，`modules/irontide/src/main/resources/data/irontide/recipe/ammo/*.json`），一次出 4 发（40mm 出 2 发）。jar `150188 B` sha256 `40c6a5f2…`，已同步 `mods/`、`.modmind/minecraft/mods/`、`D:\mc\versions\钢铁潮汐-开发\mods/` 三处。

**下一步**：① 启动一次，查 `logs/latest.log` 有无 `create:mixing` 解析报错，JEI 查 762x39 = 「1 弹壳 + 1 发射药 + 2 铁粒 → 4 发」；② 遗迹战利品实机验（`/loot give @s loot irontide:dungeon_inject`）；③ FTB 任务书三章实机验；④ 打 tag `m1`。

**卡点**：ModMind 托管运行时全被旧清单拦住（`modmind.pack.json` 只有 13 条，`mods/` 实有 50 个，报 missing `sodium-0.8.13` / `tacz-1.1.8`）：托管构建能编出 jar 但同步失败，`test_session`/服务端/`test_matrix` 都起不来。实机验证只能走 PCL 实例。技术上另有一条定论：KubeJS `shapeless` 结果只支持单个 ItemStack（`ShapelessKubeJSRecipe extends ShapelessRecipe`），多发必须走 Create 的 `results` 数组（`ProcessingOutput` 支持 `id`/`count`/`components`/`chance`）。

---

**构建**：`cd modules/irontide && GRADLE_USER_HOME="C:/Users/25571/AppData/Roaming/modmind/gradle-runtime/cache" ./gradlew build --offline`（默认 gradle home 缺 neoform-runtime，且 maven.neoforged.net 本机不可达）。

**改完同步三处**：`mods/modmind-local-irontide.jar`、`.modmind/minecraft/mods/modmind-local-irontide.jar`、`D:\mc\versions\钢铁潮汐-开发\mods/modmind-local-irontide.jar`。
