# 交接 · 2026-11-11（晚）

**目录已重整**（2026-11-11）：文档进 `文件/`（`设计/` `教程/` `交接/` `历史/`），脚本进 `脚本/`，图片进 `素材/设计图/`；`docs/` 只留 ModMind 自动生成的 AI 记录。冗余已清：`libs/modmind`、`.wt`、`modules/irontide/research-tmp`、`modules/irontide/tools`、kubejs 的 `.bak-glm`、`.agents` 的 doctor 备份。

**进度**：M1 约 85%。弹药组装已换实现：KubeJS 探针作废并删除，改为 Create 搅拌机多产物配方（10 口径，`modules/irontide/src/main/resources/data/irontide/recipe/ammo/*.json`），一次出 4 发（40mm 出 2 发）。2026-11-11 二次修正：`create:mixing` 是集合匹配，原先 9mm/45acp/22wmr 与 308/545x39/556x45/762x39 各组配料相同会串味，已改为 10 组两两不同（铜粒/锌粒/铁粒/铁锭/黄铜锭/火药组合），静态校验 10/10 唯一，jar 已重建并同步。

**2026-11-11 深夜追加**：加 Superb Warfare 0.8.9.2（+ KotlinForForge 5.12.0）与 Patchouli，51 Mod；帕秋莉手册 `mod_l61phe:irontide_arms`「钢铁潮汐·军械手册」5 章 19 条，首次进服由 `overrides/kubejs/server_scripts/irontide_guide.js` 发一本；`overrides/options.txt` 把 Iris 默认「重载光影 = R」解绑为 `key.keyboard.unknown`，R 归 TaCZ 换弹。

**2026-11-11 又追加**：FTB 任务书补第 4 章「军械手册」（chapters/arms_manual.snbt，20 个任务，与帕秋莉手册一一对应，末尾含连锁采集说明）；加 FTB Ultimine 2101.1.15（依赖 FTB Library/Architectury 已在包内），52 Mod；Ultimine 默认反引号无冲突未改键。构建通过（52 Mod + 39 override），章节内容未在游戏内打开验证。

**2026-11-11 深夜（键位返修）**：overrides/options.txt 改了但游戏里没生效 —— 玩家实际实例 D:\mc\versions\钢铁潮汐-开发 里只有 mods/config/kubejs/local/saves/... 是 junction，options.txt 是普通文件，同步不会覆盖它；该文件里一直是 key_iris.keybind.reload:key.keyboard.r。已直接改玩家实例的 options.txt 为 key.keyboard.unknown。以后键位类改动必须同时落到那个文件。

**2026-11-11 深夜（任务书查配方返修）**：任务书里对着物品按 R 查不到配方 —— FTB Quests 本体不含 JEI 集成，任务书界面的 JEI 注册在 FTB XMod Compat 里（`FTBQuestsJEIIntegration`），包里缺它。已托管安装 FTB XMod Compat 21.1.14（CurseForge 889915），53 Mod，锁文件与实例已同步。rendered 启动日志确认 ftbxmodcompat 与 ftblibrary/ftbquests 一同加载、无报错；但该次客户端在 mixin 阶段即结束、帧纯红，**JEI 插件注册与实机按 R 效果仍未验证**。

**2026-11-11 深夜（体验增强批量装）**：按用户清单装 12 个体验模组（Sound Physics Remastered、ParCool+ Compatibility Addon（连带 ParCool 4.0.2.0）、Mouse Tweaks、AppleSkin、Controlling（连带 Searchables）、Traveler's Titles、Jade Addons（Jade 本体已有）、Sophisticated Backpacks（连带 Sophisticated Core）、Nature's Compass、Explorer's Compass、Better Advancements、Simple Inventory Sorter），托管分批 apply，锁文件 36 条全部核验通过，整合包 **67 Mod**，构建同步通过（67 Mod + 39 override）。rendered 冷启动到主菜单，画面可读；Mods 列表里实拍确认 Jade 15.10.6 / Jade Addons 6.1.2 / Sophisticated Backpacks 3.26.21 / Sophisticated Core 1.5.33 / Simple Inventory Sorter 24.0.24 / ParCool+ 1.2.1。注意：第一次启动时出现过一次 `recipe mod helper has already been initialised!`（FTB XMod Compat 的 SETUP 重复触发，同一次启动里还伴随光影包 ComplementaryReimagined 加载失败导致二次资源重载），第二次冷启动干净、无该报错；如再现再查。
**下一步**：⓪ 重启客户端后在任务书里对着钢管按 R 验证配方页（需要新 mod 冷启动才生效）；① 卓越前线实机跑一轮（研究台出蓝图 → 锻造成枪 → 打一发）；② 进世界打开 FTB 任务书第 4 章「军械手册」确认排版与连线（章节 SNBT 只在世界加载时解析）；③ 试一次连锁采集默认键（反引号）；④ 打 tag m1。

**卡点**（已解）：ModMind 清单元数据已按磁盘重建（2026-11-11）——`modmind.pack.json` 47 条（逐条 sha256，排除模块产物与 `.disabled`）、`modmind.modpack.lock.json` 13 条全部核验通过（sodium→`S3DUMfBo`、tacz 1.2.0→`SzzJttH8/O2Pfsh6w`）。托管构建/同步已通（48 Mod + 7 override）。顺手修了同步器的坑：纯数据 jar 若 `neoforge.mods.toml` 写 `javafml` 又没 class 会被拒，用 `脚本/patch_datapack_modloader.ps1` 改成 `lowcodefml`（已对 DnT、malfu 执行）。无头验证在本包不可用：Sodium 拒 HeadlessMC 的 LWJGL 3.3.3，只能走 rendered。另有一条定论：KubeJS `shapeless` 结果只支持单个 ItemStack，多发必须走 Create 的 `results` 数组。

---

**构建**：`cd modules/irontide && GRADLE_USER_HOME="C:/Users/25571/AppData/Roaming/modmind/gradle-runtime/cache" ./gradlew build --offline`（默认 gradle home 缺 neoform-runtime，且 maven.neoforged.net 本机不可达）。

**同步路径（只此一条）**：`mods/` 与 `overrides/` → `modmind_build_project`（或面板同步）→ `.modmind/minecraft/{mods,config,kubejs}`；`D:\mc\versions\钢铁潮汐-开发\{mods,config,kubejs,...}` 是指向 `.modmind/minecraft/*` 的 **junction**，所以 PCL 实例自动就是最新状态，**不用再手工复制 jar 到 D 盘**。反向不成立：游戏里改的东西落在 `.modmind/minecraft/config`，不会回流 `overrides/`，下次同步被覆盖 —— 要长期保留就改工程里的 `overrides/`。
