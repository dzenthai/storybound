package dev.dzenthai.storybound.client.party;

import dev.dzenthai.storybound.client.ClientParty;
import dev.dzenthai.storybound.client.SeenMarks;
import dev.dzenthai.storybound.client.menu.BookButton;
import dev.dzenthai.storybound.client.menu.BookEditBox;
import dev.dzenthai.storybound.client.menu.JournalSkin;
import dev.dzenthai.storybound.client.menu.ListDetailView;
import dev.dzenthai.storybound.client.menu.MenuTheme;
import dev.dzenthai.storybound.network.PartyActionPayload;
import dev.dzenthai.storybound.network.PartySyncPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The party tab (November 19, 2026; before, parties were made only with commands): the invite waiting for the player,
 * their party or a way to make one, and the other players online to invite. Every button asks the server, which checks
 * it as the commands do and answers with the party as it now is ({@link ClientParty}).
 */
public class PartyView extends ListDetailView<PartyView.Entry> {
    private BookButton primary;
    private BookButton secondary;
    private @Nullable Runnable primaryAction;
    private @Nullable Runnable secondaryAction;
    /**
     * The new party's name, written on the page above "Create party" (November 19, 2026, the author); kept while the
     * menu is open, across resizes. Empty: the default name.
     */
    private BookEditBox nameBox;
    private String typedName = "";

    /** An invite the player has not looked at yet marks the tab. */
    public static boolean hasUnseen() {
        return ClientParty.invite().isPresent() && !SeenMarks.isSeen(inviteKey());
    }

    private static String inviteKey() {
        return ClientParty.invite().map(invite -> "party:invite:" + invite.leaderName() + ":" + invite.partyName()).orElse("party:invite");
    }

    @Override
    protected void init() {
        boolean stacked = detailWidth() < 180;
        int buttonWidth = stacked ? detailWidth() : Math.min(90, Math.max(36, (detailWidth() - 4) / 2));
        primary = addWidget(new BookButton(left + width - buttonWidth, buttonY() + (stacked ? 22 : 0), buttonWidth, 18, Component.empty(),
                button -> run(primaryAction)).onBook(screen::bookLayout));
        secondary = addWidget(new BookButton(stacked ? left + width - buttonWidth : left + width - 2 * buttonWidth - 4, buttonY(),
                buttonWidth, 18, Component.empty(), button -> run(secondaryAction)).onBook(screen::bookLayout));
        // Ink on the page, on a rule as a line to write on (drawn in render).
        nameBox = addWidget(new BookEditBox(font, detailX(), nameY(), detailWidth() - 4, Component.translatable("storybound.party.tab.name"), theme().ink())
                .placeholder(JournalSkin.text(Component.translatable("storybound.party.tab.name_hint")), theme().muted()));
        nameBox.setMaxLength(dev.dzenthai.storybound.core.state.PartyManager.MAX_NAME);
        nameBox.setValue(typedName);
        nameBox.setResponder(text -> typedName = text);
        super.init();
    }

