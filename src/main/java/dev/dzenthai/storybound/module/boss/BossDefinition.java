package dev.dzenthai.storybound.module.boss;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.EntitySelector;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.text.StoryText;
import dev.dzenthai.storybound.module.codex.Unlock;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * {@code data/<ns>/storybound/bosses/<path>.json}: a boss and its entry in the Bosses tab. The entry has fixed
 * sections: status (automatic), {@code lore} (opens on {@code seen} by default), {@code tactics} and
 * {@code rewards} (on {@code killed}), and statistics (after the first victory).
 *
 * @param participation share of the boss's maximum health a player must deal to share the victory
 * @param onFirstKill   run for each credited player on their first victory
 * @param onKill        run for each credited player on every victory, the first included
 */
public record BossDefinition(EntitySelector entity, StoryText title, Optional<Part> lore, Optional<Part> tactics, Optional<Rewards> rewards,
                             List<Action> onFirstKill, List<Action> onKill, float participation, boolean announce) {
    public static final String LORE = "lore";
    public static final String TACTICS = "tactics";
    public static final String REWARDS = "rewards";
    public static final List<String> SECTIONS = List.of(LORE, TACTICS, REWARDS);

    public static final Codec<BossDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            EntitySelector.CODEC.fieldOf("entity").forGetter(BossDefinition::entity),
            StoryText.CODEC.fieldOf("title").forGetter(BossDefinition::title),
            Part.codec(Unlock.Source.SEEN).optionalFieldOf(LORE).forGetter(BossDefinition::lore),
            Part.codec(Unlock.Source.KILLED).optionalFieldOf(TACTICS).forGetter(BossDefinition::tactics),
            Rewards.CODEC.optionalFieldOf(REWARDS).forGetter(BossDefinition::rewards),
            LogicCodecs.ACTIONS.optionalFieldOf("on_first_kill", List.of()).forGetter(BossDefinition::onFirstKill),
            LogicCodecs.ACTIONS.optionalFieldOf("on_kill", List.of()).forGetter(BossDefinition::onKill),
            Codec.floatRange(0f, 1f).optionalFieldOf("participation", 0.05f).forGetter(BossDefinition::participation),
            Codec.BOOL.optionalFieldOf("notify", true).forGetter(BossDefinition::announce)
    ).apply(instance, BossDefinition::new));

    /** The unlock of a section, if the boss has that section. */
    public Optional<Unlock> unlock(String section) {
        return switch (section) {
            case LORE -> lore.map(Part::unlock);
            case TACTICS -> tactics.map(Part::unlock);
            case REWARDS -> rewards.map(Rewards::unlock);
            default -> Optional.empty();
        };
    }

    /** {@code <ns>:bosses/<path>/<section>} unless the section names its own text. */
    public static ResourceLocation defaultText(ResourceLocation boss, String section) {
        return ResourceLocation.fromNamespaceAndPath(boss.getNamespace(), "bosses/" + boss.getPath() + "/" + section);
    }

    /** What clients receive: no actions and no unlock rules. */
    BossDefinition clientView() {
        return new BossDefinition(entity, title, lore.map(Part::clientView), tactics.map(Part::clientView), rewards.map(Rewards::clientView),
                List.of(), List.of(), participation, announce);
    }

    /** @param text a long text ({@code .sbt}); by default {@link #defaultText} */
    public record Part(Optional<ResourceLocation> text, Unlock unlock) {
        static Codec<Part> codec(Unlock.Source defaultSource) {
            return RecordCodecBuilder.create(instance -> instance.group(
                    ResourceLocation.CODEC.optionalFieldOf("text").forGetter(Part::text),
                    Unlock.CODEC.optionalFieldOf("unlock", Unlock.on(defaultSource)).forGetter(Part::unlock)
            ).apply(instance, Part::new));
        }

        Part clientView() {
            return new Part(text, Unlock.HIDDEN);
        }
    }

    /**
     * What the boss drops or what the story gives, for display only (the actual rewards are {@code on_kill} actions).
     *
     * @param text an optional long text under the items
     */
    public record Rewards(List<ItemStack> items, Optional<ResourceLocation> text, Unlock unlock) {
        private static final Codec<ItemStack> ITEM = Codec.withAlternative(ItemStack.CODEC, ItemStack.SIMPLE_ITEM_CODEC);
        public static final Codec<Rewards> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ITEM.listOf().optionalFieldOf("items", List.of()).forGetter(Rewards::items),
                ResourceLocation.CODEC.optionalFieldOf("text").forGetter(Rewards::text),
                Unlock.CODEC.optionalFieldOf("unlock", Unlock.on(Unlock.Source.KILLED)).forGetter(Rewards::unlock)
        ).apply(instance, Rewards::new));

        Rewards clientView() {
            return new Rewards(items, text, Unlock.HIDDEN);
        }
    }
}
