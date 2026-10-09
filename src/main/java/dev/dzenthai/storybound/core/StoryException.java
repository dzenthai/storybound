package dev.dzenthai.storybound.core;

import net.minecraft.network.chat.Component;

/** An error shown to the player or modpack author as a translated message. */
public class StoryException extends Exception {
    private final Component component;

    public StoryException(Component component) {
        super(component.getString());
        this.component = component;
    }

    public Component component() {
        return component;
    }
}
