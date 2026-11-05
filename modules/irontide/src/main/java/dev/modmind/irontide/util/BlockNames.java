package dev.modmind.irontide.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modmind.irontide.ModMindEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 方块 id 到显示名的解析器。
 *
 * <p>聊天栏走 {@link #chat(ResourceLocation)}，发出的是可翻译组件，
 * 客户端会按玩家自己的语言显示（中文环境即中文名）。
 *
 * <p>日志走 {@link #text(ResourceLocation)}，服务端日志没有客户端语言环境，
 * 因此直接从各模组 jar 内 {@code assets/<namespace>/lang/zh_cn.json} 建立字典；
 * 读不到时回落到内置常用方块表，再回落为原始 id。
 */
public final class BlockNames {
    private static final Map<String, String> BUILTIN = Map.of(
            "create:andesite_casing", "安山机壳",
            "create:brass_casing", "黄铜机壳",
            "create:copper_casing", "铜机壳",
            "create:railway_casing", "列车机壳",
            "create:gearbox", "十字齿轮箱",
            "irontide:andesite_core", "安山核心");

    private static final Map<ResourceLocation, String> ZH = new LinkedHashMap<>();
    private static volatile boolean loaded;

    private BlockNames() {
    }

    /** 服务端启动时调用一次；重复调用直接返回。 */
    public static synchronized void load(MinecraftServer server) {
        if (loaded) {
            return;
        }
        loaded = true;
        ZH.clear();
        for (Map.Entry<String, String> entry : BUILTIN.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id != null) {
                ZH.put(id, entry.getValue());
            }
        }
        try {
            ResourceManager manager = server.getResourceManager();
            Map<ResourceLocation, Resource> files =
                    manager.listResources("lang", path -> path.getPath().endsWith("zh_cn.json"));
            int parsed = 0;
            for (Map.Entry<ResourceLocation, Resource> entry : files.entrySet()) {
                try (var stream = entry.getValue().open()) {
                    JsonObject root = JsonParser
                            .parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    for (Map.Entry<String, JsonElement> kv : root.entrySet()) {
                        String key = kv.getKey();
                        if (!key.startsWith("block.")) {
                            continue;
                        }
                        String[] parts = key.split("\\.", 3);
                        if (parts.length != 3) {
                            continue;
                        }
                        ResourceLocation id = ResourceLocation.tryParse(parts[1] + ":" + parts[2]);
                        if (id == null || !kv.getValue().isJsonPrimitive()) {
                            continue;
                        }
                        ZH.put(id, kv.getValue().getAsString());
                    }
                    parsed++;
                } catch (Exception exception) {
                    ModMindEntry.LOGGER.debug("[IronTide] skipped lang file {}: {}",
                            entry.getKey(), exception.getMessage());
                }
            }
            ModMindEntry.LOGGER.info("[IronTide] block name dictionary: {} lang files, {} entries",
                    parsed, ZH.size());
        } catch (Exception exception) {
            ModMindEntry.LOGGER.warn("[IronTide] could not read zh_cn lang files, using builtin names", exception);
        }
    }

    /** 日志用：纯文本中文名，查不到回落 id。 */
    public static String text(ResourceLocation id) {
        if (id == null) {
            return "未知方块";
        }
        String name = ZH.get(id);
        return name != null ? name : id.toString();
    }

    /** 聊天栏用：可翻译组件，客户端按玩家语言渲染。 */
    public static Component chat(ResourceLocation id) {
        if (id == null) {
            return Component.literal("未知方块");
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block != null) {
            return Component.translatable(block.getDescriptionId());
        }
        return Component.literal(text(id));
    }
}
