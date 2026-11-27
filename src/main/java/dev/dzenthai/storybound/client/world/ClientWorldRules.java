package dev.dzenthai.storybound.client.world;

import dev.dzenthai.storybound.compat.jei.JeiWorldLocks;
import dev.dzenthai.storybound.core.text.ResolvedText;
import dev.dzenthai.storybound.network.WorldLocksPayload;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Client-side view of active item locks received via {@link WorldLocksPayload}: renders lock tooltips,
 * previews craft blocking in crafting tables/furnaces, and updates optional JEI hiding.
 */
public final class ClientWorldRules {
    private record ActiveLock(byte flags, Optional<ResolvedText> message) {
        ActiveLock merge(byte extraFlags, Optional<ResolvedText> extraMessage) {
            return new ActiveLock((byte) (flags | extraFlags), message.isPresent() ? message : extraMessage);
        }
    }

    private static volatile Map<Item, ActiveLock> locked = Map.of();
    private static volatile Set<Item> hiddenInJei = Set.of();

    private ClientWorldRules() {}

    public static void apply(List<WorldLocksPayload.Entry> entries) {
        Map<Item, ActiveLock> next = new IdentityHashMap<>();
        Set<Item> nextJei = new LinkedHashSet<>();
        for (WorldLocksPayload.Entry entry : entries) {
            for (ResourceLocation itemId : entry.items()) {
                Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
                if (item == null) {
                    continue;
                }
                next.merge(item, new ActiveLock(entry.flags(), entry.message()),
                        (existing, added) -> existing.merge(added.flags(), added.message()));
                if ((entry.flags() & WorldLocksPayload.FLAG_HIDE_JEI) != 0) {
                    nextJei.add(item);
                }
            }
        }
        locked = next;
        hiddenInJei = Set.copyOf(nextJei);
        refreshJei();
    }

    public static void clear() {
        locked = Map.of();
        hiddenInJei = Set.of();
        refreshJei();
    }

    public static Set<Item> hiddenInJei() {
        return hiddenInJei;
    }

    public static boolean isCraftBlocked(ItemStack stack) {
        if (stack.isEmpty() || locked.isEmpty()) {
            return false;
        }
        Player player = Minecraft.getInstance().player;
        if (player != null && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        ActiveLock lock = locked.get(stack.getItem());
        return lock != null && (lock.flags() & WorldLocksPayload.FLAG_CRAFT) != 0;
    }

    public static void onItemTooltip(ItemTooltipEvent event) {
        if (locked.isEmpty()) {
            return;
        }
        Player player = event.getEntity();
        if (player != null && (player.isCreative() || player.isSpectator())) {
            return;
        }
        ActiveLock lock = locked.get(event.getItemStack().getItem());
        if (lock == null) {
            return;
        }
        byte flags = lock.flags();
        List<String> actions = new ArrayList<>();
        if ((flags & WorldLocksPayload.FLAG_USE) != 0) actions.add(Component.translatable("storybound.world.lock.use").getString());
        if ((flags & WorldLocksPayload.FLAG_ATTACK) != 0) actions.add(Component.translatable("storybound.world.lock.attack").getString());
        if ((flags & WorldLocksPayload.FLAG_EQUIP) != 0) actions.add(Component.translatable("storybound.world.lock.equip").getString());
        if ((flags & WorldLocksPayload.FLAG_CRAFT) != 0) actions.add(Component.translatable("storybound.world.lock.craft").getString());
        if ((flags & WorldLocksPayload.FLAG_PICKUP) != 0) actions.add(Component.translatable("storybound.world.lock.pickup").getString());
        // If a rule only hides the item in JEI without blocking any actions, do not stamp a misleading red lock tooltip.
        if (actions.isEmpty()) {
            return;
        }
        Component header = lock.message().map(ResolvedText::toComponent)
                .orElseGet(() -> Component.translatable("storybound.world.item_locked_tooltip"));
        event.getToolTip().add(Component.literal("✕ ").append(header).withStyle(ChatFormatting.RED));
        event.getToolTip().add(Component.translatable("storybound.world.lock.actions", String.join(", ", actions))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void refreshJei() {
        if (ModList.get().isLoaded("jei")) {
            JeiWorldLocks.refresh();
        }
    }
}

