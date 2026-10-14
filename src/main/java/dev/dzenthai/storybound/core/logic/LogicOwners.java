package dev.dzenthai.storybound.core.logic;

import dev.dzenthai.storybound.core.data.DefinitionType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.minecraft.resources.ResourceLocation;

/**
 * Definition types whose entries contain conditions, actions or triggers (story events, documents,
 * quests, dialogues). Validators walk all of them, so a reference is checked wherever it is written.
 */
public final class LogicOwners {
    private static final List<Owner<?>> OWNERS = new CopyOnWriteArrayList<>();

    private LogicOwners() {}

    /** @param walker passes every condition, action and trigger of one entry to the given references */
    public static <T> void register(DefinitionType<T> type, BiConsumer<T, References> walker) {
        OWNERS.add(new Owner<>(type, walker));
    }

    /** Walks every loaded entry of every owner type; {@code factory} makes the receiver for one entry. */
    public static void walkAll(BiFunction<DefinitionType<?>, ResourceLocation, References> factory) {
        for (Owner<?> owner : OWNERS) {
            owner.walk(factory);
        }
    }

    private record Owner<T>(DefinitionType<T> type, BiConsumer<T, References> walker) {
        void walk(BiFunction<DefinitionType<?>, ResourceLocation, References> factory) {
            type.serverStore().all().forEach((id, value) -> walker.accept(value, factory.apply(type, id)));
        }
    }
}
