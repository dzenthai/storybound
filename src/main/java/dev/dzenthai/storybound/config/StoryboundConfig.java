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
        public final ModConfigSpec.IntValue commandPermissionLevel;
        public final ModConfigSpec.DoubleValue dialogueMaxDistance;
        public final ModConfigSpec.BooleanValue dialogueInterruptOnDamage;
        public final ModConfigSpec.BooleanValue cameraAllowed;
        public final ModConfigSpec.IntValue seenRadius;
        public final ModConfigSpec.IntValue seenAngle;
        public final ModConfigSpec.IntValue seenTicks;
        public final ModConfigSpec.IntValue killCreditRadius;

        Common(ModConfigSpec.Builder builder) {
            builder.comment("Storybound modules. A disabled module subscribes no triggers, refuses to change its state and hides its tab.")
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

            builder.translation("storybound.configuration.actions").push("actions");
            commandPermissionLevel = builder
                    .comment("Permission level of commands run by the run_command action (0-4). 2 matches command blocks.")
                    .translation("storybound.configuration.commandPermissionLevel")
                    .defineInRange("commandPermissionLevel", 2, 0, 4);
            builder.pop();

            builder.translation("storybound.configuration.dialogue").push("dialogues");
            dialogueMaxDistance = builder
                    .comment("A dialogue is interrupted when the player is farther than this from the NPC, in blocks.")
                    .translation("storybound.configuration.maxDistance")
                    .defineInRange("maxDistance", 8.0, 2.0, 64.0);
            dialogueInterruptOnDamage = builder
                    .comment("Taking damage interrupts the player's dialogue.")
                    .translation("storybound.configuration.interruptOnDamage")
                    .define("interruptOnDamage", true);
            builder.pop();

            builder.translation("storybound.configuration.camera").push("camera");
            cameraAllowed = builder
                    .comment("Dialogues on the dialogue screen may move players' cameras (shot-reverse-shot).",
                            "Each player can still turn it off in their client config; a dialogue can turn it off with \"camera\": \"off\".")
                    .translation("storybound.configuration.cameraAllowed")
                    .define("allowed", true);
            builder.pop();

            builder.comment("How the server decides that a player saw an entity (the entity_seen trigger, Codex and Bosses)",
                            "and who else gets credit for a kill.")
                    .translation("storybound.configuration.detection").push("detection");
            seenRadius = builder.comment("Entities farther than this, in blocks, are never seen.")
                    .translation("storybound.configuration.seenRadius")
                    .defineInRange("seenRadius", 24, 4, 128);
            seenAngle = builder.comment("Half the field of view, in degrees: the entity must be within this angle of where the player looks.")
                    .translation("storybound.configuration.seenAngle")
                    .defineInRange("seenAngle", 45, 5, 90);
            seenTicks = builder.comment("How long, in ticks, the entity must be in view and line of sight (in total) to count as seen.")
                    .translation("storybound.configuration.seenTicks")
                    .defineInRange("seenTicks", 20, 10, 600);
            killCreditRadius = builder.comment("Party members within this many blocks of a killed mob share the kill (shared entity_killed, Codex, Bosses).")
                    .translation("storybound.configuration.killCreditRadius")
                    .defineInRange("killCreditRadius", 48, 0, 256);
            builder.pop();
        }
    }
}
