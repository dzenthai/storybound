package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.CoreDefinitions;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.data.ValidationIssue;
import dev.dzenthai.storybound.core.data.ValidationService;
import dev.dzenthai.storybound.core.logic.Actions;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.StoryContext;
import dev.dzenthai.storybound.core.logic.StoryEvent;
import dev.dzenthai.storybound.core.logic.StoryEvents;
import dev.dzenthai.storybound.core.logic.trigger.SeenTracker;
import dev.dzenthai.storybound.core.state.PartyManager;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.boss.BossData;
import dev.dzenthai.storybound.module.boss.BossDefinition;
import dev.dzenthai.storybound.module.boss.BossEvents;
import dev.dzenthai.storybound.module.boss.BossRegistries;
import dev.dzenthai.storybound.module.boss.Bosses;
import dev.dzenthai.storybound.module.codex.Codex;
import dev.dzenthai.storybound.module.codex.CodexDefinition;
import dev.dzenthai.storybound.module.codex.CodexEvents;
import dev.dzenthai.storybound.module.codex.CodexRegistries;
import dev.dzenthai.storybound.module.document.DocumentDefinition;
import dev.dzenthai.storybound.module.document.DocumentRegistries;
import dev.dzenthai.storybound.module.document.Documents;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Codex and Bosses on a real server: "seen" detection, sections by seen/killed/read, shared kills, boss fight credit, logic, validation. */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CodexGameTests {
    private static final ResourceLocation PIGS = key("pigs");
    private static final ResourceLocation BESTIARY = key("bestiary");
    private static final ResourceLocation BOSS = key("brute");

    private static final String PIGS_JSON = """
            { "category": "animals", "entity": "minecraft:pig", "title": "gametest.codex.pigs",
              "sections": [
                { "id": "base", "unlock": { "on": ["seen"] } },
                { "id": "habits", "unlock": { "on": ["killed"], "count": 2 } },
                { "id": "lore", "unlock": { "on": ["read:gametest:bestiary"] } },
                { "id": "secret", "unlock": { "on": ["manual"] } } ] }
            """;

    private static final String BOSS_JSON = """
            { "entity": { "type": "minecraft:zombie", "tag": "gametest_brute" }, "title": "gametest.boss.brute",
              "lore": {}, "tactics": {}, "rewards": { "items": ["minecraft:bone", { "id": "minecraft:emerald", "count": 3 }] },
              "participation": 0.1,
              "on_first_kill": { "type": "add_var", "key": "gametest:brute_firsts", "scope": "player", "amount": 1 },
              "on_kill": { "type": "add_var", "key": "gametest:brute_kills", "scope": "player", "amount": 1 } }
            """;

    private CodexGameTests() {}

    // "open": the 1-block "empty" template is walled in with barriers, which block the line of sight.
    @GameTest(template = "open")
    public static void seenFiresOnceWhileInView(GameTestHelper helper) {
        LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
        ServerPlayer looking = at(helper, helper.makeMockServerPlayerInLevel(), 2.5, 1, 8);
        ServerPlayer away = at(helper, helper.makeMockServerPlayerInLevel(), 4.5, 1, 8);
        looking.lookAt(EntityAnchorArgument.Anchor.EYES, pig.getEyePosition());
        away.lookAt(EntityAnchorArgument.Anchor.EYES, away.getEyePosition().add(0, 0, 10));
        with(helper, CoreDefinitions.EVENTS.serverStore(), StoryEvent.CODEC, Map.of(key("seen_pig"),
                "{ \"trigger\": { \"type\": \"entity_seen\", \"entity\": \"minecraft:pig\" },"
                        + " \"actions\": { \"type\": \"add_var\", \"key\": \"gametest:pig_sightings\", \"scope\": \"player\", \"amount\": 1 } }"), () -> {
            StoryEvents.rebuild();
            SeenTracker.check(looking);
            SeenTracker.check(away);
            expect(helper, sightings(looking), 0, "not yet: 10 of 20 ticks in view");
            SeenTracker.check(looking);
            SeenTracker.check(away);
            expect(helper, sightings(looking), 1, "seen after 20 ticks in view");
            expect(helper, sightings(away), 0, "a player looking away does not see it");
            SeenTracker.check(looking);
            expect(helper, sightings(looking), 1, "fires once while the entity stays in range");
        });
        StoryEvents.rebuild();
        remove(helper, looking);
        remove(helper, away);
        helper.succeed();
    }

    @GameTest(template = "open")
    public static void codexSectionsOpenBySeenKilledAndRead(GameTestHelper helper) {
        ServerPlayer player = at(helper, helper.makeMockServerPlayerInLevel(), 2.5, 1, 8);
        withCodex(helper, () -> {
            LivingEntity pig = helper.spawnWithNoFreeWill(EntityType.PIG, 2, 1, 2);
            player.lookAt(EntityAnchorArgument.Anchor.EYES, pig.getEyePosition());
            SeenTracker.check(player);
            SeenTracker.check(player);
            expect(helper, Codex.data(player).sections(PIGS), Set.of("base"), "seen opens the base section");

            kill(pig, player);
            expect(helper, Codex.data(player).isUnlocked(PIGS, "habits"), false, "one kill of two");
            kill(helper.spawnWithNoFreeWill(EntityType.PIG, 4, 1, 2), player);
            expect(helper, Codex.data(player).isUnlocked(PIGS, "habits"), true, "the second kill opens habits");

            Documents.read(player, BESTIARY, false);
            expect(helper, Codex.data(player).isUnlocked(PIGS, "lore"), true, "reading the bestiary opens lore");

            helper.assertFalse(test(helper, player, "{ \"type\": \"codex_unlocked\", \"entry\": \"gametest:pigs\", \"section\": \"secret\" }"), "manual is not opened by anything else");
            run(helper, player, "{ \"type\": \"unlock_codex\", \"entry\": \"gametest:pigs\", \"section\": \"secret\" }");
            helper.assertTrue(test(helper, player, "{ \"type\": \"codex_unlocked\", \"entry\": \"gametest:pigs\", \"section\": \"secret\" }"), "unlock_codex opens it");

            Codex.lock(player, PIGS, null);
            helper.assertFalse(test(helper, player, "{ \"type\": \"codex_unlocked\", \"entry\": \"gametest:pigs\" }"), "lock forgets the entry");
        });
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partyMembersNearbyShareKills(GameTestHelper helper) {
        ServerPlayer killer = at(helper, helper.makeMockServerPlayerInLevel(), 1.5, 2, 3);
        ServerPlayer near = at(helper, helper.makeMockServerPlayerInLevel(), 3.5, 2, 3);
        ServerPlayer far = at(helper, helper.makeMockServerPlayerInLevel(), 1.5, 2, 80);
        ServerPlayer stranger = at(helper, helper.makeMockServerPlayerInLevel(), 2.5, 2, 2);
        ok(() -> PartyManager.create(killer, "Hunters"));
        for (ServerPlayer member : List.of(near, far)) {
            ok(() -> PartyManager.invite(killer, member));
            ok(() -> PartyManager.accept(member));
        }
        withCodex(helper, () -> {
            kill(helper.spawnWithNoFreeWill(EntityType.PIG, 1, 2, 1), killer);
            expect(helper, Codex.data(killer).kills(PIGS), 1, "the killer");
            expect(helper, Codex.data(near).kills(PIGS), 1, "a party member nearby shares the kill");
            expect(helper, Codex.data(far).kills(PIGS), 0, "a party member far away does not");
            expect(helper, Codex.data(stranger).kills(PIGS), 0, "a player outside the party does not");
        });
        ok(() -> PartyManager.disband(killer));
        List.of(killer, near, far, stranger).forEach(player -> remove(helper, player));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bossVictoryGoesToEveryFightParticipant(GameTestHelper helper) {
        ServerPlayer leader = at(helper, helper.makeMockServerPlayerInLevel(), 1.5, 2, 3);
        ServerPlayer ally = at(helper, helper.makeMockServerPlayerInLevel(), 3.5, 2, 3);
        ServerPlayer stranger = at(helper, helper.makeMockServerPlayerInLevel(), 2.5, 2, 4);
        ServerPlayer scratcher = at(helper, helper.makeMockServerPlayerInLevel(), 0.5, 2, 4);
        ServerPlayer distant = at(helper, helper.makeMockServerPlayerInLevel(), 1.5, 2, 90);
        ok(() -> PartyManager.create(leader, "Raid"));
        for (ServerPlayer member : List.of(ally, distant)) {
            ok(() -> PartyManager.invite(leader, member));
            ok(() -> PartyManager.accept(member));
        }
        withBoss(helper, () -> {
            Zombie brute = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 2, 1);
            brute.addTag("gametest_brute");
            hit(brute, stranger, 6f);
            hit(brute, scratcher, 1f);
            expect(helper, Bosses.data(stranger).get(BOSS).encountered(), true, "hitting a boss means meeting it");
            helper.assertTrue(Bosses.fight(brute) != null, "the fight is tracked");
            hit(brute, leader, 100f);

            for (ServerPlayer winner : List.of(leader, ally, stranger)) {
                BossData.Record record = Bosses.data(winner).get(BOSS);
                expect(helper, record.victories(), 1, winner.getGameProfile().getName() + " shares the victory");
                helper.assertTrue(record.isUnlocked(BossDefinition.TACTICS) && record.isUnlocked(BossDefinition.REWARDS), "killed opens tactics and rewards");
                helper.assertTrue(record.bestTicks() >= 0 && record.firstVictory() > 0, "statistics recorded");
                expect(helper, playerVar(winner, "brute_firsts"), 1, "on_first_kill ran for every winner");
            }
            expect(helper, Bosses.data(scratcher).get(BOSS).victories(), 0, "1 damage of 20 health is below the 10% participation");
            expect(helper, Bosses.data(distant).get(BOSS).victories(), 0, "a party member far away gets nothing");
            helper.assertTrue(test(helper, ally, "{ \"type\": \"boss_killed\", \"boss\": \"gametest:brute\" }"), "boss_killed condition");

            Zombie again = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 2, 2, 1);
            again.addTag("gametest_brute");
            hit(again, leader, 100f);
            expect(helper, Bosses.data(leader).get(BOSS).victories(), 2, "second victory");
            expect(helper, playerVar(leader, "brute_firsts"), 1, "on_first_kill only once");
            expect(helper, playerVar(leader, "brute_kills"), 2, "on_kill every time");

            run(helper, scratcher, "{ \"type\": \"unlock_boss_section\", \"boss\": \"gametest:brute\", \"section\": \"lore\" }");
            helper.assertTrue(Bosses.data(scratcher).get(BOSS).isUnlocked(BossDefinition.LORE), "unlock_boss_section");
            Bosses.reset(leader, BOSS);
            expect(helper, Bosses.data(leader).get(BOSS).victories(), 0, "reset forgets the boss");
        });
        ok(() -> PartyManager.disband(leader));
        List.of(leader, ally, stranger, scratcher, distant).forEach(player -> remove(helper, player));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void validatorReportsBrokenCodexAndBosses(GameTestHelper helper) {
        ResourceLocation broken = key("broken");
        with(helper, CodexRegistries.CODEX.serverStore(), CodexDefinition.CODEC, Map.of(broken, """
                { "title": "gametest.broken", "sections": [
                    { "id": "a", "unlock": { "on": ["seen"] } },
                    { "id": "a", "unlock": { "on": ["read:gametest:no_such_book"] } } ] }
                """), () -> with(helper, CoreDefinitions.EVENTS.serverStore(), StoryEvent.CODEC, Map.of(key("bad_refs"),
                "{ \"trigger\": { \"type\": \"custom\", \"id\": \"gametest:x\" }, \"actions\": ["
                        + "{ \"type\": \"unlock_codex\", \"entry\": \"gametest:nope\" },"
                        + "{ \"type\": \"unlock_boss_section\", \"boss\": \"gametest:nobody\", \"section\": \"lore\" } ] }"), () -> {
            List<ValidationIssue> issues = ValidationService.validate().issues();
            List<String> entryErrors = errors(issues, broken);
            for (String expected : List.of("Duplicate section id 'a'", "has no 'entity'", "Unknown document gametest:no_such_book")) {
                helper.assertTrue(entryErrors.stream().anyMatch(message -> message.contains(expected)), "expected '" + expected + "', got " + entryErrors);
            }
            List<String> eventErrors = errors(issues, key("bad_refs"));
            helper.assertTrue(eventErrors.stream().anyMatch(message -> message.contains("Unknown Codex entry gametest:nope")), "unknown entry, got " + eventErrors);
            helper.assertTrue(eventErrors.stream().anyMatch(message -> message.contains("Unknown boss gametest:nobody")), "unknown boss, got " + eventErrors);
        }));
        helper.succeed();
    }

    private static List<String> errors(List<ValidationIssue> issues, ResourceLocation entry) {
        return issues.stream().filter(issue -> entry.equals(issue.entry()) && issue.isError()).map(ValidationIssue::message).toList();
    }

    /** Installs {@code gametest:pigs} and the {@code gametest:bestiary} document and subscribes them, then restores the loaded data. */
    private static void withCodex(GameTestHelper helper, Runnable body) {
        with(helper, DocumentRegistries.DOCUMENTS.serverStore(), DocumentDefinition.CODEC, Map.of(BESTIARY, "{ \"kind\": \"book\", \"title\": \"gametest.bestiary\" }"),
                () -> with(helper, CodexRegistries.CODEX.serverStore(), CodexDefinition.CODEC, Map.of(PIGS, PIGS_JSON), () -> {
                    CodexEvents.rebuild();
                    body.run();
                }));
        CodexEvents.rebuild();
    }

    private static void withBoss(GameTestHelper helper, Runnable body) {
        with(helper, BossRegistries.BOSSES.serverStore(), BossDefinition.CODEC, Map.of(BOSS, BOSS_JSON), () -> {
            BossEvents.rebuild();
            body.run();
        });
        BossEvents.rebuild();
    }

    private static void kill(LivingEntity victim, ServerPlayer killer) {
        victim.hurt(killer.damageSources().playerAttack(killer), 1000f);
    }

    /** One player hit; resets the victim's hurt cooldown so several hits in one tick all count. */
    private static void hit(LivingEntity victim, ServerPlayer attacker, float amount) {
        victim.invulnerableTime = 0;
        victim.hurt(attacker.damageSources().playerAttack(attacker), amount);
    }

    private static int sightings(ServerPlayer player) {
        return playerVar(player, "pig_sightings");
    }

    private static int playerVar(ServerPlayer player, String path) {
        return StoryState.getStored(player, StoryScope.PLAYER, key(path)).map(value -> value instanceof StoryValue.Int number ? number.value() : -1).orElse(0);
    }

    private static ServerPlayer at(GameTestHelper helper, ServerPlayer player, double x, double y, double z) {
        player.moveTo(helper.absoluteVec(new Vec3(x, y, z)));
        return player;
    }

    private static boolean test(GameTestHelper helper, ServerPlayer player, String json) {
        return LogicCodecs.CONDITION.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new).test(StoryContext.of(player));
    }

    private static void run(GameTestHelper helper, ServerPlayer player, String json) {
        Actions.runAll(LogicCodecs.ACTIONS.parse(ops(helper), JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new),
                StoryContext.of(player), "gametest", OnFail.ABORT);
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

    private static void ok(ThrowingRunnable action) {
        try {
            action.run();
        } catch (StoryException e) {
            throw new GameTestAssertException(e.getMessage());
        }
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }

    private static void remove(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws StoryException;
    }
}
