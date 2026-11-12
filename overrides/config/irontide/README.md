# irontide 配置契约

> 这两个文件由 IronTide 服务端在启动时**只读一次**并做范围校验；改数值不用重新编译，重启客户端生效。

| 文件 | 作用 | 谁读 |
| --- | --- | --- |
| `core.json` | 全局参数：血量、燃料、应力、机壳分级、档位上限、模块数值 | IronTide 服务端，载入时校验 |
| `raids.json` | 怪潮：周期、波次表、刷怪、安全区、攻核、怪物来源 | IronTide 怪潮调度器，载入时校验 |
| `multiblocks/core_base_3.json` | T1（3×3×3）内置结构 | IronTide 结构校验器 |

## 燃料容量与模块

| 情况 | 容量 | 燃烧效率 | 每秒消耗 |
| --- | --- | --- | --- |
| 不装燃料模块（应急） | `fuel.noFuelModuleCapacity` = 12000 | `fuel.noFuelModuleEfficiency` = 1.5 | `burnPerSecond / 1.5` ≈ 3.3 |
| 燃料模块 I | `modules.fuel[0].capacity` = 60000 | 1.00 | 5 |
| 燃料模块 II | `modules.fuel[1].capacity` = 120000 | 0.80 | 6.25 |
| 燃料模块 III | `modules.fuel[2].capacity` = 240000 | 0.60 | 8.3 |

- **面板/聊天里的上限、百分比、低燃料阈值全部按「当前装的模块容量」算**（`AndesiteCoreBlockEntity#getFuelCapacity()`）。
- `fuel.capacity`（12000）是旧版遗留字段，仅为兼容老配置保留，**不再参与计算**。
- 效率越低烧得越快：容量换效率是燃料模块的核心取舍；模块数值在 `core.json → modules.fuel[]` 里调。
## 机壳材质与档位门槛

`core.json → casing` 现在是**数组**（顺序即默认优先级），每种材质一项：

| 材质 | 世界方块 `id` | 成型壳 `formed` | 生命倍率 | 护甲 | 燃料效率 | `minTier` |
| --- | --- | --- | --- | --- | --- | --- |
| 安山 | `create:andesite_casing` | `irontide:shell[type=andesite]` | 1.00 | 0.00 | 1.00 | 1 |
| 黄铜 | `create:brass_casing` | `irontide:shell[type=brass]` | 1.60 | 0.05 | 1.15 | 1 |
| 铜 | `create:copper_casing` | `irontide:shell[type=copper]` | 1.45 | 0.06 | 1.45 | 2 |
| 钢 | `create:industrial_iron_block` | `irontide:shell[type=steel]` | 2.20 | 0.12 | 1.00 | 3 |

- **生命倍率 / 护甲按格数加权平均**：`Σ(格数 × 该材质值) / 外壳总格数`，四种材质混搭时每种按自己的占比贡献。
- **`minTier` 是门槛**：核心档位序号（T1=1 … T4=4）低于门槛时，用这种材质搭的结构判为**不完整**，该格计入缺失并触发还原，避免 T1 直接铺钢壳。
- 外壳格可以放任意一种材质：成型时按**当前方块**决定换成哪种壳，校验时壳类型不必与结构图一致。
- 老配置仍可用：`casing` 写成对象 `{"andesite":{...},"brass":{...}}` 会按字段顺序读成数组。

## 约定

1. **只读一次**：服务器启动时载入并做范围校验，非法值直接报错停止，**不允许静默回退默认值**
2. **改数值不动代码**：调平衡只改这些文件，重启生效
3. **结构格式**：`layers` 的键是相对控制器的 Y 偏移，每个字符串是一层（Z 从北到南），一个字符一列（X 从西到东）
4. **图例**：`legend` 把一个字符映射到一个方块 id；`B` 允许出现在 `#` 的位置（外壳可收安山/黄铜/铜/钢，受 `minTier` 门槛限制）
5. 标注 `_placeholder` 的字段是待实测平衡值，不是最终数值

## `raids.json` 关键字段

