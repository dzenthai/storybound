package dev.dzenthai.storybound.core.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dev.dzenthai.storybound.Storybound;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Ids where a missing namespace means ours: {@code "letters"} is {@code storybound:letters}. */
public final class StoryIds {
    public static final Codec<ResourceLocation> CODEC = Codec.STRING.comapFlatMap(
            name -> {
                ResourceLocation id = parse(name);
                return id != null ? DataResult.success(id) : DataResult.error(() -> "Invalid id: " + name);
            },
            id -> id.getNamespace().equals(Storybound.MOD_ID) ? id.getPath() : id.toString());

    private StoryIds() {}

    public static @Nullable ResourceLocation parse(String name) {
        return ResourceLocation.tryParse(name.indexOf(':') >= 0 ? name : Storybound.MOD_ID + ":" + name);
    }
}
