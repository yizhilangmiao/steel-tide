# 交接 · 2026-11-11（晚）

**目录已重整**（2026-11-11）：文档进 `文件/`（`设计/` `教程/` `交接/` `历史/`），脚本进 `脚本/`，图片进 `素材/设计图/`；`docs/` 只留 ModMind 自动生成的 AI 记录。冗余已清：`libs/modmind`、`.wt`、`modules/irontide/research-tmp`、`modules/irontide/tools`、kubejs 的 `.bak-glm`、`.agents` 的 doctor 备份。

**进度**：M1 约 85%。弹药组装已换实现：KubeJS 探针作废并删除，改为 Create 搅拌机多产物配方（10 口径，`modules/irontide/src/main/resources/data/irontide/recipe/ammo/*.json`），一次出 4 发（40mm 出 2 发）。jar `150188 B` sha256 `40c6a5f2…`，已同步 `mods/`、`.modmind/minecraft/mods/`、`D:\mc\versions\钢铁潮汐-开发\mods/` 三处。

**下一步**：① 启动后 JEI 查 762x39 = 「1 弹壳 + 1 发射药 + 2 铁粒 → 4 发」（配方已同步，未验排版）；② 遗迹战利品实机验（`/loot give @s loot irontide:dungeon_inject`）；③ FTB 任务书三章实机验；④ 打 tag `m1`。

**卡点**（已解）：ModMind 清单元数据已按磁盘重建（2026-11-11）——`modmind.pack.json` 47 条（逐条 sha256，排除模块产物与 `.disabled`）、`modmind.modpack.lock.json` 13 条全部核验通过（sodium→`S3DUMfBo`、tacz 1.2.0→`SzzJttH8/O2Pfsh6w`）。托管构建/同步已通（48 Mod + 7 override）。顺手修了同步器的坑：纯数据 jar 若 `neoforge.mods.toml` 写 `javafml` 又没 class 会被拒，用 `脚本/patch_datapack_modloader.ps1` 改成 `lowcodefml`（已对 DnT、malfu 执行）。无头验证在本包不可用：Sodium 拒 HeadlessMC 的 LWJGL 3.3.3，只能走 rendered。另有一条定论：KubeJS `shapeless` 结果只支持单个 ItemStack，多发必须走 Create 的 `results` 数组。

---

**构建**：`cd modules/irontide && GRADLE_USER_HOME="C:/Users/25571/AppData/Roaming/modmind/gradle-runtime/cache" ./gradlew build --offline`（默认 gradle home 缺 neoform-runtime，且 maven.neoforged.net 本机不可达）。

**同步路径（只此一条）**：`mods/` 与 `overrides/` → `modmind_build_project`（或面板同步）→ `.modmind/minecraft/{mods,config,kubejs}`；`D:\mc\versions\钢铁潮汐-开发\{mods,config,kubejs,...}` 是指向 `.modmind/minecraft/*` 的 **junction**，所以 PCL 实例自动就是最新状态，**不用再手工复制 jar 到 D 盘**。反向不成立：游戏里改的东西落在 `.modmind/minecraft/config`，不会回流 `overrides/`，下次同步被覆盖 —— 要长期保留就改工程里的 `overrides/`。
