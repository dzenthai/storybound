package dev.dzenthai.storybound.client.codex;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a creature by its type for Codex and Bosses entries. One client-only instance per type, never added
 * to the world; recreated when the level changes.
 */
final class EntityPortraits {
    private static final Map<EntityType<?>, LivingEntity> CACHE = new HashMap<>();
    private static @Nullable ClientLevel cachedFor;

    private EntityPortraits() {}

    /** The whole creature, scaled to fit the box and turning slightly towards the mouse. */
    static void render(GuiGraphics graphics, EntityType<?> type, int x, int y, int width, int height, int mouseX, int mouseY) {
        LivingEntity entity = get(type);
        if (entity == null) {
            return;
        }
        float size = Math.max(entity.getBbHeight(), entity.getBbWidth());
        int scale = Math.max(4, Math.round(height * 0.8f / Math.max(0.3f, size)));
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x, y, x + width, y + height, scale, 0.0625f, mouseX, mouseY, entity);
    }

    private static @Nullable LivingEntity get(EntityType<?> type) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        if (level != cachedFor) {
            CACHE.clear();
            cachedFor = level;
        }
        if (!CACHE.containsKey(type)) {
            Entity created = type.create(level);
            CACHE.put(type, created instanceof LivingEntity living ? living : null);
        }
        return CACHE.get(type);
    }

    static void clear() {
        CACHE.clear();
        cachedFor = null;
    }
}
