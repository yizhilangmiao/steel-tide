package dev.modmind.irontide.config;

/**
 * 一级模块的数值。每类只用到自己那几个字段，其余留 0 —— 用一个大 record 换掉四套平行类型，
 * 解析和取值都短。
 *
 * @param fuelCapacity      燃料模块：额外容量
 * @param fuelEfficiency    燃料模块：燃烧效率倍率（1.0 = 100%）
 * @param armorHealth       装甲模块：额外生命
 * @param armorReduction    装甲模块：减伤百分比（0.10 = 10%）
 * @param beaconReward      信标模块：奖励加成（0.10 = +10%）
 * @param transferAxes      传输模块：可接轴域，0 仅水平 / 1 水平+垂直 / 2 任意轴
 */
public record ModuleSpec(
        int fuelCapacity,
        double fuelEfficiency,
        int armorHealth,
        double armorReduction,
        double beaconReward,
        int transferAxes) {

    public static final ModuleSpec EMPTY = new ModuleSpec(0, 0.0D, 0, 0.0D, 0.0D, 0);
}
