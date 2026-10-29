package dev.dzenthai.storybound.client.sound;

import dev.dzenthai.storybound.core.data.ConfigStorySource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The story's own sounds and item icons (October 30, 2026; before, either needed a resource pack made by hand): every
 * {@code .ogg} in {@code config/storybound/sounds/<namespace>/} is a sound event of that namespace, every {@code .png}
 * in {@code config/storybound/icons/<namespace>/} an icon a document's item can show, through a resource pack the mod
 * makes of the two folders, always on. A modpack ships the folders with its config, so players hear and see them; the
 * server only names them. Added files load with the resources (F3+T, or "Reload" in the editor's lists).
 */
public final class StoryAssets {
    public static final String SOUNDS = "sounds";
    public static final String ICONS = "icons";
    private static final String PACK_ID = "storybound_story_sounds";
    private static volatile StorySoundFiles.Scan last = StorySoundFiles.Scan.EMPTY;
    private static volatile StoryIconFiles.Scan lastIcons = StoryIconFiles.Scan.EMPTY;

    private StoryAssets() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(StoryAssets::onAddPackFinders);
    }

    public static Path folder() {
        return ConfigStorySource.root().resolve(SOUNDS);
    }

    public static Path iconFolder() {
        return ConfigStorySource.root().resolve(ICONS);
    }

    /** The sounds folder as the resources last loaded it: the sounds, and what is wrong with files. */
    public static StorySoundFiles.Scan last() {
        return last;
    }

    /** The icons folder as the resources last loaded it. */
    public static StoryIconFiles.Scan icons() {
        return lastIcons;
    }

    private static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        PackLocationInfo info = new PackLocationInfo(PACK_ID, Component.translatable("storybound.pack.story_sounds"), PackSource.BUILT_IN, Optional.empty());
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return new FolderPack(location);
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return new FolderPack(location);
            }
        };
        Pack pack = Pack.readMetaAndCreate(info, resources, PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) {
            event.addRepositorySource(packs -> packs.accept(pack));
        }
    }

    /**
     * The two folders as a resource pack, every file it serves by namespace and path: {@code sounds.json} made from the
     * sounds and the sounds as they are; for each icon its model and texture ({@code item/story_icon/<name>}), and the
     * document item's model with an override per icon.
     */
    private static final class FolderPack extends AbstractPackResources {
        private final Map<String, Map<String, IoSupplier<InputStream>>> files = new LinkedHashMap<>();

        FolderPack(PackLocationInfo location) {
            super(location);
            StorySoundFiles.Scan sounds = StorySoundFiles.scan(folder());
            StoryIconFiles.Scan icons = StoryIconFiles.scan(iconFolder());
            last = sounds;
            lastIcons = icons;
            for (StorySoundFiles.Sound sound : sounds.sounds()) {
                Map<String, IoSupplier<InputStream>> namespace = files.computeIfAbsent(sound.namespace(), key -> new LinkedHashMap<>());
                namespace.putIfAbsent("sounds.json", text(StorySoundFiles.soundsJson(sounds.in(sound.namespace()))));
                namespace.put("sounds/" + sound.name() + ".ogg", IoSupplier.create(sound.file()));
            }
            for (StoryIconFiles.Icon icon : icons.icons()) {
                Map<String, IoSupplier<InputStream>> namespace = files.computeIfAbsent(icon.namespace(), key -> new LinkedHashMap<>());
                namespace.put("models/item/story_icon/" + icon.name() + ".json", text(StoryIconFiles.iconModel(icon)));
                namespace.put("textures/item/story_icon/" + icon.name() + ".png", IoSupplier.create(icon.file()));
            }
            if (!icons.icons().isEmpty()) {
                files.computeIfAbsent("storybound", key -> new LinkedHashMap<>()).put("models/item/document.json", text(StoryIconFiles.documentModel(icons.icons())));
            }
        }

        @Override
        public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
            if (elements.length == 1 && elements[0].equals(PACK_META)) {
                int format = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
                return text("{\"pack\":{\"pack_format\":" + format + ",\"description\":\"Storybound: story sounds and icons\"}}");
            }
            return null;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            if (type != PackType.CLIENT_RESOURCES) {
                return null;
            }
            IoSupplier<InputStream> file = files.getOrDefault(location.getNamespace(), Map.of()).get(location.getPath());
            return file;
        }

        /** What is under a path: the texture atlas lists {@code textures/item}, as it finds every item texture. */
        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES) {
                return;
            }
            files.getOrDefault(namespace, Map.of()).forEach((file, supplier) -> {
                if (file.startsWith(path + "/")) {
                    output.accept(ResourceLocation.fromNamespaceAndPath(namespace, file), supplier);
                }
            });
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.CLIENT_RESOURCES ? new TreeSet<>(files.keySet()) : Set.of();
        }

        @Override
        public void close() {}

        private static IoSupplier<InputStream> text(String text) {
            byte[] bytes = StorySoundFiles.bytes(text);
            return () -> new ByteArrayInputStream(bytes);
        }
    }
}
