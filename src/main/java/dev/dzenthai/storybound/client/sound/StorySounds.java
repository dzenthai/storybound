package dev.dzenthai.storybound.client.sound;

import dev.dzenthai.storybound.core.data.ConfigStorySource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The story's own sounds (October 30, 2026; before, a sound needed a resource pack made by hand): every
 * {@code .ogg} in {@code config/storybound/sounds/<namespace>/} is a sound event of that namespace, through a resource
 * pack the mod makes of the folder, always on. A modpack ships the folder with its config, so players hear them too;
 * the server only names them. Added files load with the resources (F3+T, or "Reload the sounds" in the editor's list).
 */
public final class StorySounds {
    public static final String FOLDER = "sounds";
    private static final String PACK_ID = "storybound_story_sounds";
    private static volatile StorySoundFiles.Scan last = StorySoundFiles.Scan.EMPTY;

    private StorySounds() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(StorySounds::onAddPackFinders);
    }

    public static Path folder() {
        return ConfigStorySource.root().resolve(FOLDER);
    }

    /** The folder as the resources last loaded it: the sounds, and what is wrong with files. */
    public static StorySoundFiles.Scan last() {
        return last;
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

    /** The folder as a resource pack: {@code assets/<ns>/sounds.json} made from the files, the files as they are. */
    private static final class FolderPack extends AbstractPackResources {
        private final StorySoundFiles.Scan scan;

        FolderPack(PackLocationInfo location) {
            super(location);
            this.scan = StorySoundFiles.scan(folder());
            last = scan;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
            if (elements.length == 1 && elements[0].equals(PACK_META)) {
                int format = SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
                return text("{\"pack\":{\"pack_format\":" + format + ",\"description\":\"Storybound: story sounds\"}}");
            }
            return null;
        }

        @Override
        public @Nullable IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            if (type != PackType.CLIENT_RESOURCES) {
                return null;
            }
            Map<String, StorySoundFiles.Sound> sounds = scan.in(location.getNamespace());
            if (sounds.isEmpty()) {
                return null;
            }
            String path = location.getPath();
            if (path.equals("sounds.json")) {
                return text(StorySoundFiles.soundsJson(sounds));
            }
            if (path.startsWith("sounds/") && path.endsWith(".ogg")) {
                StorySoundFiles.Sound sound = sounds.get(path.substring("sounds/".length(), path.length() - ".ogg".length()));
                return sound != null && Files.isRegularFile(sound.file()) ? IoSupplier.create(sound.file()) : null;
            }
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES || !(path.equals("sounds") || path.startsWith("sounds/"))) {
                return;
            }
            scan.in(namespace).forEach((name, sound) -> {
                String file = "sounds/" + name + ".ogg";
                if (file.startsWith(path)) {
                    output.accept(ResourceLocation.fromNamespaceAndPath(namespace, file), IoSupplier.create(sound.file()));
                }
            });
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            Set<String> namespaces = new TreeSet<>();
            if (type == PackType.CLIENT_RESOURCES) {
                scan.sounds().forEach(sound -> namespaces.add(sound.namespace()));
            }
            return namespaces;
        }

        @Override
        public void close() {}

        private static IoSupplier<InputStream> text(String text) {
            byte[] bytes = StorySoundFiles.bytes(text);
            return () -> new ByteArrayInputStream(bytes);
        }
    }
}
