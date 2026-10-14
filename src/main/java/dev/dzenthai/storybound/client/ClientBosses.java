package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.module.boss.BossData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** The bosses the local player has met, as last sent by the server. */
public final class ClientBosses {
    private static volatile Map<ResourceLocation, BossData.Record> records = Map.of();

    /** Incremented on every change (on the main thread); open views compare it to know when to refresh. */
    private static volatile int version;

    private ClientBosses() {}

    public static int version() {
        return version;
    }

    public static void apply(List<BossData.Record> list) {
        Map<ResourceLocation, BossData.Record> map = new LinkedHashMap<>();
        list.forEach(record -> map.put(record.boss(), record));
        records = map;
        version++;
    }

    public static Map<ResourceLocation, BossData.Record> all() {
        return records;
    }

    public static void clear() {
        records = Map.of();
        version++;
    }
}
