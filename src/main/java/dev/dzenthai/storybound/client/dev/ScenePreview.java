package dev.dzenthai.storybound.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dzenthai.storybound.client.ScreenProjection;
import dev.dzenthai.storybound.client.ShaderPacks;
import dev.dzenthai.storybound.client.camera.SceneCamera;
import dev.dzenthai.storybound.module.scene.SceneDefinition;
import dev.dzenthai.storybound.network.ScenePayloads;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * A scene's camera view: the editor becomes a panel on the left and the rest of the screen is the world as the scene's
 * camera sees it, in the author's own world.
 * <ul>
 * <li><b>The shot</b> is drawn in the world: its flight as a gold line, a small camera at its start (green) and its end
 * (red) pointing where it looks, the anchor as a white post. Those two cameras are handles: drag one to move that point
 * across the view (with Shift, over the ground: forward and back instead of up and down).</li>
 * <li><b>The free view</b> goes anywhere: drag to turn it around its pivot (the anchor, or the handle F focused), drag
 * with the right button to look around from where it is, fly with the movement keys (jump and sneak for up and down,
 * sprint for speed), the wheel to come closer. "📷" puts the shot's point where the free view is.</li>
 * <li><b>The panel</b> holds the shot's points (right, up, forward of its anchor; the wheel over a number nudges it),
 * its length (seconds or speed), ease and look, a slider through its time, Play for the shot and for the whole scene
 * (cuts, fades and glides as players will see them), shots added, copied and removed, the black bars and a grid of
 * thirds over the picture. H hides the panel, so the whole frame shows.</li>
 * </ul>
 * Keys while no number is being typed: Space plays, the arrows step through the time, Tab switches between the shot and
 * the free view, F focuses the view on the selected point, Ctrl+S saves. The anchors are asked of the server, found as
 * a scene would find them; one not near is stood in for by the author, the numbers meaning the same around the real
 * one. Nothing is written until Save; closing with changes asks first.
 */
public final class ScenePreview extends Screen implements Catalog.Host {
    private static final int PANEL = 216;
    private static final int ROW = 24;
    private static final int GOLD = 0xFFE1B34D;
    private static final int MUTED = 0xFF9A93A6;
    private static final int TEXT = 0xFFE8E2EE;
    private static final int START = 0xFF7ED957;
    private static final int END = 0xFFE05A47;
    /** How near the cursor must be to a handle on screen to take it. */
    private static final double HANDLE_REACH = 9;
    /** How fast the free view flies, blocks a second; sprint makes it faster. */
    private static final double FLY_SPEED = 6;
    /** A glide between shots and a fade's half, in seconds, as the game plays them. */
    private static final double GLIDE = 0.5;
    private static final double FADE = 0.4;
    private static @Nullable ScenePreview open;
    private static int nextRequest = 1;

    private enum View { SHOT, FREE, SCENE }

    /** An anchor as found for the author, or stood in for. */
    private record Anchor(Vec3 pos, float yaw, int entity, boolean found, boolean asking) {}

    private final DevModeScreen editor;
    private final ResourceLocation id;
    private final JsonObject scene;
    private int shot;
    private final Map<Integer, Anchor> anchors = new HashMap<>();
    private final Map<Integer, Integer> requests = new HashMap<>();
    private final Vec3 standInPos;
    private final float standInYaw;

    private View view = View.SHOT;
    /** The free view: where it is, where it looks, what it turns around. */
    private Vec3 viewPos = Vec3.ZERO;
    private double viewYaw;
    private double viewPitch;
    private Vec3 pivot = Vec3.ZERO;
    /** The shot's time (0 to 1) and the whole scene's (seconds); whether one of them plays. */
    private double time;
    private double sceneTime;
    private boolean playing;
    private long lastFrame;

    /** What the mouse is doing over the world: 1 dragging the start, 2 the end, 3 orbiting, 4 looking around. */
    private int dragging;
    private int hovered;
    private boolean dirty;
    private boolean panelShown = true;
    private boolean grid;
    private boolean closeAsked;

    private final EditBox[] from = new EditBox[3];
    private final EditBox[] to = new EditBox[3];
    private final AxisSlider[] fromSliders = new AxisSlider[3];
    private final AxisSlider[] toSliders = new AxisSlider[3];
    /** Where the panel's middle begins; how far it is scrolled, how tall it is, where its two headings are. */
    private static final int CONTENT_TOP = 54;
    private int scroll;
    private int contentHeight;
    private int fromLabelY = CONTENT_TOP;
    private int toLabelY = CONTENT_TOP;
    private int cueLabelY = CONTENT_TOP;
    /** The thing of the shot picked on the time line, its index in the shot's "during"; -1 for none. */
    private int selectedCue = -1;
    /** Set when a thing is added or picked: the next build scrolls the panel to its fields. */
    private boolean revealCue;
    /** Why the last save stayed a draft; null when it went. */
    private @Nullable String saveProblem;
    /** The editor's list window (a sound, a track), opened over the view. */
    private final Catalog catalog = new Catalog();
    /** A short note from the list window (sounds reloaded, a sound that can't be used), shown for a few seconds. */
    private @Nullable Component note;
    private int noteColor;
    private long noteAt;
    private @Nullable TimeSlider slider;
    private boolean filling;

    ScenePreview(DevModeScreen editor, ResourceLocation id) {
        super(Component.literal(id.toString()));
        this.editor = editor;
        this.id = id;
        this.scene = ClientDevStore.parseObject(ClientDevStore.byType(ClientDevStore.TYPE_SCENE).getOrDefault(id, "{}")).deepCopy();
        Player player = Minecraft.getInstance().player;
        this.standInPos = player != null ? player.position() : Vec3.ZERO;
        this.standInYaw = player != null ? player.getYRot() : 0;
    }

    @Override
    protected void init() {
        open = this;
        SceneCamera.preview(this::pose);
        build();
        if (anchors.isEmpty()) {
            askAnchors();
        }
    }

    @Override
    public void removed() {
        if (open == this) {
            open = null;
        }
        SceneCamera.preview(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The world is the picture; only the panel has a ground.
    }

    private int left() {
        return panelShown ? PANEL : 0;
    }

    /** Where the panel's middle ends: above Save and Close. */
    private int contentBottom() {
        return height - 34;
    }

    // --- the scene's JSON

    private JsonArray shots() {
        JsonArray array = scene.get("shots") instanceof JsonArray written ? written : new JsonArray();
        scene.add("shots", array);
        if (array.isEmpty()) {
            JsonObject first = new JsonObject();
            first.addProperty("anchor", "player");
            array.add(first);
        }
        return array;
    }

    private JsonObject shotJson(int index) {
        JsonArray shots = shots();
        int at = Mth.clamp(index, 0, shots.size() - 1);
        JsonObject object = shots.get(at) instanceof JsonObject written ? written : new JsonObject();
        shots.set(at, object);
        return object;
    }

    /** Whether the shot is a talk (a dialogue) rather than a camera flight. */
    private boolean talk(int index) {
        return shotJson(index).has("dialogue");
    }

    private JsonObject camera(int index) {
        JsonObject shotJson = shotJson(index);
        JsonObject camera = shotJson.get("camera") instanceof JsonObject written ? written : new JsonObject();
        // A talk has no camera: what is read of it here is a default never written into it.
        if (!talk(index)) {
            shotJson.add("camera", camera);
        }
        if (!camera.has("from")) {
            camera.add("from", vec(new Vec3(-4, 2.5, -4)));
        }
        if (!camera.has("duration") && !camera.has("speed")) {
            camera.addProperty("duration", 5);
        }
        return camera;
    }

    private JsonObject camera() {
        return camera(shot);
    }

    private static Vec3 read(@Nullable JsonElement element, Vec3 otherwise) {
        if (element instanceof JsonArray array && array.size() == 3) {
            try {
                return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
            } catch (RuntimeException e) {
                return otherwise;
            }
        }
        return otherwise;
    }

    private static JsonArray vec(Vec3 vec) {
        JsonArray array = new JsonArray();
        array.add(round(vec.x));
        array.add(round(vec.y));
        array.add(round(vec.z));
        return array;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private Vec3 localFrom(int index) {
        return read(camera(index).get("from"), Vec3.ZERO);
    }

    private Vec3 localTo(int index) {
        JsonObject camera = camera(index);
        return camera.has("to") ? read(camera.get("to"), localFrom(index)) : localFrom(index);
    }

    private SceneDefinition.Ease ease(int index) {
        String name = camera(index).get("ease") instanceof JsonPrimitive written ? written.getAsString() : "in_out";
        for (SceneDefinition.Ease ease : SceneDefinition.Ease.values()) {
            if (ease.getSerializedName().equals(name)) {
                return ease;
            }
        }
        return SceneDefinition.Ease.IN_OUT;
    }

    private String look(int index) {
        JsonElement look = camera(index).get("look");
        return look == null ? "anchor" : look instanceof JsonPrimitive text ? text.getAsString() : "other";
    }

    private String transition(int index) {
        return shotJson(index).get("transition") instanceof JsonPrimitive written ? written.getAsString() : "cut";
    }

    private boolean bySpeed() {
        return camera().has("speed");
    }

    private double seconds(int index) {
        if (talk(index)) {
            // A talk takes no time of the scene's clock: it waits for it.
            return 0;
        }
        JsonObject camera = camera(index);
        try {
            if (camera.has("speed")) {
                double speed = Math.max(0.05, camera.get("speed").getAsDouble());
                return Math.max(0.05, Math.min(120, world(index, localFrom(index)).distanceTo(world(index, localTo(index))) / speed));
            }
            return Math.max(0.05, Math.min(120, camera.get("duration").getAsDouble()));
        } catch (RuntimeException e) {
            return 5;
        }
    }

    private double sceneSeconds() {
        double total = 0;
        for (int i = 0; i < shots().size(); i++) {
            total += seconds(i);
        }
        return total;
    }

    private void changed() {
        dirty = true;
        closeAsked = false;
    }

    // --- the anchors

    private void askAnchors() {
        anchors.clear();
        requests.clear();
        for (int i = 0; i < shots().size(); i++) {
            JsonElement anchor = shotJson(i).get("anchor");
            if (anchor == null || anchor instanceof JsonPrimitive text && text.getAsString().equals("player")) {
                Player player = Minecraft.getInstance().player;
                anchors.put(i, new Anchor(standInPos, standInYaw, player != null ? player.getId() : -1, true, false));
                continue;
            }
            int request = nextRequest++;
            requests.put(request, i);
            anchors.put(i, new Anchor(standInPos, standInYaw, -1, false, true));
            PacketDistributor.sendToServer(new ScenePayloads.AnchorQuery(request, anchor.toString()));
        }
    }

    /** The server's answer: where the shot's anchor is, or that it is not near (the author stands in). */
    public static void answer(ScenePayloads.AnchorAnswer answer) {
        ScenePreview preview = open;
        Integer index = preview != null ? preview.requests.remove(answer.request()) : null;
        if (index == null) {
            return;
        }
        preview.anchors.put(index, answer.found() ? new Anchor(answer.pos(), answer.yaw(), answer.entity(), true, false)
                : new Anchor(preview.standInPos, preview.standInYaw, -1, false, false));
    }

    private Anchor anchor(int index) {
        return anchors.getOrDefault(index, new Anchor(standInPos, standInYaw, -1, false, false));
    }

    private String anchorName() {
        JsonElement anchor = shotJson(shot).get("anchor");
        if (anchor == null || anchor instanceof JsonPrimitive) {
            return DevUi.str("scene_view.anchor_player");
        }
        JsonObject object = anchor.getAsJsonObject();
        for (String field : new String[] {"npc", "structure", "waypoint"}) {
            if (object.get(field) instanceof JsonPrimitive value) {
                return DevUi.str("scene_view.anchor_" + field, value.getAsString());
            }
        }
        return DevUi.str("scene_view.anchor_entity");
    }

    private static Vec3 forward(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
    }

    private static Vec3 right(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.cos(radians), 0, -Math.sin(radians));
    }

    /** A point given as right, up, forward of the shot's anchor, in the world. */
    private Vec3 world(int index, Vec3 offset) {
        Anchor anchor = anchor(index);
        return anchor.pos().add(right(anchor.yaw()).scale(offset.x)).add(0, offset.y, 0).add(forward(anchor.yaw()).scale(offset.z));
    }

    /** A point of the world as right, up, forward of the shot's anchor. */
    private Vec3 local(int index, Vec3 point) {
        Anchor anchor = anchor(index);
        Vec3 d = point.subtract(anchor.pos());
        return new Vec3(d.dot(right(anchor.yaw())), d.y, d.dot(forward(anchor.yaw())));
    }

    /** What the shot's camera looks at: the anchor's eyes (followed when it is an entity), else its place. */
    private Vec3 anchorTarget(int index, float partialTick) {
        Anchor anchor = anchor(index);
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = anchor.entity() >= 0 && minecraft.level != null ? minecraft.level.getEntity(anchor.entity()) : null;
        if (entity != null) {
            return entity.getEyePosition(partialTick);
        }
        return anchor.found() ? anchor.pos() : anchor.pos().add(0, 1.62, 0);
    }

    // --- poses

    /** The shot's camera at {@code t} of its time. */
    private SceneCamera.Pose shotPose(int index, double t, float partialTick) {
        Vec3 start = world(index, localFrom(index));
        Vec3 end = world(index, localTo(index));
        Vec3 pos = start.lerp(end, ease(index).apply(t));
        Vec3 target = anchorTarget(index, partialTick);
        Vec3 look = switch (look(index)) {
            case "path" -> end.subtract(start).lengthSqr() > 1.0e-6 ? pos.add(end.subtract(start)) : target;
            case "player" -> Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getEyePosition(partialTick) : target;
            default -> target;
        };
        return SceneCamera.facing(pos, look);
    }

    /** The whole scene at its second: its shot, a glide from the last one when it eases in. */
    private SceneCamera.Pose scenePose(double at, float partialTick) {
        double start = 0;
        int count = shots().size();
        int lastFlight = -1;
        for (int i = 0; i < count; i++) {
            if (talk(i)) {
                // A talk shows no flight here; the camera stays where the last one ended.
                continue;
            }
            double length = seconds(i);
            if (at < start + length || i == lastFlightIndex()) {
                double into = Math.max(0, at - start);
                SceneCamera.Pose pose = shotPose(i, Math.min(1, into / length), partialTick);
                if (lastFlight >= 0 && transition(i).equals("ease") && into < GLIDE) {
                    pose = shotPose(lastFlight, 1, partialTick).toward(pose, SceneDefinition.Ease.IN_OUT.apply(into / GLIDE));
                }
                return pose;
            }
            start += length;
            lastFlight = i;
        }
        return lastFlight >= 0 ? shotPose(lastFlight, 1, partialTick) : SceneCamera.facing(anchor(0).pos().add(0, 3, -6), anchor(0).pos());
    }

    private int lastFlightIndex() {
        for (int i = shots().size() - 1; i >= 0; i--) {
            if (!talk(i)) {
                return i;
            }
        }
        return -1;
    }

    /** How black the screen is at the scene's second: a fade around the cut into a shot that fades in. */
    private double sceneFade(double at) {
        double start = 0;
        for (int i = 0; i < shots().size(); i++) {
            if (transition(i).equals("fade")) {
                double distance = Math.abs(at - start);
                if (i == 0 && at < FADE) {
                    return 1 - at / FADE;
                }
                if (i > 0 && distance < FADE) {
                    return 1 - distance / FADE;
                }
            }
            start += seconds(i);
        }
        return 0;
    }

    /** The camera this frame, as the view shows it. */
    private SceneCamera.@Nullable Pose pose(float partialTick) {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        if (playing) {
            if (view == View.SCENE) {
                sceneTime += dt;
                if (sceneTime >= sceneSeconds()) {
                    sceneTime = sceneSeconds();
                    playing = false;
                }
            } else if (view == View.SHOT) {
                time += dt / seconds(shot);
                if (time >= 1) {
                    time = 1;
                    playing = false;
                }
                updateSlider();
            }
        }
        if (view == View.FREE) {
            fly(dt);
            return new SceneCamera.Pose(viewPos, (float) viewYaw, (float) viewPitch);
        }
        return view == View.SCENE ? scenePose(sceneTime, partialTick) : shotPose(shot, time, partialTick);
    }

    /** The movement keys fly the free view while no number is being typed. */
    private void fly(double dt) {
        Minecraft minecraft = Minecraft.getInstance();
        if (dt <= 0 || getFocused() instanceof EditBox || catalog.isOpen() || !minecraft.isWindowActive()) {
            return;
        }
        long window = minecraft.getWindow().getWindow();
        Vec3 look = direction(viewYaw, viewPitch);
        Vec3 side = right((float) viewYaw);
        Vec3 move = Vec3.ZERO;
        if (down(window, minecraft.options.keyUp)) {
            move = move.add(look);
        }
        if (down(window, minecraft.options.keyDown)) {
            move = move.subtract(look);
        }
        if (down(window, minecraft.options.keyRight)) {
            move = move.add(side);
        }
        if (down(window, minecraft.options.keyLeft)) {
            move = move.subtract(side);
        }
        if (down(window, minecraft.options.keyJump)) {
            move = move.add(0, 1, 0);
        }
        if (down(window, minecraft.options.keyShift)) {
            move = move.add(0, -1, 0);
        }
        if (move.lengthSqr() < 1.0e-6) {
            return;
        }
        double speed = FLY_SPEED * (down(window, minecraft.options.keySprint) ? 3 : 1);
        Vec3 step = move.normalize().scale(speed * dt);
        viewPos = viewPos.add(step);
        pivot = pivot.add(step);
    }

    private static boolean down(long window, KeyMapping key) {
        InputConstants.Key bound = key.getKey();
        return bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() > 0 && InputConstants.isKeyDown(window, bound.getValue());
    }

    private static Vec3 direction(double yaw, double pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /** The free view starts where the camera is now, turning around the shot's anchor. */
    private void goFree() {
        if (view == View.FREE) {
            return;
        }
        SceneCamera.Pose now = view == View.SCENE ? scenePose(sceneTime, 0) : shotPose(shot, time, 0);
        viewPos = now.pos();
        viewYaw = now.yaw();
        viewPitch = now.pitch();
        Vec3 target = anchorTarget(shot, 0);
        pivot = target.distanceTo(viewPos) > 0.5 ? target : viewPos.add(direction(viewYaw, viewPitch).scale(4));
        view = View.FREE;
        playing = false;
    }

    private void lookAt(Vec3 target) {
        SceneCamera.Pose facing = SceneCamera.facing(viewPos, target);
        viewYaw = facing.yaw();
        viewPitch = facing.pitch();
    }

    /** F: the free view turns to the selected point (or the anchor) and turns around it from now on. */
    private void focus() {
        goFree();
        Vec3 target = hovered == 1 ? world(shot, localFrom(shot)) : hovered == 2 ? world(shot, localTo(shot)) : anchorTarget(shot, 0);
        pivot = target;
        if (viewPos.distanceTo(target) < 1) {
            viewPos = target.subtract(direction(viewYaw, viewPitch).scale(5));
        }
        lookAt(target);
    }

    private void showShot(double at) {
        view = View.SHOT;
        time = Mth.clamp(at, 0, 1);
        updateSlider();
    }

    // --- the panel

    /**
     * Every widget as the editor draws it: buttons become its flat buttons ({@link DevButton}, which reads the original
     * each frame); one in the panel's middle that scrolled out of it is hidden.
     */
    @Override
    protected <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable
            & net.minecraft.client.gui.narration.NarratableEntry> T addRenderableWidget(T widget) {
        if (!catalog.isOpen() && widget instanceof net.minecraft.client.gui.components.AbstractWidget shown && shown.getY() >= 26
                && shown.getY() < height - 30) {
            shown.visible = shown.getY() >= CONTENT_TOP - 2 && shown.getY() + shown.getHeight() <= contentBottom();
        }
        if (widget instanceof Button button && !(widget instanceof DevButton)) {
            super.addRenderableWidget(new DevButton(button));
            return widget;
        }
        return super.addRenderableWidget(widget);
    }

    /** A slider as the editor would draw one: its dark box and edge, a gold fill to its value, its handle, its words. */
    static void drawSlider(GuiGraphics graphics, AbstractSliderButton slider, double value) {
        int x = slider.getX();
        int y = slider.getY();
        int w = slider.getWidth();
        int h = slider.getHeight();
        boolean hover = slider.isHoveredOrFocused();
        graphics.fill(x, y, x + w, y + h, DevUi.CARD);
        DevUi.frame(graphics, x, y, w, h, hover ? DevUi.ACCENT : DevUi.CARD_EDGE);
        int fill = (int) Math.round((w - 2) * value);
        graphics.fill(x + 1, y + h - 3, x + 1 + fill, y + h - 1, 0x80E1B34D);
        int handle = x + 1 + (int) Math.round((w - 6) * value);
        graphics.fill(handle, y + 1, handle + 4, y + h - 1, hover ? DevUi.ACCENT : DevUi.LABEL);
        var font = Minecraft.getInstance().font;
        Component message = slider.getMessage();
        graphics.drawString(font, message, x + (w - font.width(message)) / 2, y + (h - 8) / 2, DevUi.TEXT, false);
    }

    private void build() {
        clearWidgets();
        if (catalog.isOpen()) {
            catalog.addWidgets(this);
            return;
        }
        if (!panelShown) {
            return;
        }
        int x = 8;
        int count = shots().size();
        int tools = PANEL - 8 - 5 * 21;
        addRenderableWidget(small("⏴", "scene_view.prev", tools, () -> switchShot(-1))).active = shot > 0;
        addRenderableWidget(small("⏵", "scene_view.next", tools + 21, () -> switchShot(1))).active = shot < count - 1;
        addRenderableWidget(small("+", "scene_view.add", tools + 42, this::addShot));
        addRenderableWidget(small("📋", "scene_view.copy", tools + 63, this::copyShot));
        addRenderableWidget(small("✕", "scene_view.remove", tools + 84, this::removeShot)).active = count > 1;
        if (talk(shot)) {
            // A talk: its dialogue is set in the form; here the scene around it plays.
            int y = CONTENT_TOP + 24 - scroll;
            addRenderableWidget(Button.builder(Component.literal("⏩ ").append(DevUi.text("scene_view.play_scene")), b -> playScene())
                    .bounds(x, y, 200, 20).tooltip(Tooltip.create(DevUi.text("scene_view.play_scene_tooltip"))).build());
            contentHeight = 48;
            addRenderableWidget(Button.builder(DevUi.text("scene_view.save").copy().withColor(DevUi.ACCENT), b -> save())
                    .bounds(x, height - 28, 98, 20).tooltip(Tooltip.create(DevUi.text("scene_view.save_tooltip"))).build());
            addRenderableWidget(Button.builder(DevUi.text("scene_view.close"), b -> close()).bounds(x + 102, height - 28, 98, 20).build());
            return;
        }
        int y = CONTENT_TOP - scroll;
        fromLabelY = y;
        y = pointRows(x, y, true);
        toLabelY = y;
        y = pointRows(x, y, false);
        addRenderableWidget(Button.builder(Component.literal("⇄"), b -> {
            Vec3 start = localFrom(shot);
            camera().add("from", vec(localTo(shot)));
            camera().add("to", vec(start));
            changed();
            build();
        }).bounds(x + 112, toLabelY - 1, 40, 14).tooltip(Tooltip.create(DevUi.text("scene_view.swap_tooltip"))).build());
        y += 4;
        addRenderableWidget(Button.builder(DevUi.text(bySpeed() ? "scene_view.by_speed" : "scene_view.by_time"), b -> {
            JsonObject camera = camera();
            double seconds = seconds(shot);
            if (bySpeed()) {
                camera.remove("speed");
                camera.addProperty("duration", round(seconds));
            } else {
                double distance = world(shot, localFrom(shot)).distanceTo(world(shot, localTo(shot)));
                camera.remove("duration");
                camera.addProperty("speed", round(Math.max(0.05, distance / seconds)));
            }
            changed();
            build();
        }).bounds(x, y, 128, 20).tooltip(Tooltip.create(DevUi.text("scene_view.length_tooltip"))).build());
        EditBox length = addRenderableWidget(new EditBox(font, x + 134, y, 66, 20, Component.empty()));
        length.setValue(number(camera().get(bySpeed() ? "speed" : "duration")));
        length.setTooltip(Tooltip.create(DevUi.text("scene_view.nudge_tooltip")));
        length.setResponder(text -> {
            if (filling) {
                return;
            }
            double value = parse(text, -1);
            if (value > 0) {
                camera().addProperty(bySpeed() ? "speed" : "duration", Math.min(bySpeed() ? 200 : 120, value));
                changed();
            }
        });
        y += ROW;
        addRenderableWidget(Button.builder(DevUi.text("scene_view.ease_" + ease(shot).getSerializedName()), b -> {
            SceneDefinition.Ease[] all = SceneDefinition.Ease.values();
            camera().addProperty("ease", all[(ease(shot).ordinal() + 1) % all.length].getSerializedName());
            changed();
            build();
        }).bounds(x, y, 200, 20).tooltip(Tooltip.create(DevUi.text("scene_view.ease_tooltip"))).build());
        y += ROW;
        Button look = addRenderableWidget(Button.builder(DevUi.text("scene_view.look_" + look(shot)), b -> {
            String next = switch (look(shot)) {
                case "anchor" -> "path";
                case "path" -> "player";
                default -> "anchor";
            };
            camera().addProperty("look", next);
            changed();
            build();
        }).bounds(x, y, 200, 20).tooltip(Tooltip.create(DevUi.text("scene_view.look_tooltip"))).build());
        look.active = !look(shot).equals("other");
        y += ROW;
        addRenderableWidget(Button.builder(DevUi.text("scene_view.transition_" + transition(shot)), b -> {
            String next = switch (transition(shot)) {
                case "cut" -> "fade";
                case "fade" -> "ease";
                default -> "cut";
            };
            shotJson(shot).addProperty("transition", next);
            changed();
            build();
        }).bounds(x, y, 200, 20).tooltip(Tooltip.create(DevUi.text("scene_view.transition_tooltip"))).build());
        y += ROW + 6;
        slider = addRenderableWidget(new TimeSlider(x, y, 200, 20, time));
        y += ROW;
        addRenderableWidget(Button.builder(Component.literal("⏵ ").append(DevUi.text("scene_view.play")), b -> playShot())
                .bounds(x, y, 98, 20).tooltip(Tooltip.create(DevUi.text("scene_view.play_tooltip"))).build());
        addRenderableWidget(Button.builder(Component.literal("⏩ ").append(DevUi.text("scene_view.play_scene")), b -> playScene())
                .bounds(x + 102, y, 98, 20).tooltip(Tooltip.create(DevUi.text("scene_view.play_scene_tooltip"))).build());
        y += ROW;
        addRenderableWidget(Button.builder(DevUi.text("scene_view.free"), b -> goFree())
                .bounds(x, y, 98, 20).tooltip(Tooltip.create(DevUi.text("scene_view.free_tooltip"))).build());
        addRenderableWidget(Button.builder(DevUi.text(grid ? "scene_view.grid_on" : "scene_view.grid_off"), b -> {
            grid = !grid;
            build();
        }).bounds(x + 102, y, 98, 20).tooltip(Tooltip.create(DevUi.text("scene_view.grid_tooltip"))).build());
        y += ROW + 8;
        y = cueEditor(x, y);
        contentHeight = y + scroll - CONTENT_TOP;
        if (revealCue) {
            // A thing just added or picked: the panel scrolls to its fields when they are out of sight.
            revealCue = false;
            int most = Math.max(0, contentHeight - (contentBottom() - CONTENT_TOP));
            int wanted = Mth.clamp(scroll + Math.max(0, y - contentBottom()), 0, most);
            wanted = Math.min(wanted, Math.max(0, scroll + cueLabelY - CONTENT_TOP));
            if (wanted != scroll) {
                scroll = wanted;
                build();
                return;
            }
        }
        addRenderableWidget(Button.builder(DevUi.text("scene_view.save").copy().withColor(DevUi.ACCENT), b -> save())
                .bounds(x, height - 28, 98, 20).tooltip(Tooltip.create(DevUi.text("scene_view.save_tooltip"))).build());
        addRenderableWidget(Button.builder(DevUi.text("scene_view.close"), b -> close())
                .bounds(x + 102, height - 28, 98, 20).build());
    }

    private Button small(String label, String tooltip, int x, Runnable press) {
        return Button.builder(Component.literal(label), b -> press.run()).bounds(x, 6, 20, 18).tooltip(Tooltip.create(DevUi.text(tooltip))).build();
    }

    // --- what happens in the shot

    private JsonArray during(int index) {
        JsonObject shotJson = shotJson(index);
        JsonArray during = shotJson.get("during") instanceof JsonArray written ? written : new JsonArray();
        shotJson.add("during", during);
        return during;
    }

    private static String cueKind(JsonObject cue) {
        for (String kind : FormScenes.CUE_KINDS) {
            if (cue.has(kind)) {
                return kind;
            }
        }
        return "line";
    }

    private static double cueAt(JsonObject cue) {
        try {
            return cue.has("at") ? cue.get("at").getAsDouble() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private @Nullable JsonObject selectedCue() {
        JsonArray during = during(shot);
        return selectedCue >= 0 && selectedCue < during.size() && during.get(selectedCue) instanceof JsonObject cue ? cue : null;
    }

    /**
     * The kinds the view switches a thing through. Actions are left out: an empty list is not a thing yet, and the scene
     * would not save with it; they are added in the form, where their fields are.
     */
    private static final List<String> VIEW_CUE_KINDS = FormScenes.CUE_KINDS.stream().filter(kind -> !kind.equals("actions")).toList();

    /** A new line at that second of a flight shot, picked to be written; the panel scrolls to it. */
    private void addCue(int index, double seconds) {
        JsonObject cue = new JsonObject();
        cue.addProperty("at", Math.round(seconds * 10) / 10.0);
        cue.add("line", freshCue("line"));
        JsonArray during = during(index);
        during.add(cue);
        pickShot(index);
        selectedCue = during.size() - 1;
        revealCue = true;
        changed();
        build();
        showShot(seconds(index) <= 0 ? 0 : seconds / seconds(index));
    }

    /** A fresh thing of that kind: its text empty, a fade of half a second each way, no actions. */
    private static JsonElement freshCue(String kind) {
        return switch (kind) {
            case "music" -> new JsonPrimitive("");
            case "actions" -> new JsonArray();
            case "sound" -> {
                JsonObject sound = new JsonObject();
                sound.addProperty("sound", "");
                yield sound;
            }
            case "fade" -> {
                JsonObject fade = new JsonObject();
                fade.addProperty("in", 0.5);
                fade.addProperty("stay", 1);
                fade.addProperty("out", 0.5);
                yield fade;
            }
            default -> {
                JsonObject text = new JsonObject();
                text.addProperty("text", "");
                yield text;
            }
        };
    }

    /**
     * The shot's things at their seconds, edited here: "+ Here" adds one at the moment shown; the one picked on the time
     * line (or just added) shows its kind, its words or its sound, and for a line who says it, for a caption where it
     * stands and whether on black.
     */
    private int cueEditor(int x, int y) {
        cueLabelY = y;
        y += 16;
        JsonObject cue = selectedCue();
        if (cue == null) {
            return y + 24;
        }
        String kind = cueKind(cue);
        addRenderableWidget(Button.builder(DevUi.text("scene.cue." + kind), b -> {
            int at = VIEW_CUE_KINDS.indexOf(kind);
            String next = VIEW_CUE_KINDS.get((at + 1) % VIEW_CUE_KINDS.size());
            cue.remove(kind);
            cue.add(next, freshCue(next));
            changed();
            build();
        }).bounds(x, y, 156, 18).tooltip(Tooltip.create(DevUi.text("scene.cue.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
            during(shot).remove(selectedCue);
            selectedCue = -1;
            changed();
            build();
        }).bounds(x + 160, y, 40, 18).tooltip(Tooltip.create(DevUi.text("scene_view.cue_remove"))).build());
        y += 22;
        String language = ClientDevStore.activeLanguage();
        switch (kind) {
            case "title", "caption", "line" -> {
                JsonObject inner = cue.get(kind) instanceof JsonObject object ? object : new JsonObject();
                cue.add(kind, inner);
                y = textBox(x, y, inner, "text", "scene_view.cue_text", kind + ".text", language);
                if (kind.equals("title")) {
                    y = textBox(x, y, inner, "subtitle", "scene_view.cue_subtitle", kind + ".subtitle", language);
                } else if (kind.equals("line")) {
                    y = textBox(x, y, inner, "speaker", "scene_view.cue_speaker", kind + ".speaker", language);
                } else {
                    String position = inner.get("position") instanceof JsonPrimitive written ? written.getAsString() : "bottom_left";
                    boolean black = inner.get("black") instanceof JsonPrimitive written && written.getAsBoolean();
                    addRenderableWidget(Button.builder(DevUi.text("scene_view.position_" + position), b -> {
                        String next = switch (position) {
                            case "bottom_left" -> "top_left";
                            case "top_left" -> "center";
                            default -> "bottom_left";
                        };
                        inner.addProperty("position", next);
                        changed();
                        build();
                    }).bounds(x, y, 98, 18).build());
                    addRenderableWidget(Button.builder(DevUi.text(black ? "scene_view.black_on" : "scene_view.black_off"), b -> {
                        inner.addProperty("black", !black);
                        changed();
                        build();
                    }).bounds(x + 102, y, 98, 18).build());
                    y += 22;
                }
            }
            case "sound" -> {
                JsonObject inner = cue.get("sound") instanceof JsonObject object ? object : new JsonObject();
                cue.add("sound", inner);
                y = soundBox(x, y, false, () -> inner.get("sound") instanceof JsonPrimitive id ? id.getAsString() : "", value -> inner.addProperty("sound", value));
            }
            case "music" -> y = soundBox(x, y, true, () -> cue.get("music") instanceof JsonPrimitive id ? id.getAsString() : "", value -> cue.addProperty("music", value));
            default -> {
                // A fade and actions are set in the form, where their fields are.
                y += 2;
            }
        }
        return y + 4;
    }

    private int textBox(int x, int y, JsonObject inner, String field, String hint, String path, String language) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, 200, 16, Component.empty()));
        box.setMaxLength(512);
        box.setHint(DevUi.text(hint).copy().withColor(DevUi.HINT));
        String key = ClientDevStore.getKey(inner, field);
        // A key with no text yet (just saved, the server's answer not here) shows empty instead of the key itself.
        String shown = ClientDevStore.readStoryText(inner, field, "", language);
        filling = true;
        box.setValue(key != null && shown.equals(key) ? "" : shown);
        filling = false;
        box.setResponder(text -> {
            if (filling) {
                return;
            }
            ClientDevStore.writeStoryText(inner, field, text, freshTextKey(path));
            changed();
        });
        return y + 20;
    }

    /**
     * The key for a thing's text that has none yet: by the shot and the thing's place, with a number after it when that
     * key is already used in the scene (things removed or shots moved since it was given).
     */
    private String freshTextKey(String path) {
        String base = ClientDevStore.canonicalKey(ClientDevStore.TYPE_SCENE, id, "shot" + shot + ".cue" + selectedCue + "." + path);
        String json = scene.toString();
        String key = base;
        for (int n = 2; json.contains("\"" + key + "\""); n++) {
            key = base + "_" + n;
        }
        return key;
    }

    /**
     * A sound or a track as the form picks one: its id to type, "…" for the editor's list (the story's sounds, then the
     * game's and other mods'; for a track only the music) and a button that plays it.
     */
    private int soundBox(int x, int y, boolean music, java.util.function.Supplier<String> read, java.util.function.Consumer<String> write) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, 156, 18, Component.empty()));
        box.setMaxLength(256);
        box.setHint(DevUi.text(music ? "scene_view.cue_music" : "scene_view.cue_sound").copy().withColor(DevUi.HINT));
        filling = true;
        box.setValue(read.get());
        filling = false;
        box.setResponder(text -> {
            if (!filling) {
                write.accept(text.trim());
                changed();
            }
        });
        Component pick = DevUi.text(music ? "music.pick" : "sound.pick");
        addRenderableWidget(Button.builder(Component.literal("…"), b -> catalog.open(pick, music ? Catalog.Kind.MUSIC : Catalog.Kind.SOUND,
                box.getValue(), (kind, entry) -> {
            if (entry.id().startsWith(Catalog.PROBLEM)) {
                status(DevUi.text("sound.problem." + entry.id().substring(Catalog.PROBLEM.length())), DevUi.BAD);
                return;
            }
            write.accept(entry.id().equals(Catalog.CLEAR) ? "" : entry.id());
            changed();
        })).tooltip(Tooltip.create(pick)).bounds(x + 160, y, 18, 18).build());
        addRenderableWidget(Button.builder(Component.literal("⏵"), b -> {
            ResourceLocation sound = ResourceLocation.tryParse(box.getValue().trim());
            if (sound != null && !sound.getPath().isEmpty()) {
                Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvent.createVariableRangeEvent(sound), 1f));
            }
        }).tooltip(Tooltip.create(DevUi.text("sound.play"))).bounds(x + 182, y, 18, 18).build());
        return y + 22;
    }

    // --- the list window's host

    @Override
    public int hostWidth() {
        return width;
    }

    @Override
    public int hostHeight() {
        return height;
    }

    @Override
    public net.minecraft.client.gui.Font font() {
        return font;
    }

    @Override
    public <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable
            & net.minecraft.client.gui.narration.NarratableEntry> T add(T widget) {
        return addRenderableWidget(widget);
    }

    @Override
    public void focus(EditBox box) {
        setFocused(box);
        box.setFocused(true);
    }

    @Override
    public void rebuild() {
        build();
    }

    @Override
    public void status(Component text, int color) {
        note = text;
        noteColor = color;
        noteAt = net.minecraft.Util.getMillis();
    }

    // --- the time line

    private static final int LINE_H = 22;

    private int lineLeft() {
        return left() + 8;
    }

    private int lineRight() {
        return width - 8;
    }

    private int lineTop() {
        return height - 8 - LINE_H;
    }

    /** How wide a shot is drawn on the time line: its seconds; a talk a small fixed share. */
    private double shown(int index) {
        return talk(index) ? Math.max(0.8, sceneSeconds() * 0.06) : seconds(index);
    }

    private double shownTotal() {
        double total = 0;
        for (int i = 0; i < shots().size(); i++) {
            total += shown(i);
        }
        return Math.max(0.05, total);
    }

    /** Where on the time line a moment of a shot is drawn. */
    private double lineX(int index, double seconds) {
        double before = 0;
        for (int i = 0; i < index; i++) {
            before += shown(i);
        }
        return lineLeft() + (before + Math.min(seconds, shown(index))) / shownTotal() * (lineRight() - lineLeft());
    }

    private int shotAtX(double mouseX) {
        for (int i = 0; i < shots().size(); i++) {
            if (mouseX < lineX(i, shown(i))) {
                return i;
            }
        }
        return shots().size() - 1;
    }

    private static int cueColor(String kind) {
        return switch (kind) {
            case "title" -> GOLD;
            case "caption" -> 0xFFF2EAD8;
            case "line" -> 0xFF92AACC;
            case "sound" -> 0xFFA5B574;
            case "music" -> 0xFFBE96D0;
            case "fade" -> 0xFF8A8A8A;
            default -> 0xFFD9785A;
        };
    }

    /** The cue under the cursor on the time line, as {shot, index}, or null. */
    private int @Nullable [] cueAtX(double mouseX, double mouseY) {
        if (mouseY < lineTop() || mouseY > lineTop() + LINE_H) {
            return null;
        }
        for (int i = 0; i < shots().size(); i++) {
            if (talk(i)) {
                continue;
            }
            JsonArray during = during(i);
            for (int j = 0; j < during.size(); j++) {
                if (during.get(j) instanceof JsonObject cue && Math.abs(lineX(i, cueAt(cue)) - mouseX) <= 3) {
                    return new int[]{i, j};
                }
            }
        }
        return null;
    }

    /** The scene on one line at the bottom: a block a shot (a talk with the chat icon), its things as marks, where the view is. */
    private void drawTimeLine(GuiGraphics graphics) {
        int top = lineTop();
        graphics.fill(lineLeft() - 2, top - 2, lineRight() + 2, top + LINE_H + 2, 0xE01A140F);
        DevUi.frame(graphics, lineLeft() - 2, top - 2, lineRight() - lineLeft() + 4, LINE_H + 4, DevUi.CARD_EDGE);
        for (int i = 0; i < shots().size(); i++) {
            int x0 = (int) Math.round(lineX(i, 0));
            int x1 = (int) Math.round(lineX(i, shown(i)));
            int color = i == shot ? DevUi.ROW_SELECTED : DevUi.ROW_HOVER;
            graphics.fill(x0 + 1, top + 1, Math.max(x0 + 2, x1 - 1), top + LINE_H - 1, color);
            int labelColor = i == shot ? DevUi.TEXT : DevUi.MUTED;
            if (talk(i)) {
                if (x1 - x0 > 15) {
                    DevIcons.draw(graphics, "chat", x0 + 3, top + LINE_H - 13, labelColor);
                }
            } else if (x1 - x0 > font.width(String.valueOf(i + 1)) + 4) {
                graphics.drawString(font, String.valueOf(i + 1), x0 + 3, top + LINE_H - 10, labelColor, false);
            }
            if (talk(i)) {
                continue;
            }
            JsonArray during = during(i);
            for (int j = 0; j < during.size(); j++) {
                if (during.get(j) instanceof JsonObject cue) {
                    int mx = (int) Math.round(lineX(i, cueAt(cue)));
                    boolean picked = i == shot && j == selectedCue;
                    graphics.fill(mx - 1, top + 2, mx + 2, top + 11, cueColor(cueKind(cue)));
                    if (picked) {
                        DevUi.frame(graphics, mx - 2, top + 1, 5, 11, 0xFFFFFFFF);
                    }
                }
            }
        }
        double head = playhead();
        if (head >= 0) {
            int hx = (int) Math.round(head);
            graphics.fill(hx, top - 3, hx + 1, top + LINE_H + 3, DevUi.ACCENT);
        }
    }

    /** The second of a shot under the cursor, kept inside the shot. */
    private double secondsAtX(int index, double mouseX) {
        double x0 = lineX(index, 0);
        double x1 = lineX(index, shown(index));
        return x1 - x0 < 1 ? 0 : Mth.clamp((mouseX - x0) / (x1 - x0), 0, 1) * seconds(index);
    }

    /**
     * A click on the time line: on a mark it picks that thing (and its shot) to edit and drag along, the right button
     * removes it; elsewhere it picks the shot under the cursor and shows that moment, dragging goes through it, and the
     * right button adds a thing there.
     */
    private void lineClicked(double mouseX, double mouseY, int button) {
        int[] hit = cueAtX(mouseX, mouseY);
        if (hit != null) {
            if (button == 1) {
                during(hit[0]).remove(hit[1]);
                if (hit[0] == shot) {
                    selectedCue = -1;
                }
                changed();
                build();
                return;
            }
            pickShot(hit[0]);
            selectedCue = hit[1];
            revealCue = true;
            showShot(seconds(shot) <= 0 ? 0 : cueAt(during(shot).get(hit[1]).getAsJsonObject()) / seconds(shot));
            build();
            dragging = 5;
            return;
        }
        if (button == 1) {
            int index = shotAtX(mouseX);
            if (!talk(index)) {
                addCue(index, secondsAtX(index, mouseX));
            }
            return;
        }
        if (button != 0) {
            return;
        }
        selectedCue = -1;
        scrubTo(mouseX);
        build();
        dragging = 6;
    }

    /** Picks the shot under the cursor and shows the moment there. */
    private void scrubTo(double mouseX) {
        int index = shotAtX(mouseX);
        if (index != shot) {
            pickShot(index);
            build();
        }
        playing = false;
        showShot(seconds(shot) <= 0 ? 0 : secondsAtX(shot, mouseX) / seconds(shot));
    }

    private void pickShot(int index) {
        if (index != shot) {
            shot = index;
            selectedCue = -1;
            scroll = 0;
        }
    }

    /** Where the view's moment is on the time line; -1 in the free view. */
    private double playhead() {
        if (view == View.SHOT) {
            return lineX(shot, time * seconds(shot));
        }
        if (view == View.SCENE) {
            double left = sceneTime;
            for (int i = 0; i < shots().size(); i++) {
                double length = seconds(i);
                if (!talk(i) && left <= length) {
                    return lineX(i, left);
                }
                left -= length;
            }
            return lineRight();
        }
        return -1;
    }

    /**
     * A point under its heading (with "📷 from here"): a row for each axis, right, up, forward of the anchor, a slider
     * and the exact number beside it, to drag or to type.
     */
    private int pointRows(int x, int y, boolean first) {
        addRenderableWidget(Button.builder(Component.literal("📷"), b -> {
            goFree();
            camera().add(first ? "from" : "to", vec(local(shot, viewPos)));
            changed();
            build();
        }).bounds(x + 158, y - 1, 42, 14).tooltip(Tooltip.create(DevUi.text("scene_view.here_tooltip"))).build());
        y += 16;
        Vec3 value = first ? localFrom(shot) : localTo(shot);
        double[] parts = {value.x, value.y, value.z};
        EditBox[] boxes = first ? from : to;
        AxisSlider[] sliders = first ? fromSliders : toSliders;
        for (int i = 0; i < 3; i++) {
            int axis = i;
            AxisSlider slider = addRenderableWidget(new AxisSlider(x + 14, y, 138, 16, first, axis, parts[i]));
            slider.setTooltip(Tooltip.create(DevUi.text("scene_view.axis_" + i)));
            sliders[i] = slider;
            EditBox box = addRenderableWidget(new EditBox(font, x + 156, y, 44, 16, Component.empty()));
            box.setValue(String.format(Locale.ROOT, "%.2f", parts[i]));
            box.setTooltip(Tooltip.create(DevUi.text("scene_view.nudge_tooltip")));
            box.setResponder(text -> {
                if (filling) {
                    return;
                }
                double number = parse(text, Double.NaN);
                if (!Double.isNaN(number)) {
                    setAxis(first, axis, number);
                    sliders[axis].show(number);
                }
            });
            boxes[i] = box;
            y += 18;
        }
        return y + 4;
    }

    /** One axis of a point changed (a slider, a number): written, and the view shows that end of the shot. */
    private void setAxis(boolean first, int axis, double number) {
        Vec3 old = first ? localFrom(shot) : localTo(shot);
        Vec3 updated = axis == 0 ? new Vec3(number, old.y, old.z) : axis == 1 ? new Vec3(old.x, number, old.z) : new Vec3(old.x, old.y, number);
        camera().add(first ? "from" : "to", vec(updated));
        changed();
        if (view != View.FREE) {
            showShot(first ? 0 : 1);
        }
    }

    /**
     * A slider for one axis of a point. Its range is ±16 blocks around the anchor, wider (32, 64, 128) once a value
     * reaches its edge and is let go, so small moves stay fine and far points stay reachable; a number typed beyond it
     * widens it too.
     */
    private final class AxisSlider extends AbstractSliderButton {
        private final boolean first;
        private final int axis;
        private double range;

        AxisSlider(int x, int y, int width, int height, boolean first, int axis, double at) {
            super(x, y, width, height, Component.empty(), 0.5);
            this.first = first;
            this.axis = axis;
            this.range = rangeFor(at);
            this.value = toSlider(at);
            updateMessage();
        }

        private static double rangeFor(double at) {
            double range = 16;
            while (Math.abs(at) > range * 0.95 && range < 1024) {
                range *= 2;
            }
            return range;
        }

        private double toSlider(double at) {
            return Mth.clamp((at / range + 1) / 2, 0, 1);
        }

        private double number() {
            // Steps of a tenth of a block, the way the numbers are written.
            return Math.round((value * 2 - 1) * range * 10) / 10.0;
        }

        void show(double at) {
            if (Math.abs(at) > range * 0.95) {
                range = rangeFor(at);
            }
            value = toSlider(at);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format(Locale.ROOT, "%.1f", number())));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            drawSlider(graphics, this, value);
        }

        @Override
        protected void applyValue() {
            double number = number();
            setAxis(first, axis, number);
            filling = true;
            EditBox box = (first ? from : to)[axis];
            if (box != null) {
                box.setValue(String.format(Locale.ROOT, "%.2f", number));
            }
            filling = false;
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            if ((value <= 0.02 || value >= 0.98) && range < 1024) {
                double current = number();
                range *= 2;
                value = toSlider(current);
                updateMessage();
            }
        }
    }

    private static double parse(String text, double otherwise) {
        try {
            return Double.parseDouble(text.replace(',', '.').trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static String number(@Nullable JsonElement element) {
        try {
            return element == null ? "" : String.format(Locale.ROOT, "%.2f", element.getAsDouble());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The numbers as the JSON has them now, without their boxes writing back. */
    private void refreshPoints() {
        filling = true;
        Vec3 start = localFrom(shot);
        Vec3 end = localTo(shot);
        double[][] values = {{start.x, start.y, start.z}, {end.x, end.y, end.z}};
        EditBox[][] boxes = {from, to};
        AxisSlider[][] sliders = {fromSliders, toSliders};
        for (int row = 0; row < 2; row++) {
            for (int i = 0; i < 3; i++) {
                if (boxes[row][i] != null && !boxes[row][i].isFocused()) {
                    boxes[row][i].setValue(String.format(Locale.ROOT, "%.2f", values[row][i]));
                }
                if (sliders[row][i] != null) {
                    sliders[row][i].show(values[row][i]);
                }
            }
        }
        filling = false;
    }

    private void updateSlider() {
        if (slider != null) {
            slider.show(time);
        }
    }

    /** The slider through the shot's time: dragging it shows that moment; it follows the time while it plays. */
    private final class TimeSlider extends AbstractSliderButton {
        TimeSlider(int x, int y, int width, int height, double at) {
            super(x, y, width, height, Component.empty(), at);
            updateMessage();
        }

        void show(double at) {
            value = Mth.clamp(at, 0, 1);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(DevUi.text("scene_view.moment", String.format(Locale.ROOT, "%.1f", value * seconds(shot)),
                    String.format(Locale.ROOT, "%.1f", seconds(shot))));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            drawSlider(graphics, this, value);
        }

        @Override
        protected void applyValue() {
            playing = false;
            view = View.SHOT;
            time = value;
        }
    }

    private void playShot() {
        if (view != View.SHOT || time >= 1) {
            time = 0;
        }
        view = View.SHOT;
        playing = !playing || time == 0;
        lastFrame = 0;
    }

    private void playScene() {
        if (view != View.SCENE || sceneTime >= sceneSeconds()) {
            sceneTime = 0;
        }
        view = View.SCENE;
        playing = true;
        lastFrame = 0;
    }

    private void switchShot(int by) {
        shot = Mth.clamp(shot + by, 0, shots().size() - 1);
        selectedCue = -1;
        playing = false;
        if (view != View.FREE) {
            view = View.SHOT;
            time = 0;
        }
        build();
    }

    private void addShot() {
        JsonObject added = new JsonObject();
        added.add("anchor", shotJson(shot).get("anchor") != null ? shotJson(shot).get("anchor").deepCopy() : new JsonPrimitive("player"));
        JsonObject camera = new JsonObject();
        // It starts where this one ends: the next shot of the same flight.
        camera.add("from", vec(localTo(shot)));
        camera.add("to", vec(localTo(shot).add(2, 0, 0)));
        camera.addProperty("duration", 4);
        added.add("camera", camera);
        shots().add(added);
        moveLastTo(shot + 1);
    }

    private void copyShot() {
        shots().add(shotJson(shot).deepCopy());
        moveLastTo(shot + 1);
    }

    private void moveLastTo(int index) {
        JsonArray shots = shots();
        JsonElement last = shots.remove(shots.size() - 1);
        JsonArray reordered = new JsonArray();
        for (int i = 0; i <= shots.size(); i++) {
            if (i == index) {
                reordered.add(last);
            }
            if (i < shots.size()) {
                reordered.add(shots.get(i));
            }
        }
        scene.add("shots", reordered);
        shot = index;
        selectedCue = -1;
        changed();
        askAnchors();
        showShot(0);
        build();
    }

    private void removeShot() {
        JsonArray shots = shots();
        if (shots.size() <= 1) {
            return;
        }
        shots.remove(shot);
        shot = Mth.clamp(shot, 0, shots.size() - 1);
        selectedCue = -1;
        changed();
        askAnchors();
        showShot(0);
        build();
    }

    /**
     * Writes the scene. One the game would not load stays a draft (as the editor keeps one), and the panel says why
     * instead of seeming saved.
     */
    private void save() {
        String json = scene.toString();
        ClientDevStore.putLocal(ClientDevStore.TYPE_SCENE, id, json);
        saveProblem = DevChecks.problem(ClientDevStore.TYPE_SCENE, scene).orElse(null);
        DevRequests.save(ClientDevStore.TYPE_SCENE, id, json, Optional.empty());
        dirty = saveProblem != null;
        closeAsked = false;
    }

    /** Back to the editor; with changes not saved, the first press asks. */
    private void close() {
        if (dirty && !closeAsked) {
            closeAsked = true;
            return;
        }
        Minecraft.getInstance().setScreen(editor);
        editor.rebuild();
    }

    // --- input

    private boolean typing() {
        return getFocused() instanceof EditBox;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (catalog.isOpen()) {
            return catalog.keyPressed(this, keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (typing()) {
                setFocused(null);
                return true;
            }
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_S && hasControlDown()) {
            save();
            return true;
        }
        if (!typing()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_SPACE -> {
                    if (view == View.SCENE) {
                        playing = !playing;
                    } else {
                        playShot();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                    double step = (keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1) * (hasShiftDown() ? 1.0 : 0.1);
                    if (view == View.SCENE) {
                        sceneTime = Mth.clamp(sceneTime + step, 0, sceneSeconds());
                    } else {
                        showShot(time + step / seconds(shot));
                    }
                    playing = false;
                    return true;
                }
                case GLFW.GLFW_KEY_TAB -> {
                    if (view == View.FREE) {
                        showShot(time);
                    } else {
                        goFree();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_F -> {
                    focus();
                    return true;
                }
                case GLFW.GLFW_KEY_H -> {
                    panelShown = !panelShown;
                    build();
                    return true;
                }
                case GLFW.GLFW_KEY_G -> {
                    grid = !grid;
                    build();
                    return true;
                }
                default -> {
                    // The movement keys fly the free view; read each frame, not here.
                }
            }
            if (view == View.FREE && isMovementKey(keyCode)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static boolean isMovementKey(int keyCode) {
        var options = Minecraft.getInstance().options;
        for (KeyMapping key : new KeyMapping[] {options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump, options.keyShift, options.keySprint}) {
            if (key.getKey().getType() == InputConstants.Type.KEYSYM && key.getKey().getValue() == keyCode) {
                return true;
            }
        }
        return false;
    }

    /** Which handle is under the cursor: 1 the start, 2 the end, 0 none. */
    private int handleAt(double mouseX, double mouseY) {
        int found = 0;
        double best = HANDLE_REACH;
        for (int which = 1; which <= 2; which++) {
            Vec3 point = world(shot, which == 1 ? localFrom(shot) : localTo(shot));
            float[] screen = ScreenProjection.project(point, width, height);
            if (screen != null) {
                double distance = Math.hypot(screen[0] - mouseX, screen[1] - mouseY);
                if (distance < best) {
                    best = distance;
                    found = which;
                }
            }
        }
        return found;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (catalog.isOpen()) {
            return super.mouseClicked(mouseX, mouseY, button) || catalog.mouseClicked(this, mouseX, mouseY);
        }
        if (mouseX < left()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        setFocused(null);
        if (mouseY >= lineTop() - 2 && mouseY <= lineTop() + LINE_H + 2 && mouseX >= lineLeft() - 2 && mouseX <= lineRight() + 2) {
            lineClicked(mouseX, mouseY, button);
            return true;
        }
        int handle = button == 0 ? handleAt(mouseX, mouseY) : 0;
        if (handle != 0) {
            goFree();
            dragging = handle;
        } else {
            dragging = button == 1 ? 4 : 3;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != 0) {
            if (dragging <= 2 || dragging == 5) {
                build();
            }
            dragging = 0;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging == 0) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (dragging == 5) {
            if (selectedCue() instanceof JsonObject cue) {
                cue.addProperty("at", Math.round(secondsAtX(shot, mouseX) * 10) / 10.0);
                changed();
            }
            return true;
        }
        if (dragging == 6) {
            scrubTo(mouseX);
            return true;
        }
        goFree();
        if (dragging <= 2) {
            moveHandle(dragging, dragX, dragY);
        } else if (dragging == 3) {
            orbit(dragX, dragY);
        } else {
            viewYaw += dragX * 0.3;
            viewPitch = Mth.clamp(viewPitch + dragY * 0.3, -89, 89);
            pivot = viewPos.add(direction(viewYaw, viewPitch).scale(Math.max(1, pivot.distanceTo(viewPos))));
        }
        return true;
    }

    /** Turns the free view around its pivot, keeping it looking at it. */
    private void orbit(double dragX, double dragY) {
        Vec3 offset = viewPos.subtract(pivot);
        double distance = Math.max(0.3, offset.length());
        double yaw = Math.toDegrees(Math.atan2(-offset.x, offset.z)) - dragX * 0.4;
        double pitch = Mth.clamp(Math.toDegrees(Math.asin(Mth.clamp(offset.y / distance, -1, 1))) + dragY * 0.4, -89, 89);
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        viewPos = pivot.add(new Vec3(-Math.sin(y) * Math.cos(p), Math.sin(p), Math.cos(y) * Math.cos(p)).scale(distance));
        lookAt(pivot);
    }

    /** Moves a handle with the cursor across the view; with Shift, over the ground. */
    private void moveHandle(int which, double dragX, double dragY) {
        Vec3 point = world(shot, which == 1 ? localFrom(shot) : localTo(shot));
        double fov = Math.toRadians(Minecraft.getInstance().options.fov().get());
        double perPixel = point.distanceTo(viewPos) * 2 * Math.tan(fov / 2) / Math.max(1, height);
        Vec3 side = right((float) viewYaw);
        Vec3 moved;
        if (hasShiftDown()) {
            moved = point.add(side.scale(dragX * perPixel)).add(forward((float) viewYaw).scale(-dragY * perPixel));
        } else {
            Vec3 look = direction(viewYaw, viewPitch);
            Vec3 up = side.cross(look).normalize();
            moved = point.add(side.scale(dragX * perPixel)).add(up.scale(-dragY * perPixel));
        }
        camera().add(which == 1 ? "from" : "to", vec(local(shot, moved)));
        changed();
        refreshPoints();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (catalog.isOpen()) {
            catalog.scroll(scrollY);
            return true;
        }
        if (mouseX >= left()) {
            goFree();
            if (hasShiftDown()) {
                viewPos = viewPos.add(0, scrollY * 0.5, 0);
                pivot = pivot.add(0, scrollY * 0.5, 0);
            } else {
                Vec3 toPivot = pivot.subtract(viewPos);
                double distance = toPivot.length();
                double step = scrollY > 0 ? Math.min(distance * 0.15, Math.max(0, distance - 0.5)) : -distance * 0.15 - 0.2;
                viewPos = viewPos.add(distance > 1.0e-6 ? toPivot.normalize().scale(step) : direction(viewYaw, viewPitch).scale(step));
            }
            return true;
        }
        // The wheel over a number nudges it: 0.1, with Shift 1.
        for (EditBox[] row : new EditBox[][] {from, to}) {
            for (EditBox box : row) {
                if (box != null && box.visible && box.isMouseOver(mouseX, mouseY)) {
                    double value = parse(box.getValue(), 0) + Math.signum(scrollY) * (hasShiftDown() ? 1 : 0.1);
                    box.setValue(String.format(Locale.ROOT, "%.2f", value));
                    return true;
                }
            }
        }
        // Elsewhere over the panel's middle the wheel scrolls it, when it is taller than the screen.
        int most = Math.max(0, contentHeight - (contentBottom() - CONTENT_TOP));
        if (mouseY >= CONTENT_TOP && mouseY < contentBottom() && most > 0) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * 18, 0, most);
            build();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        hovered = dragging != 0 ? (dragging <= 2 ? dragging : hovered) : mouseX >= left() ? handleAt(mouseX, mouseY) : 0;
        super.mouseMoved(mouseX, mouseY);
    }

    // --- drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = left();
        boolean bars = scene.get("letterbox") instanceof JsonPrimitive on && on.getAsBoolean();
        if (bars) {
            int bar = Math.round(height * 0.12f);
            graphics.fill(left, 0, width, bar, 0xFF000000);
            graphics.fill(left, height - bar, width, height, 0xFF000000);
        }
        if (grid) {
            int color = 0x66FFFFFF;
            for (int i = 1; i < 3; i++) {
                int gx = i * width / 3;
                int gy = i * height / 3;
                if (gx > left) {
                    graphics.fill(gx, 0, gx + 1, height, color);
                }
                graphics.fill(left, gy, width, gy + 1, color);
            }
        }
        if (view == View.SCENE) {
            double black = sceneFade(sceneTime);
            if (black > 0) {
                graphics.fill(left, 0, width, height, ((int) (black * 255) << 24));
            }
        }
        drawTimeLine(graphics);
        if (panelShown) {
            graphics.fill(0, 0, PANEL, height, 0xF01A140F);
            graphics.fill(PANEL - 1, 0, PANEL, height, DevUi.PANEL_EDGE);
            String title = font.plainSubstrByWidth(id.getPath(), PANEL - 8 - 5 * 21 - 34);
            DevIcons.draw(graphics, "camera", 8, 6, DevUi.ACCENT);
            graphics.drawString(font, Component.literal(title + (dirty ? " •" : "")), 23, 8, DevUi.ACCENT, false);
            graphics.drawString(font, DevUi.text("scene_view.shot", shot + 1, shots().size()), 8, 20, DevUi.MUTED, false);
            Anchor anchor = anchor(shot);
            String status = anchor.asking() ? DevUi.str("scene_view.anchor_asking") : anchor.found() ? DevUi.str("scene_view.anchor_found")
                    : DevUi.str("scene_view.anchor_stand_in");
            graphics.drawString(font, DevUi.str("scene_view.anchor", anchorName()), 8, 32, DevUi.TEXT, false);
            graphics.drawString(font, status, 8, 42, anchor.found() ? DevUi.MUTED : DevUi.WARN, false);
            if (talk(shot)) {
                String dialogue = shotJson(shot).get("dialogue") instanceof JsonPrimitive written ? written.getAsString() : "";
                graphics.drawWordWrap(font, DevUi.text("scene_view.talk", dialogue.isEmpty() ? "—" : dialogue), 8, CONTENT_TOP, PANEL - 16, DevUi.LABEL);
            }
            if (!talk(shot) && cueLabelY >= CONTENT_TOP - 2 && cueLabelY + 8 <= contentBottom()) {
                graphics.drawString(font, DevUi.text("scene_view.cues"), 8, cueLabelY + 1, DevUi.LABEL, false);
                if (selectedCue() == null && cueLabelY + 24 <= contentBottom()) {
                    graphics.drawWordWrap(font, DevUi.text("scene_view.cue_none"), 8, cueLabelY + 16, PANEL - 16, DevUi.HINT);
                }
            }
            String[] axes = {"→", "↑", "↗"};
            int[] headings = {fromLabelY, toLabelY};
            for (int point = 0; point < 2; point++) {
                int heading = headings[point];
                if (heading >= CONTENT_TOP - 2 && heading + 8 <= contentBottom()) {
                    graphics.drawString(font, DevUi.text(point == 0 ? "scene_view.from" : "scene_view.to"), 8, heading + 2, point == 0 ? START : END, false);
                }
                for (int axis = 0; axis < 3; axis++) {
                    int rowY = heading + 16 + axis * 18;
                    if (rowY >= CONTENT_TOP - 2 && rowY + 16 <= contentBottom()) {
                        graphics.drawString(font, axes[axis], 9, rowY + 4, MUTED, false);
                    }
                }
            }
            if (Math.max(0, contentHeight - (contentBottom() - CONTENT_TOP)) > 0) {
                // A thin bar at the panel's edge: how far its middle is scrolled.
                int track = contentBottom() - CONTENT_TOP;
                int thumb = Math.max(12, track * track / Math.max(1, contentHeight));
                int top = CONTENT_TOP + (track - thumb) * scroll / Math.max(1, contentHeight - track);
                graphics.fill(PANEL - 3, top, PANEL - 1, top + thumb, 0x80FFFFFF);
            }
            if (closeAsked) {
                graphics.drawWordWrap(font, DevUi.text("scene_view.unsaved"), 8, height - 56, PANEL - 16, 0xFFE0A050);
            } else if (saveProblem != null) {
                Component problem = DevUi.text("scene_view.not_saved", saveProblem);
                int lines = font.split(problem, PANEL - 16).size();
                graphics.fill(0, height - 32 - lines * 9 - 4, PANEL - 1, height - 32, 0xF01A140F);
                graphics.drawWordWrap(font, problem, 8, height - 32 - lines * 9 - 2, PANEL - 16, DevUi.BAD);
            }
        }
        String hint = view == View.SCENE ? DevUi.str("scene_view.hint_scene", String.format(Locale.ROOT, "%.1f", sceneTime),
                String.format(Locale.ROOT, "%.1f", sceneSeconds()))
                : view == View.FREE ? DevUi.str("scene_view.hint_free") : DevUi.str("scene_view.hint_shot");
        graphics.drawString(font, hint, left + 8, lineTop() - 14, TEXT, true);
        if (!panelShown) {
            graphics.drawString(font, DevUi.text("scene_view.hint_panel"), 8, 8, TEXT, true);
        }
        if (panelShown && note != null && net.minecraft.Util.getMillis() - noteAt < 4000) {
            graphics.drawWordWrap(font, note, 8, height - 46, PANEL - 16, noteColor);
        }
        if (catalog.isOpen()) {
            catalog.render(this, graphics, mouseX, mouseY);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** The shot drawn in the world while the view is open: its flight, its two cameras, its anchor. */
    public static void onRenderLevel(RenderLevelStageEvent event) {
        ScenePreview preview = open;
        if (preview == null || event.getStage() != ShaderPacks.worldOverlayStage() || ShaderPacks.renderingShadowPass()
                || Minecraft.getInstance().screen != preview || preview.view == View.SCENE || preview.talk(preview.shot)) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        int index = preview.shot;
        Vec3 start = preview.world(index, preview.localFrom(index));
        Vec3 end = preview.world(index, preview.localTo(index));
        line(lines, pose, start, end, GOLD);
        // Ticks across the flight at every second of it, closer together where the camera is slow.
        double seconds = preview.seconds(index);
        SceneDefinition.Ease ease = preview.ease(index);
        for (int second = 1; second < seconds; second++) {
            Vec3 at = start.lerp(end, ease.apply(second / seconds));
            line(lines, pose, at.add(0, -0.12, 0), at.add(0, 0.12, 0), GOLD);
        }
        frustum(lines, pose, preview.shotPose(index, 0, partialTick), preview.hovered == 1 ? 0xFFFFFFFF : START);
        frustum(lines, pose, preview.shotPose(index, 1, partialTick), preview.hovered == 2 ? 0xFFFFFFFF : END);
        if (preview.view == View.FREE) {
            frustum(lines, pose, preview.shotPose(index, preview.time, partialTick), 0xFFB8A9D9);
        }
        Vec3 anchor = preview.anchor(index).pos();
        line(lines, pose, anchor, anchor.add(0, 2.2, 0), 0xFFFFFFFF);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    /** A small camera at the pose, pointing where it looks. */
    private static void frustum(VertexConsumer lines, PoseStack pose, SceneCamera.Pose at, int color) {
        Vec3 look = direction(at.yaw(), at.pitch());
        Vec3 side = right(at.yaw());
        Vec3 up = side.cross(look).normalize();
        Vec3 apex = at.pos();
        Vec3 middle = apex.add(look.scale(0.8));
        Vec3 w = side.scale(0.45);
        Vec3 h = up.scale(0.26);
        Vec3[] corners = {middle.add(w).add(h), middle.subtract(w).add(h), middle.subtract(w).subtract(h), middle.add(w).subtract(h)};
        for (int i = 0; i < 4; i++) {
            line(lines, pose, apex, corners[i], color);
            line(lines, pose, corners[i], corners[(i + 1) % 4], color);
        }
        // Its top edge marked, so which way is up shows.
        line(lines, pose, middle.add(h), middle.add(h.scale(1.6)), color);
    }

    private static void line(VertexConsumer lines, PoseStack pose, Vec3 a, Vec3 b, int color) {
        Vec3 normal = b.subtract(a);
        normal = normal.lengthSqr() > 1.0e-9 ? normal.normalize() : new Vec3(0, 1, 0);
        PoseStack.Pose last = pose.last();
        lines.addVertex(last, (float) a.x, (float) a.y, (float) a.z).setColor(color).setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
        lines.addVertex(last, (float) b.x, (float) b.y, (float) b.z).setColor(color).setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
