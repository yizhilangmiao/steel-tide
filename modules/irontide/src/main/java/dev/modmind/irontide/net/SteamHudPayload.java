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
public record SteamHudPayload(int health, int maxHealth, int wave, int remaining, boolean active)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SteamHudPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ModMindEntry.MOD_ID, "steam_hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SteamHudPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SteamHudPayload::health,
                    ByteBufCodecs.VAR_INT, SteamHudPayload::maxHealth,
                    ByteBufCodecs.VAR_INT, SteamHudPayload::wave,
                    ByteBufCodecs.VAR_INT, SteamHudPayload::remaining,
                    ByteBufCodecs.BOOL, SteamHudPayload::active,
                    SteamHudPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
