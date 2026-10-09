package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Exactly one of {@code "item": "minecraft:emerald"} or {@code "tag": "c:gems"}, inlined into the owner object. */
public record ItemMatcher(Optional<Holder<Item>> item, Optional<TagKey<Item>> tag) {
    public static final MapCodec<ItemMatcher> MAP_CODEC = RecordCodecBuilder.<ItemMatcher>mapCodec(instance -> instance.group(
            ItemStack.ITEM_NON_AIR_CODEC.optionalFieldOf("item").forGetter(ItemMatcher::item),
            TagKey.codec(Registries.ITEM).optionalFieldOf("tag").forGetter(ItemMatcher::tag)
    ).apply(instance, ItemMatcher::new)).validate(matcher -> matcher.item().isPresent() != matcher.tag().isPresent()
            ? DataResult.success(matcher)
            : DataResult.error(() -> "Exactly one of 'item' or 'tag' is required"));

    public boolean test(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return item.map(stack::is).orElseGet(() -> stack.is(tag.orElseThrow()));
    }
}
