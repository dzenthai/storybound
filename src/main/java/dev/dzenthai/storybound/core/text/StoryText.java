package dev.dzenthai.storybound.core.text;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.state.PartyData;
import dev.dzenthai.storybound.core.state.PartyManager;
import dev.dzenthai.storybound.core.state.StoryState;
import dev.dzenthai.storybound.core.state.StoryValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player-facing text in definitions. A translation key, never a raw string (except {@code literal}
 * for prototyping):
 * <pre>
 * "title": "mypack.quest.wolf_hunt.title"
 * "title": { "key": "mypack.npc.greet", "args": ["{player}", "{var:mypack:gold}"], "fallback": "Hello", "variant_by": "mypack:gender" }
 * "title": { "literal": "Draft" }
 * </pre>
 */
public sealed interface StoryText {
    MapCodec<Key> KEY_MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("key").forGetter(Key::key),
            Codec.STRING.listOf().optionalFieldOf("args", List.of()).forGetter(Key::args),
            Codec.STRING.optionalFieldOf("fallback").forGetter(Key::fallback),
            ResourceLocation.CODEC.optionalFieldOf("variant_by").forGetter(Key::variantBy)
    ).apply(instance, Key::new));

    Codec<StoryText> OBJECT_CODEC = Codec.either(KEY_MAP_CODEC.codec(), Codec.STRING.fieldOf("literal").codec())
            .xmap(either -> either.map(key -> (StoryText) key, Literal::new),
                    text -> text instanceof Literal literal ? Either.right(literal.text()) : Either.left((Key) text));

    /** A plain string is a key; an object is the full form. */
    Codec<StoryText> CODEC = Codec.either(Codec.STRING, OBJECT_CODEC)
            .xmap(either -> either.map(StoryText::key, text -> text),
                    text -> text instanceof Key key && key.isPlain() ? Either.left(key.key()) : Either.right(text));

    static StoryText key(String key) {
        return new Key(key, List.of(), Optional.empty(), Optional.empty());
    }

    /** Arguments and the variant resolved for this player; the client turns the result into a component. */
    ResolvedText resolve(ServerPlayer player);

    /** Without a player (item names, logs): the key with its fallback; arguments and variants are not applied. */
    Component asComponent();

    record Key(String key, List<String> args, Optional<String> fallback, Optional<ResourceLocation> variantBy) implements StoryText {
        boolean isPlain() {
            return args.isEmpty() && fallback.isEmpty() && variantBy.isEmpty();
        }

        @Override
        public ResolvedText resolve(ServerPlayer player) {
            List<Component> resolvedArgs = new ArrayList<>(args.size());
            for (String arg : args) {
                resolvedArgs.add(resolveArgument(arg, player));
            }
            Optional<String> variant = variantBy.flatMap(key -> StoryState.get(player, StoryState.declaredScope(key), key))
                    .map(StoryValues::display);
            return new ResolvedText(key, false, resolvedArgs, fallback, variant);
        }

        @Override
        public Component asComponent() {
            return Component.translatableWithFallback(key, fallback.orElse(null));
        }
    }

    record Literal(String text) implements StoryText {
        @Override
        public ResolvedText resolve(ServerPlayer player) {
            return ResolvedText.literal(text);
        }

        @Override
        public Component asComponent() {
            return Component.literal(text);
        }
    }

    /**
     * {@code {player}}, {@code {party}}, {@code {var:ns:key}} / {@code {flag:ns:key}} (the current value),
     * {@code {key:translation.key}}; anything else is inserted as written.
     */
    private static Component resolveArgument(String arg, ServerPlayer player) {
        if (!(arg.startsWith("{") && arg.endsWith("}"))) {
            return Component.literal(arg);
        }
        String token = arg.substring(1, arg.length() - 1);
        if (token.equals("player")) {
            return player.getDisplayName();
        }
        if (token.equals("party")) {
            return PartyData.get(player.server).partyOf(player.getUUID())
                    .map(party -> PartyManager.displayName(player.server, party))
                    .orElseGet(player::getDisplayName);
        }
        int colon = token.indexOf(':');
        if (colon < 0) {
            return Component.literal(arg);
        }
        String kind = token.substring(0, colon);
        String value = token.substring(colon + 1);
        if (kind.equals("key")) {
            return Component.translatable(value);
        }
        if (kind.equals("var") || kind.equals("flag")) {
            ResourceLocation key = ResourceLocation.tryParse(value);
            if (key != null) {
                return Component.literal(StoryState.get(player, StoryState.declaredScope(key), key).map(StoryValues::display).orElse(""));
            }
        }
        return Component.literal(arg);
    }
}
