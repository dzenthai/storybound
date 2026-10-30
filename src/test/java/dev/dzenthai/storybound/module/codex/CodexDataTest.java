package dev.dzenthai.storybound.module.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dzenthai.storybound.core.state.StoryMigrations;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class CodexDataTest {
    private static final ResourceLocation ZOMBIE = ResourceLocation.parse("demo:zombie");
    private static final ResourceLocation UNDEAD = ResourceLocation.parse("demo:undead");

    @Test
    void openSectionsFollowTheirRenamesUnlessTheEntryHasTheOldNameAgain() {
        CodexData data = new CodexData();
        data.open(ZOMBIE, "base");
        data.open(ZOMBIE, "habits");
        data.open(ZOMBIE, "notes");
        StoryMigrations.Renames renames = new StoryMigrations.Renames(Map.of(StoryMigrations.Kind.CODEX, Map.of(ZOMBIE, UNDEAD)), Map.of(), Map.of(),
                Map.of(UNDEAD, Map.of("habits", "povadki", "notes", "diary")));
        // The entry has "notes" again (a new section took the old name): it keeps its players.
        int moved = data.migrate(renames, (entry, section) -> entry.equals(UNDEAD) && Set.of("base", "povadki", "notes").contains(section));
        assertEquals(1, moved, "one entry moved, its sections with it");
        assertEquals(Set.of(), data.sections(ZOMBIE));
        assertEquals(Set.of("base", "povadki", "notes"), data.sections(UNDEAD));
        assertEquals(0, data.migrate(renames, (entry, section) -> Set.of("base", "povadki", "notes").contains(section)), "applying again changes nothing");
    }
}
