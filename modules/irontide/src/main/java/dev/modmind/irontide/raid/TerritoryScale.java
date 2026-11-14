package dev.modmind.irontide.raid;

import dev.modmind.irontide.config.RaidConfig;

/**
 * 领地随档位缩放（W7-B7）。
 * <p>
 * 带宽固定，只有基准半径 R 随核心档位变化：
 * <ul>
 *   <li>保护区 / 空气墙：{@code <= R+1}；</li>
 *   <li>防御区：{@code R+2 .. R+15}；</li>
 *   <li>缓冲带：{@code R+16 .. R+19}；</li>
 *   <li>刷怪环：{@code R+20 .. R+35}。</li>
 * </ul>
 * R：T1=15、T2=18、T3=22、T4=25（更高档位沿用 T4）。T4 于是回到
 * 26 / 26 / 27–40 / 41–44 / 45–60，与设计表一致。
 * <p>
 * {@code raids.json → defense.protectedRadius} 仍可微调：取它与 26 的差作为
 * 全局偏移，对每个档位同时生效，所以改一个数就能整体放大/缩小领地。
 * 前哨定位（{@code raidConfig.outpost().minRadius/maxRadius}）不随档位缩放。
 */
public final class TerritoryScale {

    /** T4 的基准半径；配置里的 protectedRadius 默认 26 就对应它。 */
    public static final int REFERENCE_R = 25;
    public static final int REFERENCE_PROTECTED = REFERENCE_R + 1;

    /** 带宽（相对 R 的偏移）。 */
    public static final int PROTECT_OFFSET = 1;
    public static final int DEFENSE_OFFSET = 15;
    public static final int BUFFER_OFFSET = 19;
    public static final int SPAWN_MIN_OFFSET = 20;
    public static final int SPAWN_MAX_OFFSET = 35;

    /**
     * 一整套有效半径。
     *
     * @param tier            档位序号（1 起）
     * @param r               基准半径 R
     * @param protectedRadius 保护区与空气墙半径（{@code R+1}）
     * @param defenseEnd      防御区外沿（{@code R+15}）
     * @param bufferEnd       缓冲带外沿（{@code R+19}）
     * @param spawnMin        刷怪环内沿（{@code R+20}）
     * @param spawnMax        刷怪环外沿（{@code R+35}）
     * @param wallLimit       空气墙钳制半径（{@code protectedRadius + 0.35}）
     */
    public record Ring(int tier, int r, int protectedRadius, int defenseEnd, int bufferEnd,
                       int spawnMin, int spawnMax, double wallLimit) {

        /** 防御区内沿（保护半径再外一格）。 */
        public int defenseStart() {
            return protectedRadius + 1;
        }
    }

    private TerritoryScale() {
    }

    /** 档位 → 基准半径 R。 */
    public static int baseRadius(int tierIndex) {
        return switch (tierIndex) {
            case 1 -> 15;
            case 2 -> 18;
            case 3 -> 22;
            default -> 25;
        };
    }

    /** 档位 → 完整半径表（使用配置的全局偏移）。 */
    public static Ring of(int tierIndex, RaidConfig config) {
        int reference = config == null ? REFERENCE_PROTECTED : config.defense().protectedRadius();
        return of(tierIndex, reference - REFERENCE_PROTECTED);
    }

    /** 档位 + 显式偏移（一般只给测试/指令用）。 */
    public static Ring of(int tierIndex, int offset) {
        int tier = Math.max(1, tierIndex);
        int r = Math.max(4, baseRadius(tier) + offset);
        int protect = r + PROTECT_OFFSET;
        return new Ring(tier, r, protect,
                r + DEFENSE_OFFSET, r + BUFFER_OFFSET,
                r + SPAWN_MIN_OFFSET, r + SPAWN_MAX_OFFSET,
                protect + 0.35D);
    }
}
