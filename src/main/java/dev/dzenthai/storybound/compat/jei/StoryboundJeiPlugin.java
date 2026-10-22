package dev.dzenthai.storybound.compat.jei;

import dev.dzenthai.storybound.Storybound;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/** Found by JEI through its annotation; never loaded without JEI. The recipes come at runtime ({@link JeiQuests}). */
@JeiPlugin
public final class StoryboundJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return Storybound.id("jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new QuestRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        JeiQuests.available(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiQuests.unavailable();
    }
}
