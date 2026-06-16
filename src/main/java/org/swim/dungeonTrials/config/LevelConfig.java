package org.swim.dungeonTrials.config;

import org.swim.dungeonTrials.model.MobSpawnRule;

import java.util.List;

public record LevelConfig(
        int level,
        int clearRequirement,
        List<MobSpawnRule> mobs
) {
}
