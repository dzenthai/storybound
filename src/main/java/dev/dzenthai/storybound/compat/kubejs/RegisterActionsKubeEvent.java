package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.latvian.mods.kubejs.event.KubeEvent;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/** Makes {@code { "type": "kubejs", "id": ... }} actions in JSON call into the script. */
public class RegisterActionsKubeEvent implements KubeEvent {
    public void add(ResourceLocation id, Consumer<KubeStoryContext> action) {
        ScriptHooks.registerAction(id, context -> action.accept(new KubeStoryContext(context)));
    }
}
