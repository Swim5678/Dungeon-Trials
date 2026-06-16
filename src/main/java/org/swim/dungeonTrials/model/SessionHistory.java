package org.swim.dungeonTrials.model;

public record SessionHistory(String playerUuid, String structureId, int highestLevel, int totalKills,
                             long durationSeconds, long completedAt, String reason) {

}
