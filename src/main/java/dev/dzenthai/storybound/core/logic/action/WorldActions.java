package dev.dzenthai.storybound.core.logic.action;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.config.StoryboundConfig;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.logic.condition.WorldConditions;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.ActionType;
import dev.dzenthai.storybound.core.logic.ItemMatcher;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.References;
import dev.dzenthai.storybound.core.logic.ScriptHooks;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.notify.NotifyKind;
import dev.dzenthai.storybound.core.notify.Notifications;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Effects on the player and the world. */
public final class WorldActions {
    static final Codec<SoundSource> SOUND_SOURCE_CODEC = Codec.STRING.comapFlatMap(
            name -> Arrays.stream(SoundSource.values())
                    .filter(source -> source.getName().equals(name))
                    .findFirst()
                    .map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown sound source: " + name)),
            SoundSource::getName);

    private WorldActions() {}

    /** {@code { "type": "give_item", "item": "minecraft:emerald", "count": 10 }}; drops what does not fit. */
    public record GiveItem(Holder<Item> item, int count, DataComponentPatch components) implements Action {
        public static final MapCodec<GiveItem> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemStack.ITEM_NON_AIR_CODEC.fieldOf("item").forGetter(GiveItem::item),
                Codec.intRange(1, 64 * 36).optionalFieldOf("count", 1).forGetter(GiveItem::count),
                DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(GiveItem::components)
        ).apply(instance, GiveItem::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.GIVE_ITEM.get();
        }

        @Override
        public void run(StoryContext context) {
            ItemStack template = new ItemStack(item, 1, components);
            int remaining = count;
            while (remaining > 0) {
                int stackSize = Math.min(remaining, template.getMaxStackSize());
                ItemHandlerHelper.giveItemToPlayer(context.player(), template.copyWithCount(stackSize));
                remaining -= stackSize;
            }
        }
    }

    /**
     * Removes exactly {@code count} matching items, or nothing: with too few items it takes none and
     * fails, which aborts the rest of the action list by default.
     */
    public record TakeItem(ItemMatcher matcher, int count) implements Action {
        public static final MapCodec<TakeItem> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ItemMatcher.MAP_CODEC.forGetter(TakeItem::matcher),
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(TakeItem::count)
        ).apply(instance, TakeItem::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.TAKE_ITEM.get();
        }

        @Override
        public void run(StoryContext context) throws StoryException {
            ServerPlayer player = context.player();
            int available = WorldConditions.countItems(player, matcher);
            if (available < count) {
                throw new StoryException(Component.translatableWithFallback("storybound.action.take_item.not_enough",
                        "Not enough items: needs %s, has %s", count, available));
            }
            removeItems(player, matcher, count);
        }
    }

    /** Takes up to {@code count} matching items from the whole inventory; returns how many it took. */
    public static int removeItems(ServerPlayer player, ItemMatcher matcher, int count) {
        Inventory inventory = player.getInventory();
        int remaining = count;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matcher.test(stack)) {
                int taken = Math.min(remaining, stack.getCount());
                stack.shrink(taken);
                remaining -= taken;
            }
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        return count - remaining;
    }

    /** Plays only for this player. The sound id may come from a resource pack, so it is not checked against the registry. */
    public record PlaySound(ResourceLocation sound, SoundSource source, float volume, float pitch) implements Action {
        public static final MapCodec<PlaySound> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("sound").forGetter(PlaySound::sound),
                SOUND_SOURCE_CODEC.optionalFieldOf("source", SoundSource.MASTER).forGetter(PlaySound::source),
                Codec.floatRange(0, 10).optionalFieldOf("volume", 1f).forGetter(PlaySound::volume),
                Codec.floatRange(0.5f, 2f).optionalFieldOf("pitch", 1f).forGetter(PlaySound::pitch)
        ).apply(instance, PlaySound::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.PLAY_SOUND.get();
        }

        @Override
        public void run(StoryContext context) {
            context.player().playNotifySound(SoundEvent.createVariableRangeEvent(sound), source, volume, pitch);
        }
    }

    /**
     * Runs a command at the player (so {@code @s} is the player) with the permission level
     * from {@code actions.commandPermissionLevel}; output is suppressed.
     */
    public record RunCommand(String command) implements Action {
        public static final MapCodec<RunCommand> CODEC = Codec.STRING.fieldOf("command").xmap(RunCommand::new, RunCommand::command);

        @Override
        public ActionType<?> type() {
            return BuiltinActions.RUN_COMMAND.get();
        }

        @Override
        public void run(StoryContext context) {
            CommandSourceStack source = context.player().createCommandSourceStack()
                    .withPermission(StoryboundConfig.COMMON.commandPermissionLevel.get())
                    .withSuppressedOutput();
            context.server().getCommands().performPrefixedCommand(source, command);
        }
    }

    /**
     * {@code { "type": "kubejs", "id": "mypack:pay_bribe", "args": { "gold": 5 } }}: an action registered by a script or a
     * mod, given {@code args} (an empty object without them).
     */
    public record Script(ResourceLocation id, JsonObject args) implements Action {
        public static final MapCodec<Script> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(Script::id),
                LogicCodecs.JSON_OBJECT.optionalFieldOf("args", new JsonObject()).forGetter(Script::args)
        ).apply(instance, Script::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.KUBEJS.get();
        }

        @Override
        public void run(StoryContext context) {
            ScriptHooks.runAction(id, context, args);
        }

        @Override
        public void collectReferences(References references) {
            references.script(id, false);
        }
    }

    /** {@code { "type": "notify", "title": "mypack.toast.rumour", "text": "mypack.toast.rumour.text" }}: a toast for the player. */
    public record Notify(StoryText title, Optional<StoryText> text) implements Action {
        public static final MapCodec<Notify> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                StoryText.CODEC.fieldOf("title").forGetter(Notify::title),
                StoryText.CODEC.optionalFieldOf("text").forGetter(Notify::text)
        ).apply(instance, Notify::new));

        @Override
        public ActionType<?> type() {
            return BuiltinActions.NOTIFY.get();
        }

        @Override
        public void run(StoryContext context) {
            ServerPlayer player = context.player();
            Notifications.send(player, NotifyKind.INFO, title.resolve(player), text.map(value -> value.resolve(player)));
        }
    }
}
