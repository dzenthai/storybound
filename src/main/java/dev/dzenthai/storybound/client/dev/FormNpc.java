package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dzenthai.storybound.client.dev.Schemas.Kind;
import dev.dzenthai.storybound.client.dev.Schemas.Node;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * The NPC binding's form ({@link FormView}): who it is, its names, how many, the relationship scale, proximity.
 * <p>
 * One layer of the form, split by topic, each seeing the ones below it; the rest of the editor uses FormView:
 * FormCore &larr; FormPickers &larr; FormInputs &larr; FormEntities &larr; FormNpc &larr; FormWaypoints &larr; FormDialogues &larr; FormStory &larr; FormView.
 */
abstract class FormNpc extends FormEntities {
    FormNpc(Host host) {
        super(host);
    }

    /**
     * An NPC binding's form in the order its author asks: who it is, what it says and the relationship its options
     * move; how a talk starts; what happens with several players; the rest. Each group but the first opens with a
     * heading at this field.
     */
    static final Map<String, String> NPC_GROUPS = Map.of(
            // "Who it is" opens the form, drawn by npcWho; then the sections.
            "dialogues", "npc.group.talk",
            "chatter", "npc.group.chatter",
            // How it walks.
            "movement", "npc.group.movement",
            "when_attacked", "npc.group.hits",
            "custom", "npc.group.scripts");
    /** Within a section, a lighter heading over the fields of one question: how a talk starts, several players. */
    static final Map<String, String> NPC_SUBGROUPS = Map.of(
            "interact", "npc.group.start",
            "exclusive", "npc.group.players");
    /** The NPC form's order after "Who it is", as the schema lists it. */
    static final List<String> NPC_ORDER = List.of("dialogues", "interact", "override_interact", "proximity", "exclusive", "busy",
            "chatter", "voice", "movement", "home_radius", "when_attacked", "invulnerable", "respawn", "custom");

    /**
     * What depends on a switch shows only while it is on, unless the file still sets it: "instead of trading" with
     * talking by key or right-click, "reply while busy" with talking to one player at a time, how far from home with
     * walking near home.
     */
    static boolean npcFieldShown(String name, @Nullable JsonObject binding) {
        return switch (name) {
            case "override_interact" -> flag(binding, "interact", true) || flag(binding, "override_interact", false);
            case "busy" -> flag(binding, "exclusive", false) || binding != null && binding.has("busy");
            case "home_radius" -> binding != null && (binding.has("home_radius")
                    || binding.get("movement") instanceof JsonPrimitive movement && movement.getAsString().equals("home"));
            default -> true;
        };
    }

    /** The fields of an NPC's "Who it is", drawn by {@link #npcWho} rather than one by one. */
    static final Set<String> NPC_WHO = Set.of("display_name", "show_name", "names", "one", "share", "entity");
    /** What makes an NPC one "by hand": the parts of its selector naming particular entities. */
    private static final List<String> HAND_PARTS = SelectorEdits.HAND_PARTS;
    private static final List<Object> ENTITY = List.of("entity");

    /**
     * An NPC's "Who it is", as its author asks: the rule, by hand (the entities carrying its tag) or dynamic (every mob
     * of a variant: its kind, where it is, its data); for dynamic, how many: all such, or one picked by the mod, and
     * the variants, a row each (a man smith and a woman farmer of Villagers Reborn as one NPC); then its name: the
     * displayed name for one NPC (by hand, or one), names from a list for all such. A field of the other way shows only
     * while the file still writes it, so it can be cleared. Showing it never changes the file.
     */
    void npcWho(Node schema, @Nullable JsonObject binding) {
        Map<String, Node> fields = Schemas.properties(schema);
        JsonElement entity = binding != null ? binding.get("entity") : null;
        boolean byHand = SelectorEdits.byHand(entity);
        if (byHand && entity.isJsonArray()) {
            // Several particular entities (an older file): their list, with no rule to switch.
            entityListField(List.of(), "entity", fields.get("entity"), entity, true);
            npcNames(schema, binding, true, false);
            return;
        }
        JsonObject selector = entity instanceof JsonObject object ? object : null;
        boolean one = flag(binding, "one", false);
        npcRule(byHand);
        Picker kinds = new Picker(Catalog.Kind.MOB, null, List.of(), DevUi.text("entity.pick"));
        if (byHand) {
            idListRows(ENTITY, "type", kinds, DevUi.text("npc.kind_optional"));
            if (selector.has("uuid") || selector.has("name")) {
                // An old or hand-written file: shown as it is, the form no longer offers these ways.
                String known = selector.has("uuid") ? DevUi.str("entity.summary.uuid", tail(selector.get("uuid").getAsString()))
                        : DevUi.str("entity.summary.name", selector.get("name").getAsString());
                text(DevUi.text("npc.known_from_file", known), DevUi.MUTED);
            }
            npcLink(ENTITY, selector);
            if (binding.has("share")) {
                // Not used by hand (the validator says so): shown while written, to take it out.
                npcShare(binding);
            }
            npcNames(schema, binding, true, false);
            return;
        }
        npcCount(one);
        if (!one || binding != null && binding.has("share")) {
            // For all such; a "one" file still writing it shows it, to take it out.
            npcShare(binding);
        }
        variantRows(ENTITY, entityListItems(fields.get("entity")), one);
        npcNames(schema, binding, one, !one);
    }

