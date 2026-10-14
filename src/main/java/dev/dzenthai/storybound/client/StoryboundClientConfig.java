package dev.dzenthai.storybound.client;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Per-player display settings ({@code config/storybound-client.toml}). */
public final class StoryboundClientConfig {
    public static final ModConfigSpec SPEC;
    public static final StoryboundClientConfig INSTANCE;

    public final ModConfigSpec.BooleanValue questTracker;
    public final ModConfigSpec.EnumValue<Corner> questTrackerCorner;
    public final ModConfigSpec.IntValue questTrackerOffsetY;
    public final ModConfigSpec.IntValue textSpeed;
    public final ModConfigSpec.BooleanValue speechBubbles;

    static {
        Pair<StoryboundClientConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(StoryboundClientConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private StoryboundClientConfig(ModConfigSpec.Builder builder) {
        builder.translation("storybound.configuration.hud").push("hud");
        questTracker = builder.comment("Show the tracked quest and its objectives on the screen.")
                .translation("storybound.configuration.questTracker")
                .define("questTracker", true);
        questTrackerCorner = builder.comment("Screen corner the quest tracker is anchored to.")
                .translation("storybound.configuration.questTrackerCorner")
                .defineEnum("questTrackerCorner", Corner.TOP_RIGHT);
        questTrackerOffsetY = builder.comment("Distance from the top or bottom edge, in GUI pixels; leaves room for a minimap.")
                .translation("storybound.configuration.questTrackerOffsetY")
                .defineInRange("questTrackerOffsetY", 70, 0, 1000);
        builder.pop();

        builder.translation("storybound.configuration.dialogue").push("dialogue");
        textSpeed = builder.comment("How fast dialogue text is printed, in characters per second; 0 shows it at once.")
                .translation("storybound.configuration.textSpeed")
                .defineInRange("textSpeed", 45, 0, 500);
        speechBubbles = builder.comment("Show what NPCs and players say in other players' dialogues above their heads.")
                .translation("storybound.configuration.speechBubbles")
                .define("speechBubbles", true);
        builder.pop();
    }

    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT;

        public boolean right() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean bottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }
    }
}
