# 交接 · 2026-11-11

**进度**：M1 约 85%。核心多方块（四向旋转）/ 燃料 / GUI / 模块 / 怪潮调度 / 安全区 / 攻核 AI / 结算 / 维修 / 音效 / 蒸汽 HUD / 弹壳 / 发射药 —— 全部已构建，大部分实机验证过。详细版见 `docs/交接报告-2026-11-11.md`。

**下一步**：① 验弹药探针 v4（`kubejs/server_scripts/irontide_ammo_probe2.js`，启动看 `Added N recipes ... with 0 failed recipes`），通了换成正式配方并删探针；② 实机验遗迹战利品（`/loot give @s loot irontide:dungeon_inject`）；③ 实机验 FTB 任务书三章；④ 打 tag `m1` 进 M2。

**卡点**：弹药组装 —— TaCZ 弹药 `max_stack_size = 1`，`setCount(5)` 被配方编码器拒绝（`Item stack with stack size of 5 was larger than maximum: 1`）。v4 试的是「一次给多个 count=1 的堆」，未验证。备选：Create mixing 的 result 传数组 / `event.custom` 手写 TaCZ 自定义格式 / 固定出 1 发。

---

**构建**：`cd modules/irontide && GRADLE_USER_HOME="C:/Users/25571/AppData/Roaming/modmind/gradle-runtime/cache" ./gradlew build --offline`（默认 gradle home 缺 neoform-runtime，且 maven.neoforged.net 不可达）。

**改完同步两处**：`mods/modmind-local-irontide.jar` 与 `.modmind/minecraft/mods/modmind-local-irontide.jar`。

**当前 jar**：144078 B sha256 `2b86e699…5610 8`。
