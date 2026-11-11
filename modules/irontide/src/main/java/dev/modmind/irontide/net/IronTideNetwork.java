package dev.modmind.irontide.net;

import dev.modmind.irontide.ModMindEntry;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络注册。目前只有一条服务端 → 客户端的蒸汽值同步。
 * <p>
 * 客户端处理类放在 {@code client} 包里，lambda 体内才引用它——专用服务器不会执行这段，
 * 类也就不会被解析，不会触发客户端类缺失。
 */
@EventBusSubscriber(modid = ModMindEntry.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class IronTideNetwork {
    private IronTideNetwork() {
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
                SteamHudPayload.TYPE,
                SteamHudPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> dev.modmind.irontide.client.SteamHudClientState.accept(payload)));
    }
}
