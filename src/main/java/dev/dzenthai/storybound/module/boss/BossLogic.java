package dev.dzenthai.storybound.module.boss;

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
import dev.dzenthai.storybound.module.codex.UnlockTarget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Bosses module's additions to the logic language. */
public final class BossLogic {
    static final DeferredRegister<ConditionType<?>> REGISTER_CONDITIONS = DeferredRegister.create(LogicRegistries.CONDITION_TYPE_KEY, Storybound.MOD_ID);
    static final DeferredRegister<ActionType<?>> REGISTER_ACTIONS = DeferredRegister.create(LogicRegistries.ACTION_TYPE_KEY, Storybound.MOD_ID);

    public static final DeferredHolder<ConditionType<?>, ConditionType<BossKilled>> BOSS_KILLED =
            REGISTER_CONDITIONS.register("boss_killed", () -> new ConditionType<>(BossKilled.CODEC));
    public static final DeferredHolder<ActionType<?>, ActionType<UnlockBossSection>> UNLOCK_BOSS_SECTION =
            REGISTER_ACTIONS.register("unlock_boss_section", () -> new ActionType<>(UnlockBossSection.CODEC));

    private BossLogic() {}

    /** {@code { "type": "boss_killed", "boss": "mypack:drowned_king", "count": 1 }}: the player has at least that many victories. */
    public record BossKilled(ResourceLocation boss, int count) implements Condition {
        public static final MapCodec<BossKilled> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("boss").forGetter(BossKilled::boss),
                Codec.intRange(1, 100_000).optionalFieldOf("count", 1).forGetter(BossKilled::count)
        ).apply(instance, BossKilled::new));

        @Override
        public ConditionType<?> type() {
            return BOSS_KILLED.get();
        }

        @Override
        public boolean test(StoryContext context) {
            return Bosses.data(context.player()).get(boss).victories() >= count;
        }

        @Override
        public void collectReferences(References references) {
            references.boss(boss);
        }
    }

    /** Opens {@code lore}, {@code tactics} or {@code rewards} of a boss, for the player or (with {@code "target": "party"}) the party. */
    public record UnlockBossSection(ResourceLocation boss, String section, UnlockTarget target) implements Action {
        public static final MapCodec<UnlockBossSection> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("boss").forGetter(UnlockBossSection::boss),
                Codec.STRING.fieldOf("section").forGetter(UnlockBossSection::section),
                UnlockTarget.CODEC.optionalFieldOf("target", UnlockTarget.PLAYER).forGetter(UnlockBossSection::target)
        ).apply(instance, UnlockBossSection::new));

        @Override
        public ActionType<?> type() {
            return UNLOCK_BOSS_SECTION.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            for (ServerPlayer player : target.players(context.player())) {
                if (!Bosses.unlock(player, boss, section)) {
                    throw new StoryException(Component.translatableWithFallback("storybound.boss.cannot_unlock",
                            "Boss %s section %s cannot be unlocked: unknown boss or section, or the Bosses module is disabled", boss.toString(), section));
                }
            }
        }

        @Override
        public void collectReferences(References references) {
            references.boss(boss);
        }
    }
}
