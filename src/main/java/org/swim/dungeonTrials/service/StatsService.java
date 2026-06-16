package org.swim.dungeonTrials.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.swim.dungeonTrials.database.DatabaseManager;
import org.swim.dungeonTrials.model.PlayerAggregate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class StatsService {

    private final DatabaseManager databaseManager;
    private final Cache<String, CompletableFuture<List<PlayerAggregate.RawTopEntry>>> topCache;

    public StatsService(DatabaseManager databaseManager, long cacheSeconds) {
        this.databaseManager = databaseManager;
        long cacheSeconds1 = Math.max(1, cacheSeconds);
        this.topCache = Caffeine.newBuilder()
                .maximumSize(64)
                .expireAfterWrite(cacheSeconds1, TimeUnit.SECONDS)
                .build();
    }

    public CompletableFuture<PlayerAggregate> getPersonalAggregateAsync(String playerUuid) {
        CompletableFuture<DatabaseManager.GlobalStatsRow> globalFuture =
                databaseManager.getGlobalStatsAsync(playerUuid);
        CompletableFuture<List<PlayerAggregate.StructureStat>> perStructFuture =
                databaseManager.getPersonalBestAsync(playerUuid);
        return globalFuture.thenCombine(perStructFuture, (g, ps) -> new PlayerAggregate(
                playerUuid,
                g.totalSessions(),
                g.highestLevelEver(),
                g.totalKills(),
                g.totalDurationSeconds(),
                g.reachedL5Count(),
                ps
        ));
    }

    public CompletableFuture<List<PlayerAggregate.RawTopEntry>> getRawTopAsync(String structureId, int limit) {
        String key = ((structureId == null || structureId.isBlank()) ? "*" : structureId) + ":" + limit;
        return topCache.get(key, k -> databaseManager.getTopPlayersAsync(structureId, limit));
    }

    public void invalidateCache() {
        topCache.invalidateAll();
    }

}
