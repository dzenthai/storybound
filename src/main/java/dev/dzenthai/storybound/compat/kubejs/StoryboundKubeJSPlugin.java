package dev.dzenthai.storybound.compat.kubejs;

import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;

/**
 * Entry point listed in {@code kubejs.plugins.txt}. KubeJS loads it only when installed,
 * so nothing outside this package ever touches KubeJS classes.
 */
public class StoryboundKubeJSPlugin implements KubeJSPlugin {
    @Override
    public void init() {
        StoryboundKubeBridge.register();
    }

    @Override
    public void registerEvents(EventGroupRegistry registry) {
        registry.register(StoryboundJSEvents.GROUP);
    }

    @Override
    public void registerBindings(BindingRegistry bindings) {
        if (bindings.type().isServer()) {
            bindings.add("Storybound", new StoryboundBinding());
        }
    }

    // Script hooks live as long as the server scripts that registered them.
    @Override
    public void beforeScriptsLoaded(ScriptManager manager) {
        if (manager.scriptType == ScriptType.SERVER) {
            ScriptHooks.clear();
        }
    }

    @Override
    public void afterScriptsLoaded(ScriptManager manager) {
        if (manager.scriptType == ScriptType.SERVER) {
            StoryboundJSEvents.REGISTER_CONDITIONS.post(ScriptType.SERVER, new RegisterConditionsKubeEvent());
            StoryboundJSEvents.REGISTER_ACTIONS.post(ScriptType.SERVER, new RegisterActionsKubeEvent());
        }
    }
}
