package dev.dzenthai.storybound.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public final class StoryboundConfig {
    public static final ModConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        COMMON_SPEC = pair.getRight();
    }

    private StoryboundConfig() {}

    /**
     * COMMON rather than SERVER: the server config is read only after the first datapack load,
     * and a disabled module must not load its data at all.
     */
    public static final class Common {
        public final ModConfigSpec.BooleanValue quests;
        public final ModConfigSpec.BooleanValue journal;
        public final ModConfigSpec.BooleanValue codex;
        public final ModConfigSpec.BooleanValue bosses;
        public final ModConfigSpec.BooleanValue waypoints;
        public final ModConfigSpec.BooleanValue dialogues;
        public final ModConfigSpec.BooleanValue copyPartyStateOnLeave;

        Common(ModConfigSpec.Builder builder) {
            builder.comment("Storybound modules. A disabled module loads no data, registers no triggers and sends no packets.")
                    .translation("storybound.configuration.modules")
                    .push("modules");
            quests = builder.translation("storybound.configuration.quests").define("quests", true);
            journal = builder.translation("storybound.configuration.journal").define("journal", true);
            codex = builder.translation("storybound.configuration.codex").define("codex", true);
            bosses = builder.translation("storybound.configuration.bosses").define("bosses", true);
            waypoints = builder.translation("storybound.configuration.waypoints").define("waypoints", true);
            dialogues = builder.translation("storybound.configuration.dialogues").define("dialogues", true);
            builder.pop();

            builder.translation("storybound.configuration.party").push("party");
            copyPartyStateOnLeave = builder
                    .comment("A player who leaves a party (or whose party is disbanded) keeps a copy of the party's story progress.",
                            "If false, they return to the solo progress they had before joining.")
                    .translation("storybound.configuration.copyStateOnLeave")
                    .define("copyStateOnLeave", true);
            builder.pop();
        }
    }
}
