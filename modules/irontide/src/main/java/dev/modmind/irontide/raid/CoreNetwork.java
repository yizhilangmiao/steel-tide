package dev.modmind.irontide.raid;

import dev.modmind.irontide.block.AndesiteCoreBlockEntity;
import dev.modmind.irontide.block.CoreState;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 核心网络（W3「前哨核心」）。
 * <p>
 * 同维度、都处于 ACTIVE、彼此距离不超过 {@code core.json → network.radius} 的核心自动互联：
 * 主核（origin）排第一，其余按距离排序。承伤与结算都按这张表分摊。
 * <p>
 * 刻意只读 {@link AndesiteCoreBlockEntity#loaded()}（已加载的 BE），不扫描区块、不加载区块。
 */
public final class CoreNetwork {
    private CoreNetwork() {
    }

    /** 与 origin 同网络的 ACTIVE 核心；origin 自己一定在第一个。 */
    public static List<AndesiteCoreBlockEntity> members(AndesiteCoreBlockEntity origin, int radius) {
        if (origin == null) {
            return List.of();
        }
        int effective = Math.max(1, radius);
        BlockPos center = origin.getBlockPos();
        double radiusSq = (double) effective * (double) effective;
        List<AndesiteCoreBlockEntity> others = new ArrayList<>();
        for (AndesiteCoreBlockEntity core : AndesiteCoreBlockEntity.loaded()) {
            if (core == origin) {
                continue;
            }
            if (core.getLevel() != origin.getLevel()) {
                continue;
            }
            if (core.getState() != CoreState.ACTIVE) {
                continue;
            }
            if (core.getBlockPos().distSqr(center) > radiusSq) {
                continue;
            }
            others.add(core);
        }
        others.sort(Comparator.comparingDouble(core -> core.getBlockPos().distSqr(center)));
        List<AndesiteCoreBlockEntity> out = new ArrayList<>(others.size() + 1);
        out.add(origin);
        out.addAll(others);
        return List.copyOf(out);
    }

    /** 离给定坐标最近的一座核心；表为空时返回 null。 */
    public static AndesiteCoreBlockEntity nearest(List<AndesiteCoreBlockEntity> members,
                                                  double x, double y, double z) {
        AndesiteCoreBlockEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (AndesiteCoreBlockEntity core : members) {
            double distSq = core.getBlockPos().distToCenterSqr(x, y, z);
            if (distSq < bestSq) {
                bestSq = distSq;
                best = core;
            }
        }
        return best;
    }
}