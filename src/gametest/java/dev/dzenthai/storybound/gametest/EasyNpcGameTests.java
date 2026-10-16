package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.dialogue.NpcBinding;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Storybound with Easy NPC's own entities: NPC bindings by type and by uuid, and the command an Easy NPC "run
 * command" action uses to start a dialogue with itself as the speaker. Lives in its own namespace and runs only with
 * {@code -Pdev_with_easynpc=true}. What only a client shows (the portrait with the NPC's skin, speech bubbles, the
 * camera framing its model) is checked by hand.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID + "_easynpc")
@PrefixGameTestTemplate(false)
public final class EasyNpcGameTests {
    private static final ResourceLocation HUMANOID = ResourceLocation.fromNamespaceAndPath("easy_npc", "humanoid");
    private static final ResourceLocation TALK = key("enpc_talk");
    private static final ResourceLocation BINDING = key("enpc_binding");

    private EasyNpcGameTests() {}

    @GameTest(template = "empty")
    public static void easyNpcsTakePartInDialogues(GameTestHelper helper) {
        if (!ModList.get().isLoaded("easy_npc")) {
            helper.fail("Easy NPC is not installed; run with -Pdev_with_easynpc=true");
            return;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(HUMANOID)
                .orElseThrow(() -> new GameTestAssertException("Easy NPC has no " + HUMANOID));
        Entity npc = helper.spawn(type, 1, 2, 1);
        helper.assertTrue(npc instanceof Mob, "an Easy NPC is a mob, so it can stand still and face the player: " + npc.getClass());
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new Vec3(2.5, 2, 2.5)));

        with(helper, DialogueRegistries.DIALOGUES.serverStore(), DialogueDefinition.CODEC, Map.of(TALK,
                "{ \"start\": \"a\", \"nodes\": { \"a\": { \"type\": \"line\", \"speaker\": \"npc\", \"text\": \"gametest.a\" } } }"), () -> {
            bindings(helper, "{ \"entity\": { \"type\": \"easy_npc:humanoid\" }, \"dialogues\": [ { \"dialogue\": \"gametest:enpc_talk\" } ] }", () -> {
                helper.assertTrue(interact(player, npc), "the click is taken by the dialogue, not Easy NPC's own screen");
                expect(helper, state(player).dialogue(), TALK, "a binding by Easy NPC's entity type starts the dialogue");
                expect(helper, state(player).camera().partner(), npc.getId(), "the Easy NPC is the one the player talks to");
                Dialogues.interrupt(player, EndReason.STOPPED);
            });
            bindings(helper, "{ \"entity\": { \"uuid\": \"" + npc.getUUID() + "\" }, \"dialogues\": [ { \"dialogue\": \"gametest:enpc_talk\" } ] }", () -> {
                interact(player, npc);
                expect(helper, state(player).dialogue(), TALK, "a binding by uuid selects that one Easy NPC");
                Dialogues.interrupt(player, EndReason.STOPPED);
            });

            // What an Easy NPC "run command" action does: the command runs as the NPC.
            helper.getLevel().getServer().getCommands().performPrefixedCommand(
                    npc.createCommandSourceStack().withPermission(2).withSuppressedOutput(), "storybound dialogue start @p gametest:enpc_talk @s");
            expect(helper, state(player).dialogue(), TALK, "the NPC's command started the dialogue for the nearest player");
            expect(helper, state(player).camera().partner(), npc.getId(), "with the NPC itself as the speaker");
            Dialogues.interrupt(player, EndReason.STOPPED);
        });
        npc.discard();
        helper.getLevel().getServer().getPlayerList().remove(player);
        helper.succeed();
    }

    private static void bindings(GameTestHelper helper, String json, Runnable body) {
        with(helper, DialogueRegistries.NPC_BINDINGS.serverStore(), NpcBinding.CODEC, Map.of(BINDING, json), () -> {
            NpcBindings.rebuild();
            body.run();
        });
        NpcBindings.rebuild();
    }

    private static boolean interact(ServerPlayer player, Entity target) {
        return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, target)).isCanceled();
    }

    private static Dialogues.State state(ServerPlayer player) {
        return Dialogues.state(player).orElseThrow(() -> new GameTestAssertException("the player should be in a dialogue"));
    }

    private static <T> void with(GameTestHelper helper, DefinitionStore<T> store, Codec<T> codec, Map<ResourceLocation, String> definitions, Runnable body) {
        Map<ResourceLocation, T> previous = store.all();
        Map<ResourceLocation, T> installed = new LinkedHashMap<>(previous);
        definitions.forEach((id, json) -> installed.put(id, codec.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new)));
        store.replace(installed);
        try {
            body.run();
        } finally {
            store.replace(previous);
        }
    }

    private static RegistryOps<JsonElement> ops(GameTestHelper helper) {
        return RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }
}
