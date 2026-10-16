package dev.dzenthai.storybound.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dzenthai.storybound.client.camera.CameraShots.Framing;
import dev.dzenthai.storybound.client.camera.CameraShots.Obstacles;
import dev.dzenthai.storybound.client.camera.CameraShots.Pose;
import dev.dzenthai.storybound.client.camera.CameraShots.ScreenPoint;
import dev.dzenthai.storybound.module.dialogue.CameraCue.Shot;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CameraShotsTest {
    private static final double EYES = 1.62;
    private static final double ASPECT = 16.0 / 9.0;
    private static final double PLAYER_FOV = 70;
    /** The near plane is 0.05 blocks in front of the camera; nothing solid may be that close. */
    private static final double NEAR = 0.05;

    /**
     * The RPG "dirty single", as in The Witcher: the camera beside the listener, who is only a sliver at the frame
     * edge; the speaker on the third away from them, eyes high, in a three-quarter view.
     */
    @Test
    void npcLineIsADirtySingleBesideThePlayer() {
        Vec3 player = eyes(0.5, 64, 0.5);
        Vec3 npc = eyes(3.5, 64, 0.5);
        Framing framing = CameraShots.frame(Shot.AUTO, npc, player, player, npc, 1, ASPECT, Block.NONE);
        assertNotNull(framing);
        Pose pose = framing.pose();
        Vec3 pos = pose.pos();
        // Facing east (+x), the right hand is south (+z).
        assertTrue(pos.z - player.z >= 0.45, "well to the player's right, not behind their head: " + pos);
        assertTrue(pos.x < player.x, "on the player's side of the scene: " + pos);
        assertEquals(1, framing.side());
        assertTrue(pose.fov() >= 25 && pose.fov() < PLAYER_FOV, "a longer lens than the player's: " + pose.fov());

        ScreenPoint face = pose.project(npc, ASPECT, PLAYER_FOV);
        assertNotNull(face);
        assertEquals(0.3, face.x(), 0.03, "the NPC on the right third, away from the player: " + face);
        assertEquals(0.33, face.y(), 0.03, "eyes high: " + face);
        // The near (right) shoulder of a player facing east is on their south side.
        ScreenPoint shoulder = pose.project(player.add(0, -0.3, 0.3), ASPECT, PLAYER_FOV);
        assertNotNull(shoulder, "the player's shoulder is in front of the camera: " + pose);
        assertTrue(shoulder.x() <= -0.75 && shoulder.x() >= -1.1, "the shoulder at the left edge: " + shoulder);
        assertTrue(shoulder.y() < 0.1, "at or below the middle: " + shoulder);
        ScreenPoint head = pose.project(player.add(0, 0.1, 0), ASPECT, PLAYER_FOV);
        assertNotNull(head);
        double radius = 0.3 / (head.depth() * Math.tan(Math.toRadians(pose.fov()) / 2));
        assertTrue(head.x() + radius / ASPECT <= -0.6 || head.y() - radius > 1,
                "the head stays out of the frame or at its rim: " + head + " radius " + radius);    }

    @Test
    void reverseShotMatchesAndKeepsTheSideOfTheAxis() {
        Vec3 player = eyes(0.5, 64, 0.5);
        Vec3 npc = eyes(3.5, 64, 0.5);
        Framing npcSpeaks = CameraShots.frame(Shot.AUTO, npc, player, player, npc, 1, ASPECT, Block.NONE);
        Framing playerSpeaks = CameraShots.frame(Shot.AUTO, player, npc, player, npc, 1, ASPECT, Block.NONE);
        assertNotNull(npcSpeaks);
        assertNotNull(playerSpeaks);
        assertTrue(playerSpeaks.pose().pos().x > npc.x, "behind the NPC: " + playerSpeaks.pose().pos());
        assertEquals(sideOfAxis(player, npc, npcSpeaks.pose().pos()), sideOfAxis(player, npc, playerSpeaks.pose().pos()), "the 180° rule");
        assertEquals(npcSpeaks.pose().fov(), playerSpeaks.pose().fov(), 3, "both faces the same size");
        ScreenPoint face = playerSpeaks.pose().project(player, ASPECT, PLAYER_FOV);
        ScreenPoint listener = playerSpeaks.pose().project(npc.add(0, 0.1, 0), ASPECT, PLAYER_FOV);
        assertNotNull(face);
        assertNotNull(listener);
        assertTrue(face.x() * listener.x() < 0, "speaker and listener on opposite sides: " + face + " " + listener);
        // Seen from the other side of the axis, the listener moves to the other edge: the shots face each other.
        ScreenPoint first = npcSpeaks.pose().project(player.add(0, 0.1, 0), ASPECT, PLAYER_FOV);
        assertNotNull(first);
        assertTrue(first.x() * listener.x() < 0, "the listeners sit on opposite edges in shot and reverse shot");
    }

    @Test
    void lensKeepsTheShotSizeWhateverTheDistance() {
        assertTrue(CameraShots.lensFor(2, 1.7) > CameraShots.lensFor(4, 1.7), "farther speakers get a longer lens");
        assertEquals(25f, CameraShots.lensFor(50, 1.7), "never narrower than 25°");
        assertEquals(70f, CameraShots.lensFor(0.5, 1.7), "never wider than 70°");
    }

    @Test
    void firstPersonShotMovesNothing() {
        Vec3 player = eyes(0.5, 64, 0.5);
        Vec3 npc = eyes(3.5, 64, 0.5);
        assertNull(CameraShots.frame(Shot.FIRST_PERSON, npc, player, player, npc, 1, ASPECT, Block.NONE));
    }

    /** The stage's "done" criterion: a dialogue in a cramped room never pushes the camera into a wall. */
    @Test
    void crampedRoomKeepsEveryShotOutOfTheWalls() {
        // Inside: x and z 0..4, a ceiling 3 blocks high; everything else is stone.
        Block room = (x, y, z) -> !(x >= 0 && x < 5 && z >= 0 && z < 5 && y >= 64 && y < 67);
        Vec3 player = eyes(1.5, 64, 2.5);
        Vec3 npc = eyes(3.5, 64, 2.5);
        for (Shot shot : new Shot[]{Shot.AUTO, Shot.TWO_SHOT, Shot.CLOSE_UP}) {
            for (int side : new int[]{1, -1}) {
                Framing npcSpeaks = CameraShots.frame(shot, npc, player, player, npc, side, ASPECT, room);
                Framing playerSpeaks = CameraShots.frame(shot, player, npc, player, npc, side, ASPECT, room);
                assertNotNull(npcSpeaks, shot + " fits this room");
                assertNotNull(playerSpeaks, shot + " fits this room");
                assertClear(npcSpeaks.pose(), room);
                assertClear(playerSpeaks.pose(), room);
                assertSees(npcSpeaks.pose().pos(), shot == Shot.TWO_SHOT ? player : npc, room);
                assertSees(playerSpeaks.pose().pos(), player, room);
            }
        }
    }

    @Test
    void playerAgainstAWallFallsBackToAnotherShot() {
        // The player's back touches the west wall, so there is no room behind their shoulder.
        Block room = (x, y, z) -> !(x >= 0 && x < 6 && z >= -3 && z < 4 && y >= 64 && y < 67);
        Vec3 player = eyes(0.3, 64, 0.5);
        Vec3 npc = eyes(2.5, 64, 0.5);
        Framing framing = CameraShots.frame(Shot.AUTO, npc, player, player, npc, 1, ASPECT, room);
        assertNotNull(framing);
        assertClear(framing.pose(), room);
        assertSees(framing.pose().pos(), npc, room);
        // No room behind: a clean single from in front of the player's face (past the nose), not inside the head.
        assertTrue(framing.pose().pos().x >= player.x + 0.45, "in front of the player's face: " + framing.pose());
        assertTrue(framing.pose().fov() < PLAYER_FOV, "with the dialogue lens: " + framing.pose());
    }

    @Test
    void oneBlockCorridorStaysOutOfTheSideWalls() {
        Block corridor = (x, y, z) -> !(z == 0 && y >= 64 && y < 66);
        Vec3 player = eyes(0.5, 64, 0.5);
        Vec3 npc = eyes(3.5, 64, 0.5);
        Framing framing = CameraShots.frame(Shot.AUTO, npc, player, player, npc, 1, ASPECT, corridor);
        assertNotNull(framing, "a clean single still fits a corridor");
        assertClear(framing.pose(), corridor);
        assertSees(framing.pose().pos(), npc, corridor);
        ScreenPoint face = framing.pose().project(npc, ASPECT, PLAYER_FOV);
        ScreenPoint head = framing.pose().project(player, ASPECT, PLAYER_FOV);
        assertNotNull(face);
        assertTrue(head == null || Math.hypot((face.x() - head.x()) * ASPECT, face.y() - head.y()) > 0.3,
                "the player's head does not hide the NPC's face: " + framing.pose());
    }

    @Test
    void nothingFitsThroughAWall() {
        // Each stands in a 1x1 closet; a wall between them hides every face, so first person.
        Block closets = (x, y, z) -> !(z == 0 && y >= 64 && y < 66 && (x == 0 || x == 2));
        Vec3 player = eyes(0.5, 64, 0.5);
        Vec3 npc = eyes(2.5, 64, 0.5);
        assertNull(CameraShots.frame(Shot.AUTO, npc, player, player, npc, 1, ASPECT, closets));
    }

    @Test
    void reachStopsShortOfAWall() {
        Block wall = (x, y, z) -> x >= 3;
        Vec3 pos = CameraShots.reach(new Vec3(0.5, 64.5, 0.5), new Vec3(5.5, 64.5, 0.5), wall);
        assertTrue(pos.x < 3 - NEAR, "kept off the wall: " + pos);
        assertTrue(pos.x > 2.5, "but not much more than the margin: " + pos);
    }

    private static Vec3 eyes(double x, double feetY, double z) {
        return new Vec3(x, feetY + EYES, z);
    }

    /** No solid block within the near plane's reach around the camera. */
    private static void assertClear(Pose pose, Block block) {
        Vec3 pos = pose.pos();
        for (double dx : new double[]{-NEAR, NEAR}) {
            for (double dy : new double[]{-NEAR, NEAR}) {
                for (double dz : new double[]{-NEAR, NEAR}) {
                    assertFalse(block.solidAt(pos.add(dx, dy, dz)), "camera at " + pos + " is in or against a wall");
                }
            }
        }
    }

    private static void assertSees(Vec3 from, Vec3 face, Block block) {
        assertTrue(block.clip(from, face).distanceTo(face) < 0.1, "a wall between " + from + " and " + face);
    }

    /** Which side of the line from the player to the NPC a point is on. */
    private static int sideOfAxis(Vec3 player, Vec3 npc, Vec3 point) {
        double cross = (npc.x - player.x) * (point.z - player.z) - (npc.z - player.z) * (point.x - player.x);
        return (int) Math.signum(cross);
    }

    /** A voxel world: which whole blocks are solid. */
    @FunctionalInterface
    private interface Block extends Obstacles {
        Block NONE = (x, y, z) -> false;

        boolean solid(int x, int y, int z);

        default boolean solidAt(Vec3 pos) {
            return solid((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
        }

        @Override
        default Vec3 clip(Vec3 from, Vec3 to) {
            Vec3 line = to.subtract(from);
            double length = line.length();
            int steps = (int) Math.ceil(length / 0.005);
            for (int i = 0; i <= steps; i++) {
                Vec3 point = from.add(line.scale(Math.min(1, i / (double) steps)));
                if (solidAt(point)) {
                    return from.add(line.scale(Math.max(0, (i - 1) / (double) steps)));
                }
            }
            return to;
        }
    }
}
