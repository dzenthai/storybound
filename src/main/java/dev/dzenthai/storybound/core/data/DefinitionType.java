package dev.dzenthai.storybound.core.data;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * A kind of data-driven definitions: where they load from (`data/<ns>/<directory>/`), how they are parsed
 * and whether clients receive them. Server and client stores are separate so that in singleplayer
 * the integrated server and the client never see each other's data.
 */
public final class DefinitionType<T> {
    private final ResourceLocation id;
    private final String directory;
    private final Codec<T> codec;
    private final @Nullable Predicate<T> syncFilter;
    private final Supplier<StreamCodec<RegistryFriendlyByteBuf, T>> streamCodec;
    private final DefinitionStore<T> serverStore = new DefinitionStore<>();
    private final DefinitionStore<T> clientStore = new DefinitionStore<>();

    private DefinitionType(ResourceLocation id, String directory, Codec<T> codec, @Nullable Predicate<T> syncFilter) {
        this.id = id;
        this.directory = directory;
        this.codec = codec;
        this.syncFilter = syncFilter;
        this.streamCodec = Suppliers.memoize(() -> ByteBufCodecs.fromCodecWithRegistries(codec));
    }

    /** Definitions that never leave the server (dialogues, NPC bindings). */
    public static <T> DefinitionType<T> serverOnly(ResourceLocation id, String directory, Codec<T> codec) {
        return new DefinitionType<>(id, directory, codec, null);
    }

    /** Definitions the client receives in full (quests, Codex). */
    public static <T> DefinitionType<T> synced(ResourceLocation id, String directory, Codec<T> codec) {
        return new DefinitionType<>(id, directory, codec, value -> true);
    }

    /** The client receives only definitions that pass the filter. */
    public static <T> DefinitionType<T> synced(ResourceLocation id, String directory, Codec<T> codec, Predicate<T> filter) {
        return new DefinitionType<>(id, directory, codec, filter);
    }

    public ResourceLocation id() {
        return id;
    }

    public String directory() {
        return directory;
    }

    public Codec<T> codec() {
        return codec;
    }

    public StreamCodec<RegistryFriendlyByteBuf, T> streamCodec() {
        return streamCodec.get();
    }

    public boolean isSynced() {
        return syncFilter != null;
    }

    public DefinitionStore<T> serverStore() {
        return serverStore;
    }

    public DefinitionStore<T> clientStore() {
        return clientStore;
    }

    /** Server definitions to send to the client. */
    public Map<ResourceLocation, T> valuesToSync() {
        if (syncFilter == null) {
            return Map.of();
        }
        Map<ResourceLocation, T> result = new LinkedHashMap<>();
        serverStore.all().forEach((key, value) -> {
            if (syncFilter.test(value)) {
                result.put(key, value);
            }
        });
        return result;
    }

    @Override
    public String toString() {
        return "DefinitionType[" + id + "]";
    }
}
