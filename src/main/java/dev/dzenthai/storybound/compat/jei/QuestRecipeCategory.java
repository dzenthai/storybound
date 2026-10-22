package dev.dzenthai.storybound.compat.jei;

import java.util.List;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** "Storybound quests": the quest's title, its items in a row and what it wants with them. */
final class QuestRecipeCategory implements IRecipeCategory<QuestRecipe> {
    private static final int WIDTH = 162;
    private static final int HEIGHT = 44;
    /** At most this many reward slots are shown in a row. */
    private static final int MAX_SLOTS = 8;

    private final IDrawable icon;

    QuestRecipeCategory(IGuiHelper helper) {
        icon = helper.createDrawableItemStack(new ItemStack(Items.WRITABLE_BOOK));
    }

    @Override
    public RecipeType<QuestRecipe> getRecipeType() {
        return JeiQuests.TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("storybound.jei.category");
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, QuestRecipe recipe, IFocusGroup focuses) {
        // Asked for: an item's uses; given: how to get it.
        RecipeIngredientRole role = recipe.kind() == QuestRecipe.Kind.BRING || recipe.kind() == QuestRecipe.Kind.HAND_IN
                ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT;
        List<List<ItemStack>> slots = recipe.slots();
        for (int i = 0; i < slots.size() && i < MAX_SLOTS; i++) {
            builder.addSlot(role, 1 + i * 20, 13).setStandardSlotBackground().addItemStacks(slots.get(i));
        }
    }

    @Override
    public void draw(QuestRecipe recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics graphics, double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, font.plainSubstrByWidth(recipe.title().getString(), WIDTH), 0, 1, 0xFF404040, false);
        Component what = switch (recipe.kind()) {
            case BRING -> Component.translatable("storybound.jei.bring", recipe.count());
            case HAND_IN -> Component.translatable("storybound.jei.hand_in", recipe.count());
            case REWARD -> Component.translatable("storybound.jei.reward");
            case CHOICE -> Component.translatable("storybound.jei.choice");
        };
        graphics.drawString(font, what, 0, 34, 0xFF606060, false);
    }
}
