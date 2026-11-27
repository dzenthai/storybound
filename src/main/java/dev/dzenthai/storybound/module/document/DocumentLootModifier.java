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

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        Map<ResourceLocation, DocumentDefinition> documents = DocumentRegistries.DOCUMENTS.serverStore().all();
        if (documents.isEmpty()) {
            return generatedLoot;
        }
        ResourceLocation tableId = context.getQueriedLootTableId();
        ServerLevel level = context.getLevel();
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        BlockPos pos = origin != null ? BlockPos.containing(origin) : null;
        ServerPlayer player = findPlayer(context);

        for (Map.Entry<ResourceLocation, DocumentDefinition> entry : documents.entrySet()) {
            DocumentDefinition doc = entry.getValue();
            if (doc.loot().isEmpty()) {
                continue;
            }
            ResourceLocation docId = entry.getKey();
            for (DocumentDefinition.LootRule rule : doc.loot()) {
                if (matches(docId, rule, tableId, level, pos, player, context)) {
                    generatedLoot.add(Documents.createStack(docId, doc));
                    recordOnce(docId, rule.once(), level, player);
                    if (player != null) {
                        StoryTrace.note(player, "document " + docId, "loot spawned from " + tableId);
                    }
                    // At most one copy of the same document per loot roll.
                    break;
                }
            }
        }
        return generatedLoot;
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
                && (pos == null || rule.structure().get().stream().noneMatch(ref -> ref.contains(level, pos)))) {
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