| 字段 | 含义 |
| --- | --- |
| `spawn.perBurst` / `spawn.intervalTicks` | 每轮刷几只 / 两轮之间隔多少 tick |
| `spawn.maxAlive` | **同时存活上限**，`0` = 不限。到顶后暂停刷怪，清掉一批再继续，避免几十只同屏掉帧 |
| `spawn.chaseRadius` | **寻路半径**。半径内交给原版 A* 寻路，半径外只做直线移动（每 3 秒补一次真寻路兜底），大幅降低怪多的寻路开销 |
| `spawn.glowWhenAliveBelow` | 剩余来袭怪**少于这个数**时给每只挂发光（默认 10；`0` = 关闭），收尾时好找漏网的 |
| `spawn.arcDegrees` | **主攻扇面张角**（度，默认 60）。每波开打先抽一个方位，整波怪都落在该方位 ±一半张角的扇面里一起压过来；`0` = 几乎排成一条线，`360` = 恢复四面八方随机 |
| `minWaveSize` | 单波规模下限（默认 32）。波次表的 `base` 低于它按它算，保证「一波不少于 32 只」 |
| `attack.damagePerSecond` / `attack.dpsCap` | 每只围核怪每秒伤害 / 全程 DPS 上限（`0` = 不限） |
| `gearMulMax` | 规模按核心档位缩放的上限（T1 → ×1.00，最高档 → ×(1 + gearMulMax)） |
| `waves[]` | 每波基础怪数（当前 32 / 38 / 44 / 50 / 56 / 62 / 68 / 74）、词缀数、词缀等级 |

## 核心网络（前哨）

`core.json → network`

| 字段 | 默认 | 含义 |
| --- | --- | --- |
| `radius` | 96 | 同维度、距离 ≤ 半径的 ACTIVE 核心与主核组成一张网络。只认已加载的核心，不扫描区块 |
| `shareDamage` | true | 攻核伤害在存活核心间均摊（每座至少 1 点）；`false` = 各挨各的 |
| `settlementSplit` | true | 残骸按存活核心数拆份，每份给该核心 128 格内最近的玩家；没人接就并回全员 |

- 前哨不是新方块：任何**成型且运转中**的核心，落在主核半径内就自动加入网络。
- 主核被摧毁或停机时，网络里还活着的核心接替锚点（聊天播报坐标），怪潮继续；全部倒下才结束。
- `radius` 校验 [8, 256]，越界拒绝启动；老配置没有 `network` 段时按 96 / true / true 走。

## 拆墙（`raids.json → break`）

| 字段 | 默认 | 含义 |
| --- | --- | --- |
| `enabled` | 缺省段时 false；示例配置为 true | 总开关 |
| `intervalTicks` | 40 | 每只怪隔多久尝试拆一格（≥5） |
| `maxPerTick` | 2 | 全局每 tick 最多拆几格，防止几十只怪同 tick 改方块 |
| `range` | 24 | 离目标核心多近才开始拆（[4, 64]） |
| `whitelist` | 见下 | 只拆名单里的方块；`#namespace:tag` 走标签，其余按方块 id |

默认白名单：`#minecraft:planks`、`#minecraft:logs`、`#minecraft:wooden_stairs`、`#minecraft:wooden_slabs`、`#minecraft:wooden_fences`、`#minecraft:fence_gates`、`#minecraft:wooden_doors`、`#minecraft:wooden_trapdoors`、`minecraft:glass`、`minecraft:glass_pane`。

- 设计口径：**木墙 / 玻璃会被拆，石墙 / 铁墙 / 核心壳不会被拆**。想加防线材料就往 `whitelist` 里加 id。
- 只对怪潮刷出来的怪生效，且只朝「离自己最近的核心」方向拆一格；“拆的是路线上的第一格阻挡”。

## 性能日志（MSPT）

怪潮期间每 100 tick（5 秒）打一行：

```
[IronTide] raid perf mspt=12.4 peak=18.9 raiders=14
```

- `mspt` = 本窗口平均每 tick 毫秒（自算，不含渲染/GC）；`peak` = 本波最高窗口平均；`raiders` = 本波峰值同屏怪数。
- ≥ 50ms（低于 20 TPS）时改打 `warn`，句尾带「低于 20 TPS」。
- 波次结束时结算日志追加 `peakMspt=`，用于比对「怪更多后掉不掉 TPS」。