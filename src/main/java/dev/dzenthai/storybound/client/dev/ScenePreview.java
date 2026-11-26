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
import dev.dzenthai.storybound.core.logic.action.ScreenFade;
import dev.dzenthai.storybound.module.scene.SceneDefinition;
import dev.dzenthai.storybound.module.scene.ScenePath;
import dev.dzenthai.storybound.module.scene.Scenes;
import dev.dzenthai.storybound.network.ScenePayloads;
import java.util.ArrayDeque;
import java.util.Deque;
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
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
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
    private static final int VIA = 0xFF5CE1E6;
    private static final int VIA_SELECTED = 0xFFA6F7FF;
    private static final int END = 0xFFE05A47;
    private static final int DRAG_POINT = 1;
    private static final int DRAG_ORBIT = 3;
    private static final int DRAG_LOOK = 4;
    private static final int DRAG_CUE = 5;
    private static final int DRAG_SCRUB = 6;
    private static final int DRAG_PAN = 7;
    /** How near the cursor must be to a handle on screen to take it. */
    private static final double HANDLE_REACH = 9;
    /** How fast the free view flies, blocks a second; sprint makes it faster. */
    private static final double FLY_SPEED = 6;
    /** A glide between shots and a fade's half, in seconds, as the game plays them. */
    private static final double GLIDE = 0.5;
    private static final double FADE = 0.4;
    private static @Nullable ScenePreview open;
    private static int nextRequest = 1;

    private enum View { SHOT, PILOT, FREE, SCENE }

    private enum PanelTab { POINT, SHOT, CUES }

    /** An anchor as found for the author, or stood in for. */
    private record Anchor(Vec3 pos, float yaw, int entity, boolean found, boolean asking) {}

    private final DevModeScreen editor;
    private final ResourceLocation id;
    private final JsonObject scene;
    private int shot;
    private final Map<Integer, Anchor> anchors = new HashMap<>();
    private final Map<Integer, Anchor> lookAnchors = new HashMap<>();
    private final Map<Integer, Integer> requests = new HashMap<>();
    /** Pending server anchor requests for a shot's fallback anchor or {@code look} object anchor. */
    private final Map<Integer, Integer> fallbackRequests = new HashMap<>();
    private final Map<Integer, Integer> lookRequests = new HashMap<>();
    /** Shots whose structure was not known: when to ask again (ms), and how often it was asked. */
    private final Map<Integer, Long> retryAt = new HashMap<>();
    private final Map<Integer, Integer> retries = new HashMap<>();
    private static final int MAX_RETRIES = 20;
    private static final long RETRY_MS = 2000;
    private static final int MAX_UNDO = 50;
    private final Vec3 standInPos;
    private final float standInYaw;
    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();
    private long lastUndoPushMs;

    private View view = View.PILOT;
    private PanelTab panelTab = PanelTab.POINT;
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

    /** What the mouse is doing over the world: DRAG_POINT, DRAG_ORBIT, DRAG_LOOK, DRAG_CUE, DRAG_SCRUB, or 0 for none. */
    private int dragging;
    private int draggedPoint = -1;
    private boolean dragMoved;
    private int groupToCycle = -1;
    private int hoveredPoint = -1;
    private boolean dirty;
    private boolean panelShown = true;
    private boolean grid;
    private boolean helpShown;
    private boolean closeAsked;

    private final EditBox[] pointBoxes = new EditBox[3];
    private final AxisSlider[] pointSliders = new AxisSlider[3];
    private final EditBox[] fadeBoxes = new EditBox[3];
    private final EditBox[] shakeBoxes = new EditBox[2];
    private final EditBox[] lookOffsetBoxes = new EditBox[2];
    private final EditBox[] rotBoxes = new EditBox[2];
    private @Nullable EditBox lengthBox;
    /** Where the panel's middle begins (below the top toolbar, title, anchor and 3-tab bar); how far it is scrolled, how tall it is. */
    private static final int CONTENT_TOP = 84;
    private int scroll;
    private int contentHeight;
    private boolean buildingScrollArea;
    private int pathLabelY = -1000;
    private int pathAxesY = -1000;
    private int modeHintY = -1000;
    private int lookOffsetLabelY = -1000;
    private int rotLabelY = -1000;
    private int speedInfoY = -1000;
    private int cueLabelY = -1000;
    private int fadeLabelY = -1000;
    private int shakeLabelY = -1000;
    /** Currently selected control point index: 0 = From, 1..N = Via, N+1 = To. */
    private int selectedPoint;
    private @Nullable FovSlider fovSlider;
    private @Nullable Button fovResetButton;
    private @Nullable RollSlider rollSlider;
    private @Nullable Button rollResetButton;
    private @Nullable AboveGroundSlider aboveGroundSlider;
    private @Nullable Button aboveGroundResetButton;
    private @Nullable ShakeSlider shakeSlider;
    private @Nullable Button shakeResetButton;
    private @Nullable Button captureButton;
    private @Nullable Button backButton;
    private @Nullable Button undoButton;
    private @Nullable Button redoButton;
    private @Nullable Button collapseButton;
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
    private @Nullable Button freeButton;
    /** When a thing was last added on the time line (ms), to flash its mark and badge there. */
    private long cueAddedAt;
    private boolean filling;
    /** Single-channel audio instances played during timeline playback (music, sound effect, line voice-over). */
    private @Nullable SoundInstance previewMusic;
    private @Nullable String previewMusicKey;
    private @Nullable SoundInstance previewSound;
    private @Nullable String previewSoundKey;
    private @Nullable SoundInstance previewVoice;
    private @Nullable String previewVoiceKey;
    private double lastAudioTime = -1;

    /** Whether the cutscene preview is currently playing timeline audio. */
    public static boolean isPlayingAudio() {
        ScenePreview preview = open;
        return preview != null && preview.playing
                && (preview.previewMusic != null || preview.previewSound != null || preview.previewVoice != null);
    }

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
        if (talk(shot)) {
            view = View.SHOT;
        } else if (view == View.PILOT) {
            time = timeForControlPoint(shot, selectedPoint);
        }
        if (selectedCue < 0) {
            selectedCue = cueAtMoment(shot, time * seconds(shot));
        }
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
        stopPreviewAudio();
        DevSoundPreview.stop();
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

    /** Where the panel's middle ends: above the pinned Play/Scene/Grid bar and Save/Close. */
    private int contentBottom() {
        return height - 58;
    }

    /** Where the 3-tab strip (Point / Shot / Cues) sits, right below the anchor line. */
    private int tabBarY() {
        return contentTop() - 21;
    }

    // --- undo / redo

    private void pushUndo() {
        String snapshot = scene.toString();
        if (undoStack.isEmpty() || !undoStack.peekLast().equals(snapshot)) {
            undoStack.addLast(snapshot);
            while (undoStack.size() > MAX_UNDO) {
                undoStack.removeFirst();
            }
            redoStack.clear();
        }
        lastUndoPushMs = net.minecraft.Util.getMillis();
    }

    private void pushUndoDebounced() {
        long now = net.minecraft.Util.getMillis();
        if (now - lastUndoPushMs > 500L) {
            pushUndo();
        } else {
            lastUndoPushMs = now;
        }
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.addLast(scene.toString());
        restoreScene(undoStack.removeLast());
        status(DevUi.text("scene_view.undone"), DevUi.ACCENT);
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.addLast(scene.toString());
        restoreScene(redoStack.removeLast());
        status(DevUi.text("scene_view.redone"), DevUi.ACCENT);
    }

    private void restoreScene(String json) {
        JsonObject parsed = ClientDevStore.parseObject(json);
        int oldShots = shots().size();
        for (String key : List.copyOf(scene.keySet())) {
            scene.remove(key);
        }
        for (var entry : parsed.entrySet()) {
            scene.add(entry.getKey(), entry.getValue().deepCopy());
        }
        shot = Mth.clamp(shot, 0, Math.max(0, shots().size() - 1));
        selectedPoint = Mth.clamp(selectedPoint, 0, Math.max(0, controlPointsLocal(shot).size() - 1));
        selectedCue = Mth.clamp(selectedCue, -1, during(shot).size() - 1);
        changed();
        if (shots().size() != oldShots) {
            askAnchors();
        }
        build();
    }

    /** Where the panel's middle begins: shifts down when the anchor line wraps onto a second line. */
    private int contentTop() {
        var f = font != null ? font : Minecraft.getInstance().font;
        int rows = Math.max(1, f.split(Component.literal(DevUi.str("scene_view.anchor", anchorName())), PANEL - 16).size());
        return CONTENT_TOP + (rows - 1) * 10;
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

    private static @Nullable Vec3 readOrNull(@Nullable JsonElement element) {
        if (element instanceof JsonArray array && array.size() == 3) {
            try {
                return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Vec3 read(@Nullable JsonElement element, Vec3 otherwise) {
        Vec3 parsed = readOrNull(element);
        return parsed != null ? parsed : otherwise;
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

    private List<Vec3> localVia(int index) {
        if (!(camera(index).get("via") instanceof JsonArray array) || array.isEmpty()) {
            return List.of();
        }
        List<Vec3> points = new java.util.ArrayList<>(array.size());
        for (JsonElement element : array) {
            Vec3 point = readOrNull(element);
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    private void writeVia(List<Vec3> points) {
        if (points.isEmpty()) {
            camera().remove("via");
            return;
        }
        JsonArray array = new JsonArray();
        for (Vec3 point : points) {
            array.add(vec(point));
        }
        camera().add("via", array);
    }

    private Vec3 localTo(int index) {
        JsonObject camera = camera(index);
        return camera.has("to") ? read(camera.get("to"), localFrom(index)) : localFrom(index);
    }

    /** All camera control points in anchor-local coordinates: From, Via..., To. */
    private List<Vec3> controlPointsLocal(int index) {
        List<Vec3> via = localVia(index);
        List<Vec3> all = new java.util.ArrayList<>(via.size() + 2);
        all.add(localFrom(index));
        all.addAll(via);
        all.add(localTo(index));
        return all;
    }

    private void setControlPointLocal(int pointIndex, Vec3 value) {
        List<Vec3> via = new java.util.ArrayList<>(localVia(shot));
        if (pointIndex == 0) {
            camera().add("from", vec(value));
        } else if (pointIndex == via.size() + 1) {
            camera().add("to", vec(value));
        } else if (pointIndex >= 1 && pointIndex <= via.size()) {
            via.set(pointIndex - 1, value);
            writeVia(via);
        }
    }

    /** Per-control-point {@code [yaw, pitch]} rotations relative to anchor yaw (for {@code look: "free"}). */
    private List<Vec2> localRotations(int index) {
        if (!(camera(index).get("rotations") instanceof JsonArray array) || array.isEmpty()) {
            return List.of();
        }
        List<Vec2> out = new java.util.ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element instanceof JsonArray pair && pair.size() == 2) {
                try {
                    out.add(new Vec2((float) pair.get(0).getAsDouble(), Mth.clamp((float) pair.get(1).getAsDouble(), -89.9f, 89.9f)));
                } catch (RuntimeException ignored) {
                }
            }
        }
        return out;
    }

    private void writeRotations(int index, List<Vec2> rots) {
        if (rots.isEmpty()) {
            camera(index).remove("rotations");
            return;
        }
        JsonArray arr = new JsonArray();
        for (Vec2 r : rots) {
            JsonArray pair = new JsonArray();
            pair.add(round(Mth.wrapDegrees(r.x)));
            pair.add(round(Mth.clamp(r.y, -89.9f, 89.9f)));
            arr.add(pair);
        }
        camera(index).add("rotations", arr);
    }

    /** World-space {@code [yaw, pitch]} per control point for {@code look: "free"} interpolation. */
    private List<Vec2> worldRotations(int index) {
        List<Vec3> pts = pathControlPositions(index);
        List<Vec2> authored = localRotations(index);
        float anchorYaw = anchor(index).yaw();
        Vec3 target = anchorTarget(index, 0f);
        List<Vec2> out = new java.util.ArrayList<>(pts.size());
        for (int i = 0; i < pts.size(); i++) {
            if (i < authored.size()) {
                Vec2 r = authored.get(i);
                out.add(new Vec2(Mth.wrapDegrees(anchorYaw + r.x), Mth.clamp(r.y, -89.9f, 89.9f)));
            } else if (!out.isEmpty()) {
                out.add(out.getLast());
            } else {
                SceneCamera.Pose f = SceneCamera.facing(pts.get(i), target);
                out.add(new Vec2(f.yaw(), f.pitch()));
            }
        }
        return out;
    }

    /**
     * Bakes every control point's current pose orientation into {@code rotations} and switches {@code look} to
     * {@code "free"} without changing the picture by a single degree.
     */
    private void ensureFreeLookBaked(int index) {
        int count = controlPointsLocal(index).size();
        float anchorYaw = anchor(index).yaw();
        List<Vec2> existing = localRotations(index);
        if (look(index).equals("free") && existing.size() == count) {
            return;
        }
        List<Vec2> baked = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (look(index).equals("free") && i < existing.size()) {
                baked.add(existing.get(i));
            } else {
                SceneCamera.Pose p = shotPose(index, timeForControlPoint(index, i), 0f);
                baked.add(new Vec2(Mth.wrapDegrees(p.yaw() - anchorYaw), Mth.clamp(p.pitch(), -89.9f, 89.9f)));
            }
        }
        camera(index).addProperty("look", "free");
        writeRotations(index, baked);
    }

    private void setRotationWorld(int index, int pointIndex, float worldYaw, float pitch) {
        ensureFreeLookBaked(index);
        List<Vec2> rots = new java.util.ArrayList<>(localRotations(index));
        if (pointIndex < 0 || pointIndex >= rots.size()) {
            return;
        }
        float relYaw = Mth.wrapDegrees(worldYaw - anchor(index).yaw());
        rots.set(pointIndex, new Vec2(relYaw, Mth.clamp(pitch, -89.9f, 89.9f)));
        writeRotations(index, rots);
    }

    private Vec2 selectedRotationLocal() {
        List<Vec2> rots = localRotations(shot);
        if (selectedPoint >= 0 && selectedPoint < rots.size()) {
            return rots.get(selectedPoint);
        }
        SceneCamera.Pose p = shotPose(shot, timeForControlPoint(shot, selectedPoint), 0f);
        return new Vec2(Mth.wrapDegrees(p.yaw() - anchor(shot).yaw()), Mth.clamp(p.pitch(), -89.9f, 89.9f));
    }

    private void refreshRotationBoxes() {
        if (!look(shot).equals("free")) {
            return;
        }
        Vec2 r = selectedRotationLocal();
        filling = true;
        if (rotBoxes[0] != null && !rotBoxes[0].isFocused()) {
            rotBoxes[0].setValue(String.format(Locale.ROOT, "%.1f", r.x));
        }
        if (rotBoxes[1] != null && !rotBoxes[1].isFocused()) {
            rotBoxes[1].setValue(String.format(Locale.ROOT, "%.1f", r.y));
        }
        filling = false;
    }

    private ScenePath shotPath(int index) {
        Vec3 start = world(index, localFrom(index));
        Vec3 end = world(index, localTo(index));
        List<Vec3> localVia = localVia(index);
        List<Vec3> worldVia = new java.util.ArrayList<>(localVia.size());
        for (Vec3 point : localVia) {
            worldVia.add(world(index, point));
        }
        float clearance = shotAboveGround(index);
        Level level = Minecraft.getInstance().level;
        if (clearance > 0f && level != null) {
            start = liftAboveGround(level, start, clearance);
            for (int i = 0; i < worldVia.size(); i++) {
                worldVia.set(i, liftAboveGround(level, worldVia.get(i), clearance));
            }
            end = liftAboveGround(level, end, clearance);
            ScenePath prelim = ScenePath.of(start, worldVia, end);
            double extraLift = 0.0;
            for (double sample : new double[] {0.25, 0.5, 0.75}) {
                Vec3 mid = prelim.pointAt(sample);
                double need = maxSurfaceY(level, mid.x, mid.z) + clearance - mid.y;
                if (need > extraLift) {
                    extraLift = need;
                }
            }
            if (extraLift > 0.0) {
                start = start.add(0, extraLift, 0);
                for (int i = 0; i < worldVia.size(); i++) {
                    worldVia.set(i, worldVia.get(i).add(0, extraLift, 0));
                }
                end = end.add(0, extraLift, 0);
            }
        }
        return ScenePath.of(start, worldVia, end);
    }

    /** World positions of all control points (From, Via..., To) as actually rendered along {@link #shotPath}. */
    private List<Vec3> pathControlPositions(int index) {
        ScenePath path = shotPath(index);
        int count = controlPointsLocal(index).size();
        List<Vec3> points = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(path.pointAt(path.parameterOfControl(i)));
        }
        return points;
    }

    /** If {@code above_ground} would override the Y coordinate the author just placed manually, turn it off so Y responds immediately. */
    private void releaseAboveGroundIfBlocking(int pointIndex, Vec3 worldPoint) {
        float clearance = shotAboveGround(shot);
        Level level = Minecraft.getInstance().level;
        if (clearance <= 0f || level == null) {
            return;
        }
        boolean blocked = worldPoint.y < maxSurfaceY(level, worldPoint.x, worldPoint.z) + clearance - 0.05;
        if (!blocked && pointIndex >= 0) {
            List<Vec3> locals = controlPointsLocal(shot);
            List<Vec3> rendered = pathControlPositions(shot);
            if (pointIndex < locals.size() && pointIndex < rendered.size()) {
                double rawY = world(shot, locals.get(pointIndex)).y;
                double liftedY = rendered.get(pointIndex).y;
                if (liftedY > rawY + 0.05 && worldPoint.y < liftedY - 0.01) {
                    blocked = true;
                }
            }
        }
        if (blocked) {
            camera().remove("above_ground");
            if (aboveGroundSlider != null) {
                aboveGroundSlider.sync();
            }
            if (aboveGroundResetButton != null) {
                aboveGroundResetButton.active = false;
            }
        }
    }

    private static Vec3 liftAboveGround(Level level, Vec3 pt, float clearance) {
        double minY = maxSurfaceY(level, pt.x, pt.z) + clearance;
        return pt.y < minY ? new Vec3(pt.x, minY, pt.z) : pt;
    }

    private static double maxSurfaceY(Level level, double x, double z) {
        int cx = Mth.floor(x);
        int cz = Mth.floor(z);
        int max = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz);
        max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx + 2, cz));
        max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx - 2, cz));
        max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz + 2));
        max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz - 2));
        return max;
    }

    private float shotFov(int index) {
        return clampedFloat(camera(index), "fov", SceneDefinition.Camera.MIN_FOV, SceneDefinition.Camera.MAX_FOV, 0f);
    }

    private float shotFovTo(int index) {
        return clampedFloat(camera(index), "fov_to", SceneDefinition.Camera.MIN_FOV, SceneDefinition.Camera.MAX_FOV, 0f);
    }

    private float shotRoll(int index) {
        return clampedFloat(camera(index), "roll", -SceneDefinition.Camera.MAX_ROLL, SceneDefinition.Camera.MAX_ROLL, 0f);
    }

    private float shotRollTo(int index, float fallbackRoll) {
        return clampedFloat(camera(index), "roll_to", -SceneDefinition.Camera.MAX_ROLL, SceneDefinition.Camera.MAX_ROLL, fallbackRoll);
    }

    private float shotAboveGround(int index) {
        return clampedFloat(camera(index), "above_ground", 0f, 64f, 0f);
    }

    private float shotShake(int index) {
        return clampedFloat(camera(index), "shake", 0f, 1f, 0f);
    }

    private boolean shotSafeAngle(int index) {
        JsonElement raw = camera(index).get("safe_angle");
        return !(raw instanceof JsonPrimitive bool) || !bool.isBoolean() || bool.getAsBoolean();
    }

    private Vec3 shotLookOffset(int index) {
        JsonElement raw = camera(index).get("look_offset");
        if (raw instanceof JsonArray arr && arr.size() == 3) {
            try {
                return new Vec3(arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble());
            } catch (RuntimeException ignored) {
            }
        }
        return Vec3.ZERO;
    }

    private static float clampedFloat(JsonObject obj, String field, float min, float max, float fallback) {
        JsonElement raw = obj.get(field);
        if (raw instanceof JsonPrimitive num && num.isNumber()) {
            try {
                return Mth.clamp((float) num.getAsDouble(), min, max);
            } catch (RuntimeException ignored) {
            }
        }
        return fallback;
    }

    /** Normalized shot time t in [0, 1] at which the camera passes through control point {@code controlIndex}. */
    private double timeForControlPoint(int index, int controlIndex) {
        double targetU = shotPath(index).parameterOfControl(controlIndex);
        if (targetU <= 0.0) {
            return 0.0;
        }
        if (targetU >= 1.0) {
            return 1.0;
        }
        SceneDefinition.Ease ease = ease(index);
        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < 16; i++) {
            double mid = (lo + hi) * 0.5;
            if (ease.apply(mid) < targetU) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) * 0.5;
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
                return Math.max(0.05, Math.min(120, shotPath(index).length() / speed));
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
        lookAnchors.clear();
        requests.clear();
        fallbackRequests.clear();
        lookRequests.clear();
        retryAt.clear();
        retries.clear();
        for (int i = 0; i < shots().size(); i++) {
            JsonElement anchor = shotJson(i).get("anchor");
            if (anchor == null || anchor instanceof JsonPrimitive text && text.getAsString().equals("player")) {
                Player player = Minecraft.getInstance().player;
                anchors.put(i, new Anchor(standInPos, standInYaw, player != null ? player.getId() : -1, true, false));
            } else {
                askAnchor(i);
            }
            if (!talk(i) && camera(i).get("look") instanceof JsonObject lookObj) {
                int req = nextRequest++;
                lookRequests.put(req, i);
                PacketDistributor.sendToServer(new ScenePayloads.AnchorQuery(req, lookObj.toString()));
            }
        }
    }

    /** Asks the server where the shot's anchor is; its answer is {@link #answer}. */
    private void askAnchor(int index) {
        JsonElement anchor = shotJson(index).get("anchor");
        if (anchor == null) {
            return;
        }
        int request = nextRequest++;
        requests.put(request, index);
        anchors.put(index, new Anchor(standInPos, standInYaw, -1, false, true));
        PacketDistributor.sendToServer(new ScenePayloads.AnchorQuery(request, anchor.toString()));
    }

    /**
     * A structure not known yet is searched for in the background: the answer "not near" is asked again every couple of
     * seconds for a while, so the camera view moves to the structure once the search has found it.
     */
    private void scheduleRetry(int index) {
        boolean structure = index < shots().size() && shotJson(index).get("anchor") instanceof JsonObject object && object.has("structure");
        int tried = retries.getOrDefault(index, 0);
        if (structure && tried < MAX_RETRIES) {
            retries.put(index, tried + 1);
            retryAt.put(index, net.minecraft.Util.getMillis() + RETRY_MS);
        }
    }

    private void retryAnchors() {
        if (retryAt.isEmpty()) {
            return;
        }
        long now = net.minecraft.Util.getMillis();
        for (var entry : List.copyOf(retryAt.entrySet())) {
            if (now >= entry.getValue()) {
                retryAt.remove(entry.getKey());
                if (entry.getKey() < shots().size()) {
                    askAnchor(entry.getKey());
                }
            }
        }
    }

    /** The server's answer: where the shot's anchor (or fallback / look anchor) is, or that it is not near. */
    public static void answer(ScenePayloads.AnchorAnswer answer) {
        ScenePreview preview = open;
        if (preview == null) {
            return;
        }
        Integer lookIdx = preview.lookRequests.remove(answer.request());
        if (lookIdx != null) {
            if (answer.found()) {
                preview.lookAnchors.put(lookIdx, new Anchor(answer.pos(), answer.yaw(), answer.entity(), true, false));
            }
            return;
        }
        Integer fbIdx = preview.fallbackRequests.remove(answer.request());
        if (fbIdx != null) {
            preview.anchors.put(fbIdx, answer.found() ? new Anchor(answer.pos(), answer.yaw(), answer.entity(), true, false)
                    : new Anchor(preview.standInPos, preview.standInYaw, -1, false, false));
            return;
        }
        Integer index = preview.requests.remove(answer.request());
        if (index == null) {
            return;
        }
        if (answer.found()) {
            preview.anchors.put(index, new Anchor(answer.pos(), answer.yaw(), answer.entity(), true, false));
            preview.retries.remove(index);
            return;
        }
        // Primary anchor not found: honour shot's optional fallback anchor just like server playback.
        JsonElement fallback = index < preview.shots().size() ? preview.shotJson(index).get("fallback") : null;
        if (fallback instanceof JsonPrimitive text && text.getAsString().equals("player")) {
            Player player = Minecraft.getInstance().player;
            preview.anchors.put(index, new Anchor(preview.standInPos, preview.standInYaw, player != null ? player.getId() : -1, true, false));
            preview.scheduleRetry(index);
            return;
        }
        if (fallback instanceof JsonObject) {
            int req = nextRequest++;
            preview.fallbackRequests.put(req, index);
            PacketDistributor.sendToServer(new ScenePayloads.AnchorQuery(req, fallback.toString()));
            preview.scheduleRetry(index);
            return;
        }
        preview.anchors.put(index, new Anchor(preview.standInPos, preview.standInYaw, -1, false, false));
        preview.scheduleRetry(index);
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
        for (String field : new String[] {"npc", "structure", "waypoint", "poi"}) {
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

    /** The shot's camera at {@code t} of its time, matching in-game playback (look_offset, fov_to, roll/roll_to, shake, free 6-DOF). */
    private SceneCamera.Pose shotPose(int index, double t, float partialTick) {
        ScenePath path = shotPath(index);
        double gone = ease(index).apply(t);
        Vec3 pos = path.pointAt(gone);
        float fov = shotFov(index);
        float fovTo = shotFovTo(index);
        if (fovTo > 0f) {
            Minecraft mc = Minecraft.getInstance();
            float startFov = fov > 0f ? fov : (mc != null && mc.options != null ? mc.options.fov().get() : 70f);
            fov = (float) Mth.lerp(gone, startFov, fovTo);
        }
        float rollStart = shotRoll(index);
        float roll = (float) Mth.lerp(gone, rollStart, shotRollTo(index, rollStart));
        SceneCamera.Pose base;
        if (look(index).equals("free")) {
            Vec2 angles = SceneCamera.interpolateRotation(worldRotations(index), path, gone);
            base = new SceneCamera.Pose(pos, angles.x, angles.y, fov, roll);
        } else {
            Vec3 target = anchorTarget(index, partialTick);
            Vec3 look = switch (look(index)) {
                case "path" -> {
                    Vec3 way = path.tangentAt(gone);
                    yield way.lengthSqr() > 1.0e-6 ? pos.add(way) : target;
                }
                case "player" -> Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getEyePosition(partialTick) : target;
                case "other" -> {
                    Anchor other = lookAnchors.get(index);
                    if (other != null) {
                        Minecraft mc = Minecraft.getInstance();
                        Entity entity = other.entity() >= 0 && mc.level != null ? mc.level.getEntity(other.entity()) : null;
                        yield entity != null ? entity.getEyePosition(partialTick) : other.pos();
                    }
                    yield target;
                }
                default -> target;
            };
            Vec3 aimed = SceneCamera.applyLookOffset(pos, look, shotLookOffset(index));
            base = SceneCamera.facing(pos, aimed).withFov(fov).withRoll(roll);
        }
        float breathing = playing ? shotShake(index) : 0f;
        if (breathing > 0f) {
            double sec = nowSeconds();
            float dYaw = (float) (breathing * (Math.sin(sec * 1.7) * 0.55 + Math.sin(sec * 4.1 + 1.3) * 0.25) * 1.6);
            float dPitch = (float) (breathing * (Math.sin(sec * 1.3 + 0.7) * 0.50 + Math.sin(sec * 3.7 + 2.1) * 0.25) * 1.2);
            float dRoll = (float) (breathing * Math.sin(sec * 1.1 + 2.4) * 0.65);
            return new SceneCamera.Pose(base.pos(), base.yaw() + dYaw, base.pitch() + dPitch, base.fov(), base.roll() + dRoll);
        }
        return base;
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
            if (talk(i)) {
                continue;
            }
            if (transition(i).equals("fade")) {
                double distance = Math.abs(at - start);
                if (start == 0 && at < FADE) {
                    return 1 - at / FADE;
                }
                if (start > 0 && distance < FADE) {
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
                double total = sceneSeconds();
                if (total <= 0) {
                    playing = false;
                } else {
                    sceneTime += dt;
                    if (sceneTime >= total) {
                        sceneTime = total;
                        playing = false;
                    }
                }
            } else if (view == View.SHOT) {
                double len = seconds(shot);
                if (talk(shot) || len <= 0) {
                    playing = false;
                } else {
                    time += dt / len;
                    if (time >= 1) {
                        time = 1;
                        playing = false;
                    }
                }
            }
        }
        if (view == View.FREE) {
            fly(dt);
            return new SceneCamera.Pose(viewPos, (float) viewYaw, (float) viewPitch);
        }
        if (view == View.PILOT) {
            flyPilot(dt);
            return shotPose(shot, timeForControlPoint(shot, selectedPoint), partialTick);
        }
        return view == View.SCENE ? scenePose(sceneTime, partialTick) : shotPose(shot, time, partialTick);
    }

    /** Whether a Ctrl+S / Ctrl+Z / Ctrl+Y shortcut is being held so flight never nudges the camera while saving or undoing. */
    private static boolean shortcutModifierHeld(long window) {
        return hasControlDown() && (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_S)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Z)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_Y));
    }

    /** True if {@code glfwKey} is down and not bound to another primary WASD movement key. */
    private static boolean extraFlightKeyDown(long window, Minecraft minecraft, int glfwKey) {
        var opts = minecraft.options;
        for (KeyMapping km : new KeyMapping[] {opts.keyUp, opts.keyDown, opts.keyLeft, opts.keyRight, opts.keyJump, opts.keyShift}) {
            if (km.getKey().getType() == InputConstants.Type.KEYSYM && km.getKey().getValue() == glfwKey) {
                return false;
            }
        }
        return InputConstants.isKeyDown(window, glfwKey);
    }

    /** In Pilot ("In camera") mode, movement keys fly the selected control point directly through its own lens. */
    private void flyPilot(double dt) {
        Minecraft minecraft = Minecraft.getInstance();
        if (dt <= 0 || talk(shot) || helpShown || getFocused() instanceof EditBox || catalog.isOpen() || !minecraft.isWindowActive()) {
            return;
        }
        long window = minecraft.getWindow().getWindow();
        if (shortcutModifierHeld(window)) {
            return;
        }
        SceneCamera.Pose cur = shotPose(shot, timeForControlPoint(shot, selectedPoint), 0f);
        Vec3 look = direction(cur.yaw(), cur.pitch());
        Vec3 side = right(cur.yaw());
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
        if (down(window, minecraft.options.keyJump) || extraFlightKeyDown(window, minecraft, GLFW.GLFW_KEY_E)) {
            move = move.add(0, 1, 0);
        }
        if (dragging != DRAG_PAN && (down(window, minecraft.options.keyShift) || extraFlightKeyDown(window, minecraft, GLFW.GLFW_KEY_Q))) {
            move = move.add(0, -1, 0);
        }
        if (move.lengthSqr() < 1.0e-6) {
            return;
        }
        pushUndoDebounced();
        double speed = FLY_SPEED * (down(window, minecraft.options.keySprint) ? 3 : 1);
        Vec3 nextWorld = cur.pos().add(move.normalize().scale(speed * dt));
        releaseAboveGroundIfBlocking(selectedPoint, nextWorld);
        setControlPointLocal(selectedPoint, local(shot, nextWorld));
        time = timeForControlPoint(shot, selectedPoint);
        changed();
        refreshPoints();
    }

    /** The movement keys fly the outside 3D overview while no number is being typed. */
    private void fly(double dt) {
        Minecraft minecraft = Minecraft.getInstance();
        if (dt <= 0 || helpShown || getFocused() instanceof EditBox || catalog.isOpen() || !minecraft.isWindowActive()) {
            return;
        }
        long window = minecraft.getWindow().getWindow();
        if (shortcutModifierHeld(window)) {
            return;
        }
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
        if (down(window, minecraft.options.keyJump) || extraFlightKeyDown(window, minecraft, GLFW.GLFW_KEY_E)) {
            move = move.add(0, 1, 0);
        }
        if (dragging != DRAG_PAN && (down(window, minecraft.options.keyShift) || extraFlightKeyDown(window, minecraft, GLFW.GLFW_KEY_Q))) {
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

    /** The outside 3D overview steps back from the current camera so the flight path and camera pyramids are immediately visible. */
    private void goFree() {
        if (view == View.FREE) {
            return;
        }
        boolean fromPilot = view == View.PILOT;
        SceneCamera.Pose now = view == View.SCENE ? scenePose(sceneTime, 0) : shotPose(shot, time, 0);
        Vec3 forward = direction(now.yaw(), now.pitch());
        viewPos = fromPilot ? now.pos().subtract(forward.scale(3.5)).add(0, 1.0, 0) : now.pos();
        viewYaw = now.yaw();
        viewPitch = now.pitch();
        Vec3 target = anchorTarget(shot, 0);
        pivot = target.distanceTo(now.pos()) > 0.5 ? target : now.pos().add(forward.scale(4));
        if (fromPilot) {
            lookAt(now.pos().lerp(pivot, 0.35));
        }
        view = View.FREE;
        playing = false;
    }

    /** Enters Pilot mode: sits inside the selected control point's lens so WASD + mouse frame the shot directly. */
    private void enterPilot() {
        if (talk(shot)) {
            return;
        }
        view = View.PILOT;
        playing = false;
        time = timeForControlPoint(shot, selectedPoint);
    }

    /** Captures the free 3D overview's exact position AND viewing direction (6-DOF) onto the selected point. */
    private void captureFromHere() {
        if (view == View.FREE) {
            pushUndo();
            releaseAboveGroundIfBlocking(selectedPoint, viewPos);
            setControlPointLocal(selectedPoint, local(shot, viewPos));
            setRotationWorld(shot, selectedPoint, (float) viewYaw, (float) viewPitch);
            // If this is a static single-point shot without an explicit "to", keep end rotation aligned too.
            if (selectedPoint == 0 && !camera().has("to")) {
                List<Vec2> rots = new java.util.ArrayList<>(localRotations(shot));
                if (rots.size() >= 2) {
                    rots.set(rots.size() - 1, rots.getFirst());
                    writeRotations(shot, rots);
                }
            }
            enterPilot();
            changed();
            build();
            status(DevUi.text("scene_view.captured_frame"), DevUi.ACCENT);
        } else if (view == View.PILOT) {
            status(DevUi.text("scene_view.pilot_already_live"), DevUi.ACCENT);
        } else {
            status(DevUi.text("scene_view.capture_needs_outside"), DevUi.WARN);
        }
    }

    private void lookAt(Vec3 target) {
        SceneCamera.Pose facing = SceneCamera.facing(viewPos, target);
        viewYaw = facing.yaw();
        viewPitch = facing.pitch();
    }

    /** F: the free view turns to the hovered control point (or the anchor) and turns around it from now on. */
    private void focus() {
        goFree();
        List<Vec3> controls = controlPointsLocal(shot);
        Vec3 target = hoveredPoint >= 0 && hoveredPoint < controls.size()
                ? world(shot, controls.get(hoveredPoint)) : anchorTarget(shot, 0);
        pivot = target;
        if (viewPos.distanceTo(target) < 1) {
            viewPos = target.subtract(direction(viewYaw, viewPitch).scale(5));
        }
        lookAt(target);
    }

    private void showShot(double at) {
        view = View.SHOT;
        playing = false;
        time = Mth.clamp(at, 0, 1);
    }

    // --- the panel

    /**
     * Every widget as the editor draws it: buttons become its flat buttons ({@link DevButton}, which reads the original
     * each frame); one in the panel's middle that scrolled out of it is hidden.
     */
    @Override
    protected <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable
            & net.minecraft.client.gui.narration.NarratableEntry> T addRenderableWidget(T widget) {
        if (!catalog.isOpen() && buildingScrollArea && widget instanceof net.minecraft.client.gui.components.AbstractWidget shown) {
            int top = contentTop();
            int bottom = contentBottom();
            shown.visible = shown.getY() >= top && shown.getY() + shown.getHeight() <= bottom;
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
        setFocused(null);
        freeButton = null;
        captureButton = null;
        backButton = null;
        undoButton = null;
        redoButton = null;
        collapseButton = null;
        lengthBox = null;
        fovSlider = null;
        fovResetButton = null;
        rollSlider = null;
        rollResetButton = null;
        aboveGroundSlider = null;
        aboveGroundResetButton = null;
        shakeSlider = null;
        shakeResetButton = null;
        buildingScrollArea = false;
        java.util.Arrays.fill(pointBoxes, null);
        java.util.Arrays.fill(pointSliders, null);
        java.util.Arrays.fill(fadeBoxes, null);
        java.util.Arrays.fill(shakeBoxes, null);
        java.util.Arrays.fill(lookOffsetBoxes, null);
        java.util.Arrays.fill(rotBoxes, null);
        pathLabelY = -1000;
        pathAxesY = -1000;
        modeHintY = -1000;
        lookOffsetLabelY = -1000;
        rotLabelY = -1000;
        speedInfoY = -1000;
        cueLabelY = -1000;
        fadeLabelY = -1000;
        shakeLabelY = -1000;
        if (catalog.isOpen()) {
            catalog.addWidgets(this);
            return;
        }
        int navX = panelShown ? 8 : 6;
        backButton = addRenderableWidget(Button.builder(Component.literal("←"), b -> close())
                .bounds(navX, 6, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.back_tooltip"))).build());
        undoButton = addRenderableWidget(Button.builder(Component.literal("↶"), b -> undo())
                .bounds(navX + 23, 6, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.undo_tooltip"))).build());
        undoButton.active = !undoStack.isEmpty();
        redoButton = addRenderableWidget(Button.builder(Component.literal("↷"), b -> redo())
                .bounds(navX + 44, 6, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.redo_tooltip"))).build());
        redoButton.active = !redoStack.isEmpty();
        collapseButton = addRenderableWidget(Button.builder(Component.literal(panelShown ? "⏴" : "⏵"), b -> {
            panelShown = !panelShown;
            build();
        }).bounds(panelShown ? PANEL + 6 : navX + 67, 6, 19, 19)
                .tooltip(Tooltip.create(DevUi.text(panelShown ? "scene_view.panel_hide_tooltip" : "scene_view.panel_show_tooltip"))).build());
        if (!panelShown) {
            return;
        }
        int x = 8;
        int count = shots().size();
        int tools = x + 200 - (5 * 19 + 4 * 2);
        addRenderableWidget(small("⏴", "scene_view.prev", tools, 6, () -> switchShot(-1))).active = shot > 0;
        addRenderableWidget(small("⏵", "scene_view.next", tools + 21, 6, () -> switchShot(1))).active = shot < count - 1;
        addRenderableWidget(small("+", "scene_view.add", tools + 42, 6, this::addShot));
        addRenderableWidget(small("📋", "scene_view.copy", tools + 63, 6, this::copyShot));
        addRenderableWidget(small("✕", "scene_view.remove", tools + 84, 6, this::removeShot)).active = count > 1;
        int top = contentTop();
        if (talk(shot)) {
            // A talk: its dialogue is set in the form; here the scene around it plays.
            buildingScrollArea = true;
            try {
                int y = top + 12 - scroll;
                addRenderableWidget(Button.builder(Component.literal("⏩ ").append(DevUi.text("scene_view.play_scene")), b -> playScene())
                        .bounds(x, y, 200, 19).tooltip(Tooltip.create(DevUi.text("scene_view.play_scene_tooltip"))).build());
                contentHeight = 40;
            } finally {
                buildingScrollArea = false;
            }
            addPinnedFooter(x);
            return;
        }
        // Clean 3-tab strip right below the anchor header: Point | Shot | Cues.
        int tabY = tabBarY();
        PanelTab[] tabs = PanelTab.values();
        int tabGap = 3;
        int tabW = (200 - tabGap * 2) / 3;
        for (int i = 0; i < tabs.length; i++) {
            PanelTab tab = tabs[i];
            boolean current = tab == panelTab;
            int bw = i == 2 ? 200 - i * (tabW + tabGap) : tabW;
            String key = switch (tab) {
                case POINT -> "scene_view.tab_point";
                case SHOT -> "scene_view.tab_shot";
                case CUES -> "scene_view.tab_cues";
            };
            Component label = Component.literal((current ? "• " : "") + DevUi.str(key))
                    .withColor(current ? DevUi.ACCENT : DevUi.LABEL);
            addRenderableWidget(Button.builder(label, b -> {
                if (panelTab != tab) {
                    panelTab = tab;
                    scroll = 0;
                    build();
                }
            }).bounds(x + i * (tabW + tabGap), tabY, bw, 16).build());
        }
        int y;
        buildingScrollArea = true;
        try {
            y = top - scroll;
            switch (panelTab) {
                case POINT -> y = buildPointTab(x, y);
                case SHOT -> y = buildShotTab(x, y);
                case CUES -> y = cueEditor(x, y);
            }
        } finally {
            buildingScrollArea = false;
        }
        contentHeight = y + scroll - top;
        int maxScroll = Math.max(0, contentHeight - (contentBottom() - top));
        if (scroll > maxScroll) {
            scroll = maxScroll;
            revealCue = false;
            build();
            return;
        }
        if (revealCue) {
            revealCue = false;
            int wanted = Mth.clamp(scroll + Math.max(0, y - contentBottom()), 0, maxScroll);
            if (cueLabelY > -500) {
                wanted = Math.min(wanted, Math.max(0, scroll + cueLabelY - top));
            }
            if (wanted != scroll) {
                scroll = wanted;
                build();
                return;
            }
        }
        addPinnedFooter(x);
    }

    /** Pinned playback bar (Play / Scene / Grid / Hotkeys) + Save / Close, always reachable on every tab. */
    private void addPinnedFooter(int x) {
        int barY = height - 52;
        if (!talk(shot)) {
            addRenderableWidget(Button.builder(Component.literal("▶ ").append(DevUi.text("scene_view.play")), b -> playShot())
                    .bounds(x, barY, 75, 19).tooltip(Tooltip.create(DevUi.text("scene_view.play_tooltip"))).build());
            addRenderableWidget(Button.builder(Component.literal("⏩ ").append(DevUi.text("scene_view.play_scene")), b -> playScene())
                    .bounds(x + 78, barY, 79, 19).tooltip(Tooltip.create(DevUi.text("scene_view.play_scene_tooltip"))).build());
            addRenderableWidget(Button.builder(Component.literal(grid ? "⊞" : "⊟").withColor(grid ? DevUi.ACCENT : DevUi.LABEL), b -> {
                grid = !grid;
                build();
            }).bounds(x + 160, barY, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.grid_tooltip"))).build());
            addRenderableWidget(Button.builder(Component.literal("⌨").withColor(helpShown ? DevUi.ACCENT : DevUi.LABEL), b -> {
                helpShown = !helpShown;
                build();
            }).bounds(x + 181, barY, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.help_tooltip"))).build());
        }
        addRenderableWidget(Button.builder(DevUi.text("scene_view.save").copy().withColor(DevUi.ACCENT), b -> save())
                .bounds(x, height - 28, 98, 20).tooltip(Tooltip.create(DevUi.text("scene_view.save_tooltip"))).build());
        addRenderableWidget(Button.builder(DevUi.text("scene_view.close"), b -> close()).bounds(x + 102, height - 28, 98, 20).build());
    }

    /** Tab 1 ("Point"): control point tabs, XYZ sliders, live movement guide, Outside-view toggle, FOV, Roll, and Yaw/Pitch or Aim offset. */
    private int buildPointTab(int x, int y) {
        y = pathRows(x, y);
        modeHintY = y;
        y += 22;
        freeButton = addRenderableWidget(Button.builder(freeLabel(), b -> {
            if (view == View.FREE) {
                enterPilot();
            } else {
                goFree();
            }
            build();
        }).bounds(x, y, 200, 19).tooltip(Tooltip.create(DevUi.text("scene_view.free_tooltip"))).build());
        y += 23;
        fovSlider = addRenderableWidget(new FovSlider(x, y, 177, 19));
        fovSlider.setTooltip(Tooltip.create(DevUi.text("scene_view.fov_tooltip")));
        fovResetButton = addRenderableWidget(Button.builder(Component.literal("↺"), b -> {
            pushUndo();
            if (selectedPoint == controlPointsLocal(shot).size() - 1 && camera().has("fov_to")) {
                camera().remove("fov_to");
            } else {
                camera().remove("fov");
                camera().remove("fov_to");
            }
            changed();
            build();
        }).bounds(x + 181, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.fov_reset"))).build());
        fovResetButton.active = camera().has("fov") || camera().has("fov_to");
        y += 22;
        rollSlider = addRenderableWidget(new RollSlider(x, y, 177, 19));
        rollSlider.setTooltip(Tooltip.create(DevUi.text("scene_view.roll_tooltip")));
        rollResetButton = addRenderableWidget(Button.builder(Component.literal("↺"), b -> {
            pushUndo();
            if (selectedPoint == controlPointsLocal(shot).size() - 1 && camera().has("roll_to")) {
                camera().remove("roll_to");
            } else {
                camera().remove("roll");
                camera().remove("roll_to");
            }
            changed();
            build();
        }).bounds(x + 181, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.roll_reset"))).build());
        rollResetButton.active = camera().has("roll") || camera().has("roll_to");
        y += 22;
        if (look(shot).equals("free")) {
            rotLabelY = y;
            y += 10;
            Vec2 rot = selectedRotationLocal();
            double[] rotVals = {rot.x, rot.y};
            for (int i = 0; i < 2; i++) {
                int axis = i;
                EditBox box = addRenderableWidget(new EditBox(font, x + i * 89, y, 85, 19, Component.empty()));
                filling = true;
                box.setValue(String.format(Locale.ROOT, "%.1f", rotVals[i]));
                filling = false;
                box.setTooltip(Tooltip.create(DevUi.text("scene_view.rot_tooltip")));
                box.setResponder(text -> {
                    if (filling) {
                        return;
                    }
                    double parsed = parse(text, Double.NaN);
                    if (!Double.isNaN(parsed)) {
                        pushUndoDebounced();
                        Vec2 cur = selectedRotationLocal();
                        float yaw = axis == 0 ? (float) parsed : cur.x;
                        float pitch = axis == 1 ? Mth.clamp((float) parsed, -89.9f, 89.9f) : cur.y;
                        setRotationWorld(shot, selectedPoint, anchor(shot).yaw() + yaw, pitch);
                        changed();
                    }
                });
                rotBoxes[i] = box;
            }
            addRenderableWidget(Button.builder(Component.literal("🎯"), b -> {
                pushUndo();
                Vec3 pt = pathControlPositions(shot).get(Mth.clamp(selectedPoint, 0, controlPointsLocal(shot).size() - 1));
                SceneCamera.Pose aimed = SceneCamera.facing(pt, anchorTarget(shot, 0f));
                setRotationWorld(shot, selectedPoint, aimed.yaw(), aimed.pitch());
                changed();
                build();
            }).bounds(x + 181, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.rot_aim_anchor"))).build());
            y += 22;
        } else {
            lookOffsetLabelY = y;
            y += 10;
            Vec3 lookOff = shotLookOffset(shot);
            double[] offVals = {lookOff.x, lookOff.y};
            for (int i = 0; i < 2; i++) {
                int axis = i;
                EditBox box = addRenderableWidget(new EditBox(font, x + i * 89, y, 85, 19, Component.empty()));
                filling = true;
                box.setValue(String.format(Locale.ROOT, "%.2f", offVals[i]));
                filling = false;
                box.setTooltip(Tooltip.create(DevUi.text("scene_view.look_offset_tooltip")));
                box.setResponder(text -> {
                    if (filling) {
                        return;
                    }
                    double parsed = parse(text, Double.NaN);
                    if (!Double.isNaN(parsed)) {
                        pushUndoDebounced();
                        Vec3 cur = shotLookOffset(shot);
                        Vec3 next = axis == 0 ? new Vec3(parsed, cur.y, cur.z) : new Vec3(cur.x, parsed, cur.z);
                        if (next.lengthSqr() < 1.0e-6) {
                            camera().remove("look_offset");
                        } else {
                            camera().add("look_offset", vec(next));
                        }
                        changed();
                    }
                });
                lookOffsetBoxes[i] = box;
            }
            Button lookOffReset = addRenderableWidget(Button.builder(Component.literal("↺"), b -> {
                pushUndo();
                camera().remove("look_offset");
                changed();
                build();
            }).bounds(x + 181, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.look_offset_reset"))).build());
            lookOffReset.active = camera().has("look_offset");
            y += 22;
        }
        return y + 4;
    }

    /** Tab 2 ("Shot"): anchor picker, timing + live speed readout, ease, look mode, transition, above-ground, breathing shake, safe angle. */
    private int buildShotTab(int x, int y) {
        addRenderableWidget(Button.builder(Component.literal(fitWidth(DevUi.str("scene_view.anchor_button", anchorName()), 190)), b -> openAnchorPicker())
                .bounds(x, y, 200, 18).tooltip(Tooltip.create(DevUi.text("scene_view.anchor_pick_tooltip"))).build());
        y += 22;
        addRenderableWidget(Button.builder(DevUi.text(bySpeed() ? "scene_view.by_speed" : "scene_view.by_time"), b -> {
            pushUndo();
            JsonObject camera = camera();
            double seconds = seconds(shot);
            if (bySpeed()) {
                camera.remove("speed");
                camera.addProperty("duration", round(Mth.clamp(seconds, 0.05, 120)));
            } else {
                double distance = shotPath(shot).length();
                camera.remove("duration");
                camera.addProperty("speed", round(Mth.clamp(distance / seconds, 0.05, 200)));
            }
            changed();
            build();
        }).bounds(x, y, 128, 18).tooltip(Tooltip.create(DevUi.text("scene_view.length_tooltip"))).build());
        EditBox length = addRenderableWidget(new EditBox(font, x + 134, y, 66, 18, Component.empty()));
        lengthBox = length;
        length.setValue(number(camera().get(bySpeed() ? "speed" : "duration")));
        length.setTooltip(Tooltip.create(DevUi.text("scene_view.nudge_tooltip")));
        length.setResponder(text -> {
            if (filling) {
                return;
            }
            double value = parse(text, -1);
            if (value > 0) {
                pushUndoDebounced();
                camera().addProperty(bySpeed() ? "speed" : "duration", Mth.clamp(value, 0.05, bySpeed() ? 200 : 120));
                changed();
            }
        });
        y += 20;
        speedInfoY = y;
        y += 12;
        addRenderableWidget(Button.builder(DevUi.text("scene_view.ease_" + ease(shot).getSerializedName()), b -> {
            pushUndo();
            SceneDefinition.Ease[] all = SceneDefinition.Ease.values();
            camera().addProperty("ease", all[(ease(shot).ordinal() + 1) % all.length].getSerializedName());
            changed();
            build();
        }).bounds(x, y, 200, 18).tooltip(Tooltip.create(DevUi.text("scene_view.ease_tooltip"))).build());
        y += 22;
        addRenderableWidget(Button.builder(DevUi.text("scene_view.look_" + look(shot)), b -> {
            pushUndo();
            String next = switch (look(shot)) {
                case "anchor" -> "free";
                case "free" -> "path";
                case "path" -> "player";
                default -> "anchor";
            };
            if (next.equals("free")) {
                ensureFreeLookBaked(shot);
            } else {
                camera().addProperty("look", next);
            }
            changed();
            build();
        }).bounds(x, y, 200, 18).tooltip(Tooltip.create(DevUi.text("scene_view.look_tooltip"))).build());
        y += 22;
        addRenderableWidget(Button.builder(DevUi.text("scene_view.transition_" + transition(shot)), b -> {
            pushUndo();
            String next = switch (transition(shot)) {
                case "cut" -> "fade";
                case "fade" -> "ease";
                default -> "cut";
            };
            shotJson(shot).addProperty("transition", next);
            changed();
            build();
        }).bounds(x, y, 200, 18).tooltip(Tooltip.create(DevUi.text("scene_view.transition_tooltip"))).build());
        y += 22;
        aboveGroundSlider = addRenderableWidget(new AboveGroundSlider(x, y, 177, 19));
        aboveGroundSlider.setTooltip(Tooltip.create(DevUi.text("scene_view.above_ground_tooltip")));
        aboveGroundResetButton = addRenderableWidget(Button.builder(Component.literal("↺"), b -> {
            pushUndo();
            camera().remove("above_ground");
            changed();
            build();
        }).bounds(x + 181, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.above_ground_reset"))).build());
        aboveGroundResetButton.active = shotAboveGround(shot) > 0f;
        y += 22;
        shakeSlider = addRenderableWidget(new ShakeSlider(x, y, 177, 19));
        shakeSlider.setTooltip(Tooltip.create(DevUi.text("scene_view.shake_tooltip")));
        shakeResetButton = addRenderableWidget(Button.builder(Component.literal("↺"), b -> {
            pushUndo();
            camera().remove("shake");
            changed();
            build();
        }).bounds(x + 181, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.shake_reset"))).build());
        shakeResetButton.active = shotShake(shot) > 0.001f;
        y += 22;
        boolean safeAngle = shotSafeAngle(shot);
        addRenderableWidget(Button.builder(DevUi.text(safeAngle ? "scene_view.safe_angle_on" : "scene_view.safe_angle_off"), b -> {
            pushUndo();
            camera().addProperty("safe_angle", !safeAngle);
            changed();
            build();
        }).bounds(x, y, 200, 18).tooltip(Tooltip.create(DevUi.text("scene_view.safe_angle_tooltip"))).build());
        return y + 22;
    }

    /** Opens a searchable anchor picker directly inside the 3D editor (player, story NPCs, POIs, structures, waypoints). */
    private void openAnchorPicker() {
        List<Catalog.Entry> entries = new java.util.ArrayList<>();
        entries.add(new Catalog.Entry("player", DevUi.str("scene.anchor.player"), new ItemStack(Items.PLAYER_HEAD)));
        entries.add(new Catalog.Entry("pick_poi", DevUi.str("scene_view.anchor_pick_poi"), new ItemStack(Items.BELL)));
        entries.add(new Catalog.Entry("pick_structure", DevUi.str("scene_view.anchor_pick_structure"), new ItemStack(Items.FILLED_MAP)));
        Map<ResourceLocation, String> npcs = ClientDevStore.byType(ClientDevStore.TYPE_NPC);
        if (!npcs.isEmpty()) {
            entries.add(new Catalog.Entry(Catalog.SECTION + "npcs", DevUi.str("scene.anchor.npc"), ItemStack.EMPTY));
            npcs.keySet().stream().sorted().forEach(npcId -> {
                JsonObject obj = ClientDevStore.parseObject(npcs.get(npcId));
                String name = ClientDevStore.readStoryText(obj, "name", npcId.getPath(), ClientDevStore.activeLanguage());
                String label = name.isBlank() || name.equals(npcId.getPath()) ? npcId.toString() : name + " (" + npcId.getPath() + ")";
                entries.add(new Catalog.Entry("npc:" + npcId, label, new ItemStack(Items.EMERALD)));
            });
        }
        Map<ResourceLocation, String> waypoints = ClientDevStore.byType(ClientDevStore.TYPE_WAYPOINT);
        if (!waypoints.isEmpty()) {
            entries.add(new Catalog.Entry(Catalog.SECTION + "waypoints", DevUi.str("scene.anchor.waypoint"), ItemStack.EMPTY));
            waypoints.keySet().stream().sorted().forEach(wpId -> {
                JsonObject obj = ClientDevStore.parseObject(waypoints.get(wpId));
                String name = ClientDevStore.readStoryText(obj, "name", wpId.getPath(), ClientDevStore.activeLanguage());
                String label = name.isBlank() || name.equals(wpId.getPath()) ? wpId.toString() : name + " (" + wpId.getPath() + ")";
                entries.add(new Catalog.Entry("waypoint:" + wpId, label, new ItemStack(Items.COMPASS)));
            });
        }
        catalog.openList(DevUi.text("scene_view.anchor_pick_title"), entries, (kind, entry) -> {
            String picked = entry.id();
            if (picked.equals("player")) {
                applyShotAnchor(new JsonPrimitive("player"));
            } else if (picked.startsWith("npc:")) {
                JsonObject obj = new JsonObject();
                obj.addProperty("npc", picked.substring(4));
                applyShotAnchor(obj);
            } else if (picked.startsWith("waypoint:")) {
                JsonObject obj = new JsonObject();
                obj.addProperty("waypoint", picked.substring(9));
                applyShotAnchor(obj);
            } else if (picked.equals("pick_poi")) {
                String currentPoi = shotJson(shot).get("anchor") instanceof JsonObject a && a.get("poi") instanceof JsonPrimitive p ? p.getAsString() : null;
                catalog.open(DevUi.text("scene_view.anchor_pick_poi"), Catalog.Kind.POI, currentPoi, (k2, e2) -> {
                    if (!e2.id().equals(Catalog.CLEAR) && !e2.id().isBlank()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("poi", e2.id());
                        obj.addProperty("radius", 64);
                        applyShotAnchor(obj);
                    }
                });
            } else if (picked.equals("pick_structure")) {
                String currentStruct = shotJson(shot).get("anchor") instanceof JsonObject a && a.get("structure") instanceof JsonPrimitive s ? s.getAsString() : null;
                catalog.open(DevUi.text("scene_view.anchor_pick_structure"), Catalog.Kind.STRUCTURE, currentStruct, (k2, e2) -> {
                    if (!e2.id().equals(Catalog.CLEAR) && !e2.id().isBlank()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("structure", e2.id());
                        applyShotAnchor(obj);
                    }
                });
            }
        });
        build();
    }

    private void applyShotAnchor(JsonElement anchorValue) {
        pushUndo();
        shotJson(shot).add("anchor", anchorValue);
        changed();
        askAnchors();
    }

    private Button small(String label, String tooltip, int x, int y, Runnable press) {
        return Button.builder(Component.literal(label), b -> press.run()).bounds(x, y, 19, 19).tooltip(Tooltip.create(DevUi.text(tooltip))).build();
    }

    private Component freeLabel() {
        return view == View.FREE
                ? DevUi.text("scene_view.free").copy().withColor(DevUi.ACCENT)
                : DevUi.text("scene_view.free_off");
    }

    private String selectedPointName() {
        int total = controlPointsLocal(shot).size();
        int sel = Mth.clamp(selectedPoint, 0, total - 1);
        return sel == 0 ? DevUi.str("scene_view.from") : sel == total - 1 ? DevUi.str("scene_view.to") : "#" + sel;
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

    /** Preferred order when adding a new thing at a moment that already has others. */
    private static final List<String> ADD_CUE_ORDER = List.of("line", "sound", "music", "title", "caption", "fade", "shake");

    /** Indices in {@code during(shotIndex)} that sit at {@code at} (within 0.08s). */
    private List<Integer> cuesAtSecond(int shotIndex, double at) {
        List<Integer> indices = new java.util.ArrayList<>();
        JsonArray during = during(shotIndex);
        for (int i = 0; i < during.size(); i++) {
            if (during.get(i) instanceof JsonObject cue && Math.abs(cueAt(cue) - at) < 0.08) {
                indices.add(i);
            }
        }
        return indices;
    }

    /** Kinds already used at {@code at} in {@code shotIndex}, ignoring {@code ignoreCue}. */
    private java.util.Set<String> cueKindsAtSecond(int shotIndex, double at, int ignoreCue) {
        java.util.Set<String> kinds = new java.util.HashSet<>();
        JsonArray during = during(shotIndex);
        for (int i = 0; i < during.size(); i++) {
            if (i != ignoreCue && during.get(i) instanceof JsonObject cue && Math.abs(cueAt(cue) - at) < 0.08) {
                kinds.add(cueKind(cue));
            }
        }
        return kinds;
    }

    /** Adds a thing at the exact moment the editor's playhead shows right now. */
    private void addCueAtPlayhead() {
        int targetShot = shot;
        double targetSeconds = time * seconds(shot);
        if (view == View.SCENE) {
            double left = sceneTime;
            for (int i = 0; i < shots().size(); i++) {
                double length = seconds(i);
                if (!talk(i) && (left <= length || i == lastFlightIndex())) {
                    targetShot = i;
                    targetSeconds = Mth.clamp(left, 0, length);
                    break;
                }
                left -= length;
            }
        }
        if (!talk(targetShot)) {
            addCue(targetShot, targetSeconds);
        }
    }

    /**
     * Adds a thing at that exact second of a flight shot, choosing the first kind not yet used at that moment so
     * several different things (e.g. chapter title + music + sound) can share one second without duplicating the same
     * kind.
     */
    private void addCue(int index, double seconds) {
        double duration = Math.max(0.05, seconds(index));
        double at = Mth.clamp(Math.round(seconds * 10) / 10.0, 0, duration);
        java.util.Set<String> used = cueKindsAtSecond(index, at, -1);
        String nextKind = ADD_CUE_ORDER.stream().filter(k -> !used.contains(k)).findFirst().orElse(null);
        if (nextKind == null) {
            List<Integer> existing = cuesAtSecond(index, at);
            if (!existing.isEmpty()) {
                pickShot(index);
                int pos = existing.indexOf(selectedCue);
                selectedCue = existing.get((pos + 1) % existing.size());
                revealCue = true;
                cueAddedAt = net.minecraft.Util.getMillis();
                build();
                showShot(duration <= 0 ? 0 : at / duration);
            }
            return;
        }
        pushUndo();
        JsonArray during = during(index);
        JsonObject cue = new JsonObject();
        cue.addProperty("at", at);
        cue.add(nextKind, freshCue(nextKind, at));
        during.add(cue);
        pickShot(index);
        selectedCue = during.size() - 1;
        panelTab = PanelTab.CUES;
        revealCue = true;
        cueAddedAt = net.minecraft.Util.getMillis();
        changed();
        build();
        showShot(duration <= 0 ? 0 : at / duration);
    }

    /** How many seconds a thing stays shown on screen from its start, for scrubbing and previewing. */
    private double cueDuration(JsonObject cue) {
        String kind = cueKind(cue);
        return switch (kind) {
            case "title" -> 5.5;
            case "line", "caption" -> {
                JsonObject inner = cue.get(kind) instanceof JsonObject obj ? obj : null;
                if (inner != null && inner.get("duration") instanceof JsonPrimitive num && num.isNumber()) {
                    try {
                        yield Math.max(0.5, num.getAsDouble());
                    } catch (RuntimeException ignored) {
                    }
                }
                String text = inner != null ? ClientDevStore.readStoryText(inner, "text", "", ClientDevStore.activeLanguage()) : "";
                yield Math.max(2.5, text.length() * 0.06);
            }
            case "fade" -> {
                JsonObject inner = cue.get("fade") instanceof JsonObject obj ? obj : null;
                double in = inner != null ? Math.max(0, parse(number(inner.get("in")), 0.5)) : 0.5;
                double stay = inner != null ? Math.max(0, parse(number(inner.get("stay")), 1.0)) : 1.0;
                double out = inner != null ? Math.max(0, parse(number(inner.get("out")), 0.5)) : 0.5;
                yield Math.max(0.05, in + stay + out);
            }
            case "shake" -> {
                JsonObject inner = cue.get("shake") instanceof JsonObject obj ? obj : null;
                yield inner != null ? Math.max(0.05, parse(number(inner.get("duration")), 0.4)) : 0.4;
            }
            default -> 1.0;
        };
    }

    /**
     * The thing of the shot right at this moment on the time line (within 0.12s of its mark); -1 when the playhead is
     * not on any mark, so scrubbing away from a thing deselects it.
     */
    private int cueAtMoment(int index, double atSeconds) {
        if (talk(index)) {
            return -1;
        }
        JsonArray during = during(index);
        if (index == shot && selectedCue >= 0 && selectedCue < during.size()
                && during.get(selectedCue) instanceof JsonObject current && Math.abs(cueAt(current) - atSeconds) <= 0.12) {
            return selectedCue;
        }
        int nearest = -1;
        double bestNear = 0.12;
        for (int i = 0; i < during.size(); i++) {
            if (during.get(i) instanceof JsonObject cue) {
                double distance = Math.abs(cueAt(cue) - atSeconds);
                if (distance <= bestNear) {
                    bestNear = distance;
                    nearest = i;
                }
            }
        }
        return nearest;
    }

    /**
     * A fresh thing of that kind: its text empty, no actions; a fade placed at the very start of a shot (at <= 0.05s)
     * begins already black (in = 0) so the shot opens out of darkness instead of flashing bright first.
     */
    static JsonElement freshCue(String kind, double at) {
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
                fade.addProperty("in", at <= 0.05 ? 0 : 0.5);
                fade.addProperty("stay", 1);
                fade.addProperty("out", 0.5);
                yield fade;
            }
            case "shake" -> {
                JsonObject shake = new JsonObject();
                shake.addProperty("intensity", 0.2);
                shake.addProperty("duration", 0.5);
                yield shake;
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
        addRenderableWidget(Button.builder(Component.literal("+"), b -> addCueAtPlayhead())
                .bounds(x + 181, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.cue_add_tooltip"))).build());
        y += 22;
        JsonObject cue = selectedCue();
        if (cue == null) {
            addRenderableWidget(Button.builder(DevUi.text("scene_view.cue_add"), b -> addCueAtPlayhead())
                    .bounds(x, y, 200, 19).tooltip(Tooltip.create(DevUi.text("scene_view.cue_add_tooltip"))).build());
            return y + 48;
        }
        List<Integer> siblings = cuesAtSecond(shot, cueAt(cue));
        if (siblings.size() > 1) {
            int cols = Math.min(3, siblings.size());
            int gap = 4;
            int tabW = (200 - (cols - 1) * gap) / cols;
            for (int n = 0; n < siblings.size(); n++) {
                int cueIndex = siblings.get(n);
                String sibKind = cueKind(during(shot).get(cueIndex).getAsJsonObject());
                boolean current = cueIndex == selectedCue;
                int col = n % cols;
                int row = n / cols;
                Component tabLabel = Component.literal((current ? "• " : "") + DevUi.str("scene.cue." + sibKind))
                        .withColor(current ? DevUi.ACCENT : cueColor(sibKind));
                addRenderableWidget(Button.builder(tabLabel, b -> {
                    selectedCue = cueIndex;
                    build();
                }).bounds(x + col * (tabW + gap), y + row * 18, col == cols - 1 ? 200 - col * (tabW + gap) : tabW, 16)
                        .tooltip(Tooltip.create(DevUi.text("scene.cue." + sibKind))).build());
            }
            y += ((siblings.size() + cols - 1) / cols) * 18 + 2;
        }
        String kind = cueKind(cue);
        addRenderableWidget(Button.builder(DevUi.text("scene.cue." + kind), b -> {
            java.util.Set<String> usedByOthers = cueKindsAtSecond(shot, cueAt(cue), selectedCue);
            int startIdx = Math.max(0, VIEW_CUE_KINDS.indexOf(kind));
            String next = kind;
            for (int step = 1; step <= VIEW_CUE_KINDS.size(); step++) {
                String candidate = VIEW_CUE_KINDS.get((startIdx + step) % VIEW_CUE_KINDS.size());
                if (!usedByOthers.contains(candidate)) {
                    next = candidate;
                    break;
                }
            }
            if (!next.equals(kind)) {
                pushUndo();
                DevSoundPreview.stop();
                cue.remove(kind);
                cue.add(next, freshCue(next, cueAt(cue)));
                changed();
                build();
            }
        }).bounds(x, y, 177, 19).tooltip(Tooltip.create(DevUi.text("scene.cue.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
            pushUndo();
            DevSoundPreview.stop();
            double removedAt = cueAt(cue);
            during(shot).remove(selectedCue);
            List<Integer> remaining = cuesAtSecond(shot, removedAt);
            selectedCue = remaining.isEmpty() ? -1 : remaining.getFirst();
            changed();
            build();
        }).bounds(x + 181, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.cue_remove"))).build());
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
                    y = soundBox(x, y, false, "scene_view.cue_voiceover",
                            () -> inner.get("sound") instanceof JsonPrimitive s ? s.getAsString() : "",
                            value -> {
                                if (value.isEmpty()) {
                                    inner.remove("sound");
                                } else {
                                    inner.addProperty("sound", value);
                                }
                            });
                } else {
                    String position = inner.get("position") instanceof JsonPrimitive written ? written.getAsString() : "bottom_left";
                    boolean black = inner.get("black") instanceof JsonPrimitive written && written.getAsBoolean();
                    addRenderableWidget(Button.builder(DevUi.text("scene_view.position_" + position), b -> {
                        pushUndo();
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
                        pushUndo();
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
                y = soundBox(x, y, false, "scene_view.cue_sound", () -> inner.get("sound") instanceof JsonPrimitive id ? id.getAsString() : "", value -> inner.addProperty("sound", value));
            }
            case "music" -> y = soundBox(x, y, true, "scene_view.cue_music", () -> cue.get("music") instanceof JsonPrimitive id ? id.getAsString() : "", value -> cue.addProperty("music", value));
            case "fade" -> {
                JsonObject inner = cue.get("fade") instanceof JsonObject object ? object : new JsonObject();
                cue.add("fade", inner);
                double in = Math.max(0, parse(number(inner.get("in")), 0.5));
                double stay = Math.max(0, parse(number(inner.get("stay")), 1.0));
                double out = Math.max(0, parse(number(inner.get("out")), 0.5));
                String style = in <= 0.001 ? "from_black" : out <= 0.001 ? "to_black" : "dip";
                addRenderableWidget(Button.builder(DevUi.text("scene_view.fade_style_" + style), b -> {
                    pushUndo();
                    switch (style) {
                        case "from_black" -> {
                            inner.addProperty("in", 0.5);
                            inner.addProperty("out", out > 0.001 ? round(out) : 0.5);
                        }
                        case "dip" -> {
                            inner.addProperty("in", in > 0.001 ? round(in) : 0.5);
                            inner.addProperty("out", 0);
                        }
                        default -> {
                            inner.addProperty("in", 0);
                            inner.addProperty("out", 0.5);
                        }
                    }
                    changed();
                    build();
                }).bounds(x, y, 200, 18).tooltip(Tooltip.create(DevUi.text("scene_view.fade_style_tooltip"))).build());
                y += 22;
                fadeLabelY = y;
                y += 10;
                String[] fields = {"in", "stay", "out"};
                double[] vals = {in, stay, out};
                double[] maxes = {ScreenFade.MAX_EDGE, ScreenFade.MAX_STAY, ScreenFade.MAX_EDGE};
                for (int i = 0; i < 3; i++) {
                    String field = fields[i];
                    double max = maxes[i];
                    EditBox box = addRenderableWidget(new EditBox(font, x + i * 68, y, 64, 16, Component.empty()));
                    filling = true;
                    box.setValue(String.format(Locale.ROOT, "%.1f", vals[i]));
                    filling = false;
                    box.setTooltip(Tooltip.create(DevUi.text("scene_view.nudge_tooltip")));
                    box.setResponder(text -> {
                        if (filling) {
                            return;
                        }
                        double parsed = parse(text, -1);
                        if (parsed >= 0) {
                            pushUndoDebounced();
                            inner.addProperty(field, round(Mth.clamp(parsed, 0, max)));
                            changed();
                        }
                    });
                    fadeBoxes[i] = box;
                }
                y += 20;
            }
            case "shake" -> {
                JsonObject inner = cue.get("shake") instanceof JsonObject object ? object : new JsonObject();
                cue.add("shake", inner);
                double intensity = Mth.clamp(parse(number(inner.get("intensity")), 0.2), 0.01, 2.0);
                double dur = Mth.clamp(parse(number(inner.get("duration")), 0.5), 0.05, 10.0);
                shakeLabelY = y;
                y += 10;
                String[] fields = {"intensity", "duration"};
                double[] vals = {intensity, dur};
                double[] mins = {0.01, 0.05};
                double[] maxes = {2.0, 10.0};
                for (int i = 0; i < 2; i++) {
                    String field = fields[i];
                    double min = mins[i];
                    double max = maxes[i];
                    EditBox box = addRenderableWidget(new EditBox(font, x + i * 102, y, 98, 16, Component.empty()));
                    filling = true;
                    box.setValue(String.format(Locale.ROOT, "%.2f", vals[i]));
                    filling = false;
                    box.setTooltip(Tooltip.create(DevUi.text("scene_view.nudge_tooltip")));
                    box.setResponder(text -> {
                        if (filling) {
                            return;
                        }
                        double parsed = parse(text, -1);
                        if (parsed > 0) {
                            pushUndoDebounced();
                            inner.addProperty(field, round(Mth.clamp(parsed, min, max)));
                            changed();
                        }
                    });
                    shakeBoxes[i] = box;
                }
                y += 20;
            }
            default -> {
                // Actions are set in the form, where their fields are.
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
            pushUndoDebounced();
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
    private int soundBox(int x, int y, boolean music, String hint, java.util.function.Supplier<String> read, java.util.function.Consumer<String> write) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, 154, 19, Component.empty()));
        box.setMaxLength(256);
        box.setHint(DevUi.text(hint).copy().withColor(DevUi.HINT));
        filling = true;
        box.setValue(read.get());
        filling = false;
        box.setResponder(text -> {
            if (!filling) {
                pushUndoDebounced();
                write.accept(text.trim());
                changed();
            }
        });
        Component pick = DevUi.text(music ? "music.pick" : "sound.pick");
        addRenderableWidget(Button.builder(Component.literal("…"), b -> {
            catalog.open(pick, music ? Catalog.Kind.MUSIC : Catalog.Kind.SOUND,
                    box.getValue(), (kind, entry) -> {
                if (entry.id().startsWith(Catalog.PROBLEM)) {
                    status(DevUi.text("sound.problem." + entry.id().substring(Catalog.PROBLEM.length())), DevUi.BAD);
                    return;
                }
                pushUndo();
                write.accept(entry.id().equals(Catalog.CLEAR) ? "" : entry.id());
                changed();
            });
            build();
        }).tooltip(Tooltip.create(pick)).bounds(x + 158, y, 19, 19).build());
        addRenderableWidget(DevSoundPreview.button(box::getValue, x + 181, y, 19, 19));
        return y + 23;
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
            case "shake" -> 0xFFE07A5F;
            default -> 0xFFD9785A;
        };
    }

    /** Cues at one second of a shot grouped together for drawing and clicking on the time line. */
    private record CueGroup(int shotIndex, double at, int x, List<Integer> indices) {}

    private List<CueGroup> cueGroups() {
        List<CueGroup> groups = new java.util.ArrayList<>();
        for (int i = 0; i < shots().size(); i++) {
            if (talk(i)) {
                continue;
            }
            JsonArray during = during(i);
            boolean[] grouped = new boolean[during.size()];
            for (int j = 0; j < during.size(); j++) {
                if (grouped[j] || !(during.get(j) instanceof JsonObject first)) {
                    continue;
                }
                double at = cueAt(first);
                List<Integer> members = new java.util.ArrayList<>();
                for (int k = j; k < during.size(); k++) {
                    if (!grouped[k] && during.get(k) instanceof JsonObject other && Math.abs(cueAt(other) - at) < 0.08) {
                        grouped[k] = true;
                        members.add(k);
                    }
                }
                groups.add(new CueGroup(i, at, (int) Math.round(lineX(i, at)), members));
            }
        }
        return groups;
    }

    /** The cue group under the cursor on the time line, or null. */
    private @Nullable CueGroup cueGroupAtX(double mouseX, double mouseY) {
        if (mouseY < lineTop() - 5 || mouseY > lineTop() + LINE_H) {
            return null;
        }
        CueGroup best = null;
        double bestDist = 5;
        for (CueGroup group : cueGroups()) {
            double dist = Math.abs(group.x() - mouseX);
            if (dist <= bestDist) {
                bestDist = dist;
                best = group;
            }
        }
        return best;
    }

    /**
     * The scene on one line at the bottom: a block a shot (a talk with the chat icon), total seconds at its right edge,
     * its things as marks (stacked when several share one second), and a playhead bar with the time it stands on beside
     * it. Returns true when a thing's badge is drawn above the time line.
     */
    private boolean drawTimeLine(GuiGraphics graphics, int mouseX, int mouseY) {
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
        }
        String endLabel = formatSeconds(sceneSeconds());
        int endW = font.width(endLabel);
        int textY = top + (LINE_H - 8) / 2;
        if (lineRight() - lineLeft() > endW + 24) {
            graphics.drawString(font, endLabel, lineRight() - endW - 4, textY, DevUi.MUTED, false);
        }
        CueGroup hoveredGroup = cueGroupAtX(mouseX, mouseY);
        CueGroup badgeGroup = null;
        boolean badgeFromAdd = false;
        for (CueGroup group : cueGroups()) {
            int mx = group.x();
            int count = group.indices().size();
            boolean picked = group.shotIndex() == shot && group.indices().contains(selectedCue);
            boolean hover = hoveredGroup != null && hoveredGroup.shotIndex() == group.shotIndex()
                    && Math.abs(hoveredGroup.at() - group.at()) < 0.08;
            JsonArray during = during(group.shotIndex());
            int halfW = count > 1 ? 3 : 2;
            int barH = 12;
            for (int s = 0; s < count; s++) {
                JsonObject member = during.get(group.indices().get(s)).getAsJsonObject();
                int y0 = top + 2 + s * barH / count;
                int y1 = top + 2 + (s + 1) * barH / count;
                graphics.fill(mx - halfW, y0, mx + halfW, Math.max(y0 + 1, y1), cueColor(cueKind(member)));
            }
            DevUi.frame(graphics, mx - halfW - 1, top + 1, halfW * 2 + 2, barH + 2, picked || hover ? 0xFFFFFFFF : 0xB0000000);
            if (count > 1) {
                graphics.drawString(font, "×" + count, mx + halfW + 2, top + 6, picked || hover ? DevUi.ACCENT : DevUi.TEXT, true);
            }
            if (picked || hover) {
                int topCueIdx = picked ? selectedCue : group.indices().getFirst();
                int capCol = cueColor(cueKind(during.get(topCueIdx).getAsJsonObject()));
                graphics.fill(mx - halfW - 1, top - 4, mx + halfW + 1, top, capCol);
                DevUi.frame(graphics, mx - halfW - 2, top - 5, halfW * 2 + 4, 6, 0xFFFFFFFF);
            }
            if (hover || (picked && badgeGroup == null)) {
                badgeGroup = group;
                badgeFromAdd = picked;
            }
        }
        double head = playhead();
        if (head >= 0) {
            int hx = (int) Math.round(head);
            graphics.fill(hx - 1, top - 3, hx + 1, top + LINE_H + 3, DevUi.ACCENT);
            double nowSec = view == View.SCENE ? sceneTime : time * seconds(shot);
            String nowLabel = formatSeconds(nowSec);
            int nowW = font.width(nowLabel);
            int rightLimit = lineRight() - endW - 10;
            int nowX = hx + 4 + nowW <= rightLimit ? hx + 4 : Math.max(lineLeft() + 2, hx - 4 - nowW);
            graphics.fill(nowX - 2, textY - 2, nowX + nowW + 2, textY + 9, 0xC81A140F);
            graphics.drawString(font, nowLabel, nowX, textY, DevUi.ACCENT, false);
        }
        boolean showingBadge = false;
        if (badgeGroup != null) {
            int badgeX = badgeGroup.x();
            JsonArray during = during(badgeGroup.shotIndex());
            int leadIdx = badgeGroup.shotIndex() == shot && badgeGroup.indices().contains(selectedCue)
                    ? selectedCue : badgeGroup.indices().getFirst();
            JsonObject leadCue = during.get(leadIdx).getAsJsonObject();
            long age = net.minecraft.Util.getMillis() - cueAddedAt;
            boolean flashing = badgeFromAdd && age >= 0 && age < 1800;
            if (flashing) {
                int cueCol = cueColor(cueKind(leadCue));
                double progress = age / 1800.0;
                double wave = 0.5 + 0.5 * Math.cos(age * 0.015);
                int alpha = (int) Math.round((1.0 - progress) * (140 + 90 * wave));
                int expand = 3 + (int) Math.round((1.0 - wave) * 3);
                graphics.fill(badgeX - expand, top - 4, badgeX + expand, top + LINE_H + 2, (alpha / 2 << 24) | (cueCol & 0x00FFFFFF));
                DevUi.frame(graphics, badgeX - expand - 1, top - 5, expand * 2 + 2, LINE_H + 8, (Math.min(255, alpha + 40) << 24) | 0x00E1B34D);
            }
            if (hoveredGroup != null || flashing) {
                showingBadge = true;
                Component badge = cueGroupBadgeLabel(badgeGroup);
                int bw = font.width(badge) + 10;
                int bh = 14;
                int bx = Mth.clamp(badgeX - bw / 2, lineLeft() - 2, lineRight() + 2 - bw);
                int by = top - 22;
                graphics.flush();
                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, 40);
                graphics.fill(bx, by, bx + bw, by + bh, 0xF01A140F);
                DevUi.frame(graphics, bx, by, bw, bh, DevUi.ACCENT);
                graphics.fill(badgeX - 1, by + bh, badgeX + 1, top - 5, DevUi.ACCENT);
                graphics.drawString(font, badge, bx + 5, by + 3, DevUi.ACCENT, false);
                graphics.flush();
                graphics.pose().popPose();
            }
        }
        return showingBadge;
    }

    private static String formatSeconds(double seconds) {
        double rounded = Math.round(seconds * 10) / 10.0;
        return Math.abs(rounded - Math.round(rounded)) < 0.05
                ? Math.round(rounded) + "s"
                : String.format(Locale.ROOT, "%.1fs", rounded);
    }

    private Component cueGroupBadgeLabel(CueGroup group) {
        JsonArray during = during(group.shotIndex());
        if (group.indices().size() == 1) {
            return cueBadgeLabel(during.get(group.indices().getFirst()).getAsJsonObject());
        }
        List<String> names = new java.util.ArrayList<>();
        for (int idx : group.indices()) {
            String kind = cueKind(during.get(idx).getAsJsonObject());
            names.add(DevUi.str("scene.cue." + kind));
        }
        return Component.literal("✦ " + String.join(" + ", names) + " · " + formatSeconds(group.at()));
    }

    private Component cueBadgeLabel(JsonObject cue) {
        String kind = cueKind(cue);
        String summary = "";
        if (kind.equals("title") || kind.equals("caption") || kind.equals("line")) {
            JsonObject inner = cue.get(kind) instanceof JsonObject obj ? obj : null;
            String text = inner != null ? ClientDevStore.readStoryText(inner, "text", "", ClientDevStore.activeLanguage()).trim() : "";
            String key = inner != null ? ClientDevStore.getKey(inner, "text") : null;
            if (!text.isEmpty() && !text.equals(key)) {
                summary = font.plainSubstrByWidth(text, 90);
                if (summary.length() < text.length()) {
                    summary += "…";
                }
            }
        }
        return Component.literal("✦ ").append(DevUi.text("scene.cue." + kind))
                .append(Component.literal((summary.isEmpty() ? "" : ": \"" + summary + "\"") + String.format(Locale.ROOT, " · %.1fs", cueAt(cue))));
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
        CueGroup hit = cueGroupAtX(mouseX, mouseY);
        if (hit != null) {
            if (button == 1) {
                pushUndo();
                DevSoundPreview.stop();
                int removeIdx = hit.shotIndex() == shot && hit.indices().contains(selectedCue)
                        ? selectedCue : hit.indices().getLast();
                during(hit.shotIndex()).remove(removeIdx);
                if (hit.shotIndex() == shot) {
                    List<Integer> remaining = cuesAtSecond(shot, hit.at());
                    selectedCue = remaining.isEmpty() ? -1 : remaining.getFirst();
                }
                changed();
                build();
                return;
            }
            pickShot(hit.shotIndex());
            if (hit.indices().contains(selectedCue)) {
                groupToCycle = hit.indices().size() > 1 ? hit.indices().get((hit.indices().indexOf(selectedCue) + 1) % hit.indices().size()) : -1;
            } else {
                selectedCue = hit.indices().getFirst();
                groupToCycle = -1;
            }
            panelTab = PanelTab.CUES;
            revealCue = true;
            showShot(seconds(shot) <= 0 ? 0 : hit.at() / seconds(shot));
            build();
            dragging = 5;
            dragMoved = false;
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
        scrubTo(mouseX);
        dragging = 6;
    }

    /** Picks the shot under the cursor and shows the moment there (and any thing at that moment). */
    private void scrubTo(double mouseX) {
        int index = shotAtX(mouseX);
        boolean shotChanged = index != shot;
        if (shotChanged) {
            pickShot(index);
        }
        playing = false;
        double sec = secondsAtX(shot, mouseX);
        showShot(seconds(shot) <= 0 ? 0 : sec / seconds(shot));
        int cue = cueAtMoment(shot, sec);
        if (shotChanged || cue != selectedCue) {
            selectedCue = cue;
            build();
        }
    }

    private void pickShot(int index) {
        if (index != shot) {
            shot = index;
            selectedCue = -1;
            selectedPoint = 0;
            hoveredPoint = -1;
            draggedPoint = -1;
            scroll = 0;
        }
    }

    /** Where the view's moment is on the time line. */
    private double playhead() {
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
        return lineX(shot, time * seconds(shot));
    }

    /**
     * Unified camera path control block: tabs for From, optional intermediate spline points (#1, #2, ...) and To in
     * flight order, plus one compact set of axis sliders for the currently selected point. From and To cannot be
     * removed; intermediate points can be inserted and removed freely between them.
     */
    private int pathRows(int x, int y) {
        List<Vec3> controls = controlPointsLocal(shot);
        int total = controls.size();
        int viaCount = total - 2;
        selectedPoint = Mth.clamp(selectedPoint, 0, total - 1);
        boolean removable = selectedPoint > 0 && selectedPoint < total - 1;
        pathLabelY = y;
        Button addBtn = addRenderableWidget(Button.builder(Component.literal("+"), b -> addViaPoint())
                .bounds(x + 118, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.path_add_tooltip"))).build());
        addBtn.active = viaCount < SceneDefinition.Camera.MAX_VIA;
        addRenderableWidget(Button.builder(Component.literal("⇄"), b -> {
            pushUndo();
            Vec3 start = localFrom(shot);
            camera().add("from", vec(localTo(shot)));
            camera().add("to", vec(start));
            List<Vec3> via = new java.util.ArrayList<>(localVia(shot));
            if (!via.isEmpty()) {
                java.util.Collections.reverse(via);
                writeVia(via);
            }
            List<Vec2> rots = new java.util.ArrayList<>(localRotations(shot));
            if (rots.size() >= 2) {
                java.util.Collections.reverse(rots);
                writeRotations(shot, rots);
            }
            if (camera().has("fov") && camera().has("fov_to")) {
                float a = shotFov(shot);
                float c = shotFovTo(shot);
                camera().addProperty("fov", c);
                camera().addProperty("fov_to", a);
            }
            if (camera().has("roll") && camera().has("roll_to")) {
                float a = shotRoll(shot);
                float c = shotRollTo(shot, a);
                camera().addProperty("roll", c);
                camera().addProperty("roll_to", a);
            }
            selectedPoint = (total - 1) - selectedPoint;
            if (view != View.FREE) {
                enterPilot();
            }
            changed();
            build();
        }).bounds(x + 139, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.swap_tooltip"))).build());
        captureButton = addRenderableWidget(Button.builder(Component.literal("📷").withColor(view == View.FREE ? DevUi.ACCENT : DevUi.LABEL), b -> captureFromHere())
                .bounds(x + 160, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.here_tooltip"))).build());
        captureButton.active = view == View.FREE;
        Button removeBtn = addRenderableWidget(Button.builder(Component.literal("✕"), b -> removeSelectedVia())
                .bounds(x + 181, y, 19, 19).tooltip(Tooltip.create(DevUi.text("scene_view.via_remove"))).build());
        removeBtn.active = removable;
        y += 22;
        int cols = Math.min(4, total);
        int gap = 4;
        int tabW = (200 - (cols - 1) * gap) / cols;
        for (int n = 0; n < total; n++) {
            int idx = n;
            boolean isFrom = idx == 0;
            boolean isTo = idx == total - 1;
            boolean current = idx == selectedPoint;
            int col = n % cols;
            int row = n / cols;
            int bw = col == cols - 1 ? 200 - col * (tabW + gap) : tabW;
            String name = isFrom ? DevUi.str("scene_view.from") : isTo ? DevUi.str("scene_view.to") : "#" + idx;
            int color = current ? DevUi.ACCENT : isFrom ? START : isTo ? END : VIA;
            Component tabLabel = Component.literal((current ? "• " : "") + name).withColor(color);
            Component tip = isFrom ? DevUi.text("scene_view.from") : isTo ? DevUi.text("scene_view.to") : DevUi.text("scene_view.via_point", idx);
            addRenderableWidget(Button.builder(tabLabel, b -> {
                selectedPoint = idx;
                if (view != View.FREE) {
                    enterPilot();
                }
                build();
            }).bounds(x + col * (tabW + gap), y + row * 18, bw, 16).tooltip(Tooltip.create(tip)).build());
        }
        y += ((total + cols - 1) / cols) * 18 + 2;
        pathAxesY = y;
        Vec3 value = controls.get(selectedPoint);
        double[] parts = {value.x, value.y, value.z};
        for (int i = 0; i < 3; i++) {
            int axis = i;
            AxisSlider slider = addRenderableWidget(new AxisSlider(x + 14, y, 138, 16, axis, parts[i]));
            slider.setTooltip(Tooltip.create(DevUi.text("scene_view.axis_" + i)));
            pointSliders[i] = slider;
            EditBox box = addRenderableWidget(new EditBox(font, x + 156, y, 44, 16, Component.empty()));
            box.setValue(String.format(Locale.ROOT, "%.2f", parts[i]));
            box.setTooltip(Tooltip.create(DevUi.text("scene_view.nudge_tooltip")));
            box.setResponder(text -> {
                if (filling) {
                    return;
                }
                double number = parse(text, Double.NaN);
                if (!Double.isNaN(number)) {
                    setAxis(axis, number);
                    pointSliders[axis].show(number);
                }
            });
            pointBoxes[i] = box;
            y += 18;
        }
        return y + 4;
    }

    private void addViaPoint() {
        List<Vec3> via = new java.util.ArrayList<>(localVia(shot));
        if (via.size() >= SceneDefinition.Camera.MAX_VIA) {
            return;
        }
        pushUndo();
        int insertAt = Mth.clamp(selectedPoint, 0, via.size());
        Vec3 added;
        double u = 0.5;
        if (view == View.FREE) {
            added = local(shot, viewPos);
        } else {
            ScenePath path = shotPath(shot);
            if (via.isEmpty()) {
                u = time > 0.08 && time < 0.92 ? ease(shot).apply(time) : 0.5;
            } else {
                u = (path.parameterOfControl(insertAt) + path.parameterOfControl(insertAt + 1)) * 0.5;
            }
            Vec3 worldPoint = path.pointAt(u);
            if (path.length() < 0.5) {
                worldPoint = worldPoint.add(0, 1.5, 0);
            }
            added = local(shot, worldPoint);
        }
        if (look(shot).equals("free")) {
            List<Vec2> rots = new java.util.ArrayList<>(localRotations(shot));
            if (rots.size() == via.size() + 2) {
                Vec2 insertedRot = view == View.FREE
                        ? new Vec2(Mth.wrapDegrees((float) viewYaw - anchor(shot).yaw()), Mth.clamp((float) viewPitch, -89.9f, 89.9f))
                        : new Vec2(Mth.wrapDegrees(shotPose(shot, u, 0f).yaw() - anchor(shot).yaw()), shotPose(shot, u, 0f).pitch());
                rots.add(insertAt + 1, insertedRot);
                writeRotations(shot, rots);
            }
        }
        via.add(insertAt, added);
        writeVia(via);
        selectedPoint = insertAt + 1;
        changed();
        if (view != View.FREE) {
            enterPilot();
        }
        build();
    }

    private void removeSelectedVia() {
        List<Vec3> via = new java.util.ArrayList<>(localVia(shot));
        int viaIndex = selectedPoint - 1;
        if (viaIndex < 0 || viaIndex >= via.size()) {
            return;
        }
        pushUndo();
        if (look(shot).equals("free")) {
            List<Vec2> rots = new java.util.ArrayList<>(localRotations(shot));
            if (selectedPoint < rots.size()) {
                rots.remove(selectedPoint);
                writeRotations(shot, rots);
            }
        }
        via.remove(viaIndex);
        writeVia(via);
        selectedPoint = via.isEmpty() ? 0 : Mth.clamp(selectedPoint, 1, via.size());
        hoveredPoint = -1;
        changed();
        if (view != View.FREE) {
            enterPilot();
        }
        build();
    }

    /** One axis of the selected control point changed (a slider, a number): written, and the view shows that point. */
    private void setAxis(int axis, double number) {
        pushUndoDebounced();
        List<Vec3> controls = controlPointsLocal(shot);
        int idx = Mth.clamp(selectedPoint, 0, controls.size() - 1);
        Vec3 old = controls.get(idx);
        Vec3 updated = axis == 0 ? new Vec3(number, old.y, old.z) : axis == 1 ? new Vec3(old.x, number, old.z) : new Vec3(old.x, old.y, number);
        if (axis == 1) {
            releaseAboveGroundIfBlocking(idx, world(shot, updated));
        }
        setControlPointLocal(idx, updated);
        changed();
        if (view != View.FREE) {
            enterPilot();
        }
    }

    /**
     * A slider for one axis of the selected control point. Its range is +-16 blocks around the anchor, wider (32, 64,
     * 128) once a value reaches its edge and is let go, so small moves stay fine and far points stay reachable.
     */
    private final class AxisSlider extends AbstractSliderButton {
        private final int axis;
        private double range;

        AxisSlider(int x, int y, int width, int height, int axis, double at) {
            super(x, y, width, height, Component.empty(), 0.5);
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
            setAxis(axis, number);
            filling = true;
            EditBox box = pointBoxes[axis];
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

    /** Shot FOV slider (20 to 120 degrees); edits {@code fov_to} when viewing To on a zooming shot, else {@code fov}. */
    private final class FovSlider extends AbstractSliderButton {
        FovSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.5);
            int shown = currentDegrees();
            this.value = toSlider(shown);
            updateMessage();
        }

        private boolean editingEnd() {
            return selectedPoint == controlPointsLocal(shot).size() - 1;
        }

        private int currentDegrees() {
            if (editingEnd() && camera().has("fov_to")) {
                return Math.round(shotFovTo(shot));
            }
            return camera().has("fov") ? Math.round(shotFov(shot)) : playerFov();
        }

        private static int playerFov() {
            Minecraft minecraft = Minecraft.getInstance();
            int raw = minecraft != null && minecraft.options != null ? minecraft.options.fov().get() : 70;
            return Mth.clamp(raw, (int) SceneDefinition.Camera.MIN_FOV, (int) SceneDefinition.Camera.MAX_FOV);
        }

        private static double toSlider(int degrees) {
            return Mth.clamp((degrees - SceneDefinition.Camera.MIN_FOV) / (double) (SceneDefinition.Camera.MAX_FOV - SceneDefinition.Camera.MIN_FOV), 0.0, 1.0);
        }

        private int degrees() {
            return (int) Math.round(SceneDefinition.Camera.MIN_FOV + value * (SceneDefinition.Camera.MAX_FOV - SceneDefinition.Camera.MIN_FOV));
        }

        void nudge(int delta) {
            int next = Mth.clamp(currentDegrees() + delta, (int) SceneDefinition.Camera.MIN_FOV, (int) SceneDefinition.Camera.MAX_FOV);
            value = toSlider(next);
            applyValue();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (editingEnd()) {
                if (camera().has("fov_to")) {
                    setMessage(DevUi.text("scene_view.fov_end_value", Math.round(shotFovTo(shot))));
                } else if (camera().has("fov")) {
                    setMessage(DevUi.text("scene_view.fov_end_value", Math.round(shotFov(shot))));
                } else {
                    setMessage(DevUi.text("scene_view.fov_default"));
                }
            } else if (camera().has("fov")) {
                setMessage(DevUi.text("scene_view.fov_value", Math.round(shotFov(shot))));
            } else {
                setMessage(DevUi.text("scene_view.fov_default"));
            }
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            drawSlider(graphics, this, value);
        }

        @Override
        protected void applyValue() {
            pushUndoDebounced();
            camera().addProperty(editingEnd() ? "fov_to" : "fov", degrees());
            changed();
            if (fovResetButton != null) {
                fovResetButton.active = true;
            }
            updateMessage();
        }
    }

    /** Terrain clearance slider ({@code above_ground}: 0 = off, up to 32 blocks above terrain, trees, and roofs). */
    private final class AboveGroundSlider extends AbstractSliderButton {
        private static final double MAX = 32.0;

        AboveGroundSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.0);
            sync();
        }

        void sync() {
            this.value = Mth.clamp(shotAboveGround(shot) / MAX, 0.0, 1.0);
            updateMessage();
        }

        private double blocks() {
            return Math.round(value * MAX * 2.0) / 2.0;
        }

        void nudge(double delta) {
            double next = Mth.clamp(shotAboveGround(shot) + delta, 0.0, MAX);
            value = Mth.clamp(next / MAX, 0.0, 1.0);
            applyValue();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float current = shotAboveGround(shot);
            if (current > 0f) {
                setMessage(DevUi.text("scene_view.above_ground_value", String.format(Locale.ROOT, "%.1f", current)));
            } else {
                setMessage(DevUi.text("scene_view.above_ground_off"));
            }
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            drawSlider(graphics, this, value);
        }

        @Override
        protected void applyValue() {
            pushUndoDebounced();
            double h = blocks();
            if (h <= 0.05) {
                camera().remove("above_ground");
            } else {
                camera().addProperty("above_ground", h);
            }
            changed();
            if (aboveGroundResetButton != null) {
                aboveGroundResetButton.active = h > 0.05;
            }
            updateMessage();
        }
    }

    /** Horizon tilt slider (-30 to +30 degrees); edits {@code roll_to} when viewing To on a tilting shot, else {@code roll}. */
    private final class RollSlider extends AbstractSliderButton {
        private static final double MAX = SceneDefinition.Camera.MAX_ROLL;

        RollSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.5);
            this.value = toSlider(currentDegrees());
            updateMessage();
        }

        private boolean editingEnd() {
            return selectedPoint == controlPointsLocal(shot).size() - 1;
        }

        private double currentDegrees() {
            float start = shotRoll(shot);
            return editingEnd() && camera().has("roll_to") ? shotRollTo(shot, start) : start;
        }

        private static double toSlider(double deg) {
            return Mth.clamp((deg / MAX + 1.0) / 2.0, 0.0, 1.0);
        }

        private double degrees() {
            return Math.round((value * 2.0 - 1.0) * MAX * 2.0) / 2.0;
        }

        void nudge(double delta) {
            double next = Mth.clamp(currentDegrees() + delta, -MAX, MAX);
            value = toSlider(next);
            applyValue();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double deg = currentDegrees();
            if (editingEnd()) {
                if (camera().has("roll_to") || Math.abs(deg) > 0.05 || camera().has("roll")) {
                    setMessage(DevUi.text("scene_view.roll_end_value", String.format(Locale.ROOT, "%.1f", deg)));
                } else {
                    setMessage(DevUi.text("scene_view.roll_off"));
                }
            } else if (Math.abs(deg) > 0.05 || camera().has("roll")) {
                setMessage(DevUi.text("scene_view.roll_value", String.format(Locale.ROOT, "%.1f", deg)));
            } else {
                setMessage(DevUi.text("scene_view.roll_off"));
            }
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            drawSlider(graphics, this, value);
        }

        @Override
        protected void applyValue() {
            pushUndoDebounced();
            double deg = degrees();
            if (editingEnd()) {
                camera().addProperty("roll_to", deg);
            } else if (Math.abs(deg) < 0.05 && !camera().has("roll_to")) {
                camera().remove("roll");
            } else {
                camera().addProperty("roll", deg);
            }
            changed();
            if (rollResetButton != null) {
                rollResetButton.active = camera().has("roll") || camera().has("roll_to");
            }
            updateMessage();
        }
    }

    /** Continuous handheld breathing slider ({@code shake}: 0 = off, up to 0.30). */
    private final class ShakeSlider extends AbstractSliderButton {
        private static final double MAX = 0.30;

        ShakeSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.0);
            this.value = Mth.clamp(shotShake(shot) / MAX, 0.0, 1.0);
            updateMessage();
        }

        private double amount() {
            return Math.round(value * MAX * 100.0) / 100.0;
        }

        void nudge(double delta) {
            double next = Mth.clamp(shotShake(shot) + delta, 0.0, MAX);
            value = Mth.clamp(next / MAX, 0.0, 1.0);
            applyValue();
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float cur = shotShake(shot);
            if (cur > 0.001f) {
                setMessage(DevUi.text("scene_view.shake_value", String.format(Locale.ROOT, "%.2f", cur)));
            } else {
                setMessage(DevUi.text("scene_view.shake_off"));
            }
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            drawSlider(graphics, this, value);
        }

        @Override
        protected void applyValue() {
            pushUndoDebounced();
            double s = amount();
            if (s <= 0.004) {
                camera().remove("shake");
            } else {
                camera().addProperty("shake", s);
            }
            changed();
            if (shakeResetButton != null) {
                shakeResetButton.active = s > 0.004;
            }
            updateMessage();
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
        List<Vec3> controls = controlPointsLocal(shot);
        int idx = Mth.clamp(selectedPoint, 0, controls.size() - 1);
        refreshPointRow(controls.get(idx), pointBoxes, pointSliders);
        filling = false;
    }

    private static void refreshPointRow(Vec3 point, EditBox[] boxes, AxisSlider[] sliders) {
        double[] values = {point.x, point.y, point.z};
        for (int i = 0; i < 3; i++) {
            if (boxes[i] != null && !boxes[i].isFocused()) {
                boxes[i].setValue(String.format(Locale.ROOT, "%.2f", values[i]));
            }
            if (sliders[i] != null) {
                sliders[i].show(values[i]);
            }
        }
    }

    private void playShot() {
        if (talk(shot)) {
            playing = false;
            stopPreviewAudio();
            return;
        }
        if (view != View.SHOT || time >= 1) {
            time = 0;
        }
        view = View.SHOT;
        playing = !playing || time == 0;
        lastFrame = 0;
        stopPreviewAudio();
        if (playing) {
            DevSoundPreview.stop();
        }
    }

    private void playScene() {
        if (view != View.SCENE || sceneTime >= sceneSeconds()) {
            sceneTime = 0;
        }
        view = View.SCENE;
        playing = true;
        lastFrame = 0;
        stopPreviewAudio();
        DevSoundPreview.stop();
    }

    private void switchShot(int by) {
        shot = Mth.clamp(shot + by, 0, shots().size() - 1);
        playing = false;
        selectedPoint = 0;
        hoveredPoint = -1;
        draggedPoint = -1;
        scroll = 0;
        if (view != View.FREE) {
            if (talk(shot)) {
                view = View.SHOT;
                time = 0;
            } else {
                enterPilot();
            }
        }
        selectedCue = cueAtMoment(shot, time * seconds(shot));
        build();
    }

    private void addShot() {
        pushUndo();
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
        pushUndo();
        JsonObject copy = shotJson(shot).deepCopy();
        rekeyCopiedCues(copy, shot + 1);
        shots().add(copy);
        moveLastTo(shot + 1);
    }

    /** Gives copied cue texts their own translation keys so editing the copy never overwrites the original shot's text. */
    private void rekeyCopiedCues(JsonObject copy, int targetShotIndex) {
        if (!(copy.get("during") instanceof JsonArray during)) {
            return;
        }
        String language = ClientDevStore.activeLanguage();
        for (int i = 0; i < during.size(); i++) {
            if (!(during.get(i) instanceof JsonObject cue)) {
                continue;
            }
            for (String kind : new String[] {"title", "caption", "line"}) {
                if (!(cue.get(kind) instanceof JsonObject inner)) {
                    continue;
                }
                for (String field : new String[] {"text", "subtitle", "speaker"}) {
                    String existingKey = ClientDevStore.getKey(inner, field);
                    if (existingKey == null || existingKey.isBlank()) {
                        continue;
                    }
                    String text = ClientDevStore.readStoryText(inner, field, "", language);
                    String base = ClientDevStore.canonicalKey(ClientDevStore.TYPE_SCENE, id, "shot" + targetShotIndex + ".cue" + i + "." + kind + "." + field);
                    String allJson = scene.toString() + copy;
                    String freshKey = base;
                    for (int n = 2; allJson.contains("\"" + freshKey + "\""); n++) {
                        freshKey = base + "_" + n;
                    }
                    inner.addProperty(field, freshKey);
                    if (!text.equals(existingKey)) {
                        ClientDevStore.writeStoryText(inner, field, text, freshKey);
                    }
                }
            }
        }
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
        selectedPoint = 0;
        hoveredPoint = -1;
        draggedPoint = -1;
        scroll = 0;
        changed();
        askAnchors();
        if (talk(shot)) {
            showShot(0);
        } else {
            enterPilot();
        }
        build();
    }

    private void removeShot() {
        JsonArray shots = shots();
        if (shots.size() <= 1) {
            return;
        }
        pushUndo();
        shots.remove(shot);
        shot = Mth.clamp(shot, 0, shots.size() - 1);
        selectedCue = -1;
        selectedPoint = 0;
        hoveredPoint = -1;
        draggedPoint = -1;
        scroll = 0;
        changed();
        askAnchors();
        if (talk(shot)) {
            showShot(0);
        } else {
            enterPilot();
        }
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
            if (!panelShown) {
                panelShown = true;
                build();
            }
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
            if (helpShown) {
                helpShown = false;
                build();
                return true;
            }
            if (typing()) {
                setFocused(null);
                return true;
            }
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F1) {
            helpShown = !helpShown;
            build();
            return true;
        }
        if (helpShown) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_S && hasControlDown()) {
            save();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_Z && hasControlDown() && !typing()) {
            if (hasShiftDown()) {
                redo();
            } else {
                undo();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_Y && hasControlDown() && !typing()) {
            redo();
            return true;
        }
        if (!typing()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_SPACE -> {
                    // In In-camera (Pilot) and Outside (3D) flight modes, Space is Minecraft's fly-up key!
                    if (view == View.PILOT || view == View.FREE) {
                        return true;
                    }
                    if (view == View.SCENE) {
                        playing = !playing;
                        if (playing) {
                            DevSoundPreview.stop();
                        } else {
                            stopPreviewAudio();
                        }
                    } else {
                        playShot();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    if (view == View.SCENE) {
                        playing = !playing;
                        if (playing) {
                            DevSoundPreview.stop();
                        } else {
                            stopPreviewAudio();
                        }
                    } else {
                        playShot();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                    double step = (keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1) * (hasShiftDown() ? 1.0 : 0.1);
                    if (view == View.SCENE) {
                        sceneTime = Mth.clamp(sceneTime + step, 0, sceneSeconds());
                    } else if (!talk(shot) && seconds(shot) > 0) {
                        showShot(time + step / seconds(shot));
                        int cue = cueAtMoment(shot, time * seconds(shot));
                        if (cue != selectedCue) {
                            selectedCue = cue;
                            build();
                        }
                    }
                    playing = false;
                    return true;
                }
                case GLFW.GLFW_KEY_TAB -> {
                    if (view == View.FREE) {
                        if (talk(shot)) {
                            showShot(time);
                        } else {
                            enterPilot();
                        }
                    } else {
                        goFree();
                    }
                    build();
                    return true;
                }
                case GLFW.GLFW_KEY_P -> {
                    if (!talk(shot)) {
                        enterPilot();
                        build();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_K -> {
                    if (!talk(shot)) {
                        captureFromHere();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_RIGHT_BRACKET -> {
                    if (!talk(shot)) {
                        int count = controlPointsLocal(shot).size();
                        int dir = keyCode == GLFW.GLFW_KEY_LEFT_BRACKET ? -1 : 1;
                        selectedPoint = Math.floorMod(selectedPoint + dir, count);
                        if (view != View.FREE) {
                            enterPilot();
                        }
                        build();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_F -> {
                    focus();
                    build();
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
                    // The movement keys fly the free view or pilot camera; read each frame, not here.
                }
            }
            if ((view == View.FREE || view == View.PILOT) && isMovementKey(keyCode)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static boolean isMovementKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_E || keyCode == GLFW.GLFW_KEY_Q) {
            return true;
        }
        var options = Minecraft.getInstance().options;
        for (KeyMapping key : new KeyMapping[] {options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump, options.keyShift, options.keySprint}) {
            if (key.getKey().getType() == InputConstants.Type.KEYSYM && key.getKey().getValue() == keyCode) {
                return true;
            }
        }
        return false;
    }

    /** Control point handle under the cursor: 0 = From, 1..N = Via, N+1 = To, or -1 for none. */
    private int handleAt(double mouseX, double mouseY) {
        int found = -1;
        double best = HANDLE_REACH;
        List<Vec3> rendered = pathControlPositions(shot);
        Vec3 activeCam = view == View.SHOT ? shotPose(shot, time, 0).pos() : viewPos;
        for (int idx = 0; idx < rendered.size(); idx++) {
            Vec3 point = rendered.get(idx);
            if (view == View.SHOT && point.distanceTo(activeCam) < 0.35) {
                continue;
            }
            float[] screen = ScreenProjection.project(point, width, height);
            if (screen != null) {
                double distance = Math.hypot(screen[0] - mouseX, screen[1] - mouseY);
                if (distance < best) {
                    best = distance;
                    found = idx;
                }
            }
        }
        return found;
    }

    private boolean overTopOverlayButton(double mouseX, double mouseY) {
        return (collapseButton != null && collapseButton.isMouseOver(mouseX, mouseY))
                || (!panelShown && ((backButton != null && backButton.isMouseOver(mouseX, mouseY))
                || (undoButton != null && undoButton.isMouseOver(mouseX, mouseY))
                || (redoButton != null && redoButton.isMouseOver(mouseX, mouseY))));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (catalog.isOpen()) {
            return super.mouseClicked(mouseX, mouseY, button) || catalog.mouseClicked(this, mouseX, mouseY);
        }
        if (mouseX < left() || overTopOverlayButton(mouseX, mouseY)) {
            boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (getFocused() != null && !children().contains(getFocused())) {
                setFocused(null);
                setDragging(false);
            }
            return handled;
        }
        if (helpShown) {
            helpShown = false;
            build();
            return true;
        }
        setFocused(null);
        if (mouseY >= lineTop() - 2 && mouseY <= lineTop() + LINE_H + 2 && mouseX >= lineLeft() - 2 && mouseX <= lineRight() + 2) {
            lineClicked(mouseX, mouseY, button);
            return true;
        }
        int handle = button == 0 && !talk(shot) && view != View.PILOT ? handleAt(mouseX, mouseY) : -1;
        if (handle >= 0) {
            goFree();
            dragging = DRAG_POINT;
            draggedPoint = handle;
            hoveredPoint = handle;
            if (selectedPoint != handle || panelTab != PanelTab.POINT) {
                selectedPoint = handle;
                panelTab = PanelTab.POINT;
                build();
            }
        } else if (button == 1) {
            dragging = DRAG_LOOK;
        } else if (button == 2 || (button == 0 && hasShiftDown())) {
            dragging = DRAG_PAN;
        } else {
            dragging = DRAG_ORBIT;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging != 0) {
            if (dragging == DRAG_CUE && !dragMoved && groupToCycle >= 0) {
                selectedCue = groupToCycle;
            }
            groupToCycle = -1;
            if (dragging == DRAG_POINT || dragging == DRAG_CUE) {
                build();
            }
            dragging = 0;
            draggedPoint = -1;
            dragMoved = false;
            return true;
        }
        if (getFocused() instanceof AxisSlider slider && !slider.isMouseOver(mouseX, mouseY)) {
            slider.onRelease(mouseX, mouseY);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging == 0) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (dragging == DRAG_CUE) {
            if (selectedCue() instanceof JsonObject cue) {
                double target = Math.round(secondsAtX(shot, mouseX) * 10) / 10.0;
                if (Math.abs(target - cueAt(cue)) >= 0.08
                        && !cueKindsAtSecond(shot, target, selectedCue).contains(cueKind(cue))) {
                    pushUndoDebounced();
                    dragMoved = true;
                    cue.addProperty("at", target);
                    showShot(seconds(shot) <= 0 ? 0 : target / seconds(shot));
                    changed();
                }
            }
            return true;
        }
        if (dragging == DRAG_SCRUB) {
            scrubTo(mouseX);
            return true;
        }
        if (view == View.PILOT && !talk(shot)) {
            pushUndoDebounced();
            if (dragging == DRAG_PAN) {
                SceneCamera.Pose cur = shotPose(shot, timeForControlPoint(shot, selectedPoint), 0f);
                double fov = Math.toRadians(cur.fov() > 0f ? cur.fov() : FovSlider.playerFov());
                double dist = Math.max(2.0, cur.pos().distanceTo(anchorTarget(shot, 0f)));
                double perPixel = dist * 2 * Math.tan(fov / 2) / Math.max(1, height);
                Vec3 side = right(cur.yaw());
                Vec3 up = side.cross(direction(cur.yaw(), cur.pitch())).normalize();
                Vec3 next = cur.pos().add(side.scale(-dragX * perPixel)).add(up.scale(dragY * perPixel));
                releaseAboveGroundIfBlocking(selectedPoint, next);
                setControlPointLocal(selectedPoint, local(shot, next));
                time = timeForControlPoint(shot, selectedPoint);
                changed();
                refreshPoints();
            } else if (dragging == DRAG_LOOK || look(shot).equals("free")) {
                boolean wasFree = look(shot).equals("free");
                SceneCamera.Pose cur = shotPose(shot, timeForControlPoint(shot, selectedPoint), 0f);
                setRotationWorld(shot, selectedPoint, cur.yaw() + (float) (dragX * 0.25), cur.pitch() + (float) (dragY * 0.25));
                changed();
                if (!wasFree) {
                    status(DevUi.text("scene_view.unlocked_free_look"), DevUi.ACCENT);
                    build();
                } else {
                    refreshRotationBoxes();
                }
            } else {
                // Left-drag in Pilot while tracking an anchor: Blender turntable orbit around the anchor (non-inverted).
                Vec3 center = anchorTarget(shot, 0f);
                Vec3 pos = pathControlPositions(shot).get(Mth.clamp(selectedPoint, 0, controlPointsLocal(shot).size() - 1));
                Vec3 offset = pos.subtract(center);
                double dist = Math.max(0.5, offset.length());
                double yaw = Math.toDegrees(Math.atan2(-offset.x, offset.z)) + dragX * 0.35;
                double pitch = Mth.clamp(Math.toDegrees(Math.asin(Mth.clamp(offset.y / dist, -1, 1))) + dragY * 0.35, -85, 85);
                double yRad = Math.toRadians(yaw);
                double pRad = Math.toRadians(pitch);
                Vec3 next = center.add(new Vec3(-Math.sin(yRad) * Math.cos(pRad), Math.sin(pRad), Math.cos(yRad) * Math.cos(pRad)).scale(dist));
                releaseAboveGroundIfBlocking(selectedPoint, next);
                setControlPointLocal(selectedPoint, local(shot, next));
                time = timeForControlPoint(shot, selectedPoint);
                changed();
                refreshPoints();
            }
            return true;
        }
        goFree();
        if (dragging == DRAG_POINT) {
            pushUndoDebounced();
            moveHandle(draggedPoint, dragX, dragY);
        } else if (dragging == DRAG_PAN) {
            pan(dragX, dragY);
        } else if (dragging == DRAG_ORBIT) {
            orbit(dragX, dragY);
        } else {
            viewYaw += dragX * 0.3;
            viewPitch = Mth.clamp(viewPitch + dragY * 0.3, -89, 89);
            pivot = viewPos.add(direction(viewYaw, viewPitch).scale(Math.max(1, pivot.distanceTo(viewPos))));
        }
        return true;
    }

    /** Blender-style turntable orbit around pivot: dragging right/down turns the scene right/down with the cursor. */
    private void orbit(double dragX, double dragY) {
        Vec3 offset = viewPos.subtract(pivot);
        double distance = Math.max(0.3, offset.length());
        double yaw = Math.toDegrees(Math.atan2(-offset.x, offset.z)) + dragX * 0.4;
        double pitch = Mth.clamp(Math.toDegrees(Math.asin(Mth.clamp(offset.y / distance, -1, 1))) + dragY * 0.4, -89, 89);
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        viewPos = pivot.add(new Vec3(-Math.sin(y) * Math.cos(p), Math.sin(p), Math.cos(y) * Math.cos(p)).scale(distance));
        lookAt(pivot);
    }

    /** Blender-style viewport pan (Shift+LMB or MMB): slides camera and pivot in the screen plane with the cursor. */
    private void pan(double dragX, double dragY) {
        double distance = Math.max(1.0, viewPos.distanceTo(pivot));
        double fov = Math.toRadians(Minecraft.getInstance().options.fov().get());
        double perPixel = distance * 2 * Math.tan(fov / 2) / Math.max(1, height);
        Vec3 side = right((float) viewYaw);
        Vec3 up = side.cross(direction(viewYaw, viewPitch)).normalize();
        Vec3 step = side.scale(-dragX * perPixel).add(up.scale(dragY * perPixel));
        viewPos = viewPos.add(step);
        pivot = pivot.add(step);
    }

    /** Moves a control-point handle with the cursor across the view; with Shift, over the ground. */
    private void moveHandle(int pointIndex, double dragX, double dragY) {
        List<Vec3> rendered = pathControlPositions(shot);
        if (pointIndex < 0 || pointIndex >= rendered.size()) {
            return;
        }
        Vec3 point = rendered.get(pointIndex);
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
        releaseAboveGroundIfBlocking(pointIndex, moved);
        setControlPointLocal(pointIndex, local(shot, moved));
        changed();
        refreshPoints();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (catalog.isOpen()) {
            catalog.scroll(scrollY);
            return true;
        }
        if (helpShown || overTopOverlayButton(mouseX, mouseY)) {
            return true;
        }
        if (mouseX >= left()) {
            if (hasAltDown() && !talk(shot) && (view == View.PILOT || view == View.SHOT)) {
                int delta = (int) -Math.signum(scrollY) * (hasShiftDown() ? 5 : 2);
                if (fovSlider != null) {
                    fovSlider.nudge(delta);
                } else {
                    pushUndoDebounced();
                    boolean end = selectedPoint == controlPointsLocal(shot).size() - 1;
                    int cur = end && camera().has("fov_to") ? Math.round(shotFovTo(shot))
                            : camera().has("fov") ? Math.round(shotFov(shot)) : FovSlider.playerFov();
                    camera().addProperty(end ? "fov_to" : "fov", Mth.clamp(cur + delta, (int) SceneDefinition.Camera.MIN_FOV, (int) SceneDefinition.Camera.MAX_FOV));
                    changed();
                }
                return true;
            }
            if (view == View.PILOT && !talk(shot)) {
                pushUndoDebounced();
                SceneCamera.Pose cur = shotPose(shot, timeForControlPoint(shot, selectedPoint), 0f);
                double step = scrollY * (hasShiftDown() ? 1.2 : 0.45);
                Vec3 next = cur.pos().add(direction(cur.yaw(), cur.pitch()).scale(step));
                releaseAboveGroundIfBlocking(selectedPoint, next);
                setControlPointLocal(selectedPoint, local(shot, next));
                time = timeForControlPoint(shot, selectedPoint);
                changed();
                refreshPoints();
                return true;
            }
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
        // The wheel over a number or axis slider nudges it: 0.1, with Shift 1.
        for (int i = 0; i < pointBoxes.length; i++) {
            EditBox box = pointBoxes[i];
            AxisSlider slider = pointSliders[i];
            if ((box != null && box.visible && box.isMouseOver(mouseX, mouseY))
                    || (slider != null && slider.visible && slider.isMouseOver(mouseX, mouseY))) {
                double base = box != null ? parse(box.getValue(), 0) : 0;
                double value = base + Math.signum(scrollY) * (hasShiftDown() ? 1 : 0.1);
                if (box != null) {
                    box.setValue(String.format(Locale.ROOT, "%.2f", value));
                }
                return true;
            }
        }
        if (lengthBox != null && lengthBox.visible && lengthBox.isMouseOver(mouseX, mouseY)) {
            double max = bySpeed() ? 200.0 : 120.0;
            double next = Mth.clamp(parse(lengthBox.getValue(), 1.0) + Math.signum(scrollY) * (hasShiftDown() ? 1.0 : 0.1), 0.05, max);
            lengthBox.setValue(String.format(Locale.ROOT, "%.2f", next));
            return true;
        }
        if (fovSlider != null && fovSlider.visible && fovSlider.isMouseOver(mouseX, mouseY)) {
            fovSlider.nudge((int) Math.signum(scrollY) * (hasShiftDown() ? 5 : 1));
            return true;
        }
        if (aboveGroundSlider != null && aboveGroundSlider.visible && aboveGroundSlider.isMouseOver(mouseX, mouseY)) {
            aboveGroundSlider.nudge(Math.signum(scrollY) * (hasShiftDown() ? 2.0 : 0.5));
            return true;
        }
        if (rollSlider != null && rollSlider.visible && rollSlider.isMouseOver(mouseX, mouseY)) {
            rollSlider.nudge(Math.signum(scrollY) * (hasShiftDown() ? 2.0 : 0.5));
            return true;
        }
        if (shakeSlider != null && shakeSlider.visible && shakeSlider.isMouseOver(mouseX, mouseY)) {
            shakeSlider.nudge(Math.signum(scrollY) * (hasShiftDown() ? 0.05 : 0.01));
            return true;
        }
        for (int i = 0; i < rotBoxes.length; i++) {
            EditBox box = rotBoxes[i];
            if (box != null && box.visible && box.isMouseOver(mouseX, mouseY)) {
                double step = hasShiftDown() ? 5.0 : 1.0;
                double next = parse(box.getValue(), 0.0) + Math.signum(scrollY) * step;
                if (i == 1) {
                    next = Mth.clamp(next, -89.9, 89.9);
                }
                box.setValue(String.format(Locale.ROOT, "%.1f", next));
                return true;
            }
        }
        for (EditBox box : lookOffsetBoxes) {
            if (box != null && box.visible && box.isMouseOver(mouseX, mouseY)) {
                double next = parse(box.getValue(), 0.0) + Math.signum(scrollY) * (hasShiftDown() ? 0.5 : 0.1);
                box.setValue(String.format(Locale.ROOT, "%.2f", next));
                return true;
            }
        }
        double[] shakeMins = {0.01, 0.05};
        double[] shakeMaxes = {2.0, 10.0};
        for (int i = 0; i < shakeBoxes.length; i++) {
            EditBox box = shakeBoxes[i];
            if (box != null && box.visible && box.isMouseOver(mouseX, mouseY)) {
                double step = i == 0 ? (hasShiftDown() ? 0.2 : 0.05) : (hasShiftDown() ? 0.5 : 0.1);
                double next = Mth.clamp(parse(box.getValue(), 0.2) + Math.signum(scrollY) * step, shakeMins[i], shakeMaxes[i]);
                box.setValue(String.format(Locale.ROOT, "%.2f", next));
                return true;
            }
        }
        double[] fadeMaxes = {ScreenFade.MAX_EDGE, ScreenFade.MAX_STAY, ScreenFade.MAX_EDGE};
        for (int i = 0; i < fadeBoxes.length; i++) {
            EditBox box = fadeBoxes[i];
            if (box != null && box.visible && box.isMouseOver(mouseX, mouseY)) {
                double next = Mth.clamp(parse(box.getValue(), 0) + Math.signum(scrollY) * (hasShiftDown() ? 0.5 : 0.1), 0, fadeMaxes[i]);
                box.setValue(String.format(Locale.ROOT, "%.1f", next));
                return true;
            }
        }
        // Elsewhere over the panel's middle the wheel scrolls it, when it is taller than the screen.
        int top = contentTop();
        int most = Math.max(0, contentHeight - (contentBottom() - top));
        if (mouseY >= top && mouseY < contentBottom() && most > 0) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY) * 18, 0, most);
            build();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        boolean overCollapse = overTopOverlayButton(mouseX, mouseY);
        hoveredPoint = dragging != 0 ? (dragging == DRAG_POINT ? draggedPoint : hoveredPoint)
                : (mouseX >= left() && !overCollapse && !talk(shot) && view != View.PILOT ? handleAt(mouseX, mouseY) : -1);
        super.mouseMoved(mouseX, mouseY);
    }

    // --- drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (playing && view == View.SHOT) {
            int active = cueAtMoment(shot, time * seconds(shot));
            if (active != selectedCue) {
                selectedCue = active;
                if (panelTab == PanelTab.CUES) {
                    build();
                }
            }
        }
        syncPreviewAudio();
        retryAnchors();
        int left = left();
        boolean bars = scene.get("letterbox") instanceof JsonPrimitive on && on.getAsBoolean();
        int bar = bars ? Math.round(height * 0.12f) : 0;
        if (view == View.SCENE) {
            double black = sceneFade(sceneTime);
            if (black > 0) {
                graphics.fill(left, 0, width, height, ((int) (black * 255) << 24));
            }
        }
        if (bars) {
            graphics.fill(left, 0, width, bar, 0xFF000000);
            graphics.fill(left, height - bar, width, height, 0xFF000000);
        }
        if (!catalog.isOpen()) {
            drawPreviewCues(graphics, left, bar);
            graphics.flush();
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
        if (panelShown) {
            graphics.fill(0, 0, PANEL, height, 0xF01A140F);
            graphics.fill(PANEL - 1, 0, PANEL, height, DevUi.PANEL_EDGE);
            graphics.fill(88, 8, 89, 23, DevUi.CARD_EDGE);
            Component shotText = DevUi.text("scene_view.shot", shot + 1, shots().size());
            int shotW = font.width(shotText);
            String title = font.plainSubstrByWidth(id.getPath(), Math.max(24, PANEL - 8 - 23 - shotW - 8 - (dirty ? 10 : 0)));
            DevIcons.draw(graphics, "camera", 8, 28, DevUi.ACCENT);
            graphics.drawString(font, Component.literal(title + (dirty ? " •" : "")), 23, 30, DevUi.ACCENT, false);
            graphics.drawString(font, shotText, PANEL - 8 - shotW, 30, DevUi.MUTED, false);
            Anchor anchor = anchor(shot);
            String status = anchor.asking() ? DevUi.str("scene_view.anchor_asking") : anchor.found() ? DevUi.str("scene_view.anchor_found")
                    : DevUi.str("scene_view.anchor_stand_in");
            var anchorRows = font.split(Component.literal(DevUi.str("scene_view.anchor", anchorName())), PANEL - 16);
            int headerY = 42;
            for (var row : anchorRows) {
                graphics.drawString(font, row, 8, headerY, DevUi.TEXT, false);
                headerY += 10;
            }
            graphics.drawString(font, fitWidth(status, PANEL - 16), 8, headerY, anchor.found() ? DevUi.MUTED : DevUi.WARN, false);
            int panelTop = contentTop();
            int panelBottom = contentBottom();
            graphics.fill(6, panelTop - 3, PANEL - 6, panelTop - 2, DevUi.CARD_EDGE);
            graphics.fill(6, panelBottom + 2, PANEL - 6, panelBottom + 3, DevUi.CARD_EDGE);
            graphics.enableScissor(0, panelTop, PANEL - 1, panelBottom);
            try {
                if (talk(shot)) {
                    String dialogue = shotJson(shot).get("dialogue") instanceof JsonPrimitive written ? written.getAsString() : "";
                    graphics.drawWordWrap(font, DevUi.text("scene_view.talk", dialogue.isEmpty() ? "—" : dialogue), 8, panelTop, PANEL - 16, DevUi.LABEL);
                } else {
                    if (pathLabelY > -500) {
                        List<Vec3> controls = controlPointsLocal(shot);
                        int sel = Mth.clamp(selectedPoint, 0, controls.size() - 1);
                        boolean isFrom = sel == 0;
                        boolean isTo = sel == controls.size() - 1;
                        Component pointHeading = isFrom ? DevUi.text("scene_view.from")
                                : isTo ? DevUi.text("scene_view.to")
                                : Component.literal(DevUi.str("scene_view.via") + " #" + sel);
                        int headingColor = isFrom ? START : isTo ? END : VIA;
                        graphics.drawString(font, pointHeading, 8, pathLabelY + 6, headingColor, false);
                    }
                    String[] axes = {"→", "↑", "↗"};
                    for (int axis = 0; axis < 3; axis++) {
                        int rowY = pathAxesY + axis * 18;
                        if (pathAxesY > -500 && rowY >= panelTop && rowY + 16 <= panelBottom) {
                            graphics.drawString(font, axes[axis], 9, rowY + 4, MUTED, false);
                        }
                    }
                    if (modeHintY > -500) {
                        String line1;
                        String line2;
                        int color1;
                        if (view == View.PILOT) {
                            line1 = DevUi.str("scene_view.mode_hint_pilot_1", selectedPointName());
                            line2 = DevUi.str(look(shot).equals("free") ? "scene_view.mode_hint_pilot_free" : "scene_view.mode_hint_pilot_aim");
                            color1 = DevUi.ACCENT;
                        } else if (view == View.FREE) {
                            line1 = DevUi.str("scene_view.mode_hint_free_1");
                            line2 = DevUi.str("scene_view.mode_hint_free_2");
                            color1 = DevUi.ACCENT;
                        } else {
                            line1 = DevUi.str("scene_view.mode_hint_shot_1");
                            line2 = DevUi.str("scene_view.mode_hint_shot_2");
                            color1 = DevUi.LABEL;
                        }
                        graphics.drawString(font, fitWidth(line1, PANEL - 16), 8, modeHintY + 1, color1, false);
                        graphics.drawString(font, fitWidth(line2, PANEL - 16), 8, modeHintY + 11, MUTED, false);
                    }
                    if (rotLabelY > -500) {
                        graphics.drawString(font, DevUi.text("scene_view.rot_yaw"), 8, rotLabelY, MUTED, false);
                        graphics.drawString(font, DevUi.text("scene_view.rot_pitch"), 96, rotLabelY, MUTED, false);
                    }
                    if (lookOffsetLabelY > -500) {
                        graphics.drawString(font, DevUi.text("scene_view.look_offset_x"), 8, lookOffsetLabelY, MUTED, false);
                        graphics.drawString(font, DevUi.text("scene_view.look_offset_y"), 96, lookOffsetLabelY, MUTED, false);
                    }
                    if (speedInfoY > -500) {
                        double len = shotPath(shot).length();
                        double sec = Math.max(0.05, seconds(shot));
                        double bps = len / sec;
                        int speedColor = bps <= 2.4 ? START : bps <= 4.5 ? GOLD : END;
                        String paceKey = bps <= 2.4 ? "scene_view.pace_cinematic" : bps <= 4.5 ? "scene_view.pace_brisk" : "scene_view.pace_fast";
                        graphics.drawString(font, DevUi.text(paceKey, String.format(Locale.ROOT, "%.1f", bps)), 8, speedInfoY, speedColor, false);
                    }
                    if (cueLabelY > -500) {
                        graphics.drawString(font, DevUi.text("scene_view.cues"), 8, cueLabelY + 6, DevUi.LABEL, false);
                        if (selectedCue() == null) {
                            graphics.drawWordWrap(font, DevUi.text("scene_view.cue_none"), 8, cueLabelY + 46, PANEL - 16, DevUi.HINT);
                        }
                    }
                    if (fadeLabelY > -500) {
                        String[] fadeLabels = {"scene_view.fade_in", "scene_view.fade_stay", "scene_view.fade_out"};
                        for (int i = 0; i < 3; i++) {
                            graphics.drawString(font, DevUi.text(fadeLabels[i]), 8 + i * 68, fadeLabelY, MUTED, false);
                        }
                    }
                    if (shakeLabelY > -500) {
                        graphics.drawString(font, DevUi.text("scene_view.shake_intensity"), 8, shakeLabelY, MUTED, false);
                        graphics.drawString(font, DevUi.text("scene_view.shake_duration"), 110, shakeLabelY, MUTED, false);
                    }
                }
            } finally {
                graphics.disableScissor();
            }
            if (Math.max(0, contentHeight - (contentBottom() - panelTop)) > 0) {
                // A thin bar at the panel's edge: how far its middle is scrolled.
                int track = contentBottom() - panelTop;
                int thumb = Math.max(12, track * track / Math.max(1, contentHeight));
                int top = panelTop + (track - thumb) * scroll / Math.max(1, contentHeight - track);
                graphics.fill(PANEL - 3, top, PANEL - 1, top + thumb, 0x80FFFFFF);
            }
        }
        boolean showingBadge = drawTimeLine(graphics, mouseX, mouseY);
        if (!showingBadge) {
            String hint = view == View.SCENE ? DevUi.str("scene_view.hint_scene", String.format(Locale.ROOT, "%.1f", sceneTime),
                    String.format(Locale.ROOT, "%.1f", sceneSeconds()))
                    : view == View.PILOT ? DevUi.str("scene_view.hint_pilot")
                    : view == View.FREE ? DevUi.str("scene_view.hint_free") : DevUi.str("scene_view.hint_shot");
            graphics.drawString(font, fitWidth(hint, Math.max(40, width - left - 16)), left + 8, lineTop() - 14, TEXT, true);
        }
        if (view == View.PILOT && !talk(shot) && !catalog.isOpen()) {
            SceneCamera.Pose pilotPose = shotPose(shot, timeForControlPoint(shot, selectedPoint), partialTick);
            List<Vec3> pts = controlPointsLocal(shot);
            int sel = Mth.clamp(selectedPoint, 0, pts.size() - 1);
            String ptName = sel == 0 ? DevUi.str("scene_view.from") : sel == pts.size() - 1 ? DevUi.str("scene_view.to") : "#" + sel;
            int fovShown = pilotPose.fov() > 0f ? Math.round(pilotPose.fov()) : FovSlider.playerFov();
            String hud = DevUi.str("scene_view.pilot_hud", ptName, fovShown, String.format(Locale.ROOT, "%.1f", pilotPose.roll()));
            int hw = DevIcons.SIZE + DevIcons.GAP + font.width(hud) + 12;
            int hx = panelShown ? PANEL + 31 : 98;
            int hy = 6;
            graphics.flush();
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 50);
            graphics.fill(hx, hy, hx + hw, hy + 19, 0xCC1A140F);
            DevUi.frame(graphics, hx, hy, hw, 19, DevUi.ACCENT);
            DevIcons.draw(graphics, "camera", hx + 5, hy + 4, DevUi.ACCENT);
            graphics.drawString(font, hud, hx + 5 + DevIcons.SIZE + DevIcons.GAP, hy + 6, DevUi.ACCENT, false);
            graphics.flush();
            graphics.pose().popPose();
        }
        if (note != null && net.minecraft.Util.getMillis() - noteAt < 4000 && !catalog.isOpen()) {
            int nw = font.width(note) + 14;
            int nx = left + Math.max(8, (width - left - nw) / 2);
            int ny = Math.max(bar + 6, 29);
            graphics.flush();
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 120);
            graphics.fill(nx, ny, nx + nw, ny + 16, 0xE61A140F);
            DevUi.frame(graphics, nx, ny, nw, 16, noteColor);
            graphics.drawString(font, note, nx + 7, ny + 4, noteColor, false);
            graphics.flush();
            graphics.pose().popPose();
        }
        if (undoButton != null) {
            undoButton.active = !undoStack.isEmpty();
        }
        if (redoButton != null) {
            redoButton.active = !redoStack.isEmpty();
        }
        if (freeButton != null) {
            freeButton.setMessage(freeLabel());
        }
        if (captureButton != null) {
            boolean canCapture = view == View.FREE;
            captureButton.active = canCapture;
            captureButton.setMessage(Component.literal("📷").withColor(canCapture ? DevUi.ACCENT : DevUi.LABEL));
        }
        if (catalog.isOpen()) {
            graphics.flush();
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 200);
            catalog.render(this, graphics, mouseX, mouseY);
            super.render(graphics, mouseX, mouseY, partialTick);
            graphics.flush();
            graphics.pose().popPose();
        } else {
            super.render(graphics, mouseX, mouseY, partialTick);
            if (helpShown) {
                drawHelpOverlay(graphics, left);
            }
            if (panelShown && (closeAsked || saveProblem != null)) {
                Component alert = closeAsked ? DevUi.text("scene_view.unsaved") : DevUi.text("scene_view.not_saved", saveProblem);
                int alertColor = closeAsked ? 0xFFE0A050 : DevUi.BAD;
                int lines = font.split(alert, PANEL - 20).size();
                int boxH = lines * 9 + 8;
                int boxY = contentBottom() - boxH - 2;
                graphics.flush();
                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, 260);
                graphics.fill(4, boxY, PANEL - 5, boxY + boxH, 0xF51A140F);
                DevUi.frame(graphics, 4, boxY, PANEL - 9, boxH, alertColor);
                graphics.drawWordWrap(font, alert, 10, boxY + 4, PANEL - 20, alertColor);
                graphics.flush();
                graphics.pose().popPose();
            }
        }
    }

    /** Clean modal hotkeys reference (F1) listing every camera, flight, timeline, and editing shortcut. */
    private void drawHelpOverlay(GuiGraphics graphics, int left) {
        int viewW = Math.max(180, width - left);
        int cardW = Math.min(350, viewW - 24);
        String[][] sections = {
                {"scene_view.help_sec_pilot", "scene_view.help_pilot_1", "scene_view.help_pilot_2", "scene_view.help_pilot_3"},
                {"scene_view.help_sec_outside", "scene_view.help_outside_1", "scene_view.help_outside_2", "scene_view.help_outside_3"},
                {"scene_view.help_sec_playback", "scene_view.help_playback_1", "scene_view.help_playback_2"},
                {"scene_view.help_sec_edit", "scene_view.help_edit_1", "scene_view.help_edit_2"}
        };
        int textW = cardW - 20;
        int totalH = 27;
        for (String[] sec : sections) {
            totalH += 13;
            for (int i = 1; i < sec.length; i++) {
                totalH += font.split(DevUi.text(sec[i]), textW).size() * 10 + 1;
            }
            totalH += 5;
        }
        totalH += 14;
        int cardH = Math.min(height - 32, totalH);
        int cx = left + (viewW - cardW) / 2;
        int cy = (height - cardH) / 2;
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 250);
        graphics.fill(left, 0, width, height, 0x88000000);
        graphics.fill(cx, cy, cx + cardW, cy + cardH, 0xF51A140F);
        DevUi.frame(graphics, cx, cy, cardW, cardH, DevUi.ACCENT);
        graphics.fill(cx + 1, cy + 1, cx + cardW - 1, cy + 20, DevUi.BAR);
        graphics.flush();
        graphics.pose().translate(0, 0, 1);
        DevIcons.draw(graphics, "keys", cx + 8, cy + 5, DevUi.ACCENT);
        graphics.drawString(font, DevUi.text("scene_view.help_title"), cx + 23, cy + 7, DevUi.ACCENT, false);
        int y = cy + 26;
        int maxY = cy + cardH - 16;
        for (String[] sec : sections) {
            if (y + 10 > maxY) {
                break;
            }
            graphics.drawString(font, DevUi.text(sec[0]), cx + 10, y, GOLD, false);
            y += 12;
            for (int i = 1; i < sec.length; i++) {
                var rows = font.split(DevUi.text(sec[i]), textW);
                for (var row : rows) {
                    if (y + 9 > maxY) {
                        break;
                    }
                    graphics.drawString(font, row, cx + 10, y, DevUi.TEXT, false);
                    y += 10;
                }
                y += 1;
            }
            y += 4;
        }
        Component closeHint = DevUi.text("scene_view.help_close");
        graphics.drawString(font, closeHint, cx + (cardW - font.width(closeHint)) / 2, cy + cardH - 12, MUTED, false);
        graphics.flush();
        graphics.pose().popPose();
    }

    /** One cue positioned on the active timeline (a single shot or the whole scene). */
    record TimedCue(int shotIndex, int cueIndex, double start, JsonObject cue) {}

    /**
     * Picks at most one cue per channel ({@code title}, {@code caption}, {@code line}, {@code fade}, {@code music},
     * {@code sound}): the latest mark at or before {@code atSeconds} (within 0.05s). A newer mark on the timeline
     * replaces the previous cue of the same channel instead of stacking on top of it.
     */
    static Map<String, TimedCue> latestCuesByChannel(List<TimedCue> cues, double atSeconds, int preferredShot, int preferredCue) {
        Map<String, TimedCue> latest = new HashMap<>();
        for (TimedCue candidate : cues) {
            if (candidate.start() > atSeconds + 0.05) {
                continue;
            }
            String kind = cueKind(candidate.cue());
            TimedCue prev = latest.get(kind);
            if (prev == null || candidate.start() > prev.start() + 1.0e-6) {
                latest.put(kind, candidate);
            } else if (Math.abs(candidate.start() - prev.start()) <= 1.0e-6) {
                boolean prevPreferred = prev.shotIndex() == preferredShot && prev.cueIndex() == preferredCue;
                boolean candPreferred = candidate.shotIndex() == preferredShot && candidate.cueIndex() == preferredCue;
                if (candPreferred || !prevPreferred) {
                    latest.put(kind, candidate);
                }
            }
        }
        return latest;
    }

    /** Current playback position in seconds on the active timeline (single shot or whole scene). */
    private double nowSeconds() {
        return view == View.SCENE ? sceneTime : time * seconds(shot);
    }

    /** Cues along the active timeline (the current shot, or every flight shot in scene playback order). */
    private List<TimedCue> timelineCues() {
        List<TimedCue> out = new java.util.ArrayList<>();
        if (view == View.SCENE) {
            double offset = 0;
            for (int i = 0; i < shots().size(); i++) {
                if (talk(i)) {
                    continue;
                }
                JsonArray during = during(i);
                for (int j = 0; j < during.size(); j++) {
                    if (during.get(j) instanceof JsonObject cue) {
                        out.add(new TimedCue(i, j, offset + cueAt(cue), cue));
                    }
                }
                offset += seconds(i);
            }
        } else if (!talk(shot)) {
            JsonArray during = during(shot);
            for (int j = 0; j < during.size(); j++) {
                if (during.get(j) instanceof JsonObject cue) {
                    out.add(new TimedCue(shot, j, cueAt(cue), cue));
                }
            }
        }
        return out;
    }

    private void stopPreviewAudio() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSoundManager() != null) {
            if (previewMusic != null) {
                minecraft.getSoundManager().stop(previewMusic);
            }
            if (previewSound != null) {
                minecraft.getSoundManager().stop(previewSound);
            }
            if (previewVoice != null) {
                minecraft.getSoundManager().stop(previewVoice);
            }
        }
        previewMusic = null;
        previewMusicKey = null;
        previewSound = null;
        previewSoundKey = null;
        previewVoice = null;
        previewVoiceKey = null;
        lastAudioTime = -1;
    }

    private @Nullable SoundInstance playPreviewSound(@Nullable String rawId, boolean stopVanillaMusic) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        ResourceLocation soundId = ResourceLocation.tryParse(rawId.trim());
        if (soundId == null || soundId.getPath().isEmpty()) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSoundManager() == null) {
            return null;
        }
        if (stopVanillaMusic) {
            minecraft.getMusicManager().stopPlaying();
        }
        SoundInstance instance = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(soundId), 1.0f, 1.0f);
        minecraft.getSoundManager().play(instance);
        return instance;
    }

    /**
     * Plays the timeline's audio channels (music, sound, line voice-over) while playing a shot or the scene: each new
     * mark of the same channel stops the previous instance first so tracks never layer over each other.
     */
    private void syncPreviewAudio() {
        if (!playing || catalog.isOpen() || DevSoundPreview.isActive()) {
            if (previewMusic != null || previewSound != null || previewVoice != null || lastAudioTime >= 0) {
                stopPreviewAudio();
            }
            return;
        }
        double now = nowSeconds();
        if (lastAudioTime >= 0 && now + 0.05 < lastAudioTime) {
            stopPreviewAudio();
        }
        Map<String, TimedCue> active = latestCuesByChannel(timelineCues(), now, shot, selectedCue);
        Minecraft minecraft = Minecraft.getInstance();

        // Music channel: continuous track, replaced cleanly whenever a newer music mark begins.
        TimedCue musicCue = active.get("music");
        String trackId = musicCue != null && musicCue.cue().get("music") instanceof JsonPrimitive p ? p.getAsString().trim()
                : scene.get("music") instanceof JsonPrimitive top ? top.getAsString().trim() : "";
        String musicKey = musicCue != null ? musicCue.shotIndex() + ":" + musicCue.cueIndex() + ":" + trackId
                : !trackId.isEmpty() ? "scene:" + trackId : null;
        if (!java.util.Objects.equals(musicKey, previewMusicKey)) {
            if (previewMusic != null && minecraft.getSoundManager() != null) {
                minecraft.getSoundManager().stop(previewMusic);
            }
            previewMusicKey = musicKey;
            previewMusic = playPreviewSound(trackId, true);
        }

        // Sound channel: plays when its mark is reached, stopping any previous sound cue first.
        TimedCue soundCue = active.get("sound");
        if (soundCue != null) {
            JsonObject inner = soundCue.cue().get("sound") instanceof JsonObject obj ? obj : null;
            String soundId = inner != null && inner.get("sound") instanceof JsonPrimitive p ? p.getAsString().trim() : "";
            String soundKey = soundCue.shotIndex() + ":" + soundCue.cueIndex() + ":" + soundId;
            boolean reached = lastAudioTime < 0 ? Math.abs(now - soundCue.start()) <= 0.12 : soundCue.start() > lastAudioTime - 0.01;
            if (!soundKey.equals(previewSoundKey) && reached) {
                if (previewSound != null && minecraft.getSoundManager() != null) {
                    minecraft.getSoundManager().stop(previewSound);
                }
                previewSoundKey = soundKey;
                previewSound = playPreviewSound(soundId, false);
            }
        }

        // Line voice-over channel: a newer line replaces the previous line's voice-over immediately.
        TimedCue lineCue = active.get("line");
        if (lineCue != null) {
            JsonObject inner = lineCue.cue().get("line") instanceof JsonObject obj ? obj : null;
            String voiceId = inner != null && inner.get("sound") instanceof JsonPrimitive p ? p.getAsString().trim() : "";
            String voiceKey = lineCue.shotIndex() + ":" + lineCue.cueIndex() + ":" + voiceId;
            boolean reached = lastAudioTime < 0 ? Math.abs(now - lineCue.start()) <= 0.12 : lineCue.start() > lastAudioTime - 0.01;
            if (!voiceKey.equals(previewVoiceKey) && reached) {
                if (previewVoice != null && minecraft.getSoundManager() != null) {
                    minecraft.getSoundManager().stop(previewVoice);
                }
                previewVoiceKey = voiceKey;
                previewVoice = playPreviewSound(voiceId, false);
            }
        }
        lastAudioTime = now;
    }

    /**
     * Draws the active cue of each visual channel (fade, caption, chapter title, subtitle line) in the preview viewport
     * at the current moment of the time line: a newer mark of the same kind replaces the earlier one cleanly.
     */
    private void drawPreviewCues(GuiGraphics graphics, int left, int bar) {
        if (view != View.SCENE && talk(shot)) {
            return;
        }
        double atSeconds = nowSeconds();
        String language = ClientDevStore.activeLanguage();
        int viewW = Math.max(80, width - left);
        // Centered overlays (titles, centered captions, subtitles) use the real full-screen center (width / 2),
        // matching in-game SceneScreen & vanilla title placement instead of shifting right by PANEL / 2.
        int centerX = width / 2;
        Map<String, TimedCue> active = latestCuesByChannel(timelineCues(), atSeconds, shot, selectedCue);
        for (String kind : new String[] {"fade", "caption", "title", "line"}) {
            TimedCue entry = active.get(kind);
            if (entry == null) {
                continue;
            }
            JsonObject cue = entry.cue();
            double len = cueDuration(cue);
            double into = atSeconds - entry.start();
            if (into < -0.05 || into > len) {
                continue;
            }
            boolean isPicked = entry.shotIndex() == shot && entry.cueIndex() == selectedCue;
            boolean framingOnly = view == View.FREE || view == View.PILOT;
            switch (kind) {
                case "fade" -> {
                    if (framingOnly) {
                        break;
                    }
                    JsonObject inner = cue.get("fade") instanceof JsonObject obj ? obj : null;
                    double in = inner != null ? Math.max(0, parse(number(inner.get("in")), 0.5)) : 0.5;
                    double stay = inner != null ? Math.max(0, parse(number(inner.get("stay")), 1.0)) : 1.0;
                    double out = inner != null ? Math.max(0, parse(number(inner.get("out")), 0.5)) : 0.5;
                    double a = fadeAlpha(into, in, stay, out);
                    if (!playing && Math.abs(into) <= 0.05 && in > 0 && a < 0.45) {
                        a = 0.45;
                    }
                    if (a > 0.01) {
                        graphics.fill(left, 0, width, height, ((int) Math.round(a * 255) << 24) | fadeColor(inner));
                    }
                }
                case "caption" -> {
                    JsonObject inner = cue.get("caption") instanceof JsonObject obj ? obj : null;
                    String text = inner != null ? previewText(inner, "text", language) : "";
                    if (text.isEmpty() && isPicked) {
                        text = "[" + DevUi.str("scene.cue.caption") + "]";
                    }
                    if (text.isEmpty()) {
                        break;
                    }
                    double a = textAlpha(into, len, playing ? TEXT_FADE : 0, TEXT_FADE);
                    if (a <= 0.02) {
                        break;
                    }
                    int shade = ((int) Math.round(a * 255) << 24);
                    boolean black = !framingOnly && inner != null && inner.get("black") instanceof JsonPrimitive b && b.getAsBoolean();
                    String pos = inner != null && inner.get("position") instanceof JsonPrimitive p ? p.getAsString() : "bottom_left";
                    if (black) {
                        graphics.fill(left, 0, width, height, ((int) Math.round(a * 0xD8) << 24));
                    }
                    var rows = font.split(Component.literal(text), Math.min(320, pos.equals("center") ? width - 48 : viewW - 48));
                    int rowH = font.lineHeight + 3;
                    int blockH = rows.size() * rowH;
                    int y = switch (pos) {
                        case "top_left" -> Math.max(bar, 12) + 12;
                        case "center" -> (height - blockH) / 2;
                        default -> height - Math.max(bar, 48) - 12 - blockH;
                    };
                    for (var row : rows) {
                        int x = pos.equals("center") ? centerX - font.width(row) / 2 : left + 24;
                        graphics.drawString(font, row, x, y, shade | 0xF2EAD8, !black);
                        y += rowH;
                    }
                }
                case "title" -> {
                    JsonObject inner = cue.get("title") instanceof JsonObject obj ? obj : null;
                    String text = inner != null ? previewText(inner, "text", language) : "";
                    String sub = inner != null ? previewText(inner, "subtitle", language) : "";
                    if (text.isEmpty() && isPicked) {
                        text = "[" + DevUi.str("scene.cue.title") + "]";
                    }
                    // As the game shows a title: up over TITLE_IN ticks, down over TITLE_OUT. Held at a mark (not playing)
                    // it is whole, so what is being typed can be read; the way out shows while scrubbing on.
                    double a = textAlpha(into, len, playing ? Scenes.TITLE_IN / 20.0 : 0, Scenes.TITLE_OUT / 20.0);
                    if (a <= 0.02) {
                        break;
                    }
                    int shade = ((int) Math.round(a * 255) << 24);
                    if (!text.isEmpty()) {
                        PoseStack pose = graphics.pose();
                        pose.pushPose();
                        pose.translate(centerX, height / 2.0 - 18, 0);
                        pose.scale(2.0f, 2.0f, 1.0f);
                        graphics.drawString(font, text, -font.width(text) / 2, 0, shade | 0xFFFFFF, true);
                        pose.popPose();
                    }
                    if (!sub.isEmpty()) {
                        graphics.drawString(font, sub, centerX - font.width(sub) / 2, height / 2 + 6, shade | (DevUi.TEXT & 0xFFFFFF), true);
                    }
                }
                case "line" -> {
                    JsonObject inner = cue.get("line") instanceof JsonObject obj ? obj : null;
                    String text = inner != null ? previewText(inner, "text", language) : "";
                    String speaker = inner != null ? previewText(inner, "speaker", language) : "";
                    if (text.isEmpty() && isPicked) {
                        text = "[" + DevUi.str("scene.cue.line") + "]";
                    }
                    if (text.isEmpty()) {
                        break;
                    }
                    double a = textAlpha(into, len, playing ? TEXT_FADE : 0, TEXT_FADE);
                    if (a <= 0.02) {
                        break;
                    }
                    int shade = ((int) Math.round(a * 255) << 24);
                    var rows = font.split(Component.literal(text), Math.min(320, width - 40));
                    int rowH = font.lineHeight + 2;
                    int count = rows.size() + (speaker.isEmpty() ? 0 : 1);
                    int bottom = Math.min(height - Math.max(bar, 24) - 8, lineTop() - 28);
                    int y = bottom - count * rowH;
                    if (!speaker.isEmpty()) {
                        Component who = Component.literal(speaker).withStyle(net.minecraft.ChatFormatting.BOLD);
                        graphics.drawString(font, who, centerX - font.width(who) / 2, y, shade | (GOLD & 0xFFFFFF), true);
                        y += rowH;
                    }
                    for (var row : rows) {
                        graphics.drawString(font, row, centerX - font.width(row) / 2, y, shade | 0xFFFFFF, true);
                        y += rowH;
                    }
                }
                default -> {
                }
            }
        }
    }

    /** A caption's or a line's fade in and out, in seconds, as the game shows them ({@code ClientScene.fadeAlpha}: 15 ticks). */
    private static final double TEXT_FADE = 0.75;

    /**
     * Opacity (0 to 1) of a text shown for {@code len} seconds, {@code into} seconds after its start: up over {@code in},
     * down over {@code out}; a short text shortens both so it still reaches full for a moment.
     */
    static double textAlpha(double into, double len, double in, double out) {
        if (into > len) {
            return 0;
        }
        into = Math.max(0, into);
        double scale = in + out > len && in + out > 0 ? len / (in + out) : 1;
        double up = in * scale;
        double down = out * scale;
        double rise = up > 0 ? into / up : 1;
        double fall = down > 0 ? (len - into) / down : 1;
        return Mth.clamp(Math.min(rise, fall), 0.0, 1.0);
    }

    /** Opacity (0 to 1) of a fade cue {@code into} seconds after its start, matching {@code ScreenFadeOverlay}. */
    static double fadeAlpha(double into, double in, double stay, double out) {
        double safeIn = Math.max(0.0, in);
        double safeStay = Math.max(0.0, stay);
        double safeOut = Math.max(0.0, out);
        double t = Math.max(0.0, into);
        if (safeIn > 0.0 && t < safeIn) {
            return Mth.clamp(t / safeIn, 0.0, 1.0);
        }
        t -= safeIn;
        if (t < safeStay) {
            return 1.0;
        }
        t -= safeStay;
        if (safeOut > 0.0 && t < safeOut) {
            return Mth.clamp(1.0 - t / safeOut, 0.0, 1.0);
        }
        return 0.0;
    }

    private static int fadeColor(@Nullable JsonObject inner) {
        if (inner != null && inner.get("color") instanceof JsonPrimitive raw) {
            String text = raw.getAsString().trim();
            if (text.startsWith("#") && text.length() == 7) {
                try {
                    return Integer.parseInt(text.substring(1), 16) & 0x00FFFFFF;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    private static String previewText(JsonObject inner, String field, String language) {
        String key = ClientDevStore.getKey(inner, field);
        String shown = ClientDevStore.readStoryText(inner, field, "", language);
        return key != null && shown.equals(key) ? "" : shown.trim();
    }

    private String fitWidth(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        int fit = Math.max(0, maxWidth - font.width("…"));
        return font.plainSubstrByWidth(text, fit) + "…";
    }

    /** The shot drawn in the world while the view is open: its flight curve, its control-point cameras, its anchor. */
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
        ScenePath path = preview.shotPath(index);
        List<Vec3> samples = path.samples();
        for (int i = 0; i + 1 < samples.size(); i++) {
            line(lines, pose, samples.get(i), samples.get(i + 1), GOLD);
        }
        // Ticks across the flight at every second of it, closer together where the camera is slow.
        double seconds = preview.seconds(index);
        SceneDefinition.Ease ease = preview.ease(index);
        for (int second = 1; second < seconds; second++) {
            Vec3 at = path.pointAt(ease.apply(second / seconds));
            line(lines, pose, at.add(0, -0.12, 0), at.add(0, 0.12, 0), GOLD);
        }
        int viaCount = preview.localVia(index).size();
        boolean piloting = preview.view == View.PILOT;
        SceneCamera.Pose startPose = preview.shotPose(index, 0, partialTick);
        int startColor = preview.hoveredPoint == 0 ? 0xFFFFFFFF : START;
        if (!piloting || preview.selectedPoint != 0) {
            frustum(lines, pose, startPose, startColor);
        }
        if (preview.selectedPoint == 0 && !piloting) {
            line(lines, pose, startPose.pos().add(0, -0.22, 0), startPose.pos().add(0, 0.22, 0), startColor);
        }
        for (int k = 0; k < viaCount; k++) {
            int pointIdx = k + 1;
            int color = preview.hoveredPoint == pointIdx ? 0xFFFFFFFF : (preview.selectedPoint == pointIdx ? VIA_SELECTED : VIA);
            SceneCamera.Pose viaPose = preview.shotPose(index, preview.timeForControlPoint(index, pointIdx), partialTick);
            if (!piloting || preview.selectedPoint != pointIdx) {
                frustum(lines, pose, viaPose, color);
            }
            if (preview.selectedPoint == pointIdx && !piloting) {
                Vec3 apex = viaPose.pos();
                line(lines, pose, apex.add(0, -0.22, 0), apex.add(0, 0.22, 0), color);
            }
        }
        int endIdx = viaCount + 1;
        SceneCamera.Pose endPose = preview.shotPose(index, 1, partialTick);
        int endColor = preview.hoveredPoint == endIdx ? 0xFFFFFFFF : END;
        if (!piloting || preview.selectedPoint != endIdx) {
            frustum(lines, pose, endPose, endColor);
        }
        if (preview.selectedPoint == endIdx && !piloting) {
            line(lines, pose, endPose.pos().add(0, -0.22, 0), endPose.pos().add(0, 0.22, 0), endColor);
        }
        if (preview.view == View.FREE) {
            frustum(lines, pose, preview.shotPose(index, preview.time, partialTick), 0xFFB8A9D9);
        }
        Vec3 anchor = preview.anchor(index).pos();
        line(lines, pose, anchor, anchor.add(0, 2.2, 0), 0xFFFFFFFF);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    /** A small camera at the pose, pointing where it looks, with FOV scaling, real Roll tilt, and a forward aim ray. */
    private static void frustum(VertexConsumer lines, PoseStack pose, SceneCamera.Pose at, int color) {
        Vec3 look = direction(at.yaw(), at.pitch());
        Vec3 baseSide = right(at.yaw());
        Vec3 baseUp = baseSide.cross(look).normalize();
        double rollRad = Math.toRadians(at.roll());
        double cos = Math.cos(rollRad);
        double sin = Math.sin(rollRad);
        Vec3 side = baseSide.scale(cos).add(baseUp.scale(sin));
        Vec3 up = baseUp.scale(cos).subtract(baseSide.scale(sin));
        Vec3 apex = at.pos();
        Vec3 middle = apex.add(look.scale(0.8));
        double fovScale = at.fov() > 0f
                ? Mth.clamp(Math.tan(Math.toRadians(at.fov() * 0.5)) / Math.tan(Math.toRadians(35.0)), 0.45, 1.8) : 1.0;
        Vec3 w = side.scale(0.45 * fovScale);
        Vec3 h = up.scale(0.26 * fovScale);
        Vec3[] corners = {middle.add(w).add(h), middle.subtract(w).add(h), middle.subtract(w).subtract(h), middle.add(w).subtract(h)};
        for (int i = 0; i < 4; i++) {
            line(lines, pose, apex, corners[i], color);
            line(lines, pose, corners[i], corners[(i + 1) % 4], color);
        }
        // Its top edge marked, so which way is up shows.
        line(lines, pose, middle.add(h), middle.add(h.scale(1.6)), color);
        // Forward aim ray so the exact viewing direction is immediately readable in 3D overview.
        line(lines, pose, middle, apex.add(look.scale(2.8)), (0x88 << 24) | (color & 0x00FFFFFF));
    }

    private static void line(VertexConsumer lines, PoseStack pose, Vec3 a, Vec3 b, int color) {
        Vec3 normal = b.subtract(a);
        normal = normal.lengthSqr() > 1.0e-9 ? normal.normalize() : new Vec3(0, 1, 0);
        PoseStack.Pose last = pose.last();
        lines.addVertex(last, (float) a.x, (float) a.y, (float) a.z).setColor(color).setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
        lines.addVertex(last, (float) b.x, (float) b.y, (float) b.z).setColor(color).setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
