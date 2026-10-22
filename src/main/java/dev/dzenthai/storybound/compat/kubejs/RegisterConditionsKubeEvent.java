package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** Makes {@code { "type": "kubejs", "id": ..., "args"?: {...} }} conditions in JSON call into the script ({@code ctx.args}). */
public class RegisterConditionsKubeEvent implements KubeEvent {
    public void add(ResourceLocation id, Predicate<KubeStoryContext> condition) {
        ScriptHooks.registerCondition(id, (context, args) -> condition.test(new KubeStoryContext(context, args)));
    }
}
