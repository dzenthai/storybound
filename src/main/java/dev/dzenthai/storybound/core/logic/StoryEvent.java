package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import dev.dzenthai.storybound.core.state.StoryScope;
import java.util.List;
import java.util.Optional;

/**
 * A "when X and conditions, then actions" rule ({@code data/<ns>/storybound/events/*.json}).
 *
 * @param onFail what happens when an action fails; {@code abort} by default
 * @param once     {@code player}, {@code party} or {@code world}: run only once per that scope; absent means every time
 * @param cooldown after running, not again for that long in that scope; absent means no wait
 */
public record StoryEvent(Trigger<?> trigger, Condition conditions, List<Action> actions, OnFail onFail, Optional<StoryScope> once,
                         Optional<Cooldown> cooldown) {
    /**
     * {@code "cooldown": { "seconds": 3600, "scope": "world" }}: once the event has run, it does not run again in that
     * scope ({@code player} by default) until that much game time has passed, counted as {@code wait} counts it (a
     * Minecraft day is 1200 seconds; sleeping does not shorten it).
     */
    public record Cooldown(int seconds, StoryScope scope) {
        public static final Codec<Cooldown> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, 100_000_000).fieldOf("seconds").forGetter(Cooldown::seconds),
                StoryScope.CODEC.optionalFieldOf("scope", StoryScope.PLAYER).forGetter(Cooldown::scope)
        ).apply(instance, Cooldown::new));
    }

    public static final Codec<StoryEvent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            LogicCodecs.TRIGGER.fieldOf("trigger").forGetter(StoryEvent::trigger),
            LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(StoryEvent::conditions),
            LogicCodecs.ACTIONS.fieldOf("actions").forGetter(StoryEvent::actions),
            OnFail.CODEC.optionalFieldOf("on_fail", OnFail.ABORT).forGetter(StoryEvent::onFail),
            StoryScope.CODEC.optionalFieldOf("once").forGetter(StoryEvent::once),
            Cooldown.CODEC.optionalFieldOf("cooldown").forGetter(StoryEvent::cooldown)
    ).apply(instance, StoryEvent::new));
}
