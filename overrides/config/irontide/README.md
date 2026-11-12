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
## 约定

1. **只读一次**：服务器启动时载入并做范围校验，非法值直接报错停止，**不允许静默回退默认值**
2. **改数值不动代码**：调平衡只改这些文件，重启生效
3. **结构格式**：`layers` 的键是相对控制器的 Y 偏移，每个字符串是一层（Z 从北到南），一个字符一列（X 从西到东）
4. **图例**：`legend` 把一个字符映射到一个方块 id；`B` 允许出现在 `#` 的位置（外壳只收安山/黄铜）
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