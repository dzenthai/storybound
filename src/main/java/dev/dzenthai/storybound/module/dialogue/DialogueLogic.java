package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.Trigger;
import dev.dzenthai.storybound.core.logic.TriggerType;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.Merchant;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/** The dialogue module's additions to the logic language. */
public final class DialogueLogic {
    static final DeferredRegister<ActionType<?>> REGISTER_ACTIONS = DeferredRegister.create(LogicRegistries.ACTION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<TriggerType<?, ?>> REGISTER_TRIGGERS = DeferredRegister.create(LogicRegistries.TRIGGER_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ActionType<?>, ActionType<StartDialogue>> START_DIALOGUE =
            REGISTER_ACTIONS.register("start_dialogue", () -> new ActionType<>(StartDialogue.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<EndDialogue>> END_DIALOGUE =
            REGISTER_ACTIONS.register("end_dialogue", () -> new ActionType<>(EndDialogue.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<OpenTrade>> OPEN_TRADE =
            REGISTER_ACTIONS.register("open_trade", () -> new ActionType<>(OpenTrade.CODEC));
    public static final DeferredHolder<TriggerType<?, ?>, TriggerType<DialogueEnded, Ended>> DIALOGUE_ENDED =
            REGISTER_TRIGGERS.register("dialogue_ended", () -> new TriggerType<>(DialogueEnded.CODEC));

    private DialogueLogic() {}

    /** The payload of {@code dialogue_ended}: the conversation's base dialogue and the last node it passed. */
    public record Ended(ResourceLocation dialogue, String node) {}

    /**
     * {@code { "type": "start_dialogue", "dialogue": "mypack:elder" }}. The context entity (the interacted NPC)
     * becomes the {@code bound} participant. Fails while the player is already talking; inside a dialogue use {@code jump} or {@code call}.
     */
    public record StartDialogue(ResourceLocation dialogue) implements Action {
        public static final MapCodec<StartDialogue> CODEC = ResourceLocation.CODEC.fieldOf("dialogue").xmap(StartDialogue::new, StartDialogue::dialogue);

        @Override
        public ActionType<?> type() {
            return START_DIALOGUE.get();
        }

        /**
         * The context entity becomes the speaker only for a dialogue that has one ({@code bound}) and only while it
         * lives: a monologue started by a kill would otherwise get the dead mob as its speaker and stop at once
         * ("the speaker is gone"). While the player talks, the dialogue waits for that to end (see
         * {@link Dialogues#startOrQueue}).
         */
        @Override
        public void run(StoryContext context) throws StoryException {
            DialogueDefinition definition = Dialogues.definition(dialogue);
            Entity speaker = context.entity();
            if (speaker != null && (!speaker.isAlive() || definition == null || !definition.hasBoundSpeaker())) {
                speaker = null;
            }
            if (!Dialogues.startOrQueue(context.player(), dialogue, speaker)) {
                throw new StoryException(Component.translatableWithFallback("storybound.dialogue.cannot_start",
                        "Dialogue %s cannot start: unknown dialogue, the player is already talking, or the dialogue module is disabled", dialogue.toString()));
            }
        }

        @Override
        public void collectReferences(References references) {
            references.dialogue(dialogue);
        }
    }

    /** Ends the player's dialogue as finished, so {@code dialogue_ended} fires. Does nothing when they are not talking. */
    public record EndDialogue() implements Action {
        public static final MapCodec<EndDialogue> CODEC = MapCodec.unit(new EndDialogue());

        @Override
        public ActionType<?> type() {
            return END_DIALOGUE.get();
        }

        @Override
        public void run(StoryContext context) {
            Dialogues.end(context.player());
        }
    }

    /**
     * {@code { "type": "open_trade" }}: ends the dialogue and opens the context entity's trading screen ("Show me your
     * wares"). Villagers and wandering traders open it the vanilla way, so reputation discounts and Hero of the Village
     * apply; any other merchant (a modded NPC) opens it directly. Fails when the entity is not a merchant, has no
     * offers or trades with someone else.
     */
    public record OpenTrade() implements Action {
        public static final MapCodec<OpenTrade> CODEC = MapCodec.unit(new OpenTrade());

        @Override
        public ActionType<?> type() {
            return OPEN_TRADE.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            ServerPlayer player = context.player();
            Entity entity = context.entity();
            Dialogues.end(player);
            if (!(entity instanceof Merchant merchant)) {
                throw new StoryException(Component.translatableWithFallback("storybound.dialogue.not_merchant",
                        "Cannot open trade: context entity is not a merchant"));
            }
            if (merchant.getTradingPlayer() != null && merchant.getTradingPlayer() != player) {
                throw new StoryException(Component.translatableWithFallback("storybound.dialogue.merchant_busy",
                        "Cannot open trade: merchant is already trading with another player"));
            }
            if (merchant.getOffers().isEmpty()) {
                throw new StoryException(Component.translatableWithFallback("storybound.dialogue.no_offers",
                        "Cannot open trade: merchant has no offers"));
            }
            if (entity instanceof Villager villager) {
                if (villager.isBaby() || villager.isSleeping()) {
                    throw new StoryException(Component.translatableWithFallback("storybound.dialogue.trade_refused",
                            "Cannot open trade: the villager does not trade right now"));
                }
                if (START_TRADING != null) {
                    // The vanilla path: reputation discounts and Hero of the Village apply.
                    try {
                        START_TRADING.invoke(villager, player);
                        return;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        Storybound.LOGGER.warn("Storybound: Villager.startTrading failed, opening the trade directly", e);
                    }
                }
                merchant.setTradingPlayer(player);
                merchant.openTradingScreen(player, entity.getDisplayName(), villager.getVillagerData().getLevel());
                return;
            }
            merchant.setTradingPlayer(player);
            merchant.openTradingScreen(player, entity.getDisplayName(), 1);
        }

        /** {@code Villager#startTrading}: private; the same name at runtime, as NeoForge runs with Mojang's names. */
        private static final @Nullable Method START_TRADING = findStartTrading();

        private static @Nullable Method findStartTrading() {
            try {
                Method method = Villager.class.getDeclaredMethod("startTrading", Player.class);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Storybound.LOGGER.warn("Storybound: Villager.startTrading not found; villagers open trades without discounts", e);
                return null;
            }
        }
    }

    /** Fires when a dialogue finishes (not when it is interrupted; that is {@code on_abort}). */
    public record DialogueEnded(Optional<ResourceLocation> dialogue, Optional<String> node) implements Trigger<Ended> {
        public static final MapCodec<DialogueEnded> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("dialogue").forGetter(DialogueEnded::dialogue),
                Codec.STRING.optionalFieldOf("node").forGetter(DialogueEnded::node)
        ).apply(instance, DialogueEnded::new));

        @Override
        public TriggerType<?, Ended> type() {
            return DIALOGUE_ENDED.get();
        }

        @Override
        public boolean matches(StoryContext context, Ended ended) {
            return dialogue.map(ended.dialogue()::equals).orElse(true) && node.map(ended.node()::equals).orElse(true);
        }

        @Override
        public void collectReferences(References references) {
            dialogue.ifPresent(references::dialogue);
        }
    }
}
