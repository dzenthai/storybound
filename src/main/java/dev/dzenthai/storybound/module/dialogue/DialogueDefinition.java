package dev.dzenthai.storybound.module.dialogue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.dzenthai.storybound.core.logic.Action;
import dev.dzenthai.storybound.core.logic.Condition;
import dev.dzenthai.storybound.core.logic.LogicCodecs;
import dev.dzenthai.storybound.core.logic.OnFail;
import dev.dzenthai.storybound.core.logic.condition.LogicConditions;
import dev.dzenthai.storybound.core.text.StoryText;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/**
 * {@code data/<ns>/storybound/dialogues/<path>.json}: a graph of nodes run by the server. Never sent to
 * clients; they receive one line or one set of options at a time.
 *
 * @param participants who may speak besides {@code player}; by default one {@code npc}, the entity the dialogue was started with
 * @param broadcast    nearby players see the lines as speech bubbles
 * @param onAbort      run when the dialogue is interrupted (walked away, ESC, damage, the NPC died)
 */
public record DialogueDefinition(Map<String, Participant> participants, String start, Map<String, Node> nodes,
                                 Presentation presentation, boolean closable, boolean broadcast, List<Action> onAbort) {
    /** Ends the current dialogue; inside a {@code call} it returns to the caller. */
    public static final String END = "@end";
    /** Ends the whole conversation, even inside a {@code call}. */
    public static final String EXIT = "@exit";
    /** The speaker id of the player in the dialogue; always available. */
    public static final String PLAYER = "player";

    private static final Map<String, Participant> DEFAULT_PARTICIPANTS = Map.of("npc", new Participant(Participant.Kind.BOUND, Optional.empty(), Optional.empty()));

    public static final Codec<DialogueDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Participant.CODEC).optionalFieldOf("participants", DEFAULT_PARTICIPANTS).forGetter(DialogueDefinition::participants),
            Codec.STRING.fieldOf("start").forGetter(DialogueDefinition::start),
            Codec.unboundedMap(Codec.STRING, Node.CODEC).fieldOf("nodes").forGetter(DialogueDefinition::nodes),
            Presentation.CODEC.optionalFieldOf("presentation", Presentation.SCREEN).forGetter(DialogueDefinition::presentation),
            Codec.BOOL.optionalFieldOf("closable", true).forGetter(DialogueDefinition::closable),
            Codec.BOOL.optionalFieldOf("broadcast", true).forGetter(DialogueDefinition::broadcast),
            LogicCodecs.ACTIONS.optionalFieldOf("on_abort", List.of()).forGetter(DialogueDefinition::onAbort)
    ).apply(instance, DialogueDefinition::new));

    public static boolean isSentinel(String target) {
        return target.equals(END) || target.equals(EXIT);
    }

    public enum Presentation implements StringRepresentable {
        /** A blocking screen: portrait, name, text, options. */
        SCREEN("screen"),
        /** Subtitles at the bottom of the screen; the player keeps moving. A choice still opens the screen. */
        HUD("hud");

        public static final Codec<Presentation> CODEC = StringRepresentable.fromEnum(Presentation::values);

        private final String name;

        Presentation(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    /**
     * @param name     overrides the entity's name ({@code bound}); required in practice for {@code virtual}
     * @param portrait a texture shown instead of an entity ({@code virtual} only)
     */
    public record Participant(Kind kind, Optional<StoryText> name, Optional<ResourceLocation> portrait) {
        public static final Codec<Participant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Kind.CODEC.fieldOf("type").forGetter(Participant::kind),
                StoryText.CODEC.optionalFieldOf("name").forGetter(Participant::name),
                ResourceLocation.CODEC.optionalFieldOf("portrait").forGetter(Participant::portrait)
        ).apply(instance, Participant::new));

        public enum Kind implements StringRepresentable {
            /** The entity the dialogue was started with (the interacted NPC, the command's speaker). */
            BOUND("bound"),
            /** No entity: a narrator, a voice, a letter read aloud. */
            VIRTUAL("virtual");

            public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

            private final String name;

            Kind(String name) {
                this.name = name;
            }

            @Override
            public String getSerializedName() {
                return name;
            }
        }
    }

    /** A node of the graph. {@code line} and {@code choice} are shown to the player; the rest run on the server without stopping. */
    public sealed interface Node {
        Codec<Node> CODEC = Kind.CODEC.dispatch("type", Node::kind, Kind::codec);

        Kind kind();

        /** Every target this node may lead to inside its own dialogue: node ids, {@code @end}, {@code @exit}. */
        void targets(Consumer<String> sink);

        default boolean visible() {
            return false;
        }

        enum Kind implements StringRepresentable {
            LINE("line", () -> Line.CODEC),
            CHOICE("choice", () -> Choice.CODEC),
            CONDITION("condition", () -> Branch.CODEC),
            ACTION("action", () -> Act.CODEC),
            RANDOM("random", () -> RandomNode.CODEC),
            JUMP("jump", () -> Jump.CODEC),
            CALL("call", () -> Call.CODEC),
            END("end", () -> End.CODEC);

            public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

            private final String name;
            // Suppliers: the node records implement Node, so reading their codecs here directly could see them uninitialized.
            private final Supplier<MapCodec<? extends Node>> codec;

            Kind(String name, Supplier<MapCodec<? extends Node>> codec) {
                this.name = name;
                this.codec = codec;
            }

            public MapCodec<? extends Node> codec() {
                return codec.get();
            }

            @Override
            public String getSerializedName() {
                return name;
            }
        }
    }

    /**
     * A spoken line. The player moves on with a click; with {@code duration} (seconds) it also moves on by itself.
     *
     * @param speaker {@code player} or a participant id
     * @param sound   played when the line is shown, e.g. a voice clip from a resource pack
     */
    public record Line(String speaker, StoryText text, String next, Optional<ResourceLocation> sound, Optional<Float> duration) implements Node {
        public static final MapCodec<Line> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("speaker").forGetter(Line::speaker),
                StoryText.CODEC.fieldOf("text").forGetter(Line::text),
                Codec.STRING.optionalFieldOf("next", END).forGetter(Line::next),
                ResourceLocation.CODEC.optionalFieldOf("sound").forGetter(Line::sound),
                Codec.floatRange(0.1f, 600f).optionalFieldOf("duration").forGetter(Line::duration)
        ).apply(instance, Line::new));

        @Override
        public Kind kind() {
            return Kind.LINE;
        }

        @Override
        public void targets(Consumer<String> sink) {
            sink.accept(next);
        }

        @Override
        public boolean visible() {
            return true;
        }
    }

    /** The player picks one option. With {@code timeout}, waiting too long picks {@code timeout.next}. */
    public record Choice(List<Option> options, Optional<Timeout> timeout) implements Node {
        public static final MapCodec<Choice> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Option.CODEC.listOf().fieldOf("options").forGetter(Choice::options),
                Timeout.CODEC.optionalFieldOf("timeout").forGetter(Choice::timeout)
        ).apply(instance, Choice::new));

        /** An option's id, or its position when it has none. */
        public String optionId(int index) {
            return options.get(index).id().orElse(String.valueOf(index));
        }

        @Override
        public Kind kind() {
            return Kind.CHOICE;
        }

        @Override
        public void targets(Consumer<String> sink) {
            options.forEach(option -> sink.accept(option.next()));
            timeout.ifPresent(value -> sink.accept(value.next()));
        }

        @Override
        public boolean visible() {
            return true;
        }
    }

    /**
     * @param locked     what happens when {@code conditions} fail: {@code hide} the option or {@code show} it greyed out
     * @param lockedText the hint on a greyed-out option
     * @param once       hidden for this player after being chosen once
     * @param actions    run when chosen; if they abort (e.g. a price could not be taken), the choice is shown again
     * @param think      seconds the NPC "thinks" (animated dots) before answering; 0 for none; by default clients derive it from the option's length
     */
    public record Option(Optional<String> id, StoryText text, Condition conditions, Locked locked, Optional<StoryText> lockedText,
                         boolean once, List<Action> actions, String next, Optional<Float> think) {
        public static final Codec<Option> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("id").forGetter(Option::id),
                StoryText.CODEC.fieldOf("text").forGetter(Option::text),
                LogicCodecs.CONDITION.optionalFieldOf("conditions", LogicConditions.ALWAYS).forGetter(Option::conditions),
                Locked.CODEC.optionalFieldOf("locked", Locked.HIDE).forGetter(Option::locked),
                StoryText.CODEC.optionalFieldOf("locked_text").forGetter(Option::lockedText),
                Codec.BOOL.optionalFieldOf("once", false).forGetter(Option::once),
                LogicCodecs.ACTIONS.optionalFieldOf("actions", List.of()).forGetter(Option::actions),
                Codec.STRING.optionalFieldOf("next", END).forGetter(Option::next),
                Codec.floatRange(0f, 10f).optionalFieldOf("think").forGetter(Option::think)
        ).apply(instance, Option::new));

        public enum Locked implements StringRepresentable {
            HIDE("hide"),
            SHOW("show");

            public static final Codec<Locked> CODEC = StringRepresentable.fromEnum(Locked::values);

            private final String name;

            Locked(String name) {
                this.name = name;
            }

            @Override
            public String getSerializedName() {
                return name;
            }
        }
    }

    public record Timeout(float seconds, String next) {
        public static final Codec<Timeout> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.floatRange(1f, 600f).fieldOf("seconds").forGetter(Timeout::seconds),
                Codec.STRING.fieldOf("next").forGetter(Timeout::next)
        ).apply(instance, Timeout::new));
    }

    /** JSON type {@code condition}: branches without showing anything. */
    public record Branch(Condition conditions, String then, String otherwise) implements Node {
        public static final MapCodec<Branch> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                LogicCodecs.CONDITION.fieldOf("conditions").forGetter(Branch::conditions),
                Codec.STRING.fieldOf("then").forGetter(Branch::then),
                Codec.STRING.fieldOf("else").forGetter(Branch::otherwise)
        ).apply(instance, Branch::new));

        @Override
        public Kind kind() {
            return Kind.CONDITION;
        }

        @Override
        public void targets(Consumer<String> sink) {
            sink.accept(then);
            sink.accept(otherwise);
        }
    }

    /** JSON type {@code action}: runs actions, then moves on. An aborted list ends the dialogue as interrupted. */
    public record Act(List<Action> actions, String next, OnFail onFail) implements Node {
        public static final MapCodec<Act> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                LogicCodecs.ACTIONS.fieldOf("actions").forGetter(Act::actions),
                Codec.STRING.optionalFieldOf("next", END).forGetter(Act::next),
                OnFail.CODEC.optionalFieldOf("on_fail", OnFail.ABORT).forGetter(Act::onFail)
        ).apply(instance, Act::new));

        @Override
        public Kind kind() {
            return Kind.ACTION;
        }

        @Override
        public void targets(Consumer<String> sink) {
            sink.accept(next);
        }
    }

    public record RandomNode(List<Weighted> options) implements Node {
        public static final MapCodec<RandomNode> CODEC = Weighted.CODEC.listOf(1, Integer.MAX_VALUE).fieldOf("options")
                .xmap(RandomNode::new, RandomNode::options);

        @Override
        public Kind kind() {
            return Kind.RANDOM;
        }

        @Override
        public void targets(Consumer<String> sink) {
            options.forEach(option -> sink.accept(option.next()));
        }

        public record Weighted(int weight, String next) {
            public static final Codec<Weighted> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.intRange(1, 10_000).optionalFieldOf("weight", 1).forGetter(Weighted::weight),
                    Codec.STRING.fieldOf("next").forGetter(Weighted::next)
            ).apply(instance, Weighted::new));
        }
    }

    /** Continues at a node of this dialogue, or at another dialogue (its {@code start} unless {@code node} is given), without returning. */
    public record Jump(Optional<String> node, Optional<ResourceLocation> dialogue) implements Node {
        public static final MapCodec<Jump> CODEC = RecordCodecBuilder.<Jump>mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("node").forGetter(Jump::node),
                ResourceLocation.CODEC.optionalFieldOf("dialogue").forGetter(Jump::dialogue)
        ).apply(instance, Jump::new)).validate(jump -> jump.node().isPresent() || jump.dialogue().isPresent()
                ? DataResult.success(jump)
                : DataResult.error(() -> "A jump needs 'node' or 'dialogue'"));

        @Override
        public Kind kind() {
            return Kind.JUMP;
        }

        @Override
        public void targets(Consumer<String> sink) {
            if (dialogue.isEmpty()) {
                node.ifPresent(sink);
            }
        }
    }

    /** Runs another dialogue (greetings, trading); when it ends, continues at {@code next}. */
    public record Call(ResourceLocation dialogue, Optional<String> node, String next) implements Node {
        public static final MapCodec<Call> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("dialogue").forGetter(Call::dialogue),
                Codec.STRING.optionalFieldOf("node").forGetter(Call::node),
                Codec.STRING.optionalFieldOf("next", END).forGetter(Call::next)
        ).apply(instance, Call::new));

        @Override
        public Kind kind() {
            return Kind.CALL;
        }

        @Override
        public void targets(Consumer<String> sink) {
            sink.accept(next);
        }
    }

    /** Ends the dialogue (like {@code @end}) after running its actions. */
    public record End(List<Action> actions) implements Node {
        public static final MapCodec<End> CODEC = LogicCodecs.ACTIONS.optionalFieldOf("actions", List.of()).xmap(End::new, End::actions);

        @Override
        public Kind kind() {
            return Kind.END;
        }

        @Override
        public void targets(Consumer<String> sink) {}
    }
}
