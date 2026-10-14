package dev.dzenthai.storybound.client.menu;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** Loads {@code storybound:theme} from {@code assets/storybound/storybound/menu/}; reloaded with F3+T. */
public final class MenuThemes extends SimpleJsonResourceReloadListener {
    public static final MenuThemes INSTANCE = new MenuThemes();
    private static final ResourceLocation THEME = Storybound.id("theme");

    private volatile MenuTheme theme = MenuTheme.FALLBACK;

    private MenuThemes() {
        super(new GsonBuilder().create(), "storybound/menu");
    }

    public MenuTheme theme() {
        return theme;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        JsonElement json = files.get(THEME);
        theme = json == null ? MenuTheme.FALLBACK : MenuTheme.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> Storybound.LOGGER.error("Storybound: invalid menu theme: {}", error))
                .orElse(MenuTheme.FALLBACK);
    }
}
