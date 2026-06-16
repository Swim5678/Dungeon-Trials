package org.swim.dungeonTrials.model;

/**
 * @deprecated retained for read compatibility; new code should use {@link RegionState}.
 */
@Deprecated
public class ActiveSession {

    private final String playerUuid;
    private final String arenaId;
    private final String worldName;
    private final String structureId;
    private final long startTime;
    private final double originX;
    private final double originY;
    private final double originZ;
    private int currentLevel;
    private int kills;
    private boolean infinite;
    private boolean awaitingInfiniteChoice;

    public ActiveSession(String playerUuid, String arenaId, String worldName, String structureId,
                         int currentLevel, int kills, long startTime,
                         double originX, double originY, double originZ) {
        this(playerUuid, arenaId, worldName, structureId, currentLevel, kills, startTime,
                originX, originY, originZ, false, false);
    }

    public ActiveSession(String playerUuid, String arenaId, String worldName, String structureId,
                         int currentLevel, int kills, long startTime,
                         double originX, double originY, double originZ,
                         boolean infinite, boolean awaitingInfiniteChoice) {
        this.playerUuid = playerUuid;
        this.arenaId = arenaId;
        this.worldName = worldName;
        this.structureId = structureId;
        this.currentLevel = currentLevel;
        this.kills = kills;
        this.startTime = startTime;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.infinite = infinite;
        this.awaitingInfiniteChoice = awaitingInfiniteChoice;
    }

    public String playerUuid() {
        return playerUuid;
    }

    public String arenaId() {
        return arenaId;
    }

    public String worldName() {
        return worldName;
    }

    public String structureId() {
        return structureId;
    }

    public int currentLevel() {
        return currentLevel;
    }

    public void currentLevel(int currentLevel) {
        this.currentLevel = currentLevel;
    }

    public int kills() {
        return kills;
    }

    public void kills(int kills) {
        this.kills = kills;
    }

    public long startTime() {
        return startTime;
    }

    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    public double originZ() {
        return originZ;
    }

    public boolean infinite() {
        return infinite;
    }

    public void infinite(boolean infinite) {
        this.infinite = infinite;
    }

    public boolean awaitingInfiniteChoice() {
        return awaitingInfiniteChoice;
    }

    public void awaitingInfiniteChoice(boolean awaitingInfiniteChoice) {
        this.awaitingInfiniteChoice = awaitingInfiniteChoice;
    }
}
