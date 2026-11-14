package dev.modmind.irontide.raid;

import dev.modmind.irontide.config.RaidConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * 主核领地（方形分区）。
 *
 * <p>判定用切比雪夫距离 {@code r(x,z)=max(|x-cx|,|z-cz|)}，y 不参与（{@code halfHeight} 只是可选兜底）。
 * 分区：{@code r<=26} 不可拆建造区（r=25 画绿线）、{@code r=26} 空气墙、{@code 27..40} 防御区、
 * {@code 41..44} 缓冲带、{@code 45..60} 刷怪环。</p>
 *
 * <p>所有阈值都能被 {@code raids.json → defense} 覆盖；这里只放默认值与纯函数，不持有状态。</p>
 */
public final class Territory {

    /** 默认的不可拆建造区半径（含）；方块坐标的 {@code r<=26} 禁止拆。 */
    public static final int DEFAULT_PROTECTED_RADIUS = 26;
    /** 空气墙钳制半径：怪的水平位置被夹在 {@code 26.35} 外。 */
    public static final double DEFAULT_WALL_LIMIT = 26.35D;

    /** 永远不可拆的方块（与配置里的 {@code break.protect} 合并）。 */
    private static final Set<String> PROTECTED_IDS = Set.of(
            "minecraft:bedrock",
            "minecraft:barrier",
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:structure_block",
            "irontide:andesite_core",
            "irontide:shell"
    );

    private Territory() {
    }

    /** 方块坐标的切比雪夫半径；中心按方块坐标，判定按 {@code pos - center}。 */
    public static int radius(BlockPos center, BlockPos pos) {
        return Math.max(Math.abs(pos.getX() - center.getX()), Math.abs(pos.getZ() - center.getZ()));
    }

    /** 实体的切比雪夫半径；中心取方块中心 {@code +0.5}，这样 26.35 的钳制与方块边界对得上。 */
    public static double radius(BlockPos center, double x, double z) {
        return Math.max(Math.abs(x - (center.getX() + 0.5D)), Math.abs(z - (center.getZ() + 0.5D)));
    }

    /** 该方块是否在不可拆建造区内（{@code r<=26}）。 */
    public static boolean isProtectedZone(BlockPos center, BlockPos pos, int protectedRadius) {
        return radius(center, pos) <= Math.max(0, protectedRadius);
    }

    /** 永远不可拆的方块（核心、壳、基岩、命令方块、结构方块等）。 */
    public static boolean isDenied(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && PROTECTED_IDS.contains(id.toString());
    }

    /** 拆墙白名单里的方块还要再过滤一次：{@code r<=26} 一律不可拆。 */
    public static boolean isBreakable(BlockPos center, BlockPos pos, RaidConfig.Defense defense) {
        return isBreakable(center, pos, defense.protectedRadius());
    }

    /** 不依赖配置对象的重载：{@code r>protectedRadius} 才可拆。 */
    public static boolean isBreakable(BlockPos center, BlockPos pos, int protectedRadius) {
        return !isProtectedZone(center, pos, protectedRadius);
    }

    /** 怪是否已经贴到空气墙（用于脉冲攻击的「贴墙」接触判定）。 */
    public static boolean isTouchingWall(Entity entity, BlockPos center, double wallLimit) {
        return radius(center, entity.getX(), entity.getZ()) <= wallLimit + 0.75D;
    }

    /**
     * 把实体水平拉回空气墙外。返回 true 表示发生过钳制。
     *
     * <p>只改水平位置并把朝向墙内的速度分量清零；y 一律不动，所以飞行怪同样被拦。</p>
     */
    public static boolean clampToWall(Entity entity, BlockPos center, double wallLimit) {
        double cx = center.getX() + 0.5D;
        double cz = center.getZ() + 0.5D;
        double dx = entity.getX() - cx;
        double dz = entity.getZ() - cz;
        double limit = Math.max(1.0D, wallLimit);
        if (Math.max(Math.abs(dx), Math.abs(dz)) >= limit) {
            return false;
        }
        double x = entity.getX();
        double z = entity.getZ();
        Vec3 velocity = entity.getDeltaMovement();
        if (Math.abs(dx) >= Math.abs(dz)) {
            x = cx + (dx >= 0.0D ? limit : -limit);
            velocity = new Vec3(0.0D, velocity.y, velocity.z);
        } else {
            z = cz + (dz >= 0.0D ? limit : -limit);
            velocity = new Vec3(velocity.x, velocity.y, 0.0D);
        }
        entity.setPos(x, entity.getY(), z);
        entity.setDeltaMovement(velocity);
        entity.fallDistance = 0.0F;
        return true;
    }
}
