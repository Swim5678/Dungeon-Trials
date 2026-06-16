package org.swim.dungeonTrials.model;

import org.bukkit.entity.EntityType;

public record MobSpawnRule(
        EntityType entityType,
        SpawnProfile profile
) {
}
