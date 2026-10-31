package dev.dzenthai.storybound.client.menu;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.UiPalette;
import dev.dzenthai.storybound.core.data.ColorCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Colours and sprites of the Adventure Menu, from {@code assets/storybound/storybound/menu/theme.json}
 * (a resource pack overrides it). Sprites are GUI sprites, usually nine-sliced through their {@code .mcmeta}.
 */
public record MenuTheme(ResourceLocation panel, ResourceLocation tab, ResourceLocation tabSelected,
                        int title, int ink, int muted, int accent, int unseen, int completed, int failed,
                        int selection, int hover, int divider, int hudBackground, int hudTitle, int hudText) {
    public static final MenuTheme FALLBACK = new MenuTheme(
            Storybound.id("menu/panel"), Storybound.id("menu/tab"), Storybound.id("menu/tab_selected"),
            // As theme.json, in the author's palette (October 31, 2026); muted and the HUD's background are not in it.
            UiPalette.opaque(UiPalette.INK), UiPalette.opaque(UiPalette.INK), 0xFF806747, UiPalette.opaque(UiPalette.DARK_BROWN),
            UiPalette.opaque(UiPalette.OCHRE), UiPalette.opaque(UiPalette.GREEN_DARK), UiPalette.opaque(UiPalette.RED_DARK),
            0x60000000 | UiPalette.TAN_LIGHT, 0x30000000 | UiPalette.TAN, UiPalette.opaque(UiPalette.TAN), 0xA62A221C,
            UiPalette.opaque(UiPalette.GOLD_LIGHT), UiPalette.opaque(UiPalette.PAPER));

    /** {@code "#RRGGBB"} (opaque) or {@code "#AARRGGBB"}. */
    public static final Codec<Integer> COLOR = ColorCodec.CODEC;

    public static final Codec<MenuTheme> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.optionalFieldOf("panel", FALLBACK.panel).forGetter(MenuTheme::panel),
            ResourceLocation.CODEC.optionalFieldOf("tab", FALLBACK.tab).forGetter(MenuTheme::tab),
            ResourceLocation.CODEC.optionalFieldOf("tab_selected", FALLBACK.tabSelected).forGetter(MenuTheme::tabSelected),
            COLOR.optionalFieldOf("title", FALLBACK.title).forGetter(MenuTheme::title),
            COLOR.optionalFieldOf("ink", FALLBACK.ink).forGetter(MenuTheme::ink),
            COLOR.optionalFieldOf("muted", FALLBACK.muted).forGetter(MenuTheme::muted),
            COLOR.optionalFieldOf("accent", FALLBACK.accent).forGetter(MenuTheme::accent),
            COLOR.optionalFieldOf("unseen", FALLBACK.unseen).forGetter(MenuTheme::unseen),
            COLOR.optionalFieldOf("completed", FALLBACK.completed).forGetter(MenuTheme::completed),
            COLOR.optionalFieldOf("failed", FALLBACK.failed).forGetter(MenuTheme::failed),
            COLOR.optionalFieldOf("selection", FALLBACK.selection).forGetter(MenuTheme::selection),
            COLOR.optionalFieldOf("hover", FALLBACK.hover).forGetter(MenuTheme::hover),
            COLOR.optionalFieldOf("divider", FALLBACK.divider).forGetter(MenuTheme::divider),
            COLOR.optionalFieldOf("hud_background", FALLBACK.hudBackground).forGetter(MenuTheme::hudBackground),
            COLOR.optionalFieldOf("hud_title", FALLBACK.hudTitle).forGetter(MenuTheme::hudTitle),
            COLOR.optionalFieldOf("hud_text", FALLBACK.hudText).forGetter(MenuTheme::hudText)
    ).apply(instance, MenuTheme::new));
}
