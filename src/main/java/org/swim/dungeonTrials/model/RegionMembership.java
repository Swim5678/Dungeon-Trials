package org.swim.dungeonTrials.model;

import java.util.UUID;

public record RegionMembership(
        String playerUuid,
        String regionId,
        long joinedAt
) {
    public UUID playerUuidAsUuid() {
        return UUID.fromString(playerUuid);
    }
}
