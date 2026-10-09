package dev.dzenthai.storybound.core.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import org.jetbrains.annotations.Nullable;

/** A group of players sharing a {@code party} scope. The leader is always a member. */
public final class Party {
    static final Codec<Party> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Party::id),
            UUIDUtil.CODEC.fieldOf("leader").forGetter(Party::leader),
            UUIDUtil.CODEC.listOf().fieldOf("members").forGetter(party -> List.copyOf(party.members)),
            Codec.STRING.optionalFieldOf("name").forGetter(Party::name)
    ).apply(instance, (id, leader, members, name) -> new Party(id, leader, members, name.orElse(null))));

    private final UUID id;
    private UUID leader;
    private final Set<UUID> members;
    private final @Nullable String name;

    Party(UUID id, UUID leader, Collection<UUID> members, @Nullable String name) {
        this.id = id;
        this.leader = leader;
        this.members = new LinkedHashSet<>(members);
        this.members.add(leader);
        this.name = name;
    }

    public UUID id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    public boolean isLeader(UUID player) {
        return leader.equals(player);
    }

    /** Join order: the longest-standing member becomes the next leader. */
    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    void addMember(UUID player) {
        members.add(player);
    }

    void removeMember(UUID player) {
        members.remove(player);
        if (leader.equals(player) && !members.isEmpty()) {
            leader = members.iterator().next();
        }
    }
}
