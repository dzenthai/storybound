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
 * @param once   {@code player}, {@code party} or {@code world}: run only once per that scope; absent means every time
 */
public record StoryEvent(Trigger<?> trigger, Condition conditions, List<Action> actions, OnFail onFail, Optional<StoryScope> once) {
    public static final Codec<StoryEvent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            LogicCodecs.TRIGGER.fieldOf("trigger").forGetter(StoryEvent::trigger),
            LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(StoryEvent::conditions),
            LogicCodecs.ACTIONS.fieldOf("actions").forGetter(StoryEvent::actions),
            OnFail.CODEC.optionalFieldOf("on_fail", OnFail.ABORT).forGetter(StoryEvent::onFail),
            StoryScope.CODEC.optionalFieldOf("once").forGetter(StoryEvent::once)
    ).apply(instance, StoryEvent::new));
}
