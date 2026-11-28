package dev.dzenthai.storybound.module.document;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryTrace;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import org.jetbrains.annotations.Nullable;

/**
 * Single dynamic NeoForge Global Loot Modifier that injects Storybound documents according to the {@code loot} rules
 * defined on each loaded {@link DocumentDefinition}. Reads the live server store on every roll so story changes in
 * {@code config/storybound/} and Dev Mode apply immediately on {@code /reload} with no extra datapack wiring.
 */
public final class DocumentLootModifier extends LootModifier {
    public static final String ONCE_PREFIX = "doc_loot/";

    public static final MapCodec<DocumentLootModifier> CODEC = RecordCodecBuilder.mapCodec(
            instance -> codecStart(instance).apply(instance, DocumentLootModifier::new));

    public DocumentLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    /** Storage key tracking whether a {@code once} document has already spawned via loot. */
    public static ResourceLocation onceKey(ResourceLocation documentId) {
        return Storybound.id(ONCE_PREFIX + documentId.getNamespace() + "/" + documentId.getPath());
    }

    private record IndexedDoc(ResourceLocation id, DocumentDefinition doc) {}

    private record LootIndex(Map<ResourceLocation, java.util.List<IndexedDoc>> byTable, java.util.List<IndexedDoc> chestDocs, boolean empty) {}

    private static volatile Map<ResourceLocation, DocumentDefinition> indexedFor;
    private static volatile LootIndex lootIndex = new LootIndex(Map.of(), java.util.List.of(), true);

    private static LootIndex indexFor(Map<ResourceLocation, DocumentDefinition> documents) {
        if (documents == indexedFor) {
            return lootIndex;
        }
        Map<ResourceLocation, java.util.List<IndexedDoc>> byTable = new java.util.HashMap<>();
        java.util.List<IndexedDoc> chestDocs = new java.util.ArrayList<>();
        for (Map.Entry<ResourceLocation, DocumentDefinition> entry : documents.entrySet()) {
            DocumentDefinition doc = entry.getValue();
            if (doc.loot().isEmpty()) {
                continue;
            }
            IndexedDoc indexed = new IndexedDoc(entry.getKey(), doc);
            boolean addedChest = false;
            java.util.Set<ResourceLocation> addedTables = null;
            for (DocumentDefinition.LootRule rule : doc.loot()) {
                if (rule.tables().isEmpty()) {
                    if (!addedChest) {
                        chestDocs.add(indexed);
                        addedChest = true;
                    }
                } else {
                    if (addedTables == null) {
                        addedTables = new java.util.HashSet<>();
                    }
                    for (ResourceLocation table : rule.tables()) {
                        if (addedTables.add(table)) {
                            byTable.computeIfAbsent(table, k -> new java.util.ArrayList<>()).add(indexed);
                        }
                    }
                }
            }
        }
        LootIndex built = new LootIndex(byTable, java.util.List.copyOf(chestDocs), byTable.isEmpty() && chestDocs.isEmpty());
        lootIndex = built;
        indexedFor = documents;
        return built;
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        Map<ResourceLocation, DocumentDefinition> documents = DocumentRegistries.DOCUMENTS.serverStore().all();
        if (documents.isEmpty()) {
            return generatedLoot;
        }
        LootIndex index = indexFor(documents);
        if (index.empty()) {
            return generatedLoot;
        }
        ResourceLocation tableId = context.getQueriedLootTableId();
        java.util.List<IndexedDoc> tableDocs = index.byTable().get(tableId);
        boolean checkChests = !index.chestDocs().isEmpty() && DocumentDefinition.LootRule.isChestTable(tableId);
        if (tableDocs == null && !checkChests) {
            return generatedLoot;
        }
        ServerLevel level = context.getLevel();
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        BlockPos pos = origin != null ? BlockPos.containing(origin) : null;
        ServerPlayer player = findPlayer(context);
        java.util.Set<ResourceLocation> spawned = (tableDocs != null && checkChests) ? new java.util.HashSet<>() : null;

        if (tableDocs != null) {
            applyDocs(generatedLoot, tableDocs, spawned, tableId, level, pos, player, context);
        }
        if (checkChests) {
            applyDocs(generatedLoot, index.chestDocs(), spawned, tableId, level, pos, player, context);
        }
        return generatedLoot;
    }