    /** The displayed name and whether it shows, and the names from a list; each also while the file writes it. */
    private void npcNames(Node schema, @Nullable JsonObject binding, boolean display, boolean names) {
        Map<String, Node> fields = Schemas.properties(schema);
        if (display || binding != null && binding.has("display_name")) {
            field(List.of(), "display_name", fields.get("display_name"), false, schema);
        }
        if (display || binding != null && binding.has("show_name")) {
            field(List.of(), "show_name", fields.get("show_name"), false, schema);
        }
        if (names || binding != null && binding.has("names")) {
            field(List.of(), "names", fields.get("names"), false, schema);
        }
    }

    /**
     * "Rule: By hand / Dynamic". To dynamic: the kind kept, the tag gone; to by hand: the node's tag, the kind of the
     * first variant. "One" goes with dynamic. What a switch took away (the variants with their data, the tag) comes
     * back on switching again, while this node is open.
     */
    private void npcRule(boolean byHand) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("npc.rule"));
        Button button = Button.builder(Component.literal(DevUi.str(byHand ? "npc.rule.hand" : "npc.rule.dynamic") + " ▸"), b -> {
            JsonObject root = host.root();
            JsonElement now = root.get("entity");
            String from = byHand ? "hand" : "dynamic";
            String to = byHand ? "dynamic" : "hand";
            if (now != null) {
                switchedAway.put("entity#rule:" + from, now.deepCopy());
            }
            JsonElement back = switchedAway.remove("entity#rule:" + to);
            JsonElement next = SelectorEdits.switchedRule(now, back, host.npcBinding().getPath().replace('/', '_'));
            if (next instanceof JsonObject empty && empty.isEmpty()) {
                // Dynamic with no kind yet: no variant, "+ Add" asks for one (not a row "no kind" beside it).
                root.remove("entity");
            } else {
                root.add("entity", next);
            }
            if (byHand) {
                giveBack(List.of(), root, "one");
            } else {
                putAway(List.of(), root, "one");
            }
            tidyNames(root);
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("npc.rule.tooltip")));
        screen.add(button);
    }

    /**
     * "How many: All such / One, picked by the mod". A new "one" is written grown-up in each variant that can be young,
     * so no child is made the Elder.
     */
    private void npcCount(boolean one) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("npc.count"));
        Button button = Button.builder(Component.literal(DevUi.str(one ? "npc.count.one" : "npc.count.all") + " ▸"), b -> {
            JsonObject root = host.root();
            if (one) {
                root.remove("one");
            } else {
                root.addProperty("one", true);
                for (JsonObject variant : SelectorEdits.variants(root.get("entity"))) {
                    if (!variant.has("baby") && MobFields.canBeYoung(kindsOf(variant))) {
                        variant.addProperty("baby", false);
                    }
                }
            }
            tidyNames(root);
            host.changed();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("npc.count.tooltip")));
        screen.add(button);
    }

    /** Whether the NPC is "one, picked by the mod": dynamic, with {@code one}. */
    static boolean isOneNode(@Nullable JsonObject binding) {
        return flag(binding, "one", false) && !SelectorEdits.byHand(binding.get("entity"));
    }

    /**
     * "Share": a slider from 1 to 100 per cent, under "How many" for all such. Written when let go (or moved by the
     * keys), as every write reloads the story; 100 writes nothing, all of them.
     */
    private void npcShare(@Nullable JsonObject binding) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("npc.share"));
        int written = binding != null && binding.get("share") instanceof JsonPrimitive share && share.isNumber() ? Math.clamp(share.getAsInt(), 1, 100) : 100;
        AbstractSliderButton slider = new AbstractSliderButton(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H, Component.empty(), (written - 1) / 99.0) {
            {
                updateMessage();
            }

            private int percent() {
                return 1 + (int) Math.round(value * 99);
            }

            @Override
            protected void updateMessage() {
                int percent = percent();
                setMessage(percent >= 100 ? DevUi.text("npc.share.all") : DevUi.text("npc.share.value", percent));
            }

            @Override
            protected void applyValue() {
                // Written once let go: see onRelease.
            }

            /**
             * In the editor's look: the dark field in its bronze frame, the share up to the handle a little lighter,
             * the handle a gold bar, the value in words.
             */
            @Override
            public void renderWidget(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                boolean lit = isHoveredOrFocused();
                int left = getX();
                int top = getY();
                graphics.fill(left, top, left + width, top + height, DevUi.CARD);
                int handle = left + 1 + (int) Math.round(value * (width - 6));
                graphics.fill(left + 1, top + 1, handle, top + height - 1, DevUi.ROW_HOVER);
                DevUi.frame(graphics, left, top, width, height, lit ? DevUi.ACCENT : DevUi.CARD_EDGE);
                graphics.fill(handle, top + 1, handle + 4, top + height - 1, lit ? DevUi.WARN : DevUi.ACCENT);
                Font font = net.minecraft.client.Minecraft.getInstance().font;
                graphics.drawCenteredString(font, getMessage(), left + width / 2, top + (height - 8) / 2, DevUi.TEXT);
            }

            @Override
            public void onRelease(double mouseX, double mouseY) {
                super.onRelease(mouseX, mouseY);
                commit();
            }

            /**
             * The arrows move it by 1%, Enter or leaving it writes it.
             */
            @Override
            public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT) {
                    int next = Math.clamp(percent() + (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT ? 1 : -1), 1, 100);
                    value = (next - 1) / 99.0;
                    updateMessage();
                    return true;
                }
                if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                    commit();
                    return true;
                }
                return super.keyPressed(keyCode, scanCode, modifiers);
            }

            @Override
            public void setFocused(boolean focused) {
                super.setFocused(focused);
                if (!focused) {
                    commit();
                }
            }

            /** Written once: the form is rebuilt after it, and this slider, gone, loses its focus (which writes) again. */
            private boolean done;

            private void commit() {
                int percent = percent();
                if (done || percent == written) {
                    return;
                }
                done = true;
                if (percent >= 100) {
                    host.root().remove("share");
                } else {
                    host.root().addProperty("share", percent);
                }
                host.changed();
            }
        };
        slider.setTooltip(Tooltip.create(DevUi.text("npc.share.tooltip")));
        screen.add(slider);
    }

    /**
     * After a switch of the rule or of how many: the name of one NPC (displayed, shown over the head) for one NPC, by
     * hand or "one", the names from a list for all such; the other kind is put away, and given back on switching again.
     */
    private void tidyNames(JsonObject root) {
        boolean oneNpc = SelectorEdits.byHand(root.get("entity")) || flag(root, "one", false);
        // A share is for all such too: one NPC is all of itself.
        List<String> mine = oneNpc ? List.of("display_name", "show_name") : List.of("names", "share");
        List<String> theirs = oneNpc ? List.of("names", "share") : List.of("display_name", "show_name");
        theirs.forEach(field -> putAway(List.of(), root, field));
        mine.forEach(field -> giveBack(List.of(), root, field));
    }

    /**
     * The end of a "one" NPC's form: who the mod picked is kept for good, also when they no longer pass the filters,
     * so changed filters need a new pick, which a button asks for ({@code /storybound debug npc part <npc> reset}).
     */
    void npcPicked() {
        group(DevUi.text("npc.group.picked"));
        text(DevUi.text("npc.picked.note"), DevUi.MUTED);
        if (!row(CONTROL_H)) {
            return;
        }
        ResourceLocation npc = host.npcBinding();
        // Two clicks: the first asks, the second frees the part; a pick cannot be undone.
        boolean armed = Util.getMillis() < resetArmedUntil;
        Button button = Button.builder(DevUi.text(armed ? "npc.picked.reset.sure" : "npc.picked.reset"), b -> {
            if (Util.getMillis() < resetArmedUntil) {
                resetArmedUntil = 0;
                if (Minecraft.getInstance().getConnection() != null) {
                    Minecraft.getInstance().getConnection().sendCommand("storybound debug npc part " + npc + " reset");
                }
            } else {
                resetArmedUntil = Util.getMillis() + RESET_CONFIRM_MILLIS;
            }
            screen.rebuild();
        }).bounds(x, rowY(CONTROL_H), w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("npc.picked.reset.tooltip")));
        screen.add(button);
    }

    /**
     * Speaking up when a player comes near: yes/no, and while yes its settings in words ("4 blocks · every 30 s"),
     * which open them.
     */
    void proximityField(List<Object> path, Node node, @Nullable JsonObject binding) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(fieldLabel("proximity", false));
        int rowTop = rowY(LABEL_H + CONTROL_H) + LABEL_H;
        JsonObject settings = binding != null && binding.get("proximity") instanceof JsonObject value ? value : null;
        boolean on = settings != null;
        int toggleW = on ? Math.min(60, w / 3) : w;
        // Not written, it is the nodes' below (layers), else off: there is no "off" over theirs.
        Component state = on ? Component.literal(DevUi.yesNo(true)) : DevUi.text("form.from_below", DevUi.yesNo(false));
        Button toggle = Button.builder(state, b -> {
            if (on) {
                ensureObject(path).remove("proximity");
            } else {
                ensureObject(path).add("proximity", new JsonObject());
            }
            host.changed();
        }).bounds(x, rowTop, toggleW, CONTROL_H).build();
        if (!node.description().isEmpty()) {
            toggle.setTooltip(Tooltip.create(Component.literal(described(node.description()))));
        }
        screen.add(toggle);
        if (on) {
            NpcProximity shown = NpcProximity.of(settings);
            Component summary = Component.empty()
                    .append(DevUi.text(shown.once() ? "npc.proximity.once" : "npc.proximity.summary", format(shown.radius()), shown.cooldown()))
                    .append(" ▸");
            screen.add(Button.builder(summary, b -> push(with(path, "proximity"), node, fieldLabel("proximity", false).getString(), false))
                    .bounds(x + toggleW + 2, rowTop, w - toggleW - 2, CONTROL_H).build());
        }
    }

    /** The settings a proximity object gives, with the defaults for what it leaves out (as {@code NpcBinding.Proximity}). */
    private record NpcProximity(double radius, int cooldown, boolean once) {
        static NpcProximity of(JsonObject settings) {
            return new NpcProximity(
                    settings.get("radius") instanceof JsonPrimitive radius && radius.isNumber() ? radius.getAsDouble() : 4,
                    settings.get("cooldown") instanceof JsonPrimitive cooldown && cooldown.isNumber() ? cooldown.getAsInt() : 30,
                    flag(settings, "once", false));
        }
    }

    /** The NPC's relationship scale, with the binding's other yes/no fields: yes makes it, no deletes it if unused. */
    void relationshipField(ResourceLocation npc) {
        if (!row(LABEL_H + CONTROL_H)) {
            return;
        }
        labelRow(DevUi.text("relationship.field"));
        boolean exists = RelationshipScales.exists(npc);
        Button button = Button.builder(Component.literal(DevUi.yesNo(exists)), b -> {
            if (RelationshipScales.exists(npc)) {
                RelationshipScales.remove(screen, npc);
            } else {
                RelationshipScales.create(screen, npc);
            }
            screen.rebuild();
        }).bounds(x, rowY(LABEL_H + CONTROL_H) + LABEL_H, w, CONTROL_H).build();
        button.setTooltip(Tooltip.create(DevUi.text("relationship.tooltip", RelationshipScales.of(npc).toString())));
        screen.add(button);
    }
}
