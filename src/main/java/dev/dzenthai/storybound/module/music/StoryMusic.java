package dev.dzenthai.storybound.module.music;

import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.state.QuestProgress;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.quest.QuestDefinition;
import dev.dzenthai.storybound.module.quest.Quests;
import dev.dzenthai.storybound.network.MusicPayload;
import dev.dzenthai.storybound.network.StoryboundNetwork;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The story's music for each player: the track of the dialogue they are in, else of the current stage of their tracked
 * quest, else of a place whose conditions hold (the highest priority); none, and vanilla's music plays. Worked out every
 * half second and sent to the client when it changes; the client fades from one track to the next.
 */
public final class StoryMusic {
    private static final int CHECK_TICKS = 10;
    /** What each player was last told to play. */
    private static final Map<UUID, Optional<ResourceLocation>> PLAYING = new HashMap<>();

    private StoryMusic() {}

    static void register(IEventBus gameBus) {
        gameBus.addListener((ServerTickEvent.Post event) -> {
            if (event.getServer().getTickCount() % CHECK_TICKS == 0) {
                event.getServer().getPlayerList().getPlayers().forEach(StoryMusic::update);
            }
        });
        gameBus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> PLAYING.remove(event.getEntity().getUUID()));
        gameBus.addListener((ServerStoppedEvent event) -> PLAYING.clear());
    }

    private static void update(ServerPlayer player) {
        Optional<ResourceLocation> track = track(player);
        if (!track.equals(PLAYING.get(player.getUUID()))) {
            PLAYING.put(player.getUUID(), track);
            StoryboundNetwork.sendToPlayer(player, new MusicPayload(track));
        }
    }

    /** The track the player should hear now: a dialogue's, a stage's, a place's; empty for vanilla's own music. */
    public static Optional<ResourceLocation> track(ServerPlayer player) {
        Optional<ResourceLocation> dialogue = Dialogues.music(player);
        if (dialogue.isPresent()) {
            return dialogue;
        }
        Optional<ResourceLocation> stage = Quests.tracked(player).flatMap(quest -> {
            QuestDefinition definition = Quests.definition(quest);
            Optional<QuestProgress> progress = Quests.progress(player, quest);
            if (definition == null || progress.isEmpty() || progress.get().status() != dev.dzenthai.storybound.core.state.QuestStatus.ACTIVE) {
                return Optional.empty();
            }
            return progress.get().stage().flatMap(definition::stage).flatMap(QuestDefinition.Stage::music);
        });
        if (stage.isPresent()) {
            return stage;
        }
        StoryContext context = StoryContext.of(player);
        return MusicRegistries.MUSIC.serverStore().all().entrySet().stream()
                .filter(entry -> entry.getValue().conditions().map(condition -> condition.test(context)).orElse(true))
                .sorted(Comparator.comparingInt((Map.Entry<ResourceLocation, MusicDefinition> entry) -> -entry.getValue().priority())
                        .thenComparing(entry -> entry.getKey().toString()))
                .map(entry -> entry.getValue().track())
                .findFirst();
    }
}
