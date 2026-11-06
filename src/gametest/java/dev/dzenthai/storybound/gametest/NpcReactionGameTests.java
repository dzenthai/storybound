package dev.dzenthai.storybound.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.dzenthai.storybound.Storybound;
import dev.dzenthai.storybound.core.StoryException;
import dev.dzenthai.storybound.core.data.DefinitionStore;
import dev.dzenthai.storybound.core.event.DialogueEvent.EndReason;
import dev.dzenthai.storybound.core.state.StoryScope;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValue;
import dev.dzenthai.storybound.module.dialogue.DialogueDefinition;
import dev.dzenthai.storybound.module.dialogue.DialogueRegistries;
import dev.dzenthai.storybound.module.dialogue.Dialogues;
import dev.dzenthai.storybound.module.dialogue.NpcBinding;
import dev.dzenthai.storybound.module.dialogue.NpcBindings;
import dev.dzenthai.storybound.module.dialogue.NpcChatter;
import dev.dzenthai.storybound.module.dialogue.NpcPhrases;
import dev.dzenthai.storybound.module.dialogue.NpcReactions;
import dev.dzenthai.storybound.module.dialogue.PhrasesDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * An NPC binding's {@code invulnerable}, {@code when_attacked} and {@code chatter}, and the phrases they say (November
 * 5, 2026): nothing hurts an invulnerable NPC but the void; a golem told to ignore a player's hit may not take the
 * player as its target (one without a binding may); with fight, patience forgives hits before it fights back; every hit
 * says the next phrase in order, and calming down starts them over; a fleeing golem runs and a phrase that is a dialogue
 * starts it; chatter speaks with a player near, in order, and keeps quiet with nobody near, in a dialogue or when its
 * conditions fail. Golems and villagers with their own AI, in a batch of their own.
 */
