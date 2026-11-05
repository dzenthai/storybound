package dev.dzenthai.storybound.client;

import dev.dzenthai.storybound.client.document.DocumentScreen;
import dev.dzenthai.storybound.core.notify.NotifyKind;
import dev.dzenthai.storybound.core.text.ResolvedText;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Client-only reactions to packets; kept out of the payload classes so they load on a dedicated server. */
public final class ClientPayloads {
    private ClientPayloads() {}

    /** @param lectern where the book lies, when it was opened from a lectern; null otherwise */
    public static void openDocument(ResourceLocation document, @Nullable BlockPos lectern) {
        Minecraft.getInstance().setScreen(new DocumentScreen(document, null).fromLectern(lectern));
    }

    public static void notify(NotifyKind kind, ResolvedText title, Optional<ResolvedText> text) {
        Minecraft.getInstance().getToasts().addToast(new StoryToast(kind, title.toComponent(), text.map(ResolvedText::toComponent).orElse(null)));
    }
}
