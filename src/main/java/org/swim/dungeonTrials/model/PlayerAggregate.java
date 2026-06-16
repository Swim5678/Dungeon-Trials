package org.swim.dungeonTrials.model;

import java.util.List;

public record PlayerAggregate(
        String playerUuid,
        int totalSessions,
        int highestLevelEver,
        long totalKills,
        long totalDurationSeconds,
        int reachedL5Count,
        List<StructureStat> perStructure
) {

    public double l5Rate() {
        if (totalSessions <= 0) return 0.0;
        return (double) reachedL5Count / totalSessions;
    }

    public record StructureStat(
            String structureId,
            int sessions,
            int highestLevel,
            long totalKills,
            long totalDurationSeconds
    ) {
    }

    public record RawTopEntry(
            String playerUuid,
            int highestLevel,
            long totalKills,
            int sessions
    ) {
    }
}
