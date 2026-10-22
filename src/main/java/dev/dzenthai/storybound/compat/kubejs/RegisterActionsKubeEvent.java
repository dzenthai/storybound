package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/** Makes {@code { "type": "kubejs", "id": ..., "args"?: {...} }} actions in JSON call into the script ({@code ctx.args}). */
public class RegisterActionsKubeEvent implements KubeEvent {
    public void add(ResourceLocation id, Consumer<KubeStoryContext> action) {
        ScriptHooks.registerAction(id, (context, args) -> action.accept(new KubeStoryContext(context, args)));
    }
}