    private static void applyDocs(ObjectArrayList<ItemStack> generatedLoot, java.util.List<IndexedDoc> docs,
                                  @Nullable java.util.Set<ResourceLocation> spawned, ResourceLocation tableId,
                                  ServerLevel level, @Nullable BlockPos pos, @Nullable ServerPlayer player, LootContext context) {
        for (int i = 0; i < docs.size(); i++) {
            IndexedDoc entry = docs.get(i);
            ResourceLocation docId = entry.id();
            if (spawned != null && spawned.contains(docId)) {
                continue;
            }
            DocumentDefinition doc = entry.doc();
            for (DocumentDefinition.LootRule rule : doc.loot()) {
                if (matches(docId, rule, tableId, level, pos, player, context)) {
                    generatedLoot.add(Documents.createStack(docId, doc));
                    recordOnce(docId, rule.once(), level, player);
                    if (spawned != null) {
                        spawned.add(docId);
                    }
                    if (player != null) {
                        StoryTrace.note(player, "document " + docId, "loot spawned from " + tableId);
                    }
                    // At most one copy of the same document per loot roll.
                    break;
                }
            }
        }
    }

    private static boolean matches(ResourceLocation docId, DocumentDefinition.LootRule rule, ResourceLocation tableId,
                                   ServerLevel level, @Nullable BlockPos pos, @Nullable ServerPlayer player, LootContext context) {
        if (rule.chance() <= 0.0f) {
            return false;
        }
        if (!rule.matchesTable(tableId)) {
            return false;
        }
        if (rule.dimension().isPresent() && !level.dimension().equals(rule.dimension().get())) {
            return false;
        }
        if (rule.biome().isPresent() && (pos == null || !rule.biome().get().contains(level.getBiome(pos)))) {
            return false;
        }
        if (rule.structure().isPresent()
                && (pos == null || !dev.dzenthai.storybound.core.logic.StructureRef.containsAny(level, pos, rule.structure().get()))) {
            return false;
        }
        if (rule.once() == DocumentDefinition.LootRule.Once.WORLD) {
            if (StoryState.getWorldStored(level.getServer(), onceKey(docId)).isPresent()) {
                return false;
            }
        } else if (rule.once() == DocumentDefinition.LootRule.Once.PLAYER) {
            if (player == null || StoryState.getStored(player, StoryScope.PLAYER, onceKey(docId)).isPresent()) {
                return false;
            }
        }
        if (rule.conditions().isPresent()) {
            if (player == null || !rule.conditions().get().test(StoryContext.of(player))) {
                return false;
            }
        }
        return rule.chance() >= 1.0f || context.getRandom().nextFloat() < rule.chance();
    }

    private static void recordOnce(ResourceLocation docId, DocumentDefinition.LootRule.Once once,
                                   ServerLevel level, @Nullable ServerPlayer player) {
        if (once == DocumentDefinition.LootRule.Once.WORLD) {
            StoryState.setWorld(level.getServer(), onceKey(docId), new StoryValue.Bool(true));
        } else if (once == DocumentDefinition.LootRule.Once.PLAYER && player != null) {
            try {
                StoryState.set(player, StoryScope.PLAYER, onceKey(docId), new StoryValue.Bool(true));
            } catch (StoryException ignored) {
                // Internal marker key is never declared with a conflicting type.
            }
        }
    }

    private static @Nullable ServerPlayer findPlayer(LootContext context) {
        Entity direct = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        if (direct instanceof ServerPlayer player) {
            return player;
        }
        Entity lastDamage = context.getParamOrNull(LootContextParams.LAST_DAMAGE_PLAYER);
        if (lastDamage instanceof ServerPlayer player) {
            return player;
        }
        Entity attacker = context.getParamOrNull(LootContextParams.ATTACKING_ENTITY);
        if (attacker instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
