package dev.dzenthai.storybound.module.codex;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.ConditionType;
import dev.dzenthai.storybound.core.logic.LogicRegistries;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.StoryContext;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Codex module's additions to the logic language. */
public final class CodexLogic {
    static final DeferredRegister<ConditionType<?>> REGISTER_CONDITIONS = DeferredRegister.create(LogicRegistries.CONDITION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<ActionType<?>> REGISTER_ACTIONS = DeferredRegister.create(LogicRegistries.ACTION_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ConditionType<?>, ConditionType<CodexUnlocked>> CODEX_UNLOCKED =
            REGISTER_CONDITIONS.register("codex_unlocked", () -> new ConditionType<>(CodexUnlocked.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<UnlockCodex>> UNLOCK_CODEX =
            REGISTER_ACTIONS.register("unlock_codex", () -> new ActionType<>(UnlockCodex.CODEC));

    private CodexLogic() {}

    /** {@code { "type": "codex_unlocked", "entry": "mypack:drowner", "section": "weakness" }}; without {@code section}, any section. */
    public record CodexUnlocked(ResourceLocation entry, Optional<String> section) implements Condition {
        public static final MapCodec<CodexUnlocked> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("entry").forGetter(CodexUnlocked::entry),
                Codec.STRING.optionalFieldOf("section").forGetter(CodexUnlocked::section)
        ).apply(instance, CodexUnlocked::new));

        @Override
        public ConditionType<?> type() {
            return CODEX_UNLOCKED.get();
        }

        @Override
        public boolean test(StoryContext context) {
            CodexData data = Codex.data(context.player());
            return section.map(id -> data.isUnlocked(entry, id)).orElseGet(() -> !data.sections(entry).isEmpty());
        }

        @Override
        public void collectReferences(References references) {
            references.codex(entry, section.orElse(null));
        }
    }

    /** Opens a section (or the whole entry) for the player, or with {@code "target": "party"} for every online party member. */
    public record UnlockCodex(ResourceLocation entry, Optional<String> section, UnlockTarget target) implements Action {
        public static final MapCodec<UnlockCodex> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("entry").forGetter(UnlockCodex::entry),
                Codec.STRING.optionalFieldOf("section").forGetter(UnlockCodex::section),
                UnlockTarget.CODEC.optionalFieldOf("target", UnlockTarget.PLAYER).forGetter(UnlockCodex::target)
        ).apply(instance, UnlockCodex::new));

        @Override
        public ActionType<?> type() {
            return UNLOCK_CODEX.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            for (ServerPlayer player : target.players(context.player())) {
                if (!Codex.unlock(player, entry, section.orElse(null))) {
                    throw new StoryException(Component.translatableWithFallback("storybound.codex.cannot_unlock",
                            "Codex entry %s cannot be unlocked: unknown entry or section, or the Codex module is disabled", entry.toString()));
                }
            }
        }

        @Override
        public void collectReferences(References references) {
            references.codex(entry, section.orElse(null));
        }
    }
}