@SuppressWarnings("removal")
@GameTestHolder(Storybound.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NpcReactionGameTests {
    private static final String BATCH = "npc_reactions";

    private NpcReactionGameTests() {}

    @GameTest(template = "open", batch = BATCH)
    public static void anInvulnerableNpcTakesNoHarmButFromTheVoid(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 2);
        npc.addTag("gt_react_inv");
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions().binding("react_inv", "{ \"entity\": { \"tag\": \"gt_react_inv\" }, \"invulnerable\": true }");
        defs.install(helper);
        float health = npc.getHealth();
        npc.hurt(player.damageSources().playerAttack(player), 5f);
        npc.hurt(player.damageSources().onFire(), 5f);
        expect(helper, npc.getHealth(), health, "a player's hit and fire do nothing");
        npc.invulnerableTime = 0;
        npc.hurt(player.damageSources().fellOutOfWorld(), 5f);
        helper.assertTrue(npc.getHealth() < health, "the void still does");
        defs.uninstall(player);
        remove(helper, player);
        helper.succeed();
    }

    @GameTest(template = "open", batch = BATCH, timeoutTicks = 200)
    public static void aGolemToldToIgnoreAHitTakesNoRevenge(GameTestHelper helper) {
        IronGolem calm = helper.spawn(EntityType.IRON_GOLEM, 1, 1, 4);
        calm.addTag("gt_react_calm");
        IronGolem wild = helper.spawn(EntityType.IRON_GOLEM, 4, 1, 4);
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions().binding("react_calm", "{ \"entity\": { \"tag\": \"gt_react_calm\" }, \"when_attacked\": { \"reaction\": \"ignore\" } }");
        defs.install(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    calm.hurt(player.damageSources().playerAttack(player), 1f);
                    wild.hurt(player.damageSources().playerAttack(player), 1f);
                    expect(helper, NpcReactions.hits(calm, player), 1, "the hit is counted");
                    // What a golem's revenge does (its HurtByTargetGoal), called here as the mock player does not always draw it.
                    calm.setTarget(player);
                    wild.setTarget(player);
                    helper.assertTrue(wild.getTarget() == player, "a golem without a binding may take the player as its target");
                    helper.assertTrue(calm.getTarget() != player, "the one told to ignore the hit may not");
                })
                .thenIdle(20)
                .thenExecute(() -> helper.assertTrue(calm.getTarget() != player, "nor later, whatever its AI wants"))
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    @GameTest(template = "open", batch = BATCH, timeoutTicks = 300)
    public static void patienceForgivesThenTheGolemFights(GameTestHelper helper) {
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, 1, 1, 4);
        golem.addTag("gt_react_fight");
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions()
                .phrases("react_warn", "{ \"order\": \"sequence\", \"lines\": [ { \"literal\": \"Careful!\" } ] }")
                .phrases("react_angry", "{ \"order\": \"sequence\", \"lines\": [ { \"literal\": \"That's it!\" } ] }")
                .binding("react_fight", "{ \"entity\": { \"tag\": \"gt_react_fight\" }, \"when_attacked\": { \"reaction\": \"fight\", \"patience\": 1, "
                        + "\"warning\": \"gametest:react_warn\", \"phrases\": \"gametest:react_angry\" } }");
        defs.install(helper);
        String warned = NpcPhrases.warningCounterFor(golem, player);
        String angry = NpcPhrases.hitCounterFor(golem, player);
        helper.startSequence()
                .thenExecute(() -> {
                    golem.hurt(player.damageSources().playerAttack(player), 1f);
                    expect(helper, NpcPhrases.position(warned), 1, "the forgiven hit: a warning");
                    expect(helper, NpcPhrases.position(angry), -1, "not the reaction's phrase");
                })
                .thenIdle(70)
                .thenExecute(() -> {
                    helper.assertTrue(golem.getTarget() != player, "the first hit is forgiven");
                    golem.invulnerableTime = 0;
                    golem.hurt(player.damageSources().playerAttack(player), 1f);
                    expect(helper, NpcReactions.hits(golem, player), 2, "two hits");
                    expect(helper, NpcPhrases.position(angry), 1, "past its patience: the reaction's phrase");
                })
                .thenWaitUntil(() -> helper.assertTrue(golem.getTarget() == player, "the second one: it fights back"))
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    /**
     * Several bindings matching one villager: the one naming it most closely applies, whatever the ids' order. "Every
     * villager" (a_every, by kind, comes back) matches all three; z_elder (by its tag, does not come back) is the
     * tagged one's, though a_every comes first by id; m_one (by uuid) beats both; the plain villager keeps a_every.
     * Whether it comes back follows the binding that applies. In a batch of its own: "every villager" would take others'.
     */
    @GameTest(template = "open", batch = "npc_precision")
    public static void theClosestBindingApplies(GameTestHelper helper) {
        Villager plain = helper.spawn(EntityType.VILLAGER, 1, 1, 1);
        Villager elder = helper.spawn(EntityType.VILLAGER, 3, 1, 1);
        Villager one = helper.spawn(EntityType.VILLAGER, 5, 1, 1);
        for (Villager villager : List.of(plain, elder, one)) {
            villager.setNoAi(true);
        }
        elder.addTag("gt_prec_elder");
        one.addTag("gt_prec_elder");
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions()
                .binding("a_every", "{ \"entity\": { \"type\": \"minecraft:villager\" }, \"respawn\": true }")
                .binding("z_elder", "{ \"entity\": { \"type\": \"minecraft:villager\", \"tag\": \"gt_prec_elder\" } }")
                .binding("m_one", "{ \"entity\": { \"uuid\": \"" + one.getUUID() + "\" } }");
        defs.install(helper);
        try {
            expect(helper, boundTo(plain), "gametest:a_every", "a plain villager: every villager's");
            expect(helper, boundTo(elder), "gametest:z_elder", "the tagged one: its tag's binding, before a_every by id");
            expect(helper, boundTo(one), "gametest:m_one", "by uuid: over the tag and the kind");
            helper.assertTrue(NpcBindings.respawns(plain), "every villager comes back");
            helper.assertFalse(NpcBindings.respawns(elder), "the elder's own binding does not say so");
        } finally {
            defs.uninstall(player);
            remove(helper, player);
        }
        helper.succeed();
    }

    private static String boundTo(Entity entity) {
        return NpcBindings.find(entity).map(found -> String.valueOf(found.id())).orElse("none");
    }

    /**
     * Calmed down, a fighting NPC gives up the fight: patience 0, calm_after 5 s (the least); the golem fights back at once and,
     * 5 s after the last hit (checked once a second), it no longer has the player as its target.
     */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 300)
    public static void aFightIsGivenUpOnceCalm(GameTestHelper helper) {
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, 1, 1, 4);
        golem.addTag("gt_react_giveup");
        ServerPlayer player = player(helper);
        // Not killed by the golem's blows (a dead player ends the fight by itself); not invulnerable either, as mobs give up on those.
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 1200, 4, false, false));
        Definitions defs = new Definitions()
                .binding("react_giveup", "{ \"entity\": { \"tag\": \"gt_react_giveup\" }, \"when_attacked\": { \"reaction\": \"fight\", \"patience\": 0, \"calm_after\": 5 } }");
        defs.install(helper);
        helper.startSequence()
                .thenExecute(() -> golem.hurt(player.damageSources().playerAttack(player), 1f))
                .thenWaitUntil(() -> helper.assertTrue(golem.getTarget() == player, "past its patience at once: it fights back"))
                .thenExecute(() -> helper.assertTrue(NpcReactions.angryAt(golem, player), "angry: it does not talk"))
                .thenIdle(40)
                .thenExecute(() -> {
                    // Still angry 2 s on. The golem's own AI may drop its target for a moment now and then, so it is
                    // given the player again here, as its revenge would: what is checked is that calming down takes it away.
                    helper.assertTrue(NpcReactions.angryAt(golem, player), "2 s on, not calm yet: still angry");
                    golem.setTarget(player);
                    helper.assertTrue(golem.getTarget() == player, "a fighting NPC may have the player as its target");
                })
                .thenWaitUntil(() -> expect(helper, NpcReactions.hits(golem, player), 0, "calmed down: the grudge is gone"))
                .thenExecute(() -> {
                    helper.assertTrue(golem.getTarget() != player, "calmed down: it gives up the fight");
                    helper.assertFalse(NpcReactions.angryAt(golem, player), "no longer angry: it talks again");
                })
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    /** Hits say the phrases top to bottom, then the last again; calmed down, they start over (calm_after 5 s). */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 600)
    public static void hitsSayThePhrasesInOrderAndCalmingDownStartsThemOver(GameTestHelper helper) {
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, 1, 1, 4);
        golem.addTag("gt_react_words");
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions()
                .phrases("react_words", "{ \"order\": \"sequence\", \"lines\": [ { \"literal\": \"Hey!\" }, { \"literal\": \"That's it!\" } ] }")
                .binding("react_words", "{ \"entity\": { \"tag\": \"gt_react_words\" }, \"when_attacked\": { \"phrases\": \"gametest:react_words\", \"calm_after\": 5 } }");
        defs.install(helper);
        String counter = NpcPhrases.hitCounterFor(golem, player);
        Runnable hit = () -> {
            golem.invulnerableTime = 0;
            golem.hurt(player.damageSources().playerAttack(player), 1f);
        };
        helper.startSequence()
                .thenExecute(() -> {
                    hit.run();
                    expect(helper, NpcPhrases.position(counter), 1, "the first hit: the first phrase");
                })
                .thenIdle(70)
                .thenExecute(() -> {
                    hit.run();
                    expect(helper, NpcPhrases.position(counter), 2, "the second hit (3 s later): the second phrase");
                })
                .thenIdle(70)
                .thenExecute(() -> {
                    hit.run();
                    expect(helper, NpcPhrases.position(counter), 3, "the third: the last one again");
                })
                .thenIdle(110)
                .thenExecute(() -> {
                    hit.run();
                    expect(helper, NpcPhrases.position(counter), 1, "5 s without a hit: calmed down, the first phrase again");
                    expect(helper, NpcReactions.hits(golem, player), 1, "and the count starts over");
                })
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    /**
     * A hit's dialogue instead of phrases (since November 6, 2026 a phrases line no longer starts one): the golem runs
     * and the scene starts; a second hit while it runs does not queue it again.
     */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 200)
    public static void aFleeingGolemRunsAndItsHitDialogueStarts(GameTestHelper helper) {
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, 2, 1, 3);
        golem.addTag("gt_react_flee");
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions()
                .dialogue("react_talk", "{ \"presentation\": \"hud\", \"start\": \"a\", \"nodes\": { \"a\": { \"type\": \"line\", \"speaker\": \"npc\", \"text\": \"gametest.a\" } } }")
                .binding("react_flee", "{ \"entity\": { \"tag\": \"gt_react_flee\" }, \"when_attacked\": { \"reaction\": \"flee\", \"dialogue\": \"gametest:react_talk\" } }");
        defs.install(helper);
        double[] before = new double[1];
        helper.startSequence()
                .thenExecute(() -> {
                    before[0] = golem.distanceTo(player);
                    golem.hurt(player.damageSources().playerAttack(player), 1f);
                    expect(helper, Dialogues.state(player).map(Dialogues.State::dialogue), Optional.of(key("react_talk")), "the hit's dialogue starts");
                })
                // Hits closer than 10 ticks count once: the second one a little later, while the scene still runs.
                .thenIdle(15)
                .thenExecute(() -> {
                    golem.invulnerableTime = 0;
                    golem.hurt(player.damageSources().playerAttack(player), 1f);
                    expect(helper, NpcReactions.hits(golem, player), 2, "a second hit");
                    expect(helper, Dialogues.state(player).map(Dialogues.State::dialogue), Optional.of(key("react_talk")), "the scene goes on");
                    expect(helper, Dialogues.queued(player), List.of(), "hit again while it runs: not queued once more");
                    Dialogues.interrupt(player, EndReason.STOPPED);
                })
                .thenWaitUntil(() -> helper.assertTrue(golem.distanceTo(player) > before[0] + 2, "it runs from the player: " + golem.distanceTo(player)))
                .thenExecute(() -> helper.assertTrue(golem.getTarget() != player, "and takes no revenge"))
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    /** An own text instead of a phrases node: said on a hit as a node's one line would be. */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 100)
    public static void anOwnTextIsSaidAsAPhrase(GameTestHelper helper) {
        IronGolem golem = helper.spawn(EntityType.IRON_GOLEM, 1, 1, 4);
        golem.addTag("gt_react_own");
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions()
                .binding("react_own", "{ \"entity\": { \"tag\": \"gt_react_own\" }, \"when_attacked\": { \"phrases\": { \"text\": { \"literal\": \"Ow!\" } } } }");
        defs.install(helper);
        golem.hurt(player.damageSources().playerAttack(player), 1f);
        expect(helper, NpcPhrases.position(NpcPhrases.hitCounterFor(golem, player)), 1, "the own text is said");
        defs.uninstall(player);
        remove(helper, player);
        helper.succeed();
    }

    /**
     * Phrases on a click (November 6, 2026): an entry of phrases says the next line in a bubble and starts no
     * conversation; the click is the NPC's (no trading), also a quick second one that says nothing (not more than one
     * phrase a second); once the story moves on (a flag), a higher entry's phrases apply and start at their first line.
     */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 200)
    public static void aClickSaysPhrasesAndStartsNoConversation(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 3);
        npc.addTag("gt_click");
        ServerPlayer player = player(helper);
        ResourceLocation thanked = key("click_thanked");
        Definitions defs = new Definitions()
                .phrases("click_wolves", "{ \"order\": \"sequence\", \"lines\": [ { \"literal\": \"Wolves again...\" }, { \"literal\": \"Stay out of the woods.\" } ] }")
                .binding("click", "{ \"entity\": { \"tag\": \"gt_click\" }, \"override_interact\": true, \"dialogues\": ["
                        + " { \"phrases\": \"gametest:click_wolves\" },"
                        + " { \"phrases\": { \"text\": { \"literal\": \"Thank you!\" } }, \"priority\": 10,"
                        + "   \"conditions\": { \"type\": \"flag\", \"key\": \"" + thanked + "\", \"scope\": \"player\" } } ] }");
        defs.install(helper);
        String wolves = NpcPhrases.clickCounterFor(npc, player, 0);
        String thanks = NpcPhrases.clickCounterFor(npc, player, 1);
        helper.startSequence()
                .thenExecute(() -> {
                    helper.assertTrue(NpcBindings.rightClick(player, npc), "the click is the NPC's: no trading");
                    expect(helper, Dialogues.state(player), Optional.empty(), "no conversation");
                    expect(helper, NpcPhrases.position(wolves), 1, "the first line is said");
                    helper.assertTrue(NpcBindings.rightClick(player, npc), "a quick second click is still the NPC's");
                    expect(helper, NpcPhrases.position(wolves), 1, "but says nothing yet");
                })
                .thenIdle(25)
                .thenExecute(() -> {
                    NpcBindings.talk(player, npc);
                    expect(helper, NpcPhrases.position(wolves), 2, "the talk key: the next line");
                    try {
                        StoryState.set(player, StoryScope.PLAYER, thanked, new StoryValue.Bool(true));
                    } catch (StoryException e) {
                        throw new GameTestAssertException(e.getMessage());
                    }
                })
                .thenIdle(25)
                .thenExecute(() -> {
                    NpcBindings.talk(player, npc);
                    expect(helper, NpcPhrases.position(thanks), 1, "the story moved on: the higher entry's phrases, from their first line");
                    expect(helper, NpcPhrases.position(wolves), 2, "the wolves' lines stay where they were");
                    expect(helper, Dialogues.state(player), Optional.empty(), "still no conversation");
                })
                .thenExecute(() -> {
                    try {
                        StoryState.clear(player, StoryScope.PLAYER, thanked);
                    } catch (StoryException e) {
                        throw new GameTestAssertException(e.getMessage());
                    }
                    defs.uninstall(player);
                    remove(helper, player);
                })
                .thenSucceed();
    }

    /**
     * Chatter variants (November 6, 2026): the NPC says its own phrases while no variant applies, then the variant's once
     * its conditions pass for the nearest player; each goes through its lines on its own counter.
     */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 400)
    public static void chatterVariantsFollowTheStory(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 3);
        npc.addTag("gt_chatter_variants");
        ServerPlayer player = player(helper);
        ResourceLocation done = key("chatter_done");
        Definitions defs = new Definitions()
                .binding("chatter_variants", "{ \"entity\": { \"tag\": \"gt_chatter_variants\" }, \"chatter\": {"
                        + " \"phrases\": { \"text\": { \"literal\": \"Wolves, everywhere.\" } },"
                        + " \"variants\": [ { \"phrases\": { \"text\": { \"literal\": \"Quiet since you came.\" } },"
                        + "   \"conditions\": { \"type\": \"flag\", \"key\": \"" + done + "\", \"scope\": \"player\" } } ],"
                        + " \"min_interval\": 2, \"max_interval\": 2, \"radius\": 6 } }");
        defs.install(helper);
        String own = NpcPhrases.chatterCounterFor(npc, -1);
        String variant = NpcPhrases.chatterCounterFor(npc, 0);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(NpcPhrases.position(own) >= 1, "before: its own phrases"))
                .thenExecute(() -> {
                    expect(helper, NpcPhrases.position(variant), -1, "not the variant yet");
                    try {
                        StoryState.set(player, StoryScope.PLAYER, done, new StoryValue.Bool(true));
                    } catch (StoryException e) {
                        throw new GameTestAssertException(e.getMessage());
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(NpcPhrases.position(variant) >= 1, "after: the variant's phrases"))
                .thenExecute(() -> {
                    try {
                        StoryState.clear(player, StoryScope.PLAYER, done);
                    } catch (StoryException e) {
                        throw new GameTestAssertException(e.getMessage());
                    }
                    defs.uninstall(player);
                    remove(helper, player);
                })
                .thenSucceed();
    }

    /** A phrases line that is a dialogue (the format before November 6, 2026) fails the file and says where it goes now. */
    @GameTest(template = "open", batch = BATCH)
    public static void aPhrasesLineIsNoLongerADialogue(GameTestHelper helper) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var old = PhrasesDefinition.CODEC.parse(ops, JsonParser.parseString("{ \"lines\": [ \"Hey!\", { \"dialogue\": \"gametest:talk\" } ] }"));
        helper.assertTrue(old.error().isPresent(), "a dialogue line is refused");
        helper.assertTrue(old.error().get().message().contains("when_attacked"), "and the message says where it goes: " + old.error().get().message());
        var both = NpcBinding.CODEC.parse(ops, JsonParser.parseString("{ \"entity\": { \"tag\": \"x\" }, \"dialogues\": [ { \"dialogue\": \"gametest:a\", \"phrases\": \"gametest:b\" } ] }"));
        helper.assertTrue(both.error().isPresent(), "a click entry is a dialogue or phrases, not both");
        var hit = NpcBinding.CODEC.parse(ops, JsonParser.parseString("{ \"entity\": { \"tag\": \"x\" }, \"when_attacked\": { \"phrases\": \"gametest:b\", \"dialogue\": \"gametest:a\" } }"));
        helper.assertTrue(hit.error().isPresent(), "a hit says phrases or starts a dialogue, not both");
        helper.succeed();
    }

    /** A hit right after the NPC chattered: its phrase cuts in at once, it does not wait for the chatter's bubble to fade. */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 300)
    public static void aHitsPhraseCutsInOverChatter(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 3);
        npc.addTag("gt_chatter_hit");
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions()
                .binding("chatter_hit", "{ \"entity\": { \"tag\": \"gt_chatter_hit\" }, "
                        + "\"chatter\": { \"phrases\": { \"text\": { \"literal\": \"Nice day.\" } }, \"min_interval\": 2, \"max_interval\": 2 }, "
                        + "\"when_attacked\": { \"phrases\": { \"text\": { \"literal\": \"Please don't kill me!\" } } } }");
        defs.install(helper);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(NpcPhrases.position(NpcPhrases.chatterCounterFor(npc)) >= 1, "it chatters"))
                .thenExecute(() -> {
                    npc.hurt(player.damageSources().playerAttack(player), 1f);
                    expect(helper, NpcPhrases.position(NpcPhrases.hitCounterFor(npc, player)), 1, "hit right after: its phrase at once");
                })
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    /**
     * An NPC angry at a player does not talk with them (November 6, 2026): talking to it says its phrase instead; a
     * subtitle conversation with it ends when it gets angry; another player talks with it as ever; calmed down, it talks.
     */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 300)
    public static void anAngryNpcDoesNotTalkWithThePlayer(GameTestHelper helper) {
        IronGolem guard = helper.spawn(EntityType.IRON_GOLEM, 1, 1, 4);
        guard.addTag("gt_angry");
        ServerPlayer player = player(helper);
        ServerPlayer other = player(helper);
        Definitions defs = new Definitions()
                .dialogue("angry_talk", "{ \"presentation\": \"hud\", \"on_abort\": { \"type\": \"add_var\", \"key\": \"gametest:angry_aborts\", \"scope\": \"world\", \"amount\": 1 }, "
                        + "\"start\": \"a\", \"nodes\": { \"a\": { \"type\": \"line\", \"speaker\": \"npc\", \"text\": \"gametest.a\" } } }")
                .binding("angry", "{ \"entity\": { \"tag\": \"gt_angry\" }, \"dialogues\": [ { \"dialogue\": \"gametest:angry_talk\" } ], "
                        + "\"when_attacked\": { \"reaction\": \"fight\", \"phrases\": { \"text\": { \"literal\": \"Get lost!\" } }, \"calm_after\": 5 } }");
        defs.install(helper);
        helper.startSequence()
                .thenExecute(() -> {
                    NpcBindings.talk(player, guard);
                    expect(helper, Dialogues.state(player).map(Dialogues.State::dialogue), Optional.of(key("angry_talk")), "calm: it talks");
                    guard.hurt(player.damageSources().playerAttack(player), 1f);
                    expect(helper, Dialogues.state(player), Optional.empty(), "hit in the middle of it: the talk ends");
                    helper.assertTrue(NpcReactions.angryAt(guard, player), "it is angry at the player");
                    NpcBindings.talk(player, guard);
                    expect(helper, Dialogues.state(player), Optional.empty(), "angry: no talk with the one who hit it");
                    NpcBindings.talk(other, guard);
                    expect(helper, Dialogues.state(other).map(Dialogues.State::dialogue), Optional.of(key("angry_talk")), "another player: it talks");
                    Dialogues.interrupt(other, EndReason.STOPPED);
                    guard.setTarget(null);
                })
                .thenIdle(110)
                .thenExecute(() -> {
                    helper.assertTrue(!NpcReactions.angryAt(guard, player), "5 s on: calmed down");
                    NpcBindings.talk(player, guard);
                    expect(helper, Dialogues.state(player).map(Dialogues.State::dialogue), Optional.of(key("angry_talk")), "and it talks again");
                })
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, other))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    /** "Does not react" is no panic either: a villager hit does not run off, as one without a binding does (November 6, 2026). */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 200)
    public static void aVillagerThatDoesNotReactDoesNotPanic(GameTestHelper helper) {
        Villager calm = helper.spawn(EntityType.VILLAGER, 1, 1, 4);
        calm.addTag("gt_no_panic");
        Villager scared = helper.spawn(EntityType.VILLAGER, 4, 1, 4);
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions().binding("no_panic", "{ \"entity\": { \"tag\": \"gt_no_panic\" }, \"when_attacked\": { \"reaction\": \"ignore\" } }");
        defs.install(helper);
        boolean[] calmPanicked = new boolean[1];
        helper.startSequence()
                .thenExecute(() -> {
                    calm.hurt(player.damageSources().playerAttack(player), 1f);
                    scared.hurt(player.damageSources().playerAttack(player), 1f);
                })
                .thenWaitUntil(() -> {
                    calmPanicked[0] |= calm.getBrain().isActive(net.minecraft.world.entity.schedule.Activity.PANIC);
                    helper.assertTrue(scared.getBrain().isActive(net.minecraft.world.entity.schedule.Activity.PANIC), "a villager without a binding panics");
                })
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(!calmPanicked[0] && !calm.getBrain().isActive(net.minecraft.world.entity.schedule.Activity.PANIC),
                        "the one that does not react does not"))
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    /** A tagged villager carries its NPC's name over its head; turned off, the name goes (November 6, 2026). */
    @GameTest(template = "open", batch = BATCH)
    public static void anNpcShowsItsNameOverItsHead(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 2);
        npc.addTag("gt_named");
        ServerPlayer player = player(helper);
        Definitions shown = new Definitions().binding("named", "{ \"entity\": { \"tag\": \"gt_named\" }, \"show_name\": true, \"display_name\": \"gametest.elder\" }");
        shown.install(helper);
        dev.dzenthai.storybound.module.dialogue.NpcNames.apply(npc);
        helper.assertTrue(npc.getCustomName() != null && npc.getCustomName().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents key
                && key.getKey().equals("gametest.elder") && npc.isCustomNameVisible(), "its name, as a key each player reads in their language, always shown");
        shown.uninstall(player);
        Definitions hidden = new Definitions().binding("named", "{ \"entity\": { \"tag\": \"gt_named\" }, \"show_name\": false, \"display_name\": \"gametest.elder\" }");
        hidden.install(helper);
        dev.dzenthai.storybound.module.dialogue.NpcNames.apply(npc);
        helper.assertTrue(npc.getCustomName() == null && !npc.isCustomNameVisible(), "show_name off: the name goes");
        helper.assertTrue(dev.dzenthai.storybound.module.dialogue.NpcNames.nameOf(npc).isPresent(), "and dialogues still name the NPC by it");
        hidden.uninstall(player);
        remove(helper, player);
        helper.succeed();
    }

    /** With a player near, chatter speaks every 2 s, in order; with nobody near, in a dialogue, it keeps quiet. */
    @GameTest(template = "open", batch = BATCH, timeoutTicks = 600)
    public static void chatterSpeaksWithAPlayerNearAndKeepsQuietOtherwise(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 3);
        npc.addTag("gt_chatter");
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions()
                .dialogue("chatter_talk", "{ \"presentation\": \"hud\", \"start\": \"a\", \"nodes\": { \"a\": { \"type\": \"line\", \"speaker\": \"npc\", \"text\": \"gametest.a\" } } }")
                .phrases("chatter", "{ \"order\": \"sequence\", \"lines\": [ { \"literal\": \"Fresh fish!\" }, { \"literal\": \"Cheap!\" } ] }")
                .binding("chatter", "{ \"entity\": { \"tag\": \"gt_chatter\" }, \"chatter\": { \"phrases\": \"gametest:chatter\", "
                        + "\"min_interval\": 2, \"max_interval\": 2, \"radius\": 6 } }");
        defs.install(helper);
        String counter = NpcPhrases.chatterCounterFor(npc);
        int[] said = new int[1];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(NpcPhrases.position(counter) >= 1, "a player near: it speaks"))
                .thenWaitUntil(() -> helper.assertTrue(NpcPhrases.position(counter) >= 2, "and again, the next one"))
                .thenExecute(() -> {
                    // Nobody near: it keeps quiet and its phrases do not move on.
                    player.teleportTo(player.getX() + 20, player.getY(), player.getZ());
                    said[0] = NpcPhrases.position(counter);
                })
                .thenIdle(100)
                .thenExecute(() -> {
                    expect(helper, NpcPhrases.position(counter), said[0], "nobody within the radius: quiet");
                    player.teleportTo(player.getX() - 20, player.getY(), player.getZ());
                    helper.assertTrue(Dialogues.start(player, key("chatter_talk"), npc), "a dialogue with it");
                })
                .thenIdle(100)
                .thenExecute(() -> {
                    expect(helper, NpcPhrases.position(counter), said[0], "in a dialogue: quiet");
                    Dialogues.interrupt(player, EndReason.STOPPED);
                    helper.assertTrue(NpcChatter.nextAt(npc) >= 0, "it still waits its turn");
                })
                .thenWaitUntil(() -> helper.assertTrue(NpcPhrases.position(counter) > said[0], "free again: it speaks"))
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    @GameTest(template = "open", batch = BATCH, timeoutTicks = 200)
    public static void chatterKeepsQuietWhileItsConditionsFail(GameTestHelper helper) {
        Villager npc = helper.spawnWithNoFreeWill(EntityType.VILLAGER, 2, 1, 3);
        npc.addTag("gt_chatter_never");
        ServerPlayer player = player(helper);
        Definitions defs = new Definitions()
                .phrases("chatter_never", "{ \"lines\": [ { \"literal\": \"Psst!\" } ] }")
                .binding("chatter_never", "{ \"entity\": { \"tag\": \"gt_chatter_never\" }, \"chatter\": { \"phrases\": \"gametest:chatter_never\", "
                        + "\"min_interval\": 2, \"max_interval\": 2, \"conditions\": { \"type\": \"flag\", \"key\": \"gametest:never_set\" } } }");
        defs.install(helper);
        helper.startSequence()
                .thenIdle(120)
                .thenExecute(() -> expect(helper, NpcPhrases.position(NpcPhrases.chatterCounterFor(npc)), -1, "its conditions fail: never a word"))
                .thenExecute(() -> defs.uninstall(player))
                .thenExecute(() -> remove(helper, player))
                .thenSucceed();
    }

    /** A survival player: mobs ignore creative ones, which would make any revenge pass unseen. */
    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.moveTo(helper.absoluteVec(new Vec3(2.5, 1, 1.5)));
        return player;
    }

    private static void remove(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
    }

    /** This test's dialogues, phrases and bindings, put in and taken out alone: others running in the batch keep theirs. */
    private static final class Definitions {
        private final Map<ResourceLocation, String> dialogues = new LinkedHashMap<>();
        private final Map<ResourceLocation, String> phrases = new LinkedHashMap<>();
        private final Map<ResourceLocation, String> bindings = new LinkedHashMap<>();

        Definitions dialogue(String path, String json) {
            dialogues.put(key(path), json);
            return this;
        }

        Definitions phrases(String path, String json) {
            phrases.put(key(path), json);
            return this;
        }

        Definitions binding(String path, String json) {
            bindings.put(key(path), json);
            return this;
        }

        void install(GameTestHelper helper) {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
            put(DialogueRegistries.DIALOGUES.serverStore(), DialogueDefinition.CODEC, dialogues, ops);
            put(DialogueRegistries.PHRASES.serverStore(), PhrasesDefinition.CODEC, phrases, ops);
            put(DialogueRegistries.NPC_BINDINGS.serverStore(), NpcBinding.CODEC, bindings, ops);
            NpcBindings.rebuild();
        }

        void uninstall(ServerPlayer player) {
            Dialogues.interrupt(player, EndReason.STOPPED);
            take(DialogueRegistries.DIALOGUES.serverStore(), List.copyOf(dialogues.keySet()));
            take(DialogueRegistries.PHRASES.serverStore(), List.copyOf(phrases.keySet()));
            take(DialogueRegistries.NPC_BINDINGS.serverStore(), List.copyOf(bindings.keySet()));
            NpcBindings.rebuild();
        }

        private static <T> void put(DefinitionStore<T> store, Codec<T> codec, Map<ResourceLocation, String> definitions, RegistryOps<JsonElement> ops) {
            Map<ResourceLocation, T> all = new LinkedHashMap<>(store.all());
            definitions.forEach((id, json) -> all.put(id, codec.parse(ops, JsonParser.parseString(json)).getOrThrow(GameTestAssertException::new)));
            store.replace(all);
        }

        private static <T> void take(DefinitionStore<T> store, List<ResourceLocation> ids) {
            Map<ResourceLocation, T> all = new LinkedHashMap<>(store.all());
            ids.forEach(all::remove);
            store.replace(all);
        }
    }

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("gametest", path);
    }

    private static void expect(GameTestHelper helper, Object actual, Object expected, String what) {
        helper.assertTrue(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }
}
