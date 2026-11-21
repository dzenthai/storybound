package dev.dzenthai.storybound.client;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Per-player display settings ({@code config/storybound-client.toml}). */
public final class StoryboundClientConfig {
    public static final ModConfigSpec SPEC;
    public static final StoryboundClientConfig INSTANCE;

    public final ModConfigSpec.IntValue hudSize;
    public final ModConfigSpec.BooleanValue questTracker;
    /** Whether the player has opened the Adventure Menu once: until then the quest tracker says which key opens it. */
    public final ModConfigSpec.BooleanValue menuOpened;
    public final ModConfigSpec.EnumValue<Corner> questTrackerCorner;
    public final ModConfigSpec.EnumValue<Corner> reputationCorner;
    public final ModConfigSpec.IntValue questTrackerOffsetY;
    public final ModConfigSpec.IntValue textSpeed;
    public final ModConfigSpec.BooleanValue speechBubbles;
    public final ModConfigSpec.DoubleValue thinkPause;
    public final ModConfigSpec.EnumValue<Untracked> untrackedQuestWaypoints;
    public final ModConfigSpec.BooleanValue waypointMarkers;
    public final ModConfigSpec.EnumValue<EdgeArrows> waypointEdgeArrows;
    public final ModConfigSpec.DoubleValue waypointMarkerScale;
    public final ModConfigSpec.DoubleValue waypointMarkerOpacity;
    public final ModConfigSpec.BooleanValue waypointAreaRings;
    public final ModConfigSpec.BooleanValue cameraEnabled;
    public final ModConfigSpec.BooleanValue cameraFloating;
    public final ModConfigSpec.ConfigValue<List<? extends String>> cameraDisableWith;

    static {
        Pair<StoryboundClientConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(StoryboundClientConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private StoryboundClientConfig(ModConfigSpec.Builder builder) {
        builder.translation("storybound.configuration.hud").push("hud");
        hudSize = builder.comment("Size of Storybound's HUD (observation bar, quest tracker, waypoint markers): 0 follows Minecraft's GUI scale",
                        "(never under 2 screen pixels to a HUD pixel at 1080p), each step one screen pixel more or less.")
                .translation("storybound.configuration.hudSize")
                .defineInRange("size", 0, -3, 3);
        questTracker =builder.comment("Show the tracked quest and its objectives on the screen.")
                .translation("storybound.configuration.questTracker")
                .define("questTracker", true);
        menuOpened = builder.comment("Set once the Adventure Menu has been opened; until then the quest tracker shows which key opens it.")
                .translation("storybound.configuration.menuOpened")
                .define("menuOpened", false);
        questTrackerCorner = builder.comment("Screen corner the quest tracker is anchored to.")
                .translation("storybound.configuration.questTrackerCorner")
                .defineEnum("questTrackerCorner", Corner.TOP_RIGHT);
        questTrackerOffsetY = builder.comment("Distance from the top or bottom edge, in HUD pixels (see size); leaves room for a minimap.")
                .translation("storybound.configuration.questTrackerOffsetY")
                .defineInRange("questTrackerOffsetY", 70, 0, 1000);
        reputationCorner = builder.comment("Screen corner where your standing with an NPC shows (stars or skulls).")
                .translation("storybound.configuration.reputationCorner")
                .defineEnum("reputationCorner", Corner.TOP_LEFT);
        builder.pop();

        builder.translation("storybound.configuration.dialogue").push("dialogue");
        textSpeed = builder.comment("How fast dialogue text is printed, in characters per second; 0 shows it at once.")
                .translation("storybound.configuration.textSpeed")
                .defineInRange("textSpeed", 45, 0, 500);
        thinkPause = builder.comment("How long NPCs think before answering an option (the dots), as a multiple of the usual pause; 0 answers at once. Never longer than 4 seconds.")
                .translation("storybound.configuration.thinkPause")
                .defineInRange("thinkPause", 1.0, 0.0, 2.0);
        speechBubbles = builder.comment("Show what NPCs and players say in other players' dialogues above their heads.")
                .translation("storybound.configuration.speechBubbles")
                .define("speechBubbles", true);
        builder.pop();

        builder.translation("storybound.configuration.waypoints").push("waypoints");
        untrackedQuestWaypoints = builder.comment("Waypoints of quests you do not track (markers and map mods): HIDE them (only the tracked quest is shown), DIM them, or SHOW them like the others.",
                        "Waypoints that belong to no quest are always shown.")
                .translation("storybound.configuration.untrackedQuestWaypoints")
                .defineEnum("untrackedQuestWaypoints", Untracked.HIDE);
        waypointMarkers = builder.comment("Draw waypoint markers in the world (icon, distance, name); map mods show waypoints on their own.")
                .translation("storybound.configuration.waypointMarkers")
                .define("markers", true);
        waypointEdgeArrows = builder.comment("Which markers get an arrow at the screen edge while you look away from them: the TRACKED quest's, ALL, or NEVER.",
                        "A resource pack draws the arrow; this decides whether it shows.")
                .translation("storybound.configuration.waypointEdgeArrows")
                .defineEnum("edgeArrows", EdgeArrows.TRACKED);
        waypointMarkerScale = builder.comment("Size of waypoint markers.")
                .translation("storybound.configuration.waypointMarkerScale")
                .defineInRange("markerScale", 1.0, 0.5, 2.5);
        waypointMarkerOpacity = builder.comment("Opacity of waypoint markers.")
                .translation("storybound.configuration.waypointMarkerOpacity")
                .defineInRange("markerOpacity", 1.0, 0.1, 1.0);
        waypointAreaRings = builder.comment("Draw the edge of search areas on the ground, near you.")
                .translation("storybound.configuration.waypointAreaRings")
                .define("areaRings", true);
        builder.pop();

        builder.translation("storybound.configuration.camera").push("camera");
        cameraEnabled = builder.comment("Move the camera in dialogues (over the shoulder of whoever listens), when the server and the dialogue allow it.")
                .translation("storybound.configuration.cameraEnabled")
                .define("enabled", true);
        cameraFloating = builder.comment("While a line plays, the mouse turns the dialogue camera a little (up and down inverted) and the cursor hides; left alone, and while you answer, it glides back.")
                .translation("storybound.configuration.cameraFloating")
                .define("floating", true);
        cameraDisableWith = builder.comment("Mod ids: the dialogue camera stays off when any of these is installed, e.g. a camera mod that conflicts with it.")
                .translation("storybound.configuration.cameraDisableWith")
                .defineListAllowEmpty("disableWith", List.of(), () -> "", value -> value instanceof String);
        builder.pop();
    }

    public enum Untracked {
        SHOW,
        DIM,
        HIDE
    }

    /**
     * Which off-screen markers get an edge arrow.
     */
    public enum EdgeArrows {
        TRACKED,
        ALL,
        NEVER
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
