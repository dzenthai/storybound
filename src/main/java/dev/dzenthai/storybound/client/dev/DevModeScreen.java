package dev.dzenthai.storybound.client.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.client.dev.ClientDevStore.Edge;
import dev.dzenthai.storybound.client.dev.ClientDevStore.NodeKey;
import dev.dzenthai.storybound.client.text.ClientConfigTexts;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.Documents;
import dev.dzenthai.storybound.network.DevModePayloads;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

public class DevModeScreen extends Screen {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final ResourceLocation OVERVIEW_CHAPTER = Storybound.id("__overview__");
    private static final int LEFT_PANEL_W = 148;
    private static final int RIGHT_PANEL_W = 220;
    private static final int TOP_BAR_H = 24;
    private static final int NODE_W = 136;
    private static final int NODE_H = 42;
    private static final int MODAL_W = 320;
    private static final int MODAL_H = 224;

    private static ResourceLocation activeChapter = OVERVIEW_CHAPTER;
    private static final Map<NodeKey, int[]> positions = new LinkedHashMap<>();
    private static @Nullable NodeKey selectedNode;
    private static final Set<NodeKey> selectedNodes = new LinkedHashSet<>();
    private static boolean inspectorCollapsed = false;
    private static int questSubTab = 0;
    private static double panX = 40;
    private static double panY = 30;
    private static float zoom = 1.0f;
    private static final Deque<HistoryAction> undoStack = new ArrayDeque<>();
    private static final Deque<HistoryAction> redoStack = new ArrayDeque<>();

    private final @Nullable Screen parent;
    private final List<CanvasNode> canvasNodes = new ArrayList<>();
    private List<Edge> edges = List.of();
    private @Nullable NodeKey draggingNode;
    private @Nullable NodeKey linkingFrom;
    private double dragOffsetX;
    private double dragOffsetY;
    private final Map<NodeKey, int[]> dragStartPositions = new LinkedHashMap<>();
    private boolean boxSelecting;
    private double boxStartX;
    private double boxStartY;
    private double boxEndX;
    private double boxEndY;
    private boolean panning;
    private double panStartX;
    private double panStartY;

    private boolean contextMenuOpen;
    private int contextMenuX;
    private int contextMenuY;
    private int contextWorldX;
    private int contextWorldY;
    private List<ContextOption> activeContextOptions = List.of();

    private boolean catalogOpen;
    private String catalogTitle = "";
    private int catalogCategory = 0;
    private boolean catalogShowLocationTabs;
    private String catalogFilter = "";
    private int catalogScroll = 0;
    private List<CatalogEntry> catalogAllEntries = List.of();
    private @Nullable EditBox catalogSearchBox;
    private @Nullable BiConsumer<Integer, CatalogEntry> catalogCallback;

    private @Nullable JsonObject editingJson;
    private @Nullable EditBox titleBox;
    private @Nullable EditBox secondaryBox;
    private @Nullable EditBox sbtBox;
    private String statusText = "";
    private int statusColor = 0xFFA8E6A3;

    public DevModeScreen(@Nullable Screen parent) {
        super(Component.literal("Storybound Dev Mode"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ClientDevStore.setListener(() -> Minecraft.getInstance().execute(this::onSnapshotUpdated));
        PacketDistributor.sendToServer(new DevModePayloads.RequestSnapshot());
        if (selectedNode != null) {
            String raw = ClientDevStore.getJson(selectedNode.type(), selectedNode.id());
            editingJson = raw != null ? ClientDevStore.parseObject(raw) : null;
        }
        rebuildGraph();
        rebuildInspectorWidgets();
    }

    @Override
    public void removed() {
        ClientDevStore.setListener(null);
        super.removed();
    }

    private void onSnapshotUpdated() {
        if (!OVERVIEW_CHAPTER.equals(activeChapter)
                && ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, activeChapter) == null) {
            activeChapter = OVERVIEW_CHAPTER;
        }
        selectedNodes.removeIf(k -> ClientDevStore.getJson(k.type(), k.id()) == null);
        rebuildGraph();
        if (selectedNode != null) {
            String raw = ClientDevStore.getJson(selectedNode.type(), selectedNode.id());
            if (raw != null) {
                editingJson = ClientDevStore.parseObject(raw);
            } else {
                selectedNode = selectedNodes.isEmpty() ? null : selectedNodes.iterator().next();
                editingJson = selectedNode != null
                        ? ClientDevStore.parseObject(ClientDevStore.getJson(selectedNode.type(), selectedNode.id()))
                        : null;
            }
        }
        rebuildInspectorWidgets();
    }

    private void rebuildGraph() {
        canvasNodes.clear();
        edges = ClientDevStore.collectEdges();

        Map<NodeKey, int[]> chapterPos = new HashMap<>();
        if (!OVERVIEW_CHAPTER.equals(activeChapter)) {
            String rawChapter = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, activeChapter);
            JsonObject chapterObj = ClientDevStore.parseObject(rawChapter);
            if (chapterObj.has("nodes") && chapterObj.get("nodes").isJsonArray()) {
                for (JsonElement el : chapterObj.getAsJsonArray("nodes")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject n = el.getAsJsonObject();
                    ResourceLocation type = normalizeType(n.has("type") ? n.get("type").getAsString() : "quest");
                    ResourceLocation id = ResourceLocation.tryParse(n.has("id") ? n.get("id").getAsString() : "");
                    if (type != null && id != null) {
                        int x = n.has("x") ? n.get("x").getAsInt() : 0;
                        int y = n.has("y") ? n.get("y").getAsInt() : 0;
                        NodeKey key = new NodeKey(type, id);
                        chapterPos.put(key, new int[]{x, y});
                        positions.put(key, new int[]{x, y});
                    }
                }
            }
        }

        List<ResourceLocation> typesOrder = List.of(
                ClientDevStore.TYPE_NPC,
                ClientDevStore.TYPE_DIALOGUE,
                ClientDevStore.TYPE_DOCUMENT,
                ClientDevStore.TYPE_QUEST,
                ClientDevStore.TYPE_WAYPOINT,
                ClientDevStore.TYPE_BOSS,
                ClientDevStore.TYPE_CODEX,
                ClientDevStore.TYPE_EVENT
        );

        int col = 0;
        for (ResourceLocation type : typesOrder) {
            Map<ResourceLocation, String> map = ClientDevStore.byType(type);
            if (map.isEmpty()) {
                continue;
            }
            int row = 0;
            for (Map.Entry<ResourceLocation, String> entry : map.entrySet()) {
                NodeKey key = new NodeKey(type, entry.getKey());
                if (!OVERVIEW_CHAPTER.equals(activeChapter) && !chapterPos.containsKey(key)) {
                    continue;
                }
                final int colIdx = col;
                int[] pos = positions.computeIfAbsent(key, k -> new int[]{colIdx * 185 + 20, rowIndex(k) * 72 + 20});
                if (pos[0] == 0 && pos[1] == 0 && row > 0) {
                    pos[0] = col * 185 + 20;
                    pos[1] = row * 72 + 20;
                }
                JsonObject obj = ClientDevStore.parseObject(entry.getValue());
                canvasNodes.add(buildNodeCard(key, obj, pos[0], pos[1]));
                row++;
            }
            col++;
        }
    }

    private int rowIndex(NodeKey key) {
        int idx = 0;
        for (ResourceLocation id : ClientDevStore.byType(key.type()).keySet()) {
            if (id.equals(key.id())) {
                return idx;
            }
            idx++;
        }
        return 0;
    }

    private CanvasNode buildNodeCard(NodeKey key, JsonObject obj, int x, int y) {
        String title = resolveDisplayTitle(key, obj);
        String badge = resolveBadge(key, obj);
        int color = colorForType(key.type(), obj);
        ItemStack icon = iconForNode(key, obj);
        boolean hasError = !ClientDevStore.issuesFor(key.type(), key.id()).isEmpty();
        return new CanvasNode(key, title, badge, color, icon, x, y, hasError);
    }

    private String resolveDisplayTitle(NodeKey key, JsonObject obj) {
        if (obj.has("title")) {
            String raw = ClientDevStore.readStoryText(obj, "title", key.id().getPath());
            return translateIfPossible(raw);
        }
        if (obj.has("name")) {
            String raw = ClientDevStore.readStoryText(obj, "name", key.id().getPath());
            return translateIfPossible(raw);
        }
        return key.id().getPath();
    }

    private String translateIfPossible(String text) {
        if (Language.getInstance().has(text)) {
            return Language.getInstance().getOrDefault(text);
        }
        return text;
    }

    private String resolveBadge(NodeKey key, JsonObject obj) {
        ResourceLocation t = key.type();
        if (t.equals(ClientDevStore.TYPE_QUEST)) {
            String cat = obj.has("category") ? obj.get("category").getAsString() : "side";
            return "Quest [" + shortId(cat) + "]";
        }
        if (t.equals(ClientDevStore.TYPE_DIALOGUE)) {
            String pres = obj.has("presentation") ? obj.get("presentation").getAsString() : "screen";
            return "Dialogue [" + pres + "]";
        }
        if (t.equals(ClientDevStore.TYPE_NPC)) {
            boolean ov = obj.has("override_interact") && obj.get("override_interact").getAsBoolean();
            return ov ? "NPC [RMB Dialogue]" : "NPC [Binding]";
        }
        if (t.equals(ClientDevStore.TYPE_DOCUMENT)) {
            String kind = obj.has("kind") ? obj.get("kind").getAsString() : "note";
            return "Item [" + kind + "]";
        }
        if (t.equals(ClientDevStore.TYPE_WAYPOINT)) {
            float r = obj.has("radius") ? obj.get("radius").getAsFloat() : 0f;
            return r > 0 ? "Area [r=" + (int) r + "]" : "Waypoint [Point]";
        }
        if (t.equals(ClientDevStore.TYPE_BOSS)) {
            return "Boss";
        }
        if (t.equals(ClientDevStore.TYPE_CODEX)) {
            return "Codex";
        }
        return "Event";
    }

