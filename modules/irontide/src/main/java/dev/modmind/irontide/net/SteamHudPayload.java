package dev.modmind.irontide.net;

import dev.modmind.irontide.ModMindEntry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：核心的蒸汽（生命）状态，给左侧 HUD 用。
 * <p>
 * {@code active} 为 false 时客户端隐藏整条 HUD——不靠客户端自己猜怪潮开没开。
 */
public record SteamHudPayload(
        int health,
        int maxHealth,
        int wave,
        int remaining,
        /** 距下一次大波还有几天；-1 = 未知/没有核心。 */
        int daysToWave,
        /** 当前小波序号（1..subWaves）。 */
        int subWave,
        /** 本小波已生成数量 / 目标数量，用于进度条。 */
        int spawned,
        int target,
        /** 下一批怪还要多久（tick）；<=0 表示本小波已刷完或未在刷。 */
        int nextSpawnTicks,
        /** 下一小波开打还要多久（tick）；<=0 表示没有待开的小波。 */
        int nextSubWaveTicks,
        /** 受伤脉冲序号：每结算一次攻核 +1，客户端用它触发一次闪红。 */
        int hitPulse,
        boolean active)
        implements CustomPacketPayload {

    /** 每大波固定 3 小波（与 {@code RaidScheduler.SUBWAVES_PER_BIG_WAVE} 一致）。 */
    public static final int SUB_WAVES = 3;

    public static final CustomPacketPayload.Type<SteamHudPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMindEntry.MOD_ID, "steam_hud"));

    /**
     * 手写编解码器：字段多到超过 {@code StreamCodec.composite} 的固定重载，用显式读写更稳当。
     * 客户端 record 必须与服务端逐字段、逐顺序一致。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, SteamHudPayload> STREAM_CODEC =
            StreamCodec.of((buffer, payload) -> {
                buffer.writeVarInt(payload.health);
                buffer.writeVarInt(payload.maxHealth);
                buffer.writeVarInt(payload.wave);
                buffer.writeVarInt(payload.remaining);
                buffer.writeVarInt(payload.daysToWave);
                buffer.writeVarInt(payload.subWave);
                buffer.writeVarInt(payload.spawned);
                buffer.writeVarInt(payload.target);
                buffer.writeVarInt(payload.nextSpawnTicks);
                buffer.writeVarInt(payload.nextSubWaveTicks);
                buffer.writeVarInt(payload.hitPulse);
                buffer.writeBoolean(payload.active);
            }, buffer -> new SteamHudPayload(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