    /** The name's line: above the buttons, with room for its label over it. */
    private int nameY() {
        return buttonY() - 22;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (nameBox.visible) {
            graphics.drawString(font, JournalSkin.text(Component.translatable("storybound.party.tab.name")), detailX(), nameY() - 13, theme().muted(), false);
            JournalSkin.bookRule(graphics, screen.bookLayout(), detailX(), nameY() + 10, detailWidth() - 4, theme().divider());
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter in the name creates the party.
        if (nameBox.visible && nameBox.isFocused() && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
            run(primaryAction);
            return true;
        }
        return false;
    }

    /** The data changes with every sync, and when the invite runs out. */
    @Override
    protected int dataVersion() {
        return ClientParty.version() * 2 + (ClientParty.invite().isPresent() ? 1 : 0);
    }

    @Override
    protected List<Row<Entry>> buildRows() {
        List<Row<Entry>> rows = new ArrayList<>();
        MenuTheme theme = theme();
        ClientParty.invite().ifPresent(invite -> {
            rows.add(Row.header(Component.translatable("storybound.party.tab.invite")));
            rows.add(Row.entry(ClientParty.partyName(invite.partyName(), invite.leaderName()), new Entry(Kind.INVITE, null, invite.leaderName())));
        });
        Optional<PartySyncPayload.Info> party = ClientParty.party();
        if (party.isPresent()) {
            PartySyncPayload.Info info = party.get();
            rows.add(Row.header(ClientParty.partyName(info.name(), leaderName(info))));
            for (PartySyncPayload.Member member : info.members()) {
                Component text = Component.literal((member.id().equals(info.leader()) ? "★ " : "") + member.name());
                rows.add(new Row<>(text, new Entry(Kind.MEMBER, member.id(), member.name()), member.online() ? 0 : theme.muted()));
            }
        } else {
            rows.add(Row.header(Component.translatable("storybound.party.tab.mine")));
            rows.add(Row.entry(Component.translatable("storybound.party.tab.none"), new Entry(Kind.SOLO, null, "")));
        }
        if (!ClientParty.players().isEmpty()) {
            rows.add(Row.header(Component.translatable("storybound.party.tab.online")));
            for (PartySyncPayload.Player player : ClientParty.players()) {
                rows.add(new Row<>(Component.literal(player.name()), new Entry(Kind.PLAYER, player.id(), player.name()),
                        player.inParty() ? theme.muted() : 0));
            }
        }
        return rows;
    }

    @Override
    protected String key(Entry entry) {
        return entry.kind() + ":" + (entry.id() != null ? entry.id() : "");
    }

    @Override
    protected boolean isUnseen(Entry entry) {
        return entry.kind() == Kind.INVITE && !SeenMarks.isSeen(inviteKey());
    }

    @Override
    protected void markSeen(Entry entry) {
        if (entry.kind() == Kind.INVITE) {
            SeenMarks.markSeen(inviteKey());
        }
    }

    @Override
    protected void buildDetail(Entry entry, Detail detail) {
        MenuTheme theme = theme();
        switch (entry.kind()) {
            case INVITE -> ClientParty.invite().ifPresent(invite -> {
                detail.heading(ClientParty.partyName(invite.partyName(), invite.leaderName()).copy().withStyle(ChatFormatting.BOLD), theme.title());
                detail.text(Component.translatable("storybound.party.tab.invite_from", invite.leaderName()), theme.ink());
                detail.gap(4);
                detail.text(Component.translatable("storybound.party.tab.invite_left", ClientParty.inviteMinutesLeft()), theme.muted());
                detail.gap(6);
                detail.text(Component.translatable("storybound.party.tab.about"), theme.muted());
            });
            case SOLO -> {
                detail.heading(Component.translatable("storybound.party.tab.none").copy().withStyle(ChatFormatting.BOLD), theme.title());
                detail.text(Component.translatable("storybound.party.tab.about"), theme.ink());
                detail.gap(6);
                detail.text(Component.translatable("storybound.party.tab.create_hint"), theme.muted());
            }
            case MEMBER -> ClientParty.party().ifPresent(info -> {
                boolean leader = entry.id().equals(info.leader());
                boolean online = info.members().stream().anyMatch(member -> member.id().equals(entry.id()) && member.online());
                detail.heading(Component.literal(entry.name()).withStyle(ChatFormatting.BOLD), theme.title());
                detail.text(Component.translatable(leader ? "storybound.party.tab.role_leader" : "storybound.party.tab.role_member"), theme.ink());
                detail.text(Component.translatable(online ? "storybound.party.tab.online_now" : "storybound.party.tab.offline_now"), theme.muted());
                if (isMe(entry.id())) {
                    detail.gap(6);
                    detail.text(Component.translatable("storybound.party.tab.leave_hint"), theme.muted());
                }
            });
            case PLAYER -> ClientParty.players().stream().filter(player -> player.id().equals(entry.id())).findFirst().ifPresent(player -> {
                detail.heading(Component.literal(player.name()).withStyle(ChatFormatting.BOLD), theme.title());
                if (player.invited()) {
                    detail.text(Component.translatable("storybound.party.tab.invited"), theme.ink());
                } else if (player.inParty()) {
                    detail.text(Component.translatable("storybound.party.tab.in_other_party"), theme.muted());
                } else if (!mayInvite()) {
                    detail.text(Component.translatable("storybound.party.tab.only_leader"), theme.muted());
                } else {
                    detail.text(Component.translatable(ClientParty.party().isPresent() ? "storybound.party.tab.invite_hint"
                            : "storybound.party.tab.invite_creates"), theme.ink());
                }
            });
        }
    }

    @Override
    protected Component emptyText() {
        return Component.translatable("storybound.party.tab.none");
    }

    /** Each entry's buttons: what may be done with it, by this player, now. */
    @Override
    protected void selectionChanged() {
        Entry entry = selected();
        setButton(primary, null, null);
        setButton(secondary, null, null);
        nameBox.visible = entry != null && entry.kind() == Kind.SOLO;
        if (!nameBox.visible) {
            nameBox.setFocused(false);
        }
        if (entry == null) {
            return;
        }
        switch (entry.kind()) {
            case INVITE -> {
                setButton(primary, "storybound.party.button.accept", () -> send("accept", null));
                setButton(secondary, "storybound.party.button.decline", () -> send("decline", null));
            }
            case SOLO -> setButton(primary, "storybound.party.button.create", () -> {
                send("create", null, typedName);
                typedName = "";
                nameBox.setValue("");
            });
            case MEMBER -> ClientParty.party().ifPresent(info -> {
                boolean iLead = myId().map(info.leader()::equals).orElse(false);
                if (isMe(entry.id())) {
                    setButton(primary, "storybound.party.button.leave", () -> send("leave", null));
                    if (iLead) {
                        setButton(secondary, "storybound.party.button.disband", () -> send("disband", null));
                    }
                } else if (iLead) {
                    UUID target = entry.id();
                    setButton(primary, "storybound.party.button.kick", () -> send("kick", target));
                }
            });
            case PLAYER -> ClientParty.players().stream().filter(player -> player.id().equals(entry.id())).findFirst().ifPresent(player -> {
                if (!player.inParty() && !player.invited() && mayInvite()) {
                    UUID target = player.id();
                    setButton(primary, "storybound.party.button.invite", () -> send("invite", target));
                }
            });
        }
    }

    private void setButton(BookButton button, @Nullable String label, @Nullable Runnable action) {
        button.visible = label != null;
        button.setMessage(label != null ? Component.translatable(label) : Component.empty());
        if (button == primary) {
            primaryAction = action;
        } else {
            secondaryAction = action;
        }
    }

    private static void run(@Nullable Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    private static void send(String action, @Nullable UUID target) {
        send(action, target, "");
    }

    private static void send(String action, @Nullable UUID target, String name) {
        PacketDistributor.sendToServer(new PartyActionPayload(action, Optional.ofNullable(target), name));
    }

    /** One in no party may invite (a party is made for them); in a party, only its leader. */
    private boolean mayInvite() {
        return ClientParty.party().map(info -> myId().map(info.leader()::equals).orElse(false)).orElse(true);
    }

    private boolean isMe(@Nullable UUID id) {
        return id != null && myId().map(id::equals).orElse(false);
    }

    private Optional<UUID> myId() {
        Player player = minecraft.player;
        return player != null ? Optional.of(player.getUUID()) : Optional.empty();
    }

    private static String leaderName(PartySyncPayload.Info info) {
        return info.members().stream().filter(member -> member.id().equals(info.leader())).map(PartySyncPayload.Member::name).findFirst().orElse("?");
    }

    enum Kind { INVITE, SOLO, MEMBER, PLAYER }

    /** A row of the tab: the invite, "not in a party", a member, or another player online. */
    public record Entry(Kind kind, @Nullable UUID id, String name) {}
}