    private int colorForType(ResourceLocation type, JsonObject obj) {
        if (type.equals(ClientDevStore.TYPE_QUEST)) {
            String cat = obj.has("category") ? shortId(obj.get("category").getAsString()) : "side";
            if ("main".equals(cat)) {
                return 0xFFD4AF37;
            }
            if ("contract".equals(cat)) {
                return 0xFFC04030;
            }
            return 0xFF4A90E2;
        }
        if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
            String pres = obj.has("presentation") ? obj.get("presentation").getAsString() : "screen";
            return "bubble".equals(pres) ? 0xFF50C878 : 0xFF26A69A;
        }
        if (type.equals(ClientDevStore.TYPE_NPC)) {
            return 0xFF66BB6A;
        }
        if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
            return 0xFFD49A5A;
        }
        if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
            return 0xFFAB47BC;
        }
        if (type.equals(ClientDevStore.TYPE_BOSS)) {
            return 0xFFE53935;
        }
        if (type.equals(ClientDevStore.TYPE_CODEX)) {
            return 0xFF5C6BC0;
        }
        return 0xFFFFA726;
    }

    private ItemStack iconForNode(NodeKey key, JsonObject obj) {
        ResourceLocation t = key.type();
        if (t.equals(ClientDevStore.TYPE_DOCUMENT)) {
            DocumentDefinition def = DocumentRegistries.DOCUMENTS.clientStore().getOrNull(key.id());
            return Documents.createStack(key.id(), def);
        }
        if (t.equals(ClientDevStore.TYPE_QUEST)) {
            String cat = obj.has("category") ? shortId(obj.get("category").getAsString()) : "side";
            if ("contract".equals(cat)) {
                return new ItemStack(Items.PAPER);
            }
            return new ItemStack("main".equals(cat) ? Items.ENCHANTED_BOOK : Items.WRITABLE_BOOK);
        }
        if (t.equals(ClientDevStore.TYPE_DIALOGUE)) {
            String pres = obj.has("presentation") ? obj.get("presentation").getAsString() : "screen";
            return new ItemStack("bubble".equals(pres) ? Items.FEATHER : Items.GOAT_HORN);
        }
        if (t.equals(ClientDevStore.TYPE_NPC)) {
            return new ItemStack(Items.EMERALD);
        }
        if (t.equals(ClientDevStore.TYPE_WAYPOINT)) {
            return new ItemStack(Items.COMPASS);
        }
        if (t.equals(ClientDevStore.TYPE_BOSS)) {
            return new ItemStack(Items.IRON_SWORD);
        }
        if (t.equals(ClientDevStore.TYPE_CODEX)) {
            return new ItemStack(Items.BOOK);
        }
        return new ItemStack(Items.REDSTONE);
    }

    private void rebuildInspectorWidgets() {
        clearWidgets();
        titleBox = null;
        secondaryBox = null;
        sbtBox = null;
        catalogSearchBox = null;

        if (catalogOpen) {
            int mx = (width - MODAL_W) / 2;
            int my = (height - MODAL_H) / 2;
            addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                catalogOpen = false;
                rebuildInspectorWidgets();
            }).bounds(mx + MODAL_W - 22, my + 4, 18, 16).build());

            int searchY = my + 24;
            if (catalogShowLocationTabs) {
                int tabW = (MODAL_W - 20) / 3;
                Button t0 = Button.builder(Component.literal("🏰 Structure"), b -> switchCatalogCategory(0))
                        .bounds(mx + 8, my + 22, tabW, 16).build();
                t0.active = catalogCategory != 0;
                addRenderableWidget(t0);

                Button t1 = Button.builder(Component.literal("🌲 Biome"), b -> switchCatalogCategory(1))
                        .bounds(mx + 8 + tabW + 2, my + 22, tabW, 16).build();
                t1.active = catalogCategory != 1;
                addRenderableWidget(t1);

                Button t2 = Button.builder(Component.literal("📌 POI"), b -> switchCatalogCategory(2))
                        .bounds(mx + 8 + (tabW + 2) * 2, my + 22, tabW, 16).build();
                t2.active = catalogCategory != 2;
                addRenderableWidget(t2);
                searchY = my + 42;
            }

            catalogSearchBox = new EditBox(font, mx + 8, searchY, MODAL_W - 84, 16, Component.literal("Search..."));
            catalogSearchBox.setMaxLength(128);
            catalogSearchBox.setHint(Component.literal("🔍 Search name or ID..."));
            catalogSearchBox.setValue(catalogFilter);
            catalogSearchBox.setResponder(val -> {
                catalogFilter = val;
                catalogScroll = 0;
            });
            addRenderableWidget(catalogSearchBox);
            setFocused(catalogSearchBox);
            catalogSearchBox.setFocused(true);

            addRenderableWidget(Button.builder(Component.literal("+ Custom ID"), b -> {
                String raw = catalogFilter.trim();
                if (!raw.isEmpty() && catalogCallback != null) {
                    String normalized = (!raw.contains(":") && !raw.startsWith("#")) ? "minecraft:" + raw : raw;
                    BiConsumer<Integer, CatalogEntry> cb = catalogCallback;
                    int cat = catalogCategory;
                    catalogOpen = false;
                    cb.accept(cat, new CatalogEntry(normalized, normalized, new ItemStack(Items.NAME_TAG)));
                    rebuildInspectorWidgets();
                }
            }).bounds(mx + MODAL_W - 72, searchY - 1, 64, 18).build());
            return;
        }

        addRenderableWidget(Button.builder(Component.literal("← Menu"), b -> onClose())
                .bounds(4, 3, 52, 18).build());
        Button undoBtn = Button.builder(Component.literal("< Undo"), b -> performUndo())
                .bounds(58, 3, 38, 18).build();
        undoBtn.active = !undoStack.isEmpty();
        addRenderableWidget(undoBtn);
        Button redoBtn = Button.builder(Component.literal("Redo >"), b -> performRedo())
                .bounds(98, 3, 42, 18).build();
        redoBtn.active = !redoStack.isEmpty();
        addRenderableWidget(redoBtn);
        addRenderableWidget(Button.builder(Component.literal("+ Chapter"), b -> createNewChapter())
                .bounds(6, height - 46, LEFT_PANEL_W - 12, 18).build());
        addRenderableWidget(Button.builder(Component.literal("📥 Example Story"), b -> {
            PacketDistributor.sendToServer(new DevModePayloads.QuickAction("install_example", Storybound.id("example")));
            status("Example story installed!", 0xFFA8E6A3);
        }).bounds(6, height - 24, LEFT_PANEL_W - 12, 18).build());

        if (isInspectorVisible()) {
            int rx = width - RIGHT_PANEL_W + 8;
            int rw = RIGHT_PANEL_W - 16;
            int y = TOP_BAR_H + 30;

            ResourceLocation type = selectedNode.type();

            if (type.equals(ClientDevStore.TYPE_QUEST)) {
                int tbw = (rw - 4) / 3;
                int objCount = getFirstStageObjectives(editingJson).size();
                int rewCount = editingJson.has("rewards") && editingJson.get("rewards").isJsonArray()
                        ? editingJson.getAsJsonArray("rewards").size() : 0;

                Button tabGen = Button.builder(Component.literal("General"), b -> {
                    questSubTab = 0;
                    rebuildInspectorWidgets();
                }).bounds(rx, y, tbw, 16).build();
                tabGen.active = questSubTab != 0;
                addRenderableWidget(tabGen);

                Button tabObj = Button.builder(Component.literal("Objectives (" + objCount + ")"), b -> {
                    questSubTab = 1;
                    rebuildInspectorWidgets();
                }).bounds(rx + tbw + 2, y, tbw, 16).build();
                tabObj.active = questSubTab != 1;
                addRenderableWidget(tabObj);

                Button tabRew = Button.builder(Component.literal("Rewards (" + rewCount + ")"), b -> {
                    questSubTab = 2;
                    rebuildInspectorWidgets();
                }).bounds(rx + (tbw + 2) * 2, y, tbw, 16).build();
                tabRew.active = questSubTab != 2;
                addRenderableWidget(tabRew);
                y += 20;

                if (questSubTab == 0) {
                    y += 10;
                    titleBox = new EditBox(font, rx, y, rw, 16, Component.literal("Title"));
                    titleBox.setMaxLength(256);
                    titleBox.setValue(ClientDevStore.readStoryText(editingJson, "title", selectedNode.id().getPath()));
                    titleBox.setResponder(val -> ClientDevStore.writeStoryText(editingJson, "title", val));
                    addRenderableWidget(titleBox);
                    y += 20;

                    String cat = editingJson.has("category") ? shortId(editingJson.get("category").getAsString()) : "side";
                    addRenderableWidget(Button.builder(Component.literal("Type: " + cat), b -> {
                        String next = switch (cat) {
                            case "main" -> "side";
                            case "side" -> "contract";
                            default -> "main";
                        };
                        editingJson.addProperty("category", "storybound:" + next);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw / 2 - 2, 18).build());

                    String failPeriod = readQuestFailPeriod(editingJson);
                    String failLabel = switch (failPeriod) {
                        case "day" -> "Until sunrise";
                        case "sunset" -> "Until sunset";
                        case "night" -> "Until night";
                        default -> "None";
                    };
                    addRenderableWidget(Button.builder(Component.literal("Deadline: " + failLabel), b -> {
                        String next = switch (failPeriod) {
                            case "" -> "day";
                            case "day" -> "sunset";
                            case "sunset" -> "night";
                            default -> "";
                        };
                        writeQuestFailPeriod(editingJson, next);
                        rebuildInspectorWidgets();
                    }).bounds(rx + rw / 2 + 2, y, rw / 2 - 2, 18).build());
                    y += 20;

                    String scope = editingJson.has("scope") ? editingJson.get("scope").getAsString() : "player";
                    addRenderableWidget(Button.builder(Component.literal("Scope: " + scope), b -> {
                        String next = switch (scope) {
                            case "player" -> "party";
                            case "party" -> "world";
                            default -> "player";
                        };
                        editingJson.addProperty("scope", next);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw / 2 - 2, 18).build());

                    boolean notify = !editingJson.has("notify") || editingJson.get("notify").getAsBoolean();
                    addRenderableWidget(Button.builder(Component.literal("Toasts: " + (notify ? "Yes" : "No")), b -> {
                        editingJson.addProperty("notify", !notify);
                        rebuildInspectorWidgets();
                    }).bounds(rx + rw / 2 + 2, y, rw / 2 - 2, 18).build());
                    y += 30;

                    secondaryBox = new EditBox(font, rx, y, rw, 16, Component.literal("Description:"));
                    secondaryBox.setMaxLength(512);
                    secondaryBox.setValue(ClientDevStore.readStoryText(editingJson, "description", ""));
                    secondaryBox.setResponder(val -> ClientDevStore.writeStoryText(editingJson, "description", val));
                    addRenderableWidget(secondaryBox);
                    y += 20;

                    int bw = (rw - 4) / 3;
                    addRenderableWidget(Button.builder(Component.literal("▶ Start"), b -> runPlaytestAction("quest_start"))
                            .bounds(rx, y, bw, 18).build());
                    addRenderableWidget(Button.builder(Component.literal("✓ Complete"), b -> runPlaytestAction("quest_complete"))
                            .bounds(rx + bw + 2, y, bw, 18).build());
                    addRenderableWidget(Button.builder(Component.literal("↺ Reset"), b -> runPlaytestAction("quest_reset"))
                            .bounds(rx + (bw + 2) * 2, y, bw, 18).build());
                } else if (questSubTab == 1) {
                    addRenderableWidget(Button.builder(Component.literal("🗺 + Visit (Biome / POI / Structure)"), b ->
                            openLocationCatalog("Select Structure, Biome or POI", (cat, entry) -> {
                                addLocationObjective(editingJson, cat, entry.id(), entry.label());
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw, 18).build());
                    y += 20;

                    addRenderableWidget(Button.builder(Component.literal("⚔ + Kill Mob (from catalog)"), b ->
                            openSingleCatalog("Select mob or #tag for objective", 3, (cat, entry) -> {
                                addKillObjective(editingJson, entry.id(), "Kill: " + entry.label(), 1);
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw, 18).build());
                    y += 20;

                    addRenderableWidget(Button.builder(Component.literal("🎒 + Item (catalog)"), b ->
                            openSingleCatalog("Select item for objective", 4, (cat, entry) -> {
                                addItemObjective(editingJson, entry.id(), entry.label(), 1);
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw - 62, 18).build());
                    addRenderableWidget(Button.builder(Component.literal("✋ Hand"), b -> {
                        addItemObjectiveFromHand(editingJson);
                        saveSelectedNode();
                        rebuildInspectorWidgets();
                    }).bounds(rx + rw - 58, y, 58, 18).build());
                    y += 20;

                    int halfW = (rw - 4) / 3;
                    addRenderableWidget(Button.builder(Component.literal("Count -1"), b -> {
                        adjustLastObjectiveCount(editingJson, -1);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, halfW, 18).build());
                    addRenderableWidget(Button.builder(Component.literal("Count +1"), b -> {
                        adjustLastObjectiveCount(editingJson, 1);
                        rebuildInspectorWidgets();
                    }).bounds(rx + halfW + 2, y, halfW, 18).build());
                    addRenderableWidget(Button.builder(Component.literal("✕ Remove"), b -> {
                        removeLastObjective(editingJson);
                        rebuildInspectorWidgets();
                    }).bounds(rx + (halfW + 2) * 2, y, halfW, 18).build());
                } else {
                    addRenderableWidget(Button.builder(Component.literal("🎒 + Catalog"), b ->
                            openSingleCatalog("Select reward item", 4, (cat, entry) -> {
                                addItemReward(editingJson, entry.id(), 1);
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw / 2 - 2, 18).build());
                    addRenderableWidget(Button.builder(Component.literal("✋ + From Hand (NBT)"), b -> {
                        addRewardFromHand(editingJson);
                        saveSelectedNode();
                        rebuildInspectorWidgets();
                    }).bounds(rx + rw / 2 + 2, y, rw / 2 - 2, 18).build());
                    y += 20;

                    addRenderableWidget(Button.builder(Component.literal("💎 + Emeralds (+5)"), b -> {
                        addEmeraldReward(editingJson, 5);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw / 2 - 2, 18).build());
                    addRenderableWidget(Button.builder(Component.literal("✨ + Exp (+50 XP)"), b -> {
                        addCommandReward(editingJson, "xp add @s 50 points");
                        rebuildInspectorWidgets();
                    }).bounds(rx + rw / 2 + 2, y, rw / 2 - 2, 18).build());
                    y += 30;

                    secondaryBox = new EditBox(font, rx, y, rw, 16, Component.literal("Reward command (@s = player):"));
                    secondaryBox.setMaxLength(256);
                    secondaryBox.setValue("give @s minecraft:golden_apple 2");
                    addRenderableWidget(secondaryBox);
                    y += 19;

                    addRenderableWidget(Button.builder(Component.literal("+ Command"), b -> {
                        if (secondaryBox != null && !secondaryBox.getValue().isBlank()) {
                            addCommandReward(editingJson, secondaryBox.getValue().trim());
                            rebuildInspectorWidgets();
                        }
                    }).bounds(rx, y, rw - 64, 18).build());
                    addRenderableWidget(Button.builder(Component.literal("✕ Remove"), b -> {
                        removeLastReward(editingJson);
                        rebuildInspectorWidgets();
                    }).bounds(rx + rw - 60, y, 60, 18).build());
                }
            } else {
                if (!type.equals(ClientDevStore.TYPE_NPC) && !type.equals(ClientDevStore.TYPE_EVENT)) {
                    y += 11;
                    String field = type.equals(ClientDevStore.TYPE_WAYPOINT) ? "name" : "title";
                    titleBox = new EditBox(font, rx, y, rw, 16, Component.literal("Title"));
                    titleBox.setMaxLength(256);
                    titleBox.setValue(ClientDevStore.readStoryText(editingJson, field, selectedNode.id().getPath()));
                    titleBox.setResponder(val -> ClientDevStore.writeStoryText(editingJson, field, val));
                    addRenderableWidget(titleBox);
                    y += 22;
                }

                if (type.equals(ClientDevStore.TYPE_DIALOGUE)) {
                    String pres = editingJson.has("presentation") ? editingJson.get("presentation").getAsString() : "screen";
                    String presLabel = switch (pres) {
                        case "bubble" -> "Bubble (Small Talk)";
                        case "hud" -> "Subtitles (HUD)";
                        default -> "Screen (Full UI)";
                    };
                    addRenderableWidget(Button.builder(Component.literal("Mode: " + presLabel), b -> {
                        String next = switch (pres) {
                            case "screen" -> "bubble";
                            case "bubble" -> "hud";
                            default -> "screen";
                        };
                        editingJson.addProperty("presentation", next);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw, 18).build());
                    y += 21;

                    String cam = editingJson.has("camera") ? editingJson.get("camera").getAsString() : "auto";
                    addRenderableWidget(Button.builder(Component.literal("Camera: " + cam), b -> {
                        editingJson.addProperty("camera", "auto".equals(cam) ? "off" : "auto");
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw / 2 - 2, 18).build());

                    boolean closable = !editingJson.has("closable") || editingJson.get("closable").getAsBoolean();
                    addRenderableWidget(Button.builder(Component.literal("ESC: " + (closable ? "Yes" : "No")), b -> {
                        editingJson.addProperty("closable", !closable);
                        rebuildInspectorWidgets();
                    }).bounds(rx + rw / 2 + 2, y, rw / 2 - 2, 18).build());
                    y += 33;

                    secondaryBox = new EditBox(font, rx, y, rw, 16, Component.literal("First dialogue line:"));
                    secondaryBox.setMaxLength(512);
                    secondaryBox.setValue(readFirstDialogueLine(editingJson));
                    secondaryBox.setResponder(val -> writeFirstDialogueLine(editingJson, val));
                    addRenderableWidget(secondaryBox);
                    y += 22;

                    addRenderableWidget(Button.builder(Component.literal("+ Trade branch (open_trade)"), b -> {
                        addTradeChoiceToDialogue(editingJson);
                        status("Added trade choice (open_trade)", 0xFFA8E6A3);
                    }).bounds(rx, y, rw, 18).build());
                    y += 21;

                    addRenderableWidget(Button.builder(Component.literal("▶ Start dialogue now"), b -> runPlaytestAction("dialogue_start"))
                            .bounds(rx, y, rw, 18).build());
                    y += 22;
                } else if (type.equals(ClientDevStore.TYPE_NPC)) {
                    boolean ov = editingJson.has("override_interact") && editingJson.get("override_interact").getAsBoolean();
                    addRenderableWidget(Button.builder(Component.literal("Override RMB (Trade→Dialogue): " + (ov ? "ON" : "OFF")), b -> {
                        editingJson.addProperty("override_interact", !ov);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw, 18).build());
                    y += 21;

                    addRenderableWidget(Button.builder(Component.literal("📋 Select mob/NPC type (catalog)"), b ->
                            openSingleCatalog("Select NPC entity type", 3, (cat, entry) -> {
                                JsonObject ent = editingJson.has("entity") && editingJson.get("entity").isJsonObject()
                                        ? editingJson.getAsJsonObject("entity") : new JsonObject();
                                ent.addProperty("type", entry.id());
                                if (!ent.has("tag") || ent.get("tag").getAsString().isBlank()) {
                                    ent.addProperty("tag", selectedNode.id().getPath());
                                }
                                editingJson.add("entity", ent);
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw, 18).build());
                    y += 32;

                    secondaryBox = new EditBox(font, rx, y, rw, 16, Component.literal("NPC tag (/tag @e add ...):"));
                    secondaryBox.setMaxLength(128);
                    JsonObject entityObj = editingJson.has("entity") && editingJson.get("entity").isJsonObject()
                            ? editingJson.getAsJsonObject("entity") : new JsonObject();
                    secondaryBox.setValue(entityObj.has("tag") ? entityObj.get("tag").getAsString() : "elder");
                    secondaryBox.setResponder(val -> {
                        JsonObject ent = editingJson.has("entity") && editingJson.get("entity").isJsonObject()
                                ? editingJson.getAsJsonObject("entity") : new JsonObject();
                        if (!ent.has("type")) {
                            ent.addProperty("type", "minecraft:villager");
                        }
                        ent.addProperty("tag", val);
                        editingJson.add("entity", ent);
                    });
                    addRenderableWidget(secondaryBox);
                    y += 22;

                    String period = readFirstEntryPeriod(editingJson);
                    addRenderableWidget(Button.builder(Component.literal("Schedule: " + (period.isEmpty() ? "Always" : period)), b -> {
                        String next = switch (period) {
                            case "" -> "day";
                            case "day" -> "sunset";
                            case "sunset" -> "night";
                            default -> "";
                        };
                        writeFirstEntryPeriod(editingJson, next);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw, 18).build());
                    y += 22;
                } else if (type.equals(ClientDevStore.TYPE_DOCUMENT)) {
                    String kind = editingJson.has("kind") ? editingJson.get("kind").getAsString() : "note";
                    addRenderableWidget(Button.builder(Component.literal("Item template: " + kind), b -> {
                        String next = switch (kind) {
                            case "book" -> "scroll";
                            case "scroll" -> "note";
                            case "note" -> "letter";
                            case "letter" -> "contract";
                            default -> "book";
                        };
                        editingJson.addProperty("kind", next);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw, 18).build());
                    y += 33;

                    secondaryBox = new EditBox(font, rx, y, rw, 16, Component.literal("Hint / Quest link:"));
                    secondaryBox.setMaxLength(256);
                    JsonObject contract = editingJson.has("contract") && editingJson.get("contract").isJsonObject()
                            ? editingJson.getAsJsonObject("contract") : new JsonObject();
                    secondaryBox.setValue(ClientDevStore.readStoryText(contract, "hint", ""));
                    secondaryBox.setResponder(val -> {
                        JsonObject c = editingJson.has("contract") && editingJson.get("contract").isJsonObject()
                                ? editingJson.getAsJsonObject("contract") : new JsonObject();
                        if (val.isBlank()) {
                            c.remove("hint");
                        } else {
                            ClientDevStore.writeStoryText(c, "hint", val);
                        }
                        editingJson.add("contract", c);
                    });
                    addRenderableWidget(secondaryBox);
                    y += 31;

                    sbtBox = new EditBox(font, rx, y, rw, 16, Component.literal("Document text (.sbt)"));
                    sbtBox.setMaxLength(2048);
                    String currentSbt = ClientConfigTexts.document(selectedNode.id(), "ru_ru")
                            .or(() -> ClientConfigTexts.document(selectedNode.id(), "en_us"))
                            .orElse("Document text...");
                    sbtBox.setValue(currentSbt.replace("\r", "").replace("\n", " "));
                    addRenderableWidget(sbtBox);
                    y += 22;

                    addRenderableWidget(Button.builder(Component.literal("🎒 Give item to self"), b -> runPlaytestAction("document_give"))
                            .bounds(rx, y, rw, 18).build());
                    y += 22;
                } else if (type.equals(ClientDevStore.TYPE_WAYPOINT)) {
                    String wpMode = readWaypointMode(editingJson);
                    addRenderableWidget(Button.builder(Component.literal("Mode: " + wpMode), b -> {
                        cycleWaypointMode(editingJson);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw, 18).build());
                    y += 20;

                    addRenderableWidget(Button.builder(Component.literal("🏰 Select structure (catalog)"), b ->
                            openSingleCatalog("Select structure for waypoint", 0, (cat, entry) -> {
                                JsonObject target = new JsonObject();
                                target.addProperty("structure", entry.id());
                                target.addProperty("search_radius", 100);
                                editingJson.add("target", target);
                                editingJson.remove("radius");
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw, 18).build());
                    y += 20;

                    addRenderableWidget(Button.builder(Component.literal("⚔ Nearest mob / NPC (catalog)"), b ->
                            openSingleCatalog("Select mob for waypoint", 3, (cat, entry) -> {
                                JsonObject target = new JsonObject();
                                target.addProperty("nearest", entry.id());
                                target.addProperty("range", 64);
                                editingJson.add("target", target);
                                editingJson.remove("radius");
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw, 18).build());
                    y += 20;

                    String colHex = editingJson.has("color") && editingJson.get("color").isJsonPrimitive()
                            ? editingJson.get("color").getAsString() : "#E8C66A";
                    addRenderableWidget(Button.builder(Component.literal("Color: " + colHex), b -> {
                        String nextCol = switch (colHex.toUpperCase(Locale.ROOT)) {
                            case "#E8C66A" -> "#E53935";
                            case "#E53935" -> "#66BB6A";
                            case "#66BB6A" -> "#4FC3F7";
                            case "#4FC3F7" -> "#AB47BC";
                            default -> "#E8C66A";
                        };
                        editingJson.addProperty("color", nextCol);
                        rebuildInspectorWidgets();
                    }).bounds(rx, y, rw / 2 - 2, 18).build());

                    String styleStr = editingJson.has("style") ? shortId(editingJson.get("style").getAsString()) : "default";
                    addRenderableWidget(Button.builder(Component.literal("Style: " + styleStr), b -> {
                        editingJson.addProperty("style", "default".equals(styleStr) ? "storybound:npc" : "storybound:default");
                        rebuildInspectorWidgets();
                    }).bounds(rx + rw / 2 + 2, y, rw / 2 - 2, 18).build());
                    y += 20;

                    addRenderableWidget(Button.builder(Component.literal("⚡ Teleport to waypoint"), b -> runPlaytestAction("waypoint_tp"))
                            .bounds(rx, y, rw, 18).build());
                    y += 22;
                } else if (type.equals(ClientDevStore.TYPE_BOSS)) {
                    addRenderableWidget(Button.builder(Component.literal("📋 Select boss mob (catalog)"), b ->
                            openSingleCatalog("Select boss mob type", 3, (cat, entry) -> {
                                JsonObject ent = editingJson.has("entity") && editingJson.get("entity").isJsonObject()
                                        ? editingJson.getAsJsonObject("entity") : new JsonObject();
                                ent.addProperty("type", entry.id());
                                if (!ent.has("tag") || ent.get("tag").getAsString().isBlank()) {
                                    ent.addProperty("tag", selectedNode.id().getPath());
                                }
                                editingJson.add("entity", ent);
                                ClientDevStore.writeStoryText(editingJson, "title", entry.label());
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw, 18).build());
                    y += 32;

                    secondaryBox = new EditBox(font, rx, y, rw, 16, Component.literal("Bestiary lore:"));
                    secondaryBox.setMaxLength(512);
                    secondaryBox.setValue(ClientDevStore.readStoryText(editingJson, "lore", ""));
                    secondaryBox.setResponder(val -> ClientDevStore.writeStoryText(editingJson, "lore", val));
                    addRenderableWidget(secondaryBox);
                    y += 22;
                } else if (type.equals(ClientDevStore.TYPE_EVENT)) {
                    addRenderableWidget(Button.builder(Component.literal("🗺 Trigger: Location (Biome/POI/Struct)"), b ->
                            openLocationCatalog("Select location for event", (cat, entry) -> {
                                JsonObject trig = new JsonObject();
                                if (cat == 0) {
                                    trig.addProperty("type", "storybound:structure_entered");
                                    trig.addProperty("structure", entry.id());
                                } else if (cat == 1) {
                                    trig.addProperty("type", "storybound:biome_entered");
                                    trig.addProperty("biome", entry.id());
                                } else {
                                    trig.addProperty("type", "storybound:poi_entered");
                                    trig.addProperty("poi", entry.id());
                                    trig.addProperty("radius", 12);
                                }
                                editingJson.add("trigger", trig);
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw, 18).build());
                    y += 20;

                    addRenderableWidget(Button.builder(Component.literal("⚔ Trigger: Mob Kill (catalog)"), b ->
                            openSingleCatalog("Select mob for event trigger", 3, (cat, entry) -> {
                                JsonObject trig = new JsonObject();
                                trig.addProperty("type", "storybound:entity_killed");
                                trig.addProperty("entity", entry.id());
                                editingJson.add("trigger", trig);
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw, 18).build());
                    y += 20;

                    addRenderableWidget(Button.builder(Component.literal("🎒 Trigger: Item (catalog)"), b ->
                            openSingleCatalog("Select item for event trigger", 4, (cat, entry) -> {
                                JsonObject trig = new JsonObject();
                                trig.addProperty("type", "storybound:item_obtained");
                                trig.addProperty("item", entry.id());
                                editingJson.add("trigger", trig);
                                saveSelectedNode();
                            })
                    ).bounds(rx, y, rw, 18).build());
                    y += 22;
                }
            }

            addRenderableWidget(Button.builder(Component.literal("💾 Save"), b -> saveSelectedNode())
                    .bounds(rx, height - 26, rw - 54, 20).build());
            addRenderableWidget(Button.builder(Component.literal("🗑"), b -> deleteSelectedNode())
                    .bounds(rx + rw - 50, height - 26, 50, 20).build());
        }
    }

    private void runPlaytestAction(String action) {
        if (selectedNode == null) {
            return;
        }
        ResourceLocation targetId = selectedNode.id();
        saveSelectedNode();
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
        PacketDistributor.sendToServer(new DevModePayloads.QuickAction(action, targetId));
    }

    private void pushHistory(String label, Runnable undo, Runnable redo) {
        undoStack.push(new HistoryAction(label, undo, redo));
        if (undoStack.size() > 64) {
            undoStack.removeLast();
        }
        redoStack.clear();
        rebuildInspectorWidgets();
    }

    private void performUndo() {
        if (undoStack.isEmpty()) {
            status("Nothing to undo (Ctrl+Z)", 0xFF90A4AE);
            return;
        }
        HistoryAction action = undoStack.pop();
        action.undo().run();
        redoStack.push(action);
        rebuildGraph();
        rebuildInspectorWidgets();
        status("↶ Undone: " + action.label(), 0xFFFFD54F);
    }

    private void performRedo() {
        if (redoStack.isEmpty()) {
            status("Nothing to redo (Ctrl+Y)", 0xFF90A4AE);
            return;
        }
        HistoryAction action = redoStack.pop();
        action.redo().run();
        undoStack.push(action);
        rebuildGraph();
        rebuildInspectorWidgets();
        status("↷ Redone: " + action.label(), 0xFF69F0AE);
    }

    private boolean isInspectorVisible() {
        return selectedNode != null && editingJson != null && !inspectorCollapsed;
    }

    private void saveSelectedNode() {
        if (selectedNode == null || editingJson == null) {
            return;
        }
        NodeKey target = selectedNode;
        String oldJson = ClientDevStore.getJson(target.type(), target.id());
        Optional<String> oldSbt = ClientConfigTexts.document(target.id(), "ru_ru")
                .or(() -> ClientConfigTexts.document(target.id(), "en_us"));
        String newJson = GSON.toJson(editingJson);
        Optional<String> newSbt = sbtBox != null ? Optional.of(sbtBox.getValue()) : Optional.empty();

        if (oldJson != null) {
            pushHistory("save " + target.id().getPath(),
                    () -> PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(target.type(), target.id(), oldJson, oldSbt)),
                    () -> PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(target.type(), target.id(), newJson, newSbt)));
        }
        ClientDevStore.putLocal(target.type(), target.id(), newJson);
        PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(target.type(), target.id(), newJson, newSbt));
        saveActiveChapterPositions();
        status("Saved: " + target.id(), 0xFFA8E6A3);
    }

    private void deleteSelectedNode() {
        if (selectedNode != null && selectedNode.type().equals(ClientDevStore.TYPE_CHAPTER)) {
            ResourceLocation chapterId = selectedNode.id();
            selectedNode = null;
            selectedNodes.clear();
            editingJson = null;
            deleteChapter(chapterId);
            rebuildInspectorWidgets();
            return;
        }
        deleteSelectedNodes();
    }

    private void deleteSelectedNodes() {
        Set<NodeKey> targets = new LinkedHashSet<>(selectedNodes);
        if (selectedNode != null) {
            targets.add(selectedNode);
        }
        if (targets.isEmpty()) {
            return;
        }
        record DeletedSnapshot(NodeKey key, String json, Optional<String> sbt, int @Nullable [] pos) {}
        List<DeletedSnapshot> snapshots = new ArrayList<>();
        for (NodeKey victim : targets) {
            String oldJson = ClientDevStore.getJson(victim.type(), victim.id());
            if (oldJson != null) {
                Optional<String> oldSbt = ClientConfigTexts.document(victim.id(), "ru_ru")
                        .or(() -> ClientConfigTexts.document(victim.id(), "en_us"));
                int[] p = positions.get(victim);
                snapshots.add(new DeletedSnapshot(victim, oldJson, oldSbt, p != null ? new int[]{p[0], p[1]} : null));
            }
            positions.remove(victim);
            PacketDistributor.sendToServer(new DevModePayloads.DeleteDefinition(victim.type(), victim.id()));
        }
        selectedNode = null;
        selectedNodes.clear();
        editingJson = null;

        if (!snapshots.isEmpty()) {
            String label = snapshots.size() == 1
                    ? "delete " + snapshots.getFirst().key().id().getPath()
                    : "delete (" + snapshots.size() + " nodes)";
            pushHistory(label,
                    () -> {
                        for (DeletedSnapshot s : snapshots) {
                            if (s.pos() != null) {
                                positions.put(s.key(), new int[]{s.pos()[0], s.pos()[1]});
                            }
                            PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(
                                    s.key().type(), s.key().id(), s.json(), s.sbt()));
                        }
                        saveActiveChapterPositions();
                    },
                    () -> {
                        for (DeletedSnapshot s : snapshots) {
                            positions.remove(s.key());
                            PacketDistributor.sendToServer(new DevModePayloads.DeleteDefinition(
                                    s.key().type(), s.key().id()));
                        }
                        saveActiveChapterPositions();
                    });
        } else {
            rebuildInspectorWidgets();
        }
        saveActiveChapterPositions();
        status("Deleted nodes: " + targets.size() + " (Ctrl+Z to undo)", 0xFFFF8A80);
    }

    private List<Map.Entry<ResourceLocation, String>> sortedChapters() {
        List<Map.Entry<ResourceLocation, String>> chapters = new ArrayList<>(
                ClientDevStore.byType(ClientDevStore.TYPE_CHAPTER).entrySet());
        chapters.sort(Comparator.<Map.Entry<ResourceLocation, String>>comparingInt(e -> {
            JsonObject o = ClientDevStore.parseObject(e.getValue());
            return o.has("order") ? o.get("order").getAsInt() : 0;
        }).thenComparing(e -> e.getKey().toString()));
        return chapters;
    }

    private void addNodeToActiveChapter(NodeKey key, int x, int y) {
        if (OVERVIEW_CHAPTER.equals(activeChapter)) {
            return;
        }
        String raw = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, activeChapter);
        JsonObject chapter = ClientDevStore.parseObject(raw);
        if (!chapter.has("title")) {
            ClientDevStore.writeStoryText(chapter, "title", activeChapter.getPath());
        }
        JsonArray arr = chapter.has("nodes") && chapter.get("nodes").isJsonArray()
                ? chapter.getAsJsonArray("nodes") : new JsonArray();
        for (int i = arr.size() - 1; i >= 0; i--) {
            if (arr.get(i).isJsonObject()) {
                JsonObject item = arr.get(i).getAsJsonObject();
                if (key.id().toString().equals(item.has("id") ? item.get("id").getAsString() : "")) {
                    arr.remove(i);
                }
            }
        }
        JsonObject item = new JsonObject();
        item.addProperty("type", key.type().getPath());
        item.addProperty("id", key.id().toString());
        item.addProperty("x", x);
        item.addProperty("y", y);
        arr.add(item);
        chapter.add("nodes", arr);
        String jsonStr = GSON.toJson(chapter);
        ClientDevStore.putLocal(ClientDevStore.TYPE_CHAPTER, activeChapter, jsonStr);
        PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(
                ClientDevStore.TYPE_CHAPTER, activeChapter, jsonStr, Optional.empty()));
    }

    private void saveActiveChapterPositions() {
        if (OVERVIEW_CHAPTER.equals(activeChapter)) {
            return;
        }
        String raw = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, activeChapter);
        JsonObject chapter = ClientDevStore.parseObject(raw);
        if (!chapter.has("title")) {
            ClientDevStore.writeStoryText(chapter, "title", activeChapter.getPath());
        }
        JsonArray arr = new JsonArray();
        for (CanvasNode node : canvasNodes) {
            JsonObject item = new JsonObject();
            item.addProperty("type", node.key().type().getPath());
            item.addProperty("id", node.key().id().toString());
            item.addProperty("x", node.x());
            item.addProperty("y", node.y());
            arr.add(item);
        }
        chapter.add("nodes", arr);
        String jsonStr = GSON.toJson(chapter);
        ClientDevStore.putLocal(ClientDevStore.TYPE_CHAPTER, activeChapter, jsonStr);
        PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(
                ClientDevStore.TYPE_CHAPTER, activeChapter, jsonStr, Optional.empty()));
    }

    private void createNewChapter() {
        int maxOrder = 0;
        for (Map.Entry<ResourceLocation, String> entry : sortedChapters()) {
            JsonObject o = ClientDevStore.parseObject(entry.getValue());
            int ord = o.has("order") ? o.get("order").getAsInt() : 0;
            if (ord > maxOrder) {
                maxOrder = ord;
            }
        }
        int nextNum = maxOrder + 1;
        ResourceLocation id = Storybound.id("chapter_" + nextNum);
        while (ClientDevStore.byType(ClientDevStore.TYPE_CHAPTER).containsKey(id)) {
            nextNum++;
            id = Storybound.id("chapter_" + nextNum);
        }
        JsonObject chapter = new JsonObject();
        ClientDevStore.writeStoryText(chapter, "title", "Chapter " + nextNum);
        chapter.addProperty("order", nextNum);
        JsonArray arr = new JsonArray();
        for (CanvasNode node : canvasNodes) {
            if (selectedNodes.contains(node.key())) {
                JsonObject item = new JsonObject();
                item.addProperty("type", node.key().type().getPath());
                item.addProperty("id", node.key().id().toString());
                item.addProperty("x", node.x());
                item.addProperty("y", node.y());
                arr.add(item);
            }
        }
        chapter.add("nodes", arr);
        String jsonStr = GSON.toJson(chapter);
        ResourceLocation createdId = id;
        ResourceLocation prevChapter = activeChapter;
        activeChapter = createdId;
        ClientDevStore.putLocal(ClientDevStore.TYPE_CHAPTER, createdId, jsonStr);
        PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(
                ClientDevStore.TYPE_CHAPTER, createdId, jsonStr, Optional.empty()));
        pushHistory("create chapter " + createdId.getPath(),
                () -> {
                    activeChapter = prevChapter;
                    PacketDistributor.sendToServer(new DevModePayloads.DeleteDefinition(ClientDevStore.TYPE_CHAPTER, createdId));
                },
                () -> {
                    activeChapter = createdId;
                    PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(
                            ClientDevStore.TYPE_CHAPTER, createdId, jsonStr, Optional.empty()));
                });
        status("Created chapter: Chapter " + nextNum, 0xFFA8E6A3);
    }

    private void deleteChapter(ResourceLocation chapterId) {
        String oldJson = ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, chapterId);
        if (oldJson == null) {
            return;
        }
        if (chapterId.equals(activeChapter)) {
            activeChapter = OVERVIEW_CHAPTER;
        }
        PacketDistributor.sendToServer(new DevModePayloads.DeleteDefinition(ClientDevStore.TYPE_CHAPTER, chapterId));
        pushHistory("delete chapter " + chapterId.getPath(),
                () -> {
                    activeChapter = chapterId;
                    PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(
                            ClientDevStore.TYPE_CHAPTER, chapterId, oldJson, Optional.empty()));
                },
                () -> {
                    if (chapterId.equals(activeChapter)) {
                        activeChapter = OVERVIEW_CHAPTER;
                    }
                    PacketDistributor.sendToServer(new DevModePayloads.DeleteDefinition(ClientDevStore.TYPE_CHAPTER, chapterId));
                });
        status("Deleted chapter: " + chapterId.getPath(), 0xFFFF8A80);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int canvasLeft = LEFT_PANEL_W;
        boolean showInspector = isInspectorVisible();
        int canvasRight = showInspector ? width - RIGHT_PANEL_W : width;

        graphics.fill(0, 0, width, height, 0xFF141619);

        renderCanvas(graphics, canvasLeft, TOP_BAR_H, canvasRight, height, mouseX, mouseY);
        renderLeftSidebar(graphics, mouseX, mouseY);
        if (showInspector) {
            renderRightInspector(graphics, canvasRight);
            renderCollapseButton(graphics, canvasRight, TOP_BAR_H + (height - TOP_BAR_H) / 2, mouseX, mouseY);
        }
        renderTopBar(graphics);

        if (catalogOpen) {
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 250);
            renderCatalogModal(graphics, mouseX, mouseY);
            for (Renderable renderable : renderables) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
            graphics.pose().popPose();
        } else {
            for (Renderable renderable : renderables) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }

        if (contextMenuOpen) {
            renderContextMenu(graphics, mouseX, mouseY);
        }

        renderIssuesTooltipIfHovered(graphics, mouseX, mouseY);
    }

    private void renderCollapseButton(GuiGraphics graphics, int cx, int cy, int mouseX, int mouseY) {
        int r = 11;
        double dx = mouseX - cx;
        double dy = mouseY - cy;
        boolean hover = (dx * dx + dy * dy) <= (r + 2) * (r + 2);
        int borderCol = hover ? 0xFFE8C66A : 0xFF3A4150;
        int fillCol = hover ? 0xFF252A34 : 0xFF16191F;
        int iconCol = hover ? 0xFFFFFFFF : 0xFF90A4AE;

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 150);
        for (int py = -r; py <= r; py++) {
            int maxX = (int) Math.floor(Math.sqrt(r * r - py * py));
            graphics.fill(cx - maxX, cy + py, cx + maxX + 1, cy + py + 1, borderCol);
        }
        int ir = r - 1;
        for (int py = -ir; py <= ir; py++) {
            int maxX = (int) Math.floor(Math.sqrt(ir * ir - py * py));
            graphics.fill(cx - maxX, cy + py, cx + maxX + 1, cy + py + 1, fillCol);
        }
        for (int i = -3; i <= 3; i++) {
            int offset = 3 - Math.abs(i);
            graphics.fill(cx - 4 + offset, cy + i, cx - 2 + offset, cy + i + 1, iconCol);
            graphics.fill(cx + 1 + offset, cy + i, cx + 3 + offset, cy + i + 1, iconCol);
        }
        graphics.pose().popPose();
    }

    private void renderTopBar(GuiGraphics graphics) {
        graphics.fill(0, 0, width, TOP_BAR_H, 0xFF1E2229);
        graphics.fill(0, TOP_BAR_H - 1, width, TOP_BAR_H, 0xFF343A46);
        graphics.drawString(font, "⚙ Dev Mode", 145, 8, 0xFFE8C66A, false);
        String hint = "RMB: context menu | Shift+LMB: multi-select | Port ●: link | Del: delete";
        List<String> issues = ClientDevStore.issues();
        int issuesCount = issues.size();
        String rightInfo = issuesCount == 0 ? "✓ No issues" : "⚠ Issues: " + issuesCount;
        int rightColor = issuesCount == 0 ? 0xFF66BB6A : 0xFFFF7043;
        int rightX = width - font.width(rightInfo) - 10;
        graphics.drawString(font, rightInfo, rightX, 8, rightColor, false);
        if (!statusText.isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(statusText, Math.max(80, rightX - 220)), 215, 8, statusColor, false);
        } else if (width > 560) {
            graphics.drawString(font, font.plainSubstrByWidth(hint, Math.max(80, rightX - 220)), 215, 8, 0xFF8C96A6, false);
        }
    }

    private void renderIssuesTooltipIfHovered(GuiGraphics graphics, int mouseX, int mouseY) {
        List<String> issues = ClientDevStore.issues();
        if (issues.isEmpty()) {
            return;
        }
        String rightInfo = "⚠ Issues: " + issues.size();
        int rightX = width - font.width(rightInfo) - 10;
        if (mouseX >= rightX - 4 && mouseY <= TOP_BAR_H) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (String issue : issues) {
                int col = issue.contains("[ERROR]") ? 0xFFFF5252 : 0xFFFFD54F;
                lines.addAll(font.split(Component.literal(issue).withColor(col), 280));
            }
            graphics.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    private void renderLeftSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(0, TOP_BAR_H, LEFT_PANEL_W, height, 0xFF1B1E24);
        graphics.fill(LEFT_PANEL_W - 1, TOP_BAR_H, LEFT_PANEL_W, height, 0xFF343A46);
        graphics.drawString(font, "Story Chapters", 8, TOP_BAR_H + 8, 0xFFE8C66A, false);

        int y = TOP_BAR_H + 24;
        drawChapterRow(graphics, OVERVIEW_CHAPTER, "★ All Nodes (" + totalNodeCount() + ")", y, mouseX, mouseY);
        y += 20;

        for (Map.Entry<ResourceLocation, String> entry : sortedChapters()) {
            JsonObject obj = ClientDevStore.parseObject(entry.getValue());
            String name = ClientDevStore.readStoryText(obj, "title", entry.getKey().getPath());
            drawChapterRow(graphics, entry.getKey(), "• " + name, y, mouseX, mouseY);
            y += 20;
        }
    }

    private void drawChapterRow(GuiGraphics graphics, ResourceLocation id, String label, int y, int mouseX, int mouseY) {
        boolean selected = id.equals(activeChapter);
        boolean hover = mouseX >= 4 && mouseX < LEFT_PANEL_W - 4 && mouseY >= y && mouseY < y + 18;
        int bg = selected ? 0xFF2D3748 : hover ? 0xFF252A34 : 0x00000000;
        if (bg != 0) {
            graphics.fill(4, y, LEFT_PANEL_W - 4, y + 18, bg);
        }
        int color = selected ? 0xFFFFD54F : hover ? 0xFFFFFFFF : 0xFFB0B8C4;
        graphics.drawString(font, font.plainSubstrByWidth(label, LEFT_PANEL_W - 16), 8, y + 5, color, false);
    }

    private void renderRightInspector(GuiGraphics graphics, int left) {
        graphics.fill(left, TOP_BAR_H, width, height, 0xFF1B1E24);
        graphics.fill(left, TOP_BAR_H, left + 1, height, 0xFF343A46);
        if (selectedNode == null) {
            return;
        }
        String header = selectedNodes.size() > 1 ? "Inspector (selected: " + selectedNodes.size() + ")" : "Node Inspector";
        graphics.drawString(font, header, left + 8, TOP_BAR_H + 6, 0xFFE8C66A, false);
        graphics.drawString(font, font.plainSubstrByWidth(selectedNode.id().toString(), RIGHT_PANEL_W - 16),
                left + 8, TOP_BAR_H + 18, 0xFF90A4AE, false);

        if (titleBox != null) {
            graphics.drawString(font, "Name / Title:", left + 8, titleBox.getY() - 10, 0xFFB0B8C4, false);
        }
        if (secondaryBox != null) {
            graphics.drawString(font, secondaryBox.getMessage(), left + 8, secondaryBox.getY() - 10, 0xFFB0B8C4, false);
        }
        if (sbtBox != null) {
            graphics.drawString(font, "Document text (.sbt):", left + 8, sbtBox.getY() - 10, 0xFFB0B8C4, false);
        }

        if (editingJson != null && selectedNode.type().equals(ClientDevStore.TYPE_QUEST)) {
            if (questSubTab == 1) {
                int oy = TOP_BAR_H + 136;
                graphics.drawString(font, "Stage Objectives:", left + 8, oy, 0xFFE8C66A, false);
                oy += 12;
                JsonArray objs = getFirstStageObjectives(editingJson);
                if (objs.isEmpty()) {
                    graphics.drawString(font, "No objectives (add above)", left + 8, oy, 0xFF78909C, false);
                } else {
                    for (int i = 0; i < objs.size() && oy < height - 68; i++) {
                        if (!objs.get(i).isJsonObject()) {
                            continue;
                        }
                        JsonObject o = objs.get(i).getAsJsonObject();
                        int count = o.has("count") ? o.get("count").getAsInt() : 1;
                        String text = ClientDevStore.readStoryText(o, "text", o.has("id") ? o.get("id").getAsString() : "Objective");
                        String line = "• " + text + " (×" + count + ")";
                        graphics.drawString(font, font.plainSubstrByWidth(line, RIGHT_PANEL_W - 16), left + 8, oy, 0xFFE0E6ED, false);
                        oy += 12;
                    }
                }
            } else if (questSubTab == 2) {
                int ry = TOP_BAR_H + 148;
                graphics.drawString(font, "Rewards List:", left + 8, ry, 0xFFE8C66A, false);
                ry += 14;
                JsonArray rewards = editingJson.has("rewards") && editingJson.get("rewards").isJsonArray()
                        ? editingJson.getAsJsonArray("rewards") : new JsonArray();
                if (rewards.isEmpty()) {
                    graphics.drawString(font, "No rewards (add above)", left + 8, ry, 0xFF78909C, false);
                } else {
                    for (int i = 0; i < rewards.size() && ry < height - 68; i++) {
                        if (!rewards.get(i).isJsonObject()) {
                            continue;
                        }
                        JsonObject r = rewards.get(i).getAsJsonObject();
                        String rType = r.has("type") ? shortId(r.get("type").getAsString()) : "";
                        if ("give_item".equals(rType) && r.has("item")) {
                            ResourceLocation itemId = ResourceLocation.tryParse(r.get("item").getAsString());
                            Item item = itemId != null ? BuiltInRegistries.ITEM.get(itemId) : Items.AIR;
                            int count = r.has("count") ? r.get("count").getAsInt() : 1;
                            ItemStack stack = new ItemStack(item != Items.AIR ? item : Items.PAPER, count);
                            boolean hasNbt = r.has("components") && r.get("components").isJsonObject()
                                    && !r.getAsJsonObject("components").entrySet().isEmpty();
                            if (hasNbt && minecraft != null && minecraft.level != null) {
                                DataComponentPatch.CODEC.parse(
                                        RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess()),
                                        r.get("components")
                                ).result().ifPresent(stack::applyComponents);
                            }
                            graphics.renderItem(stack, left + 8, ry - 4);
                            String label = stack.getHoverName().getString() + " ×" + count + (hasNbt ? " [NBT]" : "");
                            graphics.drawString(font, font.plainSubstrByWidth(label, RIGHT_PANEL_W - 36),
                                    left + 28, ry, hasNbt ? 0xFFCE93D8 : 0xFFA8E6A3, false);
                            ry += 18;
                        } else if ("run_command".equals(rType) && r.has("command")) {
                            String cmd = "⚡ /" + r.get("command").getAsString();
                            graphics.drawString(font, font.plainSubstrByWidth(cmd, RIGHT_PANEL_W - 16),
                                    left + 8, ry, 0xFF80CBC4, false);
                            ry += 14;
                        } else {
                            graphics.drawString(font, "• " + rType, left + 8, ry, 0xFFB0B8C4, false);
                            ry += 14;
                        }
                    }
                }
            }
        } else if (editingJson != null && selectedNode.type().equals(ClientDevStore.TYPE_WAYPOINT)) {
            int wy = TOP_BAR_H + 162;
            String summary = describeWaypointTarget(editingJson);
            graphics.drawString(font, font.plainSubstrByWidth(summary, RIGHT_PANEL_W - 16), left + 8, wy, 0xFF80CBC4, false);
        } else if (editingJson != null && (selectedNode.type().equals(ClientDevStore.TYPE_NPC) || selectedNode.type().equals(ClientDevStore.TYPE_BOSS))) {
            JsonObject ent = editingJson.has("entity") && editingJson.get("entity").isJsonObject()
                    ? editingJson.getAsJsonObject("entity") : new JsonObject();
            String entType = ent.has("type") ? ent.get("type").getAsString() : "none";
            int ey = selectedNode.type().equals(ClientDevStore.TYPE_NPC) ? TOP_BAR_H + 148 : TOP_BAR_H + 132;
            graphics.drawString(font, font.plainSubstrByWidth("Mob: " + entType, RIGHT_PANEL_W - 16), left + 8, ey, 0xFF80CBC4, false);
        } else if (editingJson != null && selectedNode.type().equals(ClientDevStore.TYPE_EVENT)) {
            int ey = TOP_BAR_H + 96;
            graphics.drawString(font, "Active Trigger:", left + 8, ey, 0xFFE8C66A, false);
            graphics.drawString(font, font.plainSubstrByWidth(describeEventTrigger(editingJson), RIGHT_PANEL_W - 16),
                    left + 8, ey + 12, 0xFF80CBC4, false);
        }

        List<String> nodeIssues = ClientDevStore.issuesFor(selectedNode.type(), selectedNode.id());
        if (!nodeIssues.isEmpty()) {
            int iy = height - 62;
            graphics.fill(left + 6, iy - 2, width - 6, height - 30, 0x44E53935);
            graphics.drawString(font, font.plainSubstrByWidth("⚠ " + nodeIssues.getFirst(), RIGHT_PANEL_W - 16),
                    left + 8, iy + 2, 0xFFFF8A80, false);
        }
    }

    private void renderCanvas(GuiGraphics graphics, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        graphics.enableScissor(left, top, right, bottom);

        int step = Math.max(12, Math.round(24 * zoom));
        int offsetX = ((int) Math.round(panX) % step + step) % step;
        int offsetY = ((int) Math.round(panY) % step + step) % step;
        for (int gx = left + offsetX; gx < right; gx += step) {
            graphics.fill(gx, top, gx + 1, bottom, 0xFF1C2026);
        }
        for (int gy = top + offsetY; gy < bottom; gy += step) {
            graphics.fill(left, gy, right, gy + 1, 0xFF1C2026);
        }

        graphics.pose().pushPose();
        graphics.pose().translate((float) (left + panX), (float) (top + panY), 0);
        graphics.pose().scale(zoom, zoom, 1.0f);

        Map<NodeKey, CanvasNode> byKey = new HashMap<>();
        for (CanvasNode n : canvasNodes) {
            byKey.put(n.key(), n);
        }

        int edgeIdx = 0;
        for (Edge edge : edges) {
            CanvasNode from = byKey.get(edge.from());
            CanvasNode to = byKey.get(edge.to());
            if (from != null && to != null) {
                int x1 = from.x() + NODE_W;
                int y1 = from.y() + NODE_H / 2;
                int x2 = to.x();
                int y2 = to.y() + NODE_H / 2;
                boolean highlighted = selectedNodes.contains(edge.from()) || selectedNodes.contains(edge.to())
                        || edge.from().equals(selectedNode) || edge.to().equals(selectedNode);
                drawDirectedEdge(graphics, x1, y1, x2, y2, highlighted ? 0xFFFFD54F : 0xFF546E7A, edge.label(), edgeIdx);
                edgeIdx++;
            }
        }

        double worldMouseX = (mouseX - left - panX) / zoom;
        double worldMouseY = (mouseY - top - panY) / zoom;

        if (linkingFrom != null && byKey.containsKey(linkingFrom)) {
            CanvasNode src = byKey.get(linkingFrom);
            drawDirectedEdge(graphics, src.x() + NODE_W, src.y() + NODE_H / 2,
                    (int) worldMouseX, (int) worldMouseY, 0xFF69F0AE, "link", 0);
        }

        for (CanvasNode node : canvasNodes) {
            boolean primary = node.key().equals(selectedNode);
            boolean multi = selectedNodes.contains(node.key());
            boolean hovered = worldMouseX >= node.x() && worldMouseX < node.x() + NODE_W
                    && worldMouseY >= node.y() && worldMouseY < node.y() + NODE_H;
            renderNodeCard(graphics, node, primary, multi, hovered);
        }

        if (boxSelecting) {
            int bx1 = (int) Math.min(boxStartX, boxEndX);
            int by1 = (int) Math.min(boxStartY, boxEndY);
            int bx2 = (int) Math.max(boxStartX, boxEndX);
            int by2 = (int) Math.max(boxStartY, boxEndY);
            graphics.fill(bx1, by1, bx2, by2, 0x3364B5F6);
            drawHLine(graphics, bx1, bx2, by1, 0xFF64B5F6);
            drawHLine(graphics, bx1, bx2, by2, 0xFF64B5F6);
            drawVLine(graphics, bx1, by1, by2, 0xFF64B5F6);
            drawVLine(graphics, bx2, by1, by2, 0xFF64B5F6);
        }

        graphics.pose().popPose();
        graphics.disableScissor();
    }

    private void renderNodeCard(GuiGraphics graphics, CanvasNode node, boolean primary, boolean multi, boolean hovered) {
        int x = node.x();
        int y = node.y();
        int border = node.hasError() ? 0xFFFF5252 : primary ? 0xFFFFE082 : multi ? 0xFF4FC3F7 : hovered ? 0xFFB0BEC5 : 0xFF37474F;
        int pad = (primary || multi) ? 2 : 1;
        graphics.fill(x - pad, y - pad, x + NODE_W + pad, y + NODE_H + pad, border);
        graphics.fill(x, y, x + NODE_W, y + NODE_H, 0xFF21252B);
        graphics.fill(x, y, x + NODE_W, y + 12, node.color());

        graphics.drawString(font, font.plainSubstrByWidth(node.badge(), NODE_W - 8), x + 4, y + 2, 0xFF111317, false);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, -140);
        graphics.renderItem(node.icon(), x + 4, y + 18);
        graphics.pose().popPose();
        graphics.drawString(font, font.plainSubstrByWidth(node.title(), NODE_W - 28), x + 24, y + 17, 0xFFF5F5F5, false);
        graphics.drawString(font, font.plainSubstrByWidth(node.key().id().getPath(), NODE_W - 28), x + 24, y + 28, 0xFF90A4AE, false);

        int portX = x + NODE_W - 3;
        int portY = y + NODE_H / 2 - 3;
        graphics.fill(portX - 1, portY - 1, portX + 7, portY + 7, 0xFF111317);
        graphics.fill(portX, portY, portX + 6, portY + 6, 0xFFFFD54F);
    }

    private void drawDirectedEdge(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color, String label, int edgeIdx) {
        int laneOffset = ((edgeIdx % 5) - 2) * 5;
        int midX = x2 >= x1 ? Math.min(x1 + 24 + laneOffset, (x1 + x2) / 2 + laneOffset) : (x1 + x2) / 2 + laneOffset;
        drawHLine(graphics, x1, midX, y1, color);
        drawVLine(graphics, midX, y1, y2, color);
        drawHLine(graphics, midX, x2, y2, color);
        graphics.fill(x2 - 5, y2 - 3, x2, y2 + 3, color);
        if (!label.isEmpty()) {
            int lw = font.width(label);
            int lx = midX - lw / 2;
            int ly = (y1 + y2) / 2 - 4 + ((edgeIdx % 3) - 1) * 7;
            graphics.fill(lx - 2, ly - 1, lx + lw + 2, ly + 9, 0xDD141619);
            graphics.drawString(font, label, lx, ly, color == 0xFFFFD54F ? 0xFFFFE082 : 0xFFB0BEC5, false);
        }
    }

    private void drawHLine(GuiGraphics graphics, int x1, int x2, int y, int color) {
        int min = Math.min(x1, x2);
        int max = Math.max(x1, x2);
        graphics.fill(min, y, max + 1, y + 2, color);
    }

    private void drawVLine(GuiGraphics graphics, int x, int y1, int y2, int color) {
        int min = Math.min(y1, y2);
        int max = Math.max(y1, y2);
        graphics.fill(x, min, x + 2, max + 1, color);
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        List<ContextOption> options = activeContextOptions;
        int menuW = 216;
        int itemH = 16;
        int menuH = options.size() * itemH + 6;
        int mx = Math.min(contextMenuX, width - menuW - 6);
        int my = Math.min(contextMenuY, height - menuH - 6);

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        graphics.fill(mx - 1, my - 1, mx + menuW + 1, my + menuH + 1, 0xFFE8C66A);
        graphics.fill(mx, my, mx + menuW, my + menuH, 0xFF1E2229);

        for (int i = 0; i < options.size(); i++) {
            int iy = my + 3 + i * itemH;
            boolean hover = mouseX >= mx && mouseX < mx + menuW && mouseY >= iy && mouseY < iy + itemH;
            if (hover) {
                graphics.fill(mx + 2, iy, mx + menuW - 2, iy + itemH, 0xFF2D3748);
            }
            String lbl = options.get(i).label();
            int textColor = lbl.startsWith("🗑") ? (hover ? 0xFFFF8A80 : 0xFFFF5252) : (hover ? 0xFFFFD54F : 0xFFE0E6ED);
            graphics.drawString(font, lbl, mx + 6, iy + 4, textColor, false);
        }
        graphics.pose().popPose();
    }

    private List<ContextOption> buildCanvasContextOptions(@Nullable CanvasNode rightClickedNode) {
        List<ContextOption> opts = new ArrayList<>();
        if (selectedNodes.size() > 1) {
            int selCount = selectedNodes.size();
            opts.add(new ContextOption("🗑 Delete selected (" + selCount + ")", this::deleteSelectedNodes));
            opts.add(new ContextOption("✕ Clear selection", () -> {
                selectedNodes.clear();
                selectedNode = null;
                editingJson = null;
                rebuildInspectorWidgets();
            }));
            return opts;
        }

        if (rightClickedNode != null) {
            NodeKey target = rightClickedNode.key();
            selectedNodes.clear();
            selectedNodes.add(target);
            selectedNode = target;
            inspectorCollapsed = false;
            editingJson = ClientDevStore.parseObject(ClientDevStore.getJson(target.type(), target.id()));
            rebuildInspectorWidgets();

            opts.add(new ContextOption("🗑 Delete: " + target.id().getPath(), this::deleteSelectedNodes));
            opts.add(new ContextOption("📋 Duplicate: " + target.id().getPath(), () -> duplicateNode(target)));

            ResourceLocation t = target.type();
            if (t.equals(ClientDevStore.TYPE_NPC)) {
                opts.add(new ContextOption("💬 + Link Dialogue (Screen)", () -> createAndLinkNode(target, "dialogue_screen", false)));
                opts.add(new ContextOption("💭 + Link Small Talk (Bubble)", () -> createAndLinkNode(target, "dialogue_bubble", false)));
            } else if (t.equals(ClientDevStore.TYPE_DIALOGUE)) {
                opts.add(new ContextOption("📜 + Link Main Quest", () -> createAndLinkNode(target, "quest_main", false)));
                opts.add(new ContextOption("🗡 + Link Side Quest", () -> createAndLinkNode(target, "quest_side", false)));
                opts.add(new ContextOption("📖 + Link Document (give)", () -> createAndLinkNode(target, "document_item", false)));
                opts.add(new ContextOption("🧑 + Link to new NPC", () -> createAndLinkNode(target, "npc_villager", true)));
            } else if (t.equals(ClientDevStore.TYPE_DOCUMENT)) {
                opts.add(new ContextOption("📜 + Link Main Quest", () -> createAndLinkNode(target, "quest_main", false)));
                opts.add(new ContextOption("🗡 + Link Side Quest", () -> createAndLinkNode(target, "quest_side", false)));
                opts.add(new ContextOption("💬 + Give from new Dialogue", () -> createAndLinkNode(target, "dialogue_screen", true)));
            } else if (t.equals(ClientDevStore.TYPE_QUEST)) {
                opts.add(new ContextOption("📍 + Link Waypoint / Area", () -> createAndLinkNode(target, "waypoint_area", false)));
                opts.add(new ContextOption("📜 + Link Next Quest", () -> createAndLinkNode(target, "quest_main", false)));
                opts.add(new ContextOption("🗡 + Link Side Quest", () -> createAndLinkNode(target, "quest_side", false)));
                opts.add(new ContextOption("🐉 + Link Boss (Bestiary)", () -> createAndLinkNode(target, "boss_entry", false)));
                opts.add(new ContextOption("💬 + Start from new Dialogue", () -> createAndLinkNode(target, "dialogue_screen", true)));
                opts.add(new ContextOption("📖 + Start from Document", () -> createAndLinkNode(target, "document_item", true)));
            } else if (t.equals(ClientDevStore.TYPE_WAYPOINT)) {
                opts.add(new ContextOption("📜 + Link to new Quest", () -> createAndLinkNode(target, "quest_main", true)));
            } else if (t.equals(ClientDevStore.TYPE_BOSS)) {
                opts.add(new ContextOption("🗡 + Link Hunt Quest", () -> createAndLinkNode(target, "quest_side", true)));
            }
            return opts;
        }

        opts.addAll(List.of(
                new ContextOption("📜 + Main Quest", () -> createNodeFromTemplate("quest_main")),
                new ContextOption("🗡 + Side Quest", () -> createNodeFromTemplate("quest_side")),
                new ContextOption("⚔ + Witcher Contract (Doc + Quest)", () -> createNodeFromTemplate("contract_bundle")),
                new ContextOption("💬 + Dialogue (Screen & Camera)", () -> createNodeFromTemplate("dialogue_screen")),
                new ContextOption("💭 + Dialogue (Small Talk Bubble)", () -> createNodeFromTemplate("dialogue_bubble")),
                new ContextOption("🧑 + NPC Binding (Villager & Trade)", () -> createNodeFromTemplate("npc_villager")),
                new ContextOption("📖 + Document Item (Book/Scroll/Note)", () -> createNodeFromTemplate("document_item")),
                new ContextOption("📍 + Waypoint / Search Area", () -> createNodeFromTemplate("waypoint_area")),
                new ContextOption("🐉 + Boss / Bestiary Entry", () -> createNodeFromTemplate("boss_entry"))
        ));
        return opts;
    }

    private void createAndLinkNode(NodeKey source, String template, boolean reverseLink) {
        int[] srcPos = positions.getOrDefault(source, new int[]{contextWorldX, contextWorldY});
        int targetX = reverseLink ? srcPos[0] - 180 : srcPos[0] + 180;
        int targetY = srcPos[1];
        for (int attempt = 0; attempt < 12; attempt++) {
            boolean occupied = false;
            for (CanvasNode n : canvasNodes) {
                if (Math.abs(n.x() - targetX) < NODE_W - 10 && Math.abs(n.y() - targetY) < NODE_H + 6) {
                    occupied = true;
                    break;
                }
            }
            if (!occupied) {
                break;
            }
            targetY += 54;
        }
        contextWorldX = targetX;
        contextWorldY = targetY;
        NodeKey created = createNodeFromTemplate(template);
        if (created != null) {
            createSmartLink(reverseLink ? created : source, reverseLink ? source : created);
            rebuildGraph();
            rebuildInspectorWidgets();
        }
    }

    private void duplicateNode(NodeKey source) {
        String raw = ClientDevStore.getJson(source.type(), source.id());
        if (raw == null) {
            return;
        }
        int index = totalNodeCount() + 1;
        ResourceLocation newId = Storybound.id(source.id().getPath() + "_copy_" + index);
        JsonObject copy = ClientDevStore.parseObject(raw);
        Optional<String> sbt = ClientConfigTexts.document(source.id(), "ru_ru")
                .or(() -> ClientConfigTexts.document(source.id(), "en_us"));
        int[] srcPos = positions.getOrDefault(source, new int[]{contextWorldX, contextWorldY});
        contextWorldX = srcPos[0] + 24;
        contextWorldY = srcPos[1] + 24;
        placeAndSaveNewNode(new NodeKey(source.type(), newId), copy, sbt);
    }

    private @Nullable NodeKey createNodeFromTemplate(String template) {
        int index = totalNodeCount() + 1;
        switch (template) {
            case "quest_main", "quest_side" -> {
                ResourceLocation id = Storybound.id("quest_" + index);
                JsonObject q = new JsonObject();
                q.addProperty("category", "quest_main".equals(template) ? "storybound:main" : "storybound:side");
                ClientDevStore.writeStoryText(q, "title", "quest_main".equals(template) ? "Main Quest " + index : "Side Quest " + index);
                ClientDevStore.writeStoryText(q, "description", "Quest description...");
                JsonArray stages = new JsonArray();
                JsonObject stage = new JsonObject();
                stage.addProperty("id", "start");
                ClientDevStore.writeStoryText(stage, "log", "Quest start.");
                stages.add(stage);
                q.add("stages", stages);
                NodeKey key = new NodeKey(ClientDevStore.TYPE_QUEST, id);
                placeAndSaveNewNode(key, q, Optional.empty());
                return key;
            }
            case "contract_bundle" -> {
                ResourceLocation questId = Storybound.id("contract_quest_" + index);
                JsonObject q = new JsonObject();
                q.addProperty("category", "storybound:contract");
                ClientDevStore.writeStoryText(q, "title", "Contract: Beast " + index);
                ClientDevStore.writeStoryText(q, "description", "Track and slay the beast as contracted.");
                JsonArray stages = new JsonArray();
                JsonObject stage = new JsonObject();
                stage.addProperty("id", "hunt");
                ClientDevStore.writeStoryText(stage, "log", "Investigate clues and defeat the target.");
                stages.add(stage);
                q.add("stages", stages);
                NodeKey questKey = new NodeKey(ClientDevStore.TYPE_QUEST, questId);
                positions.put(questKey, new int[]{contextWorldX + 190, contextWorldY});
                String questJsonStr = GSON.toJson(q);
                ClientDevStore.putLocal(ClientDevStore.TYPE_QUEST, questId, questJsonStr);
                PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(
                        ClientDevStore.TYPE_QUEST, questId, questJsonStr, Optional.empty()));
                addNodeToActiveChapter(questKey, contextWorldX + 190, contextWorldY);

                ResourceLocation docId = Storybound.id("contract_note_" + index);
                JsonObject doc = new JsonObject();
                doc.addProperty("kind", "contract");
                ClientDevStore.writeStoryText(doc, "title", "Notice: Contract #" + index);
                JsonObject contract = new JsonObject();
                contract.addProperty("quest", questId.toString());
                ClientDevStore.writeStoryText(contract, "conditions", "Objective: Slay the monster nearby");
                ClientDevStore.writeStoryText(contract, "reward", "Reward: Emeralds and trophy");
                ClientDevStore.writeStoryText(contract, "hint", "Hint: The beast hunts during rainy nights.");
                doc.add("contract", contract);
                NodeKey docKey = new NodeKey(ClientDevStore.TYPE_DOCUMENT, docId);
                placeAndSaveNewNode(docKey, doc,
                        Optional.of("Monster Hunter Wanted!\n\nVillagers fear stepping outside after sunset. Read this notice to accept the contract."));
                return docKey;
            }
            case "dialogue_screen", "dialogue_bubble" -> {
                ResourceLocation id = Storybound.id("dialogue_" + index);
                JsonObject d = new JsonObject();
                boolean bubble = "dialogue_bubble".equals(template);
                d.addProperty("presentation", bubble ? "bubble" : "screen");
                d.addProperty("camera", bubble ? "off" : "auto");
                d.addProperty("start", "greet");
                JsonObject participants = new JsonObject();
                JsonObject npc = new JsonObject();
                npc.addProperty("type", "bound");
                ClientDevStore.writeStoryText(npc, "name", "Villager");
                participants.add("npc", npc);
                d.add("participants", participants);
                JsonObject nodes = new JsonObject();
                JsonObject greet = new JsonObject();
                greet.addProperty("type", "line");
                greet.addProperty("speaker", "npc");
                ClientDevStore.writeStoryText(greet, "text", bubble ? "It's late, come back tomorrow!" : "Greetings, traveler!");
                greet.addProperty("next", "@end");
                nodes.add("greet", greet);
                d.add("nodes", nodes);
                NodeKey key = new NodeKey(ClientDevStore.TYPE_DIALOGUE, id);
                placeAndSaveNewNode(key, d, Optional.empty());
                return key;
            }
            case "npc_villager" -> {
                ResourceLocation id = Storybound.id("npc_" + index);
                JsonObject npc = new JsonObject();
                JsonObject entity = new JsonObject();
                entity.addProperty("type", "minecraft:villager");
                entity.addProperty("tag", "npc_" + index);
                npc.add("entity", entity);
                npc.addProperty("interact", true);
                npc.addProperty("override_interact", true);
                npc.add("dialogues", new JsonArray());
                NodeKey key = new NodeKey(ClientDevStore.TYPE_NPC, id);
                placeAndSaveNewNode(key, npc, Optional.empty());
                return key;
            }
            case "document_item" -> {
                ResourceLocation id = Storybound.id("document_" + index);
                JsonObject doc = new JsonObject();
                doc.addProperty("kind", "scroll");
                ClientDevStore.writeStoryText(doc, "title", "Ancient Scroll " + index);
                NodeKey key = new NodeKey(ClientDevStore.TYPE_DOCUMENT, id);
                placeAndSaveNewNode(key, doc,
                        Optional.of("Marginal notes reveal a forgotten shrine..."));
                return key;
            }
            case "waypoint_area" -> {
                ResourceLocation id = Storybound.id("waypoint_" + index);
                JsonObject wp = new JsonObject();
                ClientDevStore.writeStoryText(wp, "name", "Search Area " + index);
                JsonObject target = new JsonObject();
                JsonArray pos = new JsonArray();
                if (minecraft != null && minecraft.player != null) {
                    pos.add(Math.round(minecraft.player.getX()));
                    pos.add(Math.round(minecraft.player.getY()));
                    pos.add(Math.round(minecraft.player.getZ()));
                    target.addProperty("dimension", minecraft.player.level().dimension().location().toString());
                } else {
                    pos.add(0);
                    pos.add(64);
                    pos.add(0);
                }
                target.add("pos", pos);
                wp.add("target", target);
                wp.addProperty("radius", 30.0);
                NodeKey key = new NodeKey(ClientDevStore.TYPE_WAYPOINT, id);
                placeAndSaveNewNode(key, wp, Optional.empty());
                return key;
            }
            case "boss_entry" -> {
                ResourceLocation id = Storybound.id("boss_" + index);
                JsonObject boss = new JsonObject();
                ClientDevStore.writeStoryText(boss, "title", "Pack Leader " + index);
                ClientDevStore.writeStoryText(boss, "lore", "Legends speak of a dangerous predator in these lands.");
                JsonObject selector = new JsonObject();
                selector.addProperty("type", "minecraft:wolf");
                selector.addProperty("tag", "boss_" + index);
                boss.add("entity", selector);
                NodeKey key = new NodeKey(ClientDevStore.TYPE_BOSS, id);
                placeAndSaveNewNode(key, boss, Optional.empty());
                return key;
            }
            default -> {
                return null;
            }
        }
    }

    private void placeAndSaveNewNode(NodeKey key, JsonObject json, Optional<String> sbt) {
        int wx = contextWorldX;
        int wy = contextWorldY;
        String jsonStr = GSON.toJson(json);
        positions.put(key, new int[]{wx, wy});
        selectedNodes.clear();
        selectedNodes.add(key);
        selectedNode = key;
        inspectorCollapsed = false;
        editingJson = json;
        ClientDevStore.putLocal(key.type(), key.id(), jsonStr);
        PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(key.type(), key.id(), jsonStr, sbt));
        addNodeToActiveChapter(key, wx, wy);
        pushHistory("create " + key.id().getPath(),
                () -> {
                    positions.remove(key);
                    selectedNodes.remove(key);
                    if (key.equals(selectedNode)) {
                        selectedNode = null;
                        editingJson = null;
                    }
                    PacketDistributor.sendToServer(new DevModePayloads.DeleteDefinition(key.type(), key.id()));
                },
                () -> {
                    positions.put(key, new int[]{wx, wy});
                    ClientDevStore.putLocal(key.type(), key.id(), jsonStr);
                    PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(key.type(), key.id(), jsonStr, sbt));
                    addNodeToActiveChapter(key, wx, wy);
                });
        status("Created node: " + key.id().getPath(), 0xFFA8E6A3);
    }

    private void createSmartLink(NodeKey from, NodeKey to) {
        if (from.equals(to)) {
            return;
        }
        String rawFrom = ClientDevStore.getJson(from.type(), from.id());
        if (rawFrom == null) {
            return;
        }
        JsonObject obj = ClientDevStore.parseObject(rawFrom);
        boolean linked = false;

        if (from.type().equals(ClientDevStore.TYPE_NPC) && to.type().equals(ClientDevStore.TYPE_DIALOGUE)) {
            JsonArray arr = obj.has("dialogues") && obj.get("dialogues").isJsonArray() ? obj.getAsJsonArray("dialogues") : new JsonArray();
            JsonObject entry = new JsonObject();
            entry.addProperty("dialogue", to.id().toString());
            entry.addProperty("priority", arr.size() * 10);
            arr.add(entry);
            obj.add("dialogues", arr);
            linked = true;
        } else if (from.type().equals(ClientDevStore.TYPE_DOCUMENT) && to.type().equals(ClientDevStore.TYPE_QUEST)) {
            JsonObject contract = obj.has("contract") && obj.get("contract").isJsonObject() ? obj.getAsJsonObject("contract") : new JsonObject();
            contract.addProperty("quest", to.id().toString());
            obj.add("contract", contract);
            linked = true;
        } else if (from.type().equals(ClientDevStore.TYPE_QUEST) && to.type().equals(ClientDevStore.TYPE_WAYPOINT)) {
            JsonArray stages = obj.has("stages") && obj.get("stages").isJsonArray() ? obj.getAsJsonArray("stages") : new JsonArray();
            if (stages.isEmpty()) {
                JsonObject stage = new JsonObject();
                stage.addProperty("id", "start");
                stages.add(stage);
            }
            JsonObject firstStage = stages.get(0).getAsJsonObject();
            JsonArray wps = firstStage.has("waypoints") && firstStage.get("waypoints").isJsonArray()
                    ? firstStage.getAsJsonArray("waypoints") : new JsonArray();
            wps.add(to.id().toString());
            firstStage.add("waypoints", wps);
            obj.add("stages", stages);
            linked = true;
        } else if (from.type().equals(ClientDevStore.TYPE_QUEST) && to.type().equals(ClientDevStore.TYPE_QUEST)) {
            JsonArray onComplete = obj.has("on_complete") && obj.get("on_complete").isJsonArray()
                    ? obj.getAsJsonArray("on_complete") : new JsonArray();
            JsonObject act = new JsonObject();
            act.addProperty("type", "set_quest_state");
            act.addProperty("quest", to.id().toString());
            act.addProperty("state", "active");
            onComplete.add(act);
            obj.add("on_complete", onComplete);
            linked = true;
        } else if (from.type().equals(ClientDevStore.TYPE_QUEST) && to.type().equals(ClientDevStore.TYPE_BOSS)) {
            JsonArray onStart = obj.has("on_start") && obj.get("on_start").isJsonArray()
                    ? obj.getAsJsonArray("on_start") : new JsonArray();
            JsonObject act = new JsonObject();
            act.addProperty("type", "unlock_boss_section");
            act.addProperty("boss", to.id().toString());
            act.addProperty("section", "lore");
            onStart.add(act);
            obj.add("on_start", onStart);
            linked = true;
        } else if (from.type().equals(ClientDevStore.TYPE_DIALOGUE) && to.type().equals(ClientDevStore.TYPE_QUEST)) {
            addQuestActionToDialogue(obj, to.id());
            linked = true;
        } else if (from.type().equals(ClientDevStore.TYPE_DIALOGUE) && to.type().equals(ClientDevStore.TYPE_DOCUMENT)) {
            addGiveDocumentToDialogue(obj, to.id());
            linked = true;
        }

        if (linked) {
            String newJson = GSON.toJson(obj);
            if (from.equals(selectedNode)) {
                editingJson = obj;
            }
            ClientDevStore.putLocal(from.type(), from.id(), newJson);
            pushHistory("link " + from.id().getPath() + " → " + to.id().getPath(),
                    () -> {
                        ClientDevStore.putLocal(from.type(), from.id(), rawFrom);
                        PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(from.type(), from.id(), rawFrom, Optional.empty()));
                    },
                    () -> {
                        ClientDevStore.putLocal(from.type(), from.id(), newJson);
                        PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(from.type(), from.id(), newJson, Optional.empty()));
                    });
            PacketDistributor.sendToServer(new DevModePayloads.SaveDefinition(from.type(), from.id(), newJson, Optional.empty()));
            status("Linked: " + from.id().getPath() + " → " + to.id().getPath(), 0xFF69F0AE);
        } else {
            status("This pair of nodes links via conditions/actions", 0xFFFFD54F);
        }
    }

    private void addQuestActionToDialogue(JsonObject dialogue, ResourceLocation questId) {
        JsonObject nodes = dialogue.has("nodes") && dialogue.get("nodes").isJsonObject() ? dialogue.getAsJsonObject("nodes") : new JsonObject();
        String start = dialogue.has("start") ? dialogue.get("start").getAsString() : "greet";
        if (nodes.has(start) && nodes.get(start).isJsonObject()) {
            JsonObject startNode = nodes.getAsJsonObject(start);
            startNode.addProperty("next", "start_quest_" + questId.getPath());
        }
        JsonObject actNode = new JsonObject();
        actNode.addProperty("type", "end");
        JsonArray actions = new JsonArray();
        JsonObject setQuest = new JsonObject();
        setQuest.addProperty("type", "set_quest_state");
        setQuest.addProperty("quest", questId.toString());
        setQuest.addProperty("state", "active");
        actions.add(setQuest);
        actNode.add("actions", actions);
        nodes.add("start_quest_" + questId.getPath(), actNode);
        dialogue.add("nodes", nodes);
    }

    private void addGiveDocumentToDialogue(JsonObject dialogue, ResourceLocation docId) {
        JsonObject nodes = dialogue.has("nodes") && dialogue.get("nodes").isJsonObject() ? dialogue.getAsJsonObject("nodes") : new JsonObject();
        String start = dialogue.has("start") ? dialogue.get("start").getAsString() : "greet";
        if (nodes.has(start) && nodes.get(start).isJsonObject()) {
            nodes.getAsJsonObject(start).addProperty("next", "give_doc_" + docId.getPath());
        }
        JsonObject actNode = new JsonObject();
        actNode.addProperty("type", "end");
        JsonArray actions = new JsonArray();
        JsonObject give = new JsonObject();
        give.addProperty("type", "give_document");
        give.addProperty("document", docId.toString());
        actions.add(give);
        actNode.add("actions", actions);
        nodes.add("give_doc_" + docId.getPath(), actNode);
        dialogue.add("nodes", nodes);
    }

    private void addTradeChoiceToDialogue(JsonObject dialogue) {
        JsonObject nodes = dialogue.has("nodes") && dialogue.get("nodes").isJsonObject() ? dialogue.getAsJsonObject("nodes") : new JsonObject();
        String start = dialogue.has("start") ? dialogue.get("start").getAsString() : "greet";
        if (nodes.has(start) && nodes.get(start).isJsonObject()) {
            nodes.getAsJsonObject(start).addProperty("next", "trade_hub");
        }
        JsonObject choice = new JsonObject();
        choice.addProperty("type", "choice");
        JsonArray options = new JsonArray();

        JsonObject tradeOpt = new JsonObject();
        tradeOpt.addProperty("id", "trade");
        ClientDevStore.writeStoryText(tradeOpt, "text", "Show me your wares.");
        JsonArray actions = new JsonArray();
        JsonObject openTrade = new JsonObject();
        openTrade.addProperty("type", "open_trade");
        actions.add(openTrade);
        tradeOpt.add("actions", actions);
        tradeOpt.addProperty("next", "@end");
        options.add(tradeOpt);

        JsonObject byeOpt = new JsonObject();
        byeOpt.addProperty("id", "bye");
        ClientDevStore.writeStoryText(byeOpt, "text", "Farewell.");
        byeOpt.addProperty("next", "@end");
        options.add(byeOpt);

        choice.add("options", options);
        nodes.add("trade_hub", choice);
        dialogue.add("nodes", nodes);
    }

    private String readFirstDialogueLine(JsonObject dialogue) {
        if (!dialogue.has("nodes") || !dialogue.get("nodes").isJsonObject()) {
            return "";
        }
        JsonObject nodes = dialogue.getAsJsonObject("nodes");
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                JsonObject n = entry.getValue().getAsJsonObject();
                if (n.has("type") && "line".equals(n.get("type").getAsString())) {
                    return ClientDevStore.readStoryText(n, "text", "");
                }
            }
        }
        return "";
    }

    private void writeFirstDialogueLine(JsonObject dialogue, String text) {
        if (!dialogue.has("nodes") || !dialogue.get("nodes").isJsonObject()) {
            return;
        }
        JsonObject nodes = dialogue.getAsJsonObject("nodes");
        for (Map.Entry<String, JsonElement> entry : nodes.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                JsonObject n = entry.getValue().getAsJsonObject();
                if (n.has("type") && "line".equals(n.get("type").getAsString())) {
                    ClientDevStore.writeStoryText(n, "text", text);
                    return;
                }
            }
        }
    }

    private String readFirstEntryPeriod(JsonObject npc) {
        if (!npc.has("dialogues") || !npc.get("dialogues").isJsonArray()) {
            return "";
        }
        JsonArray arr = npc.getAsJsonArray("dialogues");
        if (arr.isEmpty() || !arr.get(0).isJsonObject()) {
            return "";
        }
        JsonObject first = arr.get(0).getAsJsonObject();
        if (first.has("conditions") && first.get("conditions").isJsonObject()) {
            JsonObject cond = first.getAsJsonObject("conditions");
            if (cond.has("period")) {
                return cond.get("period").getAsString();
            }
        }
        return "";
    }

    private void writeFirstEntryPeriod(JsonObject npc, String period) {
        if (!npc.has("dialogues") || !npc.get("dialogues").isJsonArray()) {
            return;
        }
        JsonArray arr = npc.getAsJsonArray("dialogues");
        if (arr.isEmpty() || !arr.get(0).isJsonObject()) {
            return;
        }
        JsonObject first = arr.get(0).getAsJsonObject();
        if (period.isEmpty()) {
            first.remove("conditions");
        } else {
            JsonObject cond = new JsonObject();
            cond.addProperty("type", "time");
            cond.addProperty("period", period);
            first.add("conditions", cond);
        }
    }

    private JsonArray getFirstStageObjectives(JsonObject quest) {
        if (!quest.has("stages") || !quest.get("stages").isJsonArray()) {
            return new JsonArray();
        }
        JsonArray stages = quest.getAsJsonArray("stages");
        if (stages.isEmpty() || !stages.get(0).isJsonObject()) {
            return new JsonArray();
        }
        JsonObject first = stages.get(0).getAsJsonObject();
        if (!first.has("objectives") || !first.get("objectives").isJsonArray()) {
            return new JsonArray();
        }
        return first.getAsJsonArray("objectives");
    }

    private JsonArray ensureFirstStageObjectives(JsonObject quest) {
        JsonArray stages = quest.has("stages") && quest.get("stages").isJsonArray()
                ? quest.getAsJsonArray("stages") : new JsonArray();
        if (stages.isEmpty() || !stages.get(0).isJsonObject()) {
            JsonObject st = new JsonObject();
            st.addProperty("id", "stage_1");
            st.add("objectives", new JsonArray());
            stages.add(st);
            quest.add("stages", stages);
        }
        JsonObject first = stages.get(0).getAsJsonObject();
        if (!first.has("objectives") || !first.get("objectives").isJsonArray()) {
            first.add("objectives", new JsonArray());
        }
        return first.getAsJsonArray("objectives");
    }

    private String readQuestFailPeriod(JsonObject quest) {
        if (quest.has("fail_when") && quest.get("fail_when").isJsonObject()) {
            JsonObject fw = quest.getAsJsonObject("fail_when");
            if (fw.has("period")) {
                return fw.get("period").getAsString();
            }
        }
        return "";
    }

    private void writeQuestFailPeriod(JsonObject quest, String period) {
        if (period == null || period.isBlank()) {
            quest.remove("fail_when");
        } else {
            JsonObject fw = new JsonObject();
            fw.addProperty("type", "storybound:time");
            fw.addProperty("period", period);
            quest.add("fail_when", fw);
        }
    }

    private void addLocationObjective(JsonObject quest, int cat, String id, String label) {
        JsonArray objs = ensureFirstStageObjectives(quest);
        JsonObject obj = new JsonObject();
        obj.addProperty("id", "obj_" + (objs.size() + 1));
        obj.addProperty("count", 1);
        JsonObject trigger = new JsonObject();
        if (cat == 0) {
            ClientDevStore.writeStoryText(obj, "text", "Find structure: " + label);
            trigger.addProperty("type", "storybound:structure_entered");
            trigger.addProperty("structure", id);
        } else if (cat == 1) {
            ClientDevStore.writeStoryText(obj, "text", "Visit biome: " + label);
            trigger.addProperty("type", "storybound:biome_entered");
            trigger.addProperty("biome", id);
        } else {
            ClientDevStore.writeStoryText(obj, "text", "Locate POI: " + label);
            trigger.addProperty("type", "storybound:poi_entered");
            trigger.addProperty("poi", id);
            trigger.addProperty("radius", 12);
        }
        obj.add("trigger", trigger);
        objs.add(obj);
        status("Added location objective: " + label, 0xFFA8E6A3);
    }

    private void addItemObjective(JsonObject quest, String itemId, String label, int count) {
        JsonArray objs = ensureFirstStageObjectives(quest);
        JsonObject obj = new JsonObject();
        obj.addProperty("id", "obj_" + (objs.size() + 1));
        ClientDevStore.writeStoryText(obj, "text", "Deliver: " + label);
        obj.addProperty("count", Math.max(1, count));
        JsonObject trigger = new JsonObject();
        trigger.addProperty("type", "storybound:item_obtained");
        trigger.addProperty("item", itemId);
        obj.add("trigger", trigger);
        objs.add(obj);
        status("Added objective: " + label, 0xFFA8E6A3);
    }

    private void addItemReward(JsonObject quest, String itemId, int count) {
        JsonArray rewards = quest.has("rewards") && quest.get("rewards").isJsonArray()
                ? quest.getAsJsonArray("rewards") : new JsonArray();
        for (JsonElement el : rewards) {
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("item") && itemId.equals(obj.get("item").getAsString()) && !obj.has("components")) {
                    int prev = obj.has("count") ? obj.get("count").getAsInt() : 1;
                    obj.addProperty("count", prev + count);
                    quest.add("rewards", rewards);
                    return;
                }
            }
        }
        JsonObject reward = new JsonObject();
        reward.addProperty("type", "storybound:give_item");
        reward.addProperty("item", itemId);
        reward.addProperty("count", Math.max(1, count));
        rewards.add(reward);
        quest.add("rewards", rewards);
    }

    private void addKillObjective(JsonObject quest, String entityId, String label, int count) {
        JsonArray objs = ensureFirstStageObjectives(quest);
        JsonObject obj = new JsonObject();
        obj.addProperty("id", "obj_" + (objs.size() + 1));
        ClientDevStore.writeStoryText(obj, "text", label);
        obj.addProperty("count", Math.max(1, count));
        JsonObject trigger = new JsonObject();
        trigger.addProperty("type", "storybound:entity_killed");
        trigger.addProperty("entity", entityId);
        obj.add("trigger", trigger);
        objs.add(obj);
        status("Added objective: " + label, 0xFFA8E6A3);
    }

    private void addItemObjectiveFromHand(JsonObject quest) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack stack = minecraft.player.getMainHandItem();
        if (stack.isEmpty()) {
            status("Hold an item in main hand!", 0xFFFF8A80);
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String itemName = stack.getHoverName().getString();
        int count = Math.max(1, stack.getCount());
        addItemObjective(quest, itemId, itemName, count);
    }

    private void adjustLastObjectiveCount(JsonObject quest, int delta) {
        JsonArray objs = getFirstStageObjectives(quest);
        if (objs.isEmpty() || !objs.get(objs.size() - 1).isJsonObject()) {
            return;
        }
        JsonObject last = objs.get(objs.size() - 1).getAsJsonObject();
        int current = last.has("count") ? last.get("count").getAsInt() : 1;
        last.addProperty("count", Math.max(1, current + delta));
    }

    private void removeLastObjective(JsonObject quest) {
        JsonArray objs = getFirstStageObjectives(quest);
        if (!objs.isEmpty()) {
            objs.remove(objs.size() - 1);
        }
    }

    private void addRewardFromHand(JsonObject quest) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        ItemStack stack = minecraft.player.getMainHandItem();
        if (stack.isEmpty()) {
            status("Hold reward item in main hand!", 0xFFFF8A80);
            return;
        }
        JsonArray rewards = quest.has("rewards") && quest.get("rewards").isJsonArray()
                ? quest.getAsJsonArray("rewards") : new JsonArray();
        JsonObject reward = new JsonObject();
        reward.addProperty("type", "storybound:give_item");
        reward.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        reward.addProperty("count", Math.max(1, stack.getCount()));
        DataComponentPatch patch = stack.getComponentsPatch();
        if (!patch.isEmpty()) {
            DataComponentPatch.CODEC.encodeStart(
                    RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess()),
                    patch
            ).result().ifPresent(elem -> {
                if (elem.isJsonObject() && !elem.getAsJsonObject().entrySet().isEmpty()) {
                    reward.add("components", elem.getAsJsonObject());
                }
            });
        }
        rewards.add(reward);
        quest.add("rewards", rewards);
        status("Added reward: " + stack.getHoverName().getString() + " ×" + stack.getCount(), 0xFFA8E6A3);
    }

    private void addEmeraldReward(JsonObject quest, int count) {
        addItemReward(quest, "minecraft:emerald", count);
    }

    private void addCommandReward(JsonObject quest, String command) {
        String clean = command.startsWith("/") ? command.substring(1) : command;
        JsonArray rewards = quest.has("rewards") && quest.get("rewards").isJsonArray()
                ? quest.getAsJsonArray("rewards") : new JsonArray();
        JsonObject reward = new JsonObject();
        reward.addProperty("type", "storybound:run_command");
        reward.addProperty("command", clean);
        rewards.add(reward);
        quest.add("rewards", rewards);
    }

    private void removeLastReward(JsonObject quest) {
        if (quest.has("rewards") && quest.get("rewards").isJsonArray()) {
            JsonArray rewards = quest.getAsJsonArray("rewards");
            if (!rewards.isEmpty()) {
                rewards.remove(rewards.size() - 1);
            }
        }
    }

    private String readWaypointMode(JsonObject wp) {
        if (wp.has("target") && wp.get("target").isJsonObject()) {
            JsonObject t = wp.getAsJsonObject("target");
            if (t.has("structure")) {
                return "1. Structure";
            }
            if (t.has("nearest")) {
                return "2. Nearest Mob/NPC";
            }
            if (t.has("entity")) {
                return "4. Entity UUID";
            }
        }
        double radius = wp.has("radius") && wp.get("radius").isJsonPrimitive() ? wp.get("radius").getAsDouble() : 0.0;
        if (radius > 0.0) {
            return "3. Search Area (R=" + (int) radius + "m)";
        }
        return "3. Point / Area";
    }

    private void cycleWaypointMode(JsonObject wp) {
        String cur = readWaypointMode(wp);
        if (cur.startsWith("1.")) {
            wp.remove("radius");
            JsonObject t = new JsonObject();
            t.addProperty("nearest", "minecraft:villager");
            t.addProperty("range", 64);
            wp.add("target", t);
        } else if (cur.startsWith("2.")) {
            wp.addProperty("radius", 35.0);
            JsonObject t = new JsonObject();
            JsonArray pos = new JsonArray();
            if (minecraft != null && minecraft.player != null) {
                pos.add(Math.round(minecraft.player.getX() * 10.0) / 10.0);
                pos.add(Math.round(minecraft.player.getY() * 10.0) / 10.0);
                pos.add(Math.round(minecraft.player.getZ() * 10.0) / 10.0);
            } else {
                pos.add(0);
                pos.add(70);
                pos.add(0);
            }
            t.add("pos", pos);
            t.addProperty("dimension", "minecraft:overworld");
            wp.add("target", t);
        } else {
            wp.remove("radius");
            JsonObject t = new JsonObject();
            t.addProperty("structure", "#minecraft:village");
            t.addProperty("search_radius", 100);
            wp.add("target", t);
        }
    }

    private String describeWaypointTarget(JsonObject wp) {
        if (!wp.has("target") || !wp.get("target").isJsonObject()) {
            return "No target set";
        }
        JsonObject t = wp.getAsJsonObject("target");
        if (t.has("structure")) {
            return "🏰 Structure: " + t.get("structure").getAsString();
        }
        if (t.has("nearest")) {
            return "⚔ Nearest: " + t.get("nearest").getAsString();
        }
        if (t.has("pos") && t.get("pos").isJsonArray()) {
            JsonArray p = t.get("pos").getAsJsonArray();
            if (p.size() >= 3) {
                return "📍 Pos: [" + p.get(0).getAsInt() + ", " + p.get(1).getAsInt() + ", " + p.get(2).getAsInt() + "]";
            }
        }
        if (t.has("entity")) {
            String uuid = t.get("entity").getAsString();
            return "UUID: " + (uuid.length() > 13 ? uuid.substring(0, 13) + "…" : uuid);
        }
        return "Target configured";
    }

    private String describeEventTrigger(JsonObject event) {
        if (!event.has("trigger") || !event.get("trigger").isJsonObject()) {
            return "None";
        }
        JsonObject t = event.getAsJsonObject("trigger");
        String type = t.has("type") ? shortId(t.get("type").getAsString()) : "";
        if ("structure_entered".equals(type) && t.has("structure")) {
            return "🏰 Structure: " + t.get("structure").getAsString();
        }
        if ("biome_entered".equals(type) && t.has("biome")) {
            return "🌲 Biome: " + t.get("biome").getAsString();
        }
        if ("poi_entered".equals(type) && t.has("poi")) {
            return "📌 POI: " + t.get("poi").getAsString();
        }
        if ("entity_killed".equals(type) && t.has("entity")) {
            return "⚔ Kill: " + t.get("entity").getAsString();
        }
        if ("item_obtained".equals(type) && t.has("item")) {
            return "🎒 Item: " + t.get("item").getAsString();
        }
        return type.isEmpty() ? "None" : type;
    }

    private void openLocationCatalog(String title, BiConsumer<Integer, CatalogEntry> callback) {
        this.catalogOpen = true;
        this.catalogTitle = title;
        this.catalogShowLocationTabs = true;
        this.catalogCategory = 0;
        this.catalogFilter = "";
        this.catalogScroll = 0;
        this.catalogCallback = callback;
        this.catalogAllEntries = buildCatalogEntries(0);
        rebuildInspectorWidgets();
    }

    private void openSingleCatalog(String title, int category, BiConsumer<Integer, CatalogEntry> callback) {
        this.catalogOpen = true;
        this.catalogTitle = title;
        this.catalogShowLocationTabs = false;
        this.catalogCategory = category;
        this.catalogFilter = "";
        this.catalogScroll = 0;
        this.catalogCallback = callback;
        this.catalogAllEntries = buildCatalogEntries(category);
        rebuildInspectorWidgets();
    }

    private void switchCatalogCategory(int category) {
        this.catalogCategory = category;
        this.catalogScroll = 0;
        this.catalogAllEntries = buildCatalogEntries(category);
        rebuildInspectorWidgets();
    }

    private List<CatalogEntry> buildCatalogEntries(int category) {
        List<CatalogEntry> list = new ArrayList<>();
        if (category == 0) {
            list.add(new CatalogEntry("#minecraft:village", "Village (any)", new ItemStack(Items.BELL)));
            list.add(new CatalogEntry("#minecraft:mineshaft", "Mineshaft", new ItemStack(Items.RAIL)));
            list.add(new CatalogEntry("#minecraft:ruined_portal", "Ruined Portal", new ItemStack(Items.OBSIDIAN)));
            list.add(new CatalogEntry("#minecraft:shipwreck", "Shipwreck", new ItemStack(Items.OAK_BOAT)));
            list.add(new CatalogEntry("#minecraft:ocean_ruin", "Ocean Ruin", new ItemStack(Items.PRISMARINE)));
            list.add(new CatalogEntry("#minecraft:eye_of_ender_located", "Stronghold", new ItemStack(Items.ENDER_EYE)));
            list.add(new CatalogEntry("minecraft:pillager_outpost", "Pillager Outpost", new ItemStack(Items.CROSSBOW)));
            list.add(new CatalogEntry("minecraft:ancient_city", "Ancient City", new ItemStack(Items.SCULK_SHRIEKER)));
            list.add(new CatalogEntry("minecraft:trial_chambers", "Trial Chambers", new ItemStack(Items.TRIAL_KEY)));
            list.add(new CatalogEntry("minecraft:mansion", "Woodland Mansion", new ItemStack(Items.TOTEM_OF_UNDYING)));
            list.add(new CatalogEntry("minecraft:monument", "Ocean Monument", new ItemStack(Items.SEA_LANTERN)));
            list.add(new CatalogEntry("minecraft:fortress", "Nether Fortress", new ItemStack(Items.BLAZE_ROD)));
            list.add(new CatalogEntry("minecraft:bastion_remnant", "Bastion Remnant", new ItemStack(Items.GILDED_BLACKSTONE)));
            list.add(new CatalogEntry("minecraft:end_city", "End City", new ItemStack(Items.PURPUR_BLOCK)));
            list.add(new CatalogEntry("minecraft:desert_pyramid", "Desert Pyramid", new ItemStack(Items.CHISELED_SANDSTONE)));
            list.add(new CatalogEntry("minecraft:jungle_pyramid", "Jungle Pyramid", new ItemStack(Items.MOSSY_COBBLESTONE)));
            list.add(new CatalogEntry("minecraft:swamp_hut", "Swamp Hut", new ItemStack(Items.CAULDRON)));
            list.add(new CatalogEntry("minecraft:igloo", "Igloo", new ItemStack(Items.SNOW_BLOCK)));
            list.add(new CatalogEntry("minecraft:trail_ruins", "Trail Ruins", new ItemStack(Items.BRUSH)));
            if (minecraft != null && minecraft.level != null) {
                Set<String> seen = new LinkedHashSet<>();
                for (CatalogEntry e : list) {
                    seen.add(e.id());
                }
                minecraft.level.registryAccess().registry(Registries.STRUCTURE).ifPresent(reg -> {
                    reg.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).forEach(id -> {
                        if (seen.add(id.toString())) {
                            list.add(new CatalogEntry(id.toString(), humanizePath(id.getPath()), new ItemStack(Items.FILLED_MAP)));
                        }
                    });
                });
            }
        } else if (category == 1) {
            list.add(new CatalogEntry("#minecraft:is_forest", "Forest (any)", new ItemStack(Items.OAK_SAPLING)));
            list.add(new CatalogEntry("#minecraft:is_taiga", "Taiga (any)", new ItemStack(Items.SPRUCE_SAPLING)));
            list.add(new CatalogEntry("#minecraft:is_mountain", "Mountains (any)", new ItemStack(Items.GOAT_HORN)));
            list.add(new CatalogEntry("#minecraft:is_jungle", "Jungle (any)", new ItemStack(Items.JUNGLE_SAPLING)));
            list.add(new CatalogEntry("#minecraft:is_ocean", "Ocean (any)", new ItemStack(Items.KELP)));
            list.add(new CatalogEntry("#minecraft:is_badlands", "Badlands", new ItemStack(Items.TERRACOTTA)));
            list.add(new CatalogEntry("#minecraft:is_nether", "Nether (any biome)", new ItemStack(Items.NETHERRACK)));
            if (minecraft != null && minecraft.level != null) {
                minecraft.level.registryAccess().registry(Registries.BIOME).ifPresent(reg -> {
                    reg.keySet().stream().sorted(Comparator.comparing(ResourceLocation::toString)).forEach(id -> {
                        String key = "biome." + id.getNamespace() + "." + id.getPath();
                        String tr = Language.getInstance().has(key)
                                ? Component.translatable(key).getString()
                                : humanizePath(id.getPath());
                        list.add(new CatalogEntry(id.toString(), tr, new ItemStack(Items.GRASS_BLOCK)));
                    });
                });
            }
        } else if (category == 2) {
            Map<String, String> poiNames = Map.ofEntries(
                    Map.entry("minecraft:armorer", "Armorer (Blast Furnace)"),
                    Map.entry("minecraft:weaponsmith", "Weaponsmith (Grindstone)"),
                    Map.entry("minecraft:toolsmith", "Toolsmith (Smithing Table)"),
                    Map.entry("minecraft:librarian", "Librarian (Lectern)"),
                    Map.entry("minecraft:cleric", "Cleric (Brewing Stand)"),
                    Map.entry("minecraft:farmer", "Farmer (Composter)"),
                    Map.entry("minecraft:fletcher", "Fletcher (Fletching Table)"),
                    Map.entry("minecraft:cartographer", "Cartographer (Cartography Table)"),
                    Map.entry("minecraft:butcher", "Butcher (Smoker)"),
                    Map.entry("minecraft:leatherworker", "Leatherworker (Cauldron)"),
                    Map.entry("minecraft:mason", "Mason (Stonecutter)"),
                    Map.entry("minecraft:shepherd", "Shepherd (Loom)"),
                    Map.entry("minecraft:fisherman", "Fisherman (Barrel)"),
                    Map.entry("minecraft:meeting", "Village Meeting (Bell)"),
                    Map.entry("minecraft:home", "Home (Bed)"),
                    Map.entry("minecraft:lodestone", "Lodestone"),
                    Map.entry("minecraft:nether_portal", "Nether Portal"),
                    Map.entry("minecraft:beehive", "Beehive"),
                    Map.entry("minecraft:bee_nest", "Bee Nest"),
                    Map.entry("minecraft:lightning_rod", "Lightning Rod")
            );
            BuiltInRegistries.POINT_OF_INTEREST_TYPE.keySet().stream()
                    .sorted(Comparator.comparing(ResourceLocation::toString))
                    .forEach(id -> {
                        String full = id.toString();
                        String label = poiNames.getOrDefault(full, humanizePath(id.getPath()));
                        list.add(new CatalogEntry(full, label, new ItemStack(Items.COMPASS)));
                    });
        } else if (category == 3) {
            list.add(new CatalogEntry("#minecraft:raiders", "Raiders (all)", new ItemStack(Items.CROSSBOW)));
            list.add(new CatalogEntry("#minecraft:skeletons", "Skeletons (all)", new ItemStack(Items.BONE)));
            list.add(new CatalogEntry("#minecraft:zombies", "Zombies (all)", new ItemStack(Items.ROTTEN_FLESH)));
            list.add(new CatalogEntry("#minecraft:illager", "Illagers (all)", new ItemStack(Items.EMERALD)));
            BuiltInRegistries.ENTITY_TYPE.entrySet().stream()
                    .filter(e -> e.getValue().getCategory() != MobCategory.MISC
                            || e.getValue() == EntityType.VILLAGER
                            || e.getValue() == EntityType.WANDERING_TRADER
                            || e.getValue() == EntityType.IRON_GOLEM
                            || e.getValue() == EntityType.SNOW_GOLEM)
                    .sorted(Comparator.comparing(e -> e.getKey().location().toString()))
                    .forEach(e -> {
                        String id = e.getKey().location().toString();
                        String label = e.getValue().getDescription().getString();
                        list.add(new CatalogEntry(id, label, new ItemStack(Items.SPAWNER)));
                    });
        } else if (category == 4) {
            BuiltInRegistries.ITEM.entrySet().stream()
                    .filter(e -> e.getValue() != Items.AIR)
                    .sorted(Comparator.comparing(e -> e.getKey().location().toString()))
                    .forEach(e -> {
                        String id = e.getKey().location().toString();
                        Item item = e.getValue();
                        list.add(new CatalogEntry(id, item.getDescription().getString(), new ItemStack(item)));
                    });
        }
        return list;
    }

    private List<CatalogEntry> filteredCatalogEntries() {
        String q = catalogFilter == null ? "" : catalogFilter.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return catalogAllEntries;
        }
        List<CatalogEntry> out = new ArrayList<>();
        for (CatalogEntry entry : catalogAllEntries) {
            if (entry.label().toLowerCase(Locale.ROOT).contains(q)
                    || entry.id().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(entry);
            }
        }
        return out;
    }

    private static String humanizePath(String path) {
        String[] parts = path.split("[_/]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return sb.isEmpty() ? path : sb.toString();
    }

    private void renderCatalogModal(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, height, 0xAA0A0C10);
        int mx = (width - MODAL_W) / 2;
        int my = (height - MODAL_H) / 2;

        graphics.fill(mx - 1, my - 1, mx + MODAL_W + 1, my + MODAL_H + 1, 0xFFE8C66A);
        graphics.fill(mx, my, mx + MODAL_W, my + MODAL_H, 0xFF181C24);
        graphics.fill(mx, my, mx + MODAL_W, my + 20, 0xFF222834);
        graphics.drawString(font, catalogTitle, mx + 8, my + 6, 0xFFE8C66A, false);

        int listTop = my + (catalogShowLocationTabs ? 62 : 44);
        int rowH = 19;
        int maxRows = (MODAL_H - (listTop - my) - 8) / rowH;

        List<CatalogEntry> filtered = filteredCatalogEntries();
        int maxScroll = Math.max(0, filtered.size() - maxRows);
        catalogScroll = Mth.clamp(catalogScroll, 0, maxScroll);

        graphics.fill(mx + 6, listTop - 1, mx + MODAL_W - 6, my + MODAL_H - 6, 0xFF12151B);

        if (filtered.isEmpty()) {
            graphics.drawString(font, "No results found (click '+ Custom ID' above)", mx + 14, listTop + 12, 0xFF90A4AE, false);
            return;
        }

        for (int i = 0; i < maxRows && (catalogScroll + i) < filtered.size(); i++) {
            CatalogEntry entry = filtered.get(catalogScroll + i);
            int ry = listTop + i * rowH;
            boolean hover = mouseX >= mx + 8 && mouseX < mx + MODAL_W - 8 && mouseY >= ry && mouseY < ry + rowH;
            if (hover) {
                graphics.fill(mx + 7, ry, mx + MODAL_W - 7, ry + rowH - 1, 0xFF2D3748);
            }
            graphics.renderItem(entry.icon(), mx + 10, ry + 1);
            String titleStr = font.plainSubstrByWidth(entry.label(), 155);
            graphics.drawString(font, titleStr, mx + 30, ry + 5, hover ? 0xFFFFD54F : 0xFFE0E6ED, false);
            String idStr = font.plainSubstrByWidth(entry.id(), 118);
            int idW = font.width(idStr);
            graphics.drawString(font, idStr, mx + MODAL_W - 12 - idW, ry + 5, 0xFF78909C, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (catalogOpen) {
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            int mx = (width - MODAL_W) / 2;
            int my = (height - MODAL_H) / 2;
            if (mouseX < mx || mouseX >= mx + MODAL_W || mouseY < my || mouseY >= my + MODAL_H) {
                catalogOpen = false;
                rebuildInspectorWidgets();
                return true;
            }
            int listTop = my + (catalogShowLocationTabs ? 62 : 44);
            int rowH = 19;
            int maxRows = (MODAL_H - (listTop - my) - 8) / rowH;
            List<CatalogEntry> filtered = filteredCatalogEntries();
            for (int i = 0; i < maxRows && (catalogScroll + i) < filtered.size(); i++) {
                int ry = listTop + i * rowH;
                if (mouseX >= mx + 8 && mouseX < mx + MODAL_W - 8 && mouseY >= ry && mouseY < ry + rowH) {
                    CatalogEntry chosen = filtered.get(catalogScroll + i);
                    BiConsumer<Integer, CatalogEntry> cb = catalogCallback;
                    int cat = catalogCategory;
                    catalogOpen = false;
                    if (cb != null) {
                        cb.accept(cat, chosen);
                    }
                    rebuildInspectorWidgets();
                    return true;
                }
            }
            return true;
        }

        if (contextMenuOpen) {
            List<ContextOption> options = activeContextOptions;
            int menuW = 216;
            int itemH = 16;
            int menuH = options.size() * itemH + 6;
            int mx = Math.min(contextMenuX, width - menuW - 6);
            int my = Math.min(contextMenuY, height - menuH - 6);
            for (int i = 0; i < options.size(); i++) {
                int iy = my + 3 + i * itemH;
                if (mouseX >= mx && mouseX < mx + menuW && mouseY >= iy && mouseY < iy + itemH) {
                    contextMenuOpen = false;
                    options.get(i).action().run();
                    return true;
                }
            }
            contextMenuOpen = false;
            return true;
        }

        if (isInspectorVisible() && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int cx = width - RIGHT_PANEL_W;
            int cy = TOP_BAR_H + (height - TOP_BAR_H) / 2;
            double dx = mouseX - cx;
            double dy = mouseY - cy;
            if (dx * dx + dy * dy <= 169) {
                inspectorCollapsed = true;
                rebuildInspectorWidgets();
                return true;
            }
        }

        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (mouseX < LEFT_PANEL_W && mouseY >= TOP_BAR_H + 24 && mouseY < height - 50) {
            int idx = ((int) mouseY - (TOP_BAR_H + 24)) / 20;
            if (idx == 0) {
                activeChapter = OVERVIEW_CHAPTER;
                rebuildGraph();
                return true;
            }
            List<Map.Entry<ResourceLocation, String>> chapters = sortedChapters();
            if (idx - 1 >= 0 && idx - 1 < chapters.size()) {
                ResourceLocation clickedChapter = chapters.get(idx - 1).getKey();
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    JsonObject obj = ClientDevStore.parseObject(chapters.get(idx - 1).getValue());
                    String name = ClientDevStore.readStoryText(obj, "title", clickedChapter.getPath());
                    contextMenuOpen = true;
                    contextMenuX = (int) mouseX;
                    contextMenuY = (int) mouseY;
                    activeContextOptions = List.of(
                            new ContextOption("✏ Rename Chapter", () -> {
                                activeChapter = clickedChapter;
                                NodeKey chKey = new NodeKey(ClientDevStore.TYPE_CHAPTER, clickedChapter);
                                selectedNodes.clear();
                                selectedNodes.add(chKey);
                                selectedNode = chKey;
                                inspectorCollapsed = false;
                                editingJson = ClientDevStore.parseObject(ClientDevStore.getJson(ClientDevStore.TYPE_CHAPTER, clickedChapter));
                                rebuildGraph();
                                rebuildInspectorWidgets();
                                if (titleBox != null) {
                                    setFocused(titleBox);
                                    titleBox.setFocused(true);
                                }
                            }),
                            new ContextOption("🗑 Delete Chapter: " + name, () -> deleteChapter(clickedChapter))
                    );
                    return true;
                }
                activeChapter = clickedChapter;
                rebuildGraph();
                return true;
            }
        }

        int canvasLeft = LEFT_PANEL_W;
        int canvasRight = isInspectorVisible() ? width - RIGHT_PANEL_W : width;
        if (mouseX >= canvasLeft && mouseX < canvasRight && mouseY >= TOP_BAR_H) {
            double wx = (mouseX - canvasLeft - panX) / zoom;
            double wy = (mouseY - TOP_BAR_H - panY) / zoom;

            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                CanvasNode hoveredNode = null;
                for (int i = canvasNodes.size() - 1; i >= 0; i--) {
                    CanvasNode node = canvasNodes.get(i);
                    if (wx >= node.x() && wx < node.x() + NODE_W && wy >= node.y() && wy < node.y() + NODE_H) {
                        hoveredNode = node;
                        break;
                    }
                }
                contextMenuOpen = true;
                contextMenuX = (int) mouseX;
                contextMenuY = (int) mouseY;
                contextWorldX = (int) Math.round(wx);
                contextWorldY = (int) Math.round(wy);
                activeContextOptions = buildCanvasContextOptions(hoveredNode);
                return true;
            }

            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                for (int i = canvasNodes.size() - 1; i >= 0; i--) {
                    CanvasNode node = canvasNodes.get(i);
                    int portX = node.x() + NODE_W - 6;
                    int portY = node.y() + NODE_H / 2 - 6;
                    if (wx >= portX && wx <= portX + 12 && wy >= portY && wy <= portY + 12) {
                        linkingFrom = node.key();
                        return true;
                    }
                    if (wx >= node.x() && wx < node.x() + NODE_W && wy >= node.y() && wy < node.y() + NODE_H) {
                        inspectorCollapsed = false;
                        if (hasShiftDown()) {
                            if (selectedNodes.contains(node.key())) {
                                selectedNodes.remove(node.key());
                                if (node.key().equals(selectedNode)) {
                                    selectedNode = selectedNodes.isEmpty() ? null : selectedNodes.iterator().next();
                                }
                            } else {
                                selectedNodes.add(node.key());
                                selectedNode = node.key();
                            }
                            editingJson = selectedNode != null
                                    ? ClientDevStore.parseObject(ClientDevStore.getJson(selectedNode.type(), selectedNode.id()))
                                    : null;
                            rebuildInspectorWidgets();
                            return true;
                        }
                        if (!selectedNodes.contains(node.key())) {
                            selectedNodes.clear();
                            selectedNodes.add(node.key());
                        }
                        selectedNode = node.key();
                        editingJson = ClientDevStore.parseObject(ClientDevStore.getJson(node.key().type(), node.key().id()));
                        draggingNode = node.key();
                        dragOffsetX = wx - node.x();
                        dragOffsetY = wy - node.y();
                        dragStartPositions.clear();
                        for (NodeKey sel : selectedNodes) {
                            int[] p = positions.get(sel);
                            if (p != null) {
                                dragStartPositions.put(sel, new int[]{p[0], p[1]});
                            }
                        }
                        rebuildInspectorWidgets();
                        return true;
                    }
                }
                if (hasShiftDown()) {
                    boxSelecting = true;
                    boxStartX = wx;
                    boxStartY = wy;
                    boxEndX = wx;
                    boxEndY = wy;
                    return true;
                }
                if (selectedNodes.size() > 1) {
                    selectedNodes.clear();
                    if (selectedNode != null) {
                        selectedNodes.add(selectedNode);
                    }
                    rebuildInspectorWidgets();
                }
                panning = true;
                panStartX = mouseX - panX;
                panStartY = mouseY - panY;
                return true;
            }

            if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                panning = true;
                panStartX = mouseX - panX;
                panStartY = mouseY - panY;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (catalogOpen) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (super.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        if (boxSelecting) {
            boxEndX = (mouseX - LEFT_PANEL_W - panX) / zoom;
            boxEndY = (mouseY - TOP_BAR_H - panY) / zoom;
            double minX = Math.min(boxStartX, boxEndX);
            double minY = Math.min(boxStartY, boxEndY);
            double maxX = Math.max(boxStartX, boxEndX);
            double maxY = Math.max(boxStartY, boxEndY);
            selectedNodes.clear();
            for (CanvasNode node : canvasNodes) {
                if (node.x() + NODE_W >= minX && node.x() <= maxX && node.y() + NODE_H >= minY && node.y() <= maxY) {
                    selectedNodes.add(node.key());
                }
            }
            selectedNode = selectedNodes.isEmpty() ? null : selectedNodes.iterator().next();
            editingJson = selectedNode != null
                    ? ClientDevStore.parseObject(ClientDevStore.getJson(selectedNode.type(), selectedNode.id()))
                    : null;
            return true;
        }
        if (draggingNode != null) {
            double wx = (mouseX - LEFT_PANEL_W - panX) / zoom;
            double wy = (mouseY - TOP_BAR_H - panY) / zoom;
            int[] anchorStart = dragStartPositions.get(draggingNode);
            if (anchorStart != null) {
                int nx = (int) Math.round((wx - dragOffsetX) / 8.0) * 8;
                int ny = (int) Math.round((wy - dragOffsetY) / 8.0) * 8;
                int dx = nx - anchorStart[0];
                int dy = ny - anchorStart[1];
                for (Map.Entry<NodeKey, int[]> entry : dragStartPositions.entrySet()) {
                    positions.put(entry.getKey(), new int[]{entry.getValue()[0] + dx, entry.getValue()[1] + dy});
                }
            }
            rebuildGraph();
            return true;
        }
        if (panning) {
            panX = mouseX - panStartX;
            panY = mouseY - panStartY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (catalogOpen) {
            return super.mouseReleased(mouseX, mouseY, button);
        }
        if (boxSelecting) {
            boxSelecting = false;
            rebuildInspectorWidgets();
            return true;
        }
        if (linkingFrom != null) {
            double wx = (mouseX - LEFT_PANEL_W - panX) / zoom;
            double wy = (mouseY - TOP_BAR_H - panY) / zoom;
            for (CanvasNode node : canvasNodes) {
                if (wx >= node.x() && wx < node.x() + NODE_W && wy >= node.y() && wy < node.y() + NODE_H) {
                    createSmartLink(linkingFrom, node.key());
                    break;
                }
            }
            linkingFrom = null;
            return true;
        }
        if (draggingNode != null) {
            Map<NodeKey, int[]> before = new LinkedHashMap<>();
            Map<NodeKey, int[]> after = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<NodeKey, int[]> entry : dragStartPositions.entrySet()) {
                int[] oldP = entry.getValue();
                int[] newP = positions.get(entry.getKey());
                if (newP != null) {
                    before.put(entry.getKey(), new int[]{oldP[0], oldP[1]});
                    after.put(entry.getKey(), new int[]{newP[0], newP[1]});
                    if (oldP[0] != newP[0] || oldP[1] != newP[1]) {
                        changed = true;
                    }
                }
            }
            draggingNode = null;
            dragStartPositions.clear();
            if (changed) {
                pushHistory("move (" + after.size() + ")",
                        () -> {
                            for (Map.Entry<NodeKey, int[]> e : before.entrySet()) {
                                positions.put(e.getKey(), new int[]{e.getValue()[0], e.getValue()[1]});
                            }
                            saveActiveChapterPositions();
                        },
                        () -> {
                            for (Map.Entry<NodeKey, int[]> e : after.entrySet()) {
                                positions.put(e.getKey(), new int[]{e.getValue()[0], e.getValue()[1]});
                            }
                            saveActiveChapterPositions();
                        });
            }
            saveActiveChapterPositions();
            return true;
        }
        panning = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (catalogOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                catalogOpen = false;
                rebuildInspectorWidgets();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                List<CatalogEntry> filtered = filteredCatalogEntries();
                if (!filtered.isEmpty() && catalogCallback != null) {
                    BiConsumer<Integer, CatalogEntry> cb = catalogCallback;
                    int cat = catalogCategory;
                    catalogOpen = false;
                    cb.accept(cat, filtered.getFirst());
                    rebuildInspectorWidgets();
                    return true;
                }
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (hasControlDown()) {
            if (keyCode == GLFW.GLFW_KEY_Z) {
                if (hasShiftDown()) {
                    performRedo();
                } else {
                    performUndo();
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Y) {
                performRedo();
                return true;
            }
        }
        boolean textFocused = (titleBox != null && titleBox.isFocused())
                || (secondaryBox != null && secondaryBox.isFocused())
                || (sbtBox != null && sbtBox.isFocused());
        if (textFocused && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            saveSelectedNode();
            return true;
        }
        if (!textFocused && (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE)) {
            if (!selectedNodes.isEmpty() || selectedNode != null) {
                deleteSelectedNodes();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (catalogOpen) {
            catalogScroll = Math.max(0, catalogScroll - (int) Math.signum(scrollY) * 3);
            return true;
        }
        int canvasLeft = LEFT_PANEL_W;
        int canvasRight = isInspectorVisible() ? width - RIGHT_PANEL_W : width;
        if (mouseX >= canvasLeft && mouseX < canvasRight && mouseY >= TOP_BAR_H) {
            float oldZoom = zoom;
            zoom = Mth.clamp(zoom + (float) scrollY * 0.1f, 0.35f, 2.0f);
            double relX = mouseX - canvasLeft - panX;
            double relY = mouseY - TOP_BAR_H - panY;
            panX -= relX * (zoom / oldZoom - 1.0);
            panY -= relY * (zoom / oldZoom - 1.0);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void status(String text, int color) {
        this.statusText = text;
        this.statusColor = color;
    }

    private int totalNodeCount() {
        int sum = 0;
        for (Map.Entry<ResourceLocation, Map<ResourceLocation, String>> entry : ClientDevStore.all().entrySet()) {
            if (!entry.getKey().equals(ClientDevStore.TYPE_CHAPTER) && !entry.getKey().equals(ClientDevStore.TYPE_FLAG)) {
                sum += entry.getValue().size();
            }
        }
        return sum;
    }

    private static @Nullable ResourceLocation normalizeType(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.contains(":")) {
            return Storybound.id(lower);
        }
        return ResourceLocation.tryParse(lower);
    }

    private static String shortId(String raw) {
        return raw.startsWith("storybound:") ? raw.substring("storybound:".length()) : raw;
    }

    private record CanvasNode(NodeKey key, String title, String badge, int color, ItemStack icon, int x, int y, boolean hasError) {}

    private record ContextOption(String label, Runnable action) {}

    private record HistoryAction(String label, Runnable undo, Runnable redo) {}

    private record CatalogEntry(String id, String label, ItemStack icon) {}
}

