package org.swim.dungeonTrials.database;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.swim.dungeonTrials.DungeonTrials;
import org.swim.dungeonTrials.model.ActiveSession;
import org.swim.dungeonTrials.model.RegionState;
import org.swim.dungeonTrials.model.SessionHistory;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseManager {

    private static final int TARGET_SCHEMA_VERSION = 3;
    private static final String DEFAULT_STRUCTURE_ID = "trial_chambers";

    private final DungeonTrials plugin;
    private final File dataFolder;
    private final Cache<String, CompletableFuture<List<SessionHistory>>> historyCache;
    private final ExecutorService dbExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Connection connection;

    public DatabaseManager(DungeonTrials plugin, File dataFolder) {
        this.plugin = plugin;
        this.dataFolder = dataFolder;
        this.historyCache = Caffeine.newBuilder()
                .maximumSize(50)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DungeonTrials-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" +
                    new File(dataFolder, "sessions.db").getAbsolutePath());

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA busy_timeout=3000");
            }

            runMigrations();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize database", e);
        }
    }

    private void runMigrations() throws SQLException {
        int current = readUserVersion();
        if (current >= TARGET_SCHEMA_VERSION) {
            createV3Tables();
            return;
        }

        if (current <= 0) {
            migrateV0ToV3();
        } else if (current == 1) {
            migrateV1ToV3();
        } else {
            migrateV2ToV3();
        }

        setUserVersion();
    }

    private int readUserVersion() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log("readUserVersion", e);
        }
        return 0;
    }

    private void setUserVersion() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA user_version = " + DatabaseManager.TARGET_SCHEMA_VERSION);
        }
    }

    private boolean tableExists(String name) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + name + "'")) {
            return rs.next();
        }
    }

    private void migrateV0ToV3() throws SQLException {
        if (tableExists("worlds")) {
            List<LegacyWorldRecord> legacy = readLegacyWorlds();
            dropTable("worlds");
            createV3Tables();
            insertLegacyArenas(legacy);
        } else {
            createV3Tables();
        }

        if (tableExists("active_sessions")) {
            // 加上欄位檢查：只有真正的 V0 結構才有 world_uuid
            if (columnExists("active_sessions", "world_uuid")) {
                migrateV0ActiveSessions();
            }
        }

        if (tableExists("session_history")) {
            if (!columnExists("session_history", "structure_id")) {
                migrateV0SessionHistory();
            }
        }
    }

    private void migrateV1ToV3() throws SQLException {
        createV3Tables();
        if (!columnExists("active_sessions", "arena_id")) {
            migrateV0ActiveSessions();
        }
        if (!columnExists("session_history", "structure_id")) {
            migrateV0SessionHistory();
        }
    }

    private void migrateV2ToV3() throws SQLException {
        if (tableExists("active_sessions") && !columnExists("active_sessions", "infinite")) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE active_sessions ADD COLUMN infinite INTEGER NOT NULL DEFAULT 0");
            }
        }
        if (tableExists("active_sessions") && !columnExists("active_sessions", "awaiting_infinite_choice")) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE active_sessions ADD COLUMN awaiting_infinite_choice INTEGER NOT NULL DEFAULT 0");
            }
        }
    }

    private List<LegacyWorldRecord> readLegacyWorlds() throws SQLException {
        List<LegacyWorldRecord> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT world_uuid, player_uuid, status, created_at, last_used FROM worlds")) {
            while (rs.next()) {
                result.add(new LegacyWorldRecord(
                        rs.getString("world_uuid"),
                        rs.getString("player_uuid"),
                        rs.getString("status"),
                        rs.getLong("created_at"),
                        rs.getLong("last_used")
                ));
            }
        }
        return result;
    }

    private void insertLegacyArenas(List<LegacyWorldRecord> legacy) throws SQLException {
        if (legacy.isEmpty()) return;
        String sql = """
                    INSERT OR REPLACE INTO arenas
                    (arena_id, strategy_id, world_name, owner_uuid, structure_id, created_at, last_used, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (LegacyWorldRecord rec : legacy) {
                ps.setString(1, rec.worldUuid());
                ps.setString(2, "legacy");
                ps.setString(3, rec.worldUuid());
                ps.setString(4, rec.playerUuid());
                ps.setString(5, DEFAULT_STRUCTURE_ID);
                ps.setLong(6, rec.createdAt());
                ps.setLong(7, rec.lastUsed());
                ps.setString(8, rec.status() != null ? rec.status() : "ACTIVE");
                ps.executeUpdate();
            }
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private void migrateV0ActiveSessions() throws SQLException {
        List<LegacySessionRecord> sessions = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT player_uuid, world_uuid, current_level, kills, start_time, origin_x, origin_y, origin_z FROM active_sessions")) {
            while (rs.next()) {
                sessions.add(new LegacySessionRecord(
                        rs.getString("player_uuid"),
                        rs.getString("world_uuid"),
                        rs.getInt("current_level"),
                        rs.getInt("kills"),
                        rs.getLong("start_time"),
                        rs.getDouble("origin_x"),
                        rs.getDouble("origin_y"),
                        rs.getDouble("origin_z")
                ));
            }
        }

        List<String> validArenas = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT arena_id FROM arenas")) {
            while (rs.next()) {
                validArenas.add(rs.getString(1));
            }
        }

        List<LegacySessionRecord> kept = new ArrayList<>();
        for (LegacySessionRecord s : sessions) {
            if (validArenas.contains(s.worldUuid())) {
                kept.add(s);
            } else {
                plugin.getLogger().warning("Dropping orphan active_session for player " + s.playerUuid()
                        + " (no matching arena " + s.worldUuid() + ")");
            }
        }

        dropTable("active_sessions");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                        CREATE TABLE active_sessions (
                            player_uuid TEXT PRIMARY KEY,
                            arena_id TEXT NOT NULL,
                            world_name TEXT NOT NULL,
                            current_level INTEGER NOT NULL DEFAULT 1,
                            kills INTEGER NOT NULL DEFAULT 0,
                            start_time INTEGER NOT NULL,
                            origin_x REAL NOT NULL,
                            origin_y REAL NOT NULL,
                            origin_z REAL NOT NULL,
                            infinite INTEGER NOT NULL DEFAULT 0,
                            awaiting_infinite_choice INTEGER NOT NULL DEFAULT 0
                        )
                    """);
        }

        if (!kept.isEmpty()) {
            String sql = """
                        INSERT INTO active_sessions
                        (player_uuid, arena_id, world_name, current_level, kills, start_time,
                         origin_x, origin_y, origin_z, infinite, awaiting_infinite_choice)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (LegacySessionRecord s : kept) {
                    ps.setString(1, s.playerUuid());
                    ps.setString(2, s.worldUuid());
                    ps.setString(3, s.worldUuid());
                    ps.setInt(4, s.currentLevel());
                    ps.setInt(5, s.kills());
                    ps.setLong(6, s.startTime());
                    ps.setDouble(7, s.originX());
                    ps.setDouble(8, s.originY());
                    ps.setDouble(9, s.originZ());
                    ps.executeUpdate();
                }
            }
        }
    }

    private void migrateV0SessionHistory() throws SQLException {
        if (columnExists("session_history", "structure_id")) return;

        List<LegacyHistoryRecord> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, player_uuid, highest_level, total_kills, duration_seconds, completed_at, reason FROM session_history")) {
            while (rs.next()) {
                rows.add(new LegacyHistoryRecord(
                        rs.getInt("id"),
                        rs.getString("player_uuid"),
                        rs.getInt("highest_level"),
                        rs.getInt("total_kills"),
                        rs.getLong("duration_seconds"),
                        rs.getLong("completed_at"),
                        rs.getString("reason")
                ));
            }
        }

        dropTable("session_history");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                        CREATE TABLE session_history (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            player_uuid TEXT NOT NULL,
                            structure_id TEXT,
                            highest_level INTEGER NOT NULL,
                            total_kills INTEGER NOT NULL,
                            duration_seconds INTEGER NOT NULL,
                            completed_at INTEGER NOT NULL,
                            reason TEXT NOT NULL
                        )
                    """);
        }

        if (!rows.isEmpty()) {
            String sql = """
                        INSERT INTO session_history
                        (id, player_uuid, structure_id, highest_level, total_kills, duration_seconds, completed_at, reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (LegacyHistoryRecord r : rows) {
                    ps.setInt(1, r.id());
                    ps.setString(2, r.playerUuid());
                    ps.setString(3, DEFAULT_STRUCTURE_ID);
                    ps.setInt(4, r.highestLevel());
                    ps.setInt(5, r.totalKills());
                    ps.setLong(6, r.durationSeconds());
                    ps.setLong(7, r.completedAt());
                    ps.setString(8, r.reason());
                    ps.executeUpdate();
                }
            }
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private void dropTable(String name) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS " + name);
        }
    }

    private void createV3Tables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS arenas (
                            arena_id TEXT PRIMARY KEY,
                            strategy_id TEXT NOT NULL,
                            world_name TEXT NOT NULL,
                            owner_uuid TEXT NOT NULL,
                            structure_id TEXT,
                            created_at INTEGER NOT NULL,
                            last_used INTEGER NOT NULL,
                            status TEXT NOT NULL DEFAULT 'ACTIVE'
                        )
                    """);
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS active_sessions (
                            player_uuid TEXT PRIMARY KEY,
                            arena_id TEXT NOT NULL,
                            world_name TEXT NOT NULL,
                            current_level INTEGER NOT NULL DEFAULT 1,
                            kills INTEGER NOT NULL DEFAULT 0,
                            start_time INTEGER NOT NULL,
                            origin_x REAL NOT NULL,
                            origin_y REAL NOT NULL,
                            origin_z REAL NOT NULL,
                            infinite INTEGER NOT NULL DEFAULT 0,
                            awaiting_infinite_choice INTEGER NOT NULL DEFAULT 0
                        )
                    """);
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS session_history (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            player_uuid TEXT NOT NULL,
                            structure_id TEXT,
                            highest_level INTEGER NOT NULL,
                            total_kills INTEGER NOT NULL,
                            duration_seconds INTEGER NOT NULL,
                            completed_at INTEGER NOT NULL,
                            reason TEXT NOT NULL
                        )
                    """);
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS region_states (
                            region_id TEXT PRIMARY KEY,
                            world_name TEXT NOT NULL,
                            structure_id TEXT NOT NULL,
                            owner_uuid TEXT NOT NULL,
                            current_level INTEGER NOT NULL DEFAULT 1,
                            kills INTEGER NOT NULL DEFAULT 0,
                            start_time INTEGER NOT NULL,
                            infinite INTEGER NOT NULL DEFAULT 0,
                            awaiting_infinite_choice INTEGER NOT NULL DEFAULT 0
                        )
                    """);
            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS region_members (
                            region_id TEXT NOT NULL,
                            player_uuid TEXT NOT NULL,
                            joined_at INTEGER NOT NULL,
                            PRIMARY KEY (region_id, player_uuid)
                        )
                    """);
            stmt.execute("""
                        CREATE INDEX IF NOT EXISTS idx_region_members_player
                            ON region_members(player_uuid)
                    """);
        }
    }

    @SuppressWarnings("DuplicatedCode")
    public void saveActiveSession(ActiveSession session) {
        if (closed.get()) return;
        submit(() -> {
            if (closed.get() || connection == null) return;
            String sql = """
                        INSERT OR REPLACE INTO active_sessions
                        (player_uuid, arena_id, world_name, current_level, kills, start_time,
                         origin_x, origin_y, origin_z, infinite, awaiting_infinite_choice)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, session.playerUuid());
                ps.setString(2, session.arenaId());
                ps.setString(3, session.worldName());
                ps.setInt(4, session.currentLevel());
                ps.setInt(5, session.kills());
                ps.setLong(6, session.startTime());
                ps.setDouble(7, session.originX());
                ps.setDouble(8, session.originY());
                ps.setDouble(9, session.originZ());
                ps.setInt(10, session.infinite() ? 1 : 0);
                ps.setInt(11, session.awaitingInfiniteChoice() ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                log("saveActiveSession", e);
            }
        });
    }

    public void deleteActiveSession(String playerUuid) {
        if (closed.get()) return;
        submit(() -> {
            if (closed.get() || connection == null) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM active_sessions WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                log("deleteActiveSession", e);
            }
        });
    }

    public void saveSessionHistory(SessionHistory history) {
        if (closed.get()) return;
        submit(() -> {
            if (closed.get() || connection == null) return;
            String sql = """
                        INSERT INTO session_history
                        (player_uuid, structure_id, highest_level, total_kills, duration_seconds, completed_at, reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, history.playerUuid());
                ps.setString(2, history.structureId());
                ps.setInt(3, history.highestLevel());
                ps.setInt(4, history.totalKills());
                ps.setLong(5, history.durationSeconds());
                ps.setLong(6, history.completedAt());
                ps.setString(7, history.reason());
                ps.executeUpdate();
            } catch (SQLException e) {
                log("saveSessionHistory", e);
            }
        });
        historyCache.invalidate(history.playerUuid());
    }

    public CompletableFuture<GlobalStatsRow> getGlobalStatsAsync(String playerUuid) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
        }
        CompletableFuture<GlobalStatsRow> future = new CompletableFuture<>();
        submit(() -> {
            if (closed.get() || connection == null) {
                future.completeExceptionally(new IllegalStateException("Database closed"));
                return;
            }
            String sql = """
                        SELECT COUNT(*) AS total_sessions,
                               COALESCE(MAX(highest_level), 0) AS max_lvl,
                               COALESCE(SUM(total_kills), 0) AS total_kills,
                               COALESCE(SUM(duration_seconds), 0) AS total_sec,
                               COALESCE(SUM(CASE WHEN highest_level >= 5 THEN 1 ELSE 0 END), 0) AS reached_l5
                        FROM session_history
                        WHERE player_uuid = ?
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        future.complete(new GlobalStatsRow(
                                rs.getInt("total_sessions"),
                                rs.getInt("max_lvl"),
                                rs.getLong("total_kills"),
                                rs.getLong("total_sec"),
                                rs.getInt("reached_l5")
                        ));
                    } else {
                        future.complete(new GlobalStatsRow(0, 0, 0L, 0L, 0));
                    }
                }
            } catch (SQLException e) {
                log("getGlobalStatsAsync", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<List<org.swim.dungeonTrials.model.PlayerAggregate.StructureStat>> getPersonalBestAsync(String playerUuid) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
        }
        CompletableFuture<List<org.swim.dungeonTrials.model.PlayerAggregate.StructureStat>> future = new CompletableFuture<>();
        submit(() -> {
            if (closed.get() || connection == null) {
                future.completeExceptionally(new IllegalStateException("Database closed"));
                return;
            }
            String sql = """
                        SELECT structure_id,
                               COUNT(*) AS sessions,
                               COALESCE(MAX(highest_level), 0) AS max_lvl,
                               COALESCE(SUM(total_kills), 0) AS total_kills,
                               COALESCE(SUM(duration_seconds), 0) AS total_sec
                        FROM session_history
                        WHERE player_uuid = ? AND structure_id IS NOT NULL
                        GROUP BY structure_id
                        ORDER BY sessions DESC, max_lvl DESC
                    """;
            List<org.swim.dungeonTrials.model.PlayerAggregate.StructureStat> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, playerUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new org.swim.dungeonTrials.model.PlayerAggregate.StructureStat(
                                rs.getString("structure_id"),
                                rs.getInt("sessions"),
                                rs.getInt("max_lvl"),
                                rs.getLong("total_kills"),
                                rs.getLong("total_sec")
                        ));
                    }
                }
                future.complete(out);
            } catch (SQLException e) {
                log("getPersonalBestAsync", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<List<org.swim.dungeonTrials.model.PlayerAggregate.RawTopEntry>> getTopPlayersAsync(String structureId, int limit) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
        }
        CompletableFuture<List<org.swim.dungeonTrials.model.PlayerAggregate.RawTopEntry>> future = new CompletableFuture<>();
        submit(() -> {
            if (closed.get() || connection == null) {
                future.completeExceptionally(new IllegalStateException("Database closed"));
                return;
            }
            String sql;
            if (structureId == null || structureId.isBlank()) {
                sql = """
                            SELECT player_uuid,
                                   COALESCE(MAX(highest_level), 0) AS max_lvl,
                                   COALESCE(SUM(total_kills), 0) AS total_kills,
                                   COUNT(*) AS sessions
                            FROM session_history
                            WHERE player_uuid IS NOT NULL
                            GROUP BY player_uuid
                            ORDER BY max_lvl DESC, total_kills DESC
                            LIMIT ?
                        """;
            } else {
                sql = """
                            SELECT player_uuid,
                                   COALESCE(MAX(highest_level), 0) AS max_lvl,
                                   COALESCE(SUM(total_kills), 0) AS total_kills,
                                   COUNT(*) AS sessions
                            FROM session_history
                            WHERE structure_id = ?
                            GROUP BY player_uuid
                            ORDER BY max_lvl DESC, total_kills DESC
                            LIMIT ?
                        """;
            }
            List<org.swim.dungeonTrials.model.PlayerAggregate.RawTopEntry> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int idx = 1;
                if (structureId != null && !structureId.isBlank()) {
                    ps.setString(idx++, structureId);
                }
                ps.setInt(idx, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new org.swim.dungeonTrials.model.PlayerAggregate.RawTopEntry(
                                rs.getString("player_uuid"),
                                rs.getInt("max_lvl"),
                                rs.getLong("total_kills"),
                                rs.getInt("sessions")
                        ));
                    }
                }
                future.complete(out);
            } catch (SQLException e) {
                log("getTopPlayersAsync", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void saveArena(String arenaId, String strategyId, String worldName,
                          String ownerUuid, String structureId) {
        if (closed.get()) return;
        submit(() -> {
            if (closed.get() || connection == null) return;
            String sql = """
                        INSERT OR REPLACE INTO arenas
                        (arena_id, strategy_id, world_name, owner_uuid, structure_id, created_at, last_used, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """;
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, arenaId);
                ps.setString(2, strategyId);
                ps.setString(3, worldName);
                ps.setString(4, ownerUuid);
                ps.setString(5, structureId);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                log("saveArena", e);
            }
        });
    }

    public void deleteArena(String arenaId) {
        if (closed.get()) return;
        submit(() -> {
            if (closed.get() || connection == null) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM arenas WHERE arena_id = ?")) {
                ps.setString(1, arenaId);
                ps.executeUpdate();
            } catch (SQLException e) {
                log("deleteArena", e);
            }
        });
    }

    public void saveRegionState(RegionState region) {
        if (closed.get()) return;
        submit(() -> {
            if (closed.get() || connection == null) return;
            String sql = """
                        INSERT OR REPLACE INTO region_states
                        (region_id, world_name, structure_id, owner_uuid, current_level, kills, start_time,
                         infinite, awaiting_infinite_choice)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, region.regionId());
                ps.setString(2, region.worldName());
                ps.setString(3, region.structureId());
                ps.setString(4, region.ownerUuid().toString());
                ps.setInt(5, region.currentLevel());
                ps.setInt(6, region.kills());
                ps.setLong(7, region.startTime());
                ps.setInt(8, region.infinite() ? 1 : 0);
                ps.setInt(9, region.awaitingInfiniteChoice() ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                log("saveRegionState", e);
            }
        });
    }

    public void deleteRegionState(String regionId) {
        if (closed.get()) return;
        submit(() -> {
            if (closed.get() || connection == null) return;
            try {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM region_members WHERE region_id = ?")) {
                    ps.setString(1, regionId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM region_states WHERE region_id = ?")) {
                    ps.setString(1, regionId);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                log("deleteRegionState", e);
            }
        });
    }

    public void addRegionMember(String regionId, String playerUuid, long joinedAt) {
        if (closed.get()) return;
        submit(() -> {
            if (closed.get() || connection == null) return;
            String sql = """
                        INSERT OR REPLACE INTO region_members
                        (region_id, player_uuid, joined_at)
                        VALUES (?, ?, ?)
                    """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, regionId);
                ps.setString(2, playerUuid);
                ps.setLong(3, joinedAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                log("addRegionMember", e);
            }
        });
    }

    public void removeRegionMember(String regionId, String playerUuid) {
        if (closed.get()) return;
        submit(() -> {
            if (closed.get() || connection == null) return;
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM region_members WHERE region_id = ? AND player_uuid = ?")) {
                ps.setString(1, regionId);
                ps.setString(2, playerUuid);
                ps.executeUpdate();
            } catch (SQLException e) {
                log("removeRegionMember", e);
            }
        });
    }

    public CompletableFuture<List<RegionState>> loadAllRegionsAsync() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
        }
        CompletableFuture<List<RegionState>> future = new CompletableFuture<>();
        submit(() -> {
            if (closed.get() || connection == null) {
                future.completeExceptionally(new IllegalStateException("Database closed"));
                return;
            }
            List<RegionState> regions = new ArrayList<>();
            Map<String, List<String>> membersByRegion = new HashMap<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT region_id, world_name, structure_id, owner_uuid, current_level, kills, "
                                 + "start_time, infinite, awaiting_infinite_choice FROM region_states")) {
                while (rs.next()) {
                    String regionId = rs.getString("region_id");
                    UUID ownerUuid;
                    try {
                        ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    RegionState region = new RegionState(
                            regionId,
                            rs.getString("world_name"),
                            rs.getString("structure_id"),
                            ownerUuid,
                            rs.getInt("current_level"),
                            rs.getLong("start_time")
                    );
                    region.kills(rs.getInt("kills"));
                    region.infinite(rs.getInt("infinite") != 0);
                    region.awaitingInfiniteChoice(rs.getInt("awaiting_infinite_choice") != 0);
                    regions.add(region);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
                return;
            }
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT region_id, player_uuid FROM region_members")) {
                while (rs.next()) {
                    String regionId = rs.getString("region_id");
                    String playerUuid = rs.getString("player_uuid");
                    membersByRegion.computeIfAbsent(regionId, k -> new ArrayList<>()).add(playerUuid);
                }
            } catch (SQLException e) {
                future.completeExceptionally(e);
                return;
            }
            for (RegionState region : regions) {
                List<String> members = membersByRegion.get(region.regionId());
                if (members != null) {
                    for (String m : members) {
                        try {
                            region.addMember(UUID.fromString(m));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                backfillOwnerMember(region);
            }
            future.complete(regions);
        });
        return future;
    }

    private void backfillOwnerMember(RegionState region) {
        if (region.members().contains(region.ownerUuid())) return;
        region.addMember(region.ownerUuid());
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO region_members (region_id, player_uuid, joined_at) VALUES (?, ?, ?)")) {
            ps.setString(1, region.regionId());
            ps.setString(2, region.ownerUuid().toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            log("backfillOwnerMember", e);
        }
    }

    public CompletableFuture<List<String>> getOrphanArenasAsync() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
        }
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        submit(() -> {
            if (closed.get() || connection == null) {
                future.completeExceptionally(new IllegalStateException("Database closed"));
                return;
            }
            List<String> arenas = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT a.arena_id FROM arenas a LEFT JOIN active_sessions s ON a.arena_id = s.arena_id WHERE s.player_uuid IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arenas.add(rs.getString(1));
                }
                future.complete(arenas);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<List<ArenaDetails>> getAllArenaDetailsAsync() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
        }
        CompletableFuture<List<ArenaDetails>> future = new CompletableFuture<>();
        submit(() -> {
            if (closed.get() || connection == null) {
                future.completeExceptionally(new IllegalStateException("Database closed"));
                return;
            }
            List<ArenaDetails> out = new ArrayList<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT arena_id, strategy_id, world_name, owner_uuid, structure_id, "
                                 + "created_at, last_used, status FROM arenas "
                                 + "ORDER BY status, last_used DESC")) {
                while (rs.next()) {
                    out.add(new ArenaDetails(
                            rs.getString("arena_id"),
                            rs.getString("strategy_id"),
                            rs.getString("world_name"),
                            rs.getString("owner_uuid"),
                            rs.getString("structure_id"),
                            rs.getLong("created_at"),
                            rs.getLong("last_used"),
                            rs.getString("status")
                    ));
                }
                future.complete(out);
            } catch (SQLException e) {
                log("getAllArenaDetailsAsync", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<ArenaDetails> getArenaDetailsAsync(String arenaId) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
        }
        CompletableFuture<ArenaDetails> future = new CompletableFuture<>();
        submit(() -> {
            if (closed.get() || connection == null) {
                future.completeExceptionally(new IllegalStateException("Database closed"));
                return;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT arena_id, strategy_id, world_name, owner_uuid, structure_id, "
                            + "created_at, last_used, status FROM arenas WHERE arena_id = ?")) {
                ps.setString(1, arenaId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        future.complete(new ArenaDetails(
                                rs.getString("arena_id"),
                                rs.getString("strategy_id"),
                                rs.getString("world_name"),
                                rs.getString("owner_uuid"),
                                rs.getString("structure_id"),
                                rs.getLong("created_at"),
                                rs.getLong("last_used"),
                                rs.getString("status")
                        ));
                    } else {
                        future.complete(null);
                    }
                }
            } catch (SQLException e) {
                log("getArenaDetailsAsync", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<List<ArenaRecord>> getAllArenasAsync() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
        }
        CompletableFuture<List<ArenaRecord>> future = new CompletableFuture<>();
        submit(() -> {
            if (closed.get() || connection == null) {
                future.completeExceptionally(new IllegalStateException("Database closed"));
                return;
            }
            List<ArenaRecord> records = new ArrayList<>();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT arena_id, strategy_id, world_name, owner_uuid FROM arenas")) {
                while (rs.next()) {
                    records.add(new ArenaRecord(
                            rs.getString("arena_id"),
                            rs.getString("strategy_id"),
                            rs.getString("world_name"),
                            rs.getString("owner_uuid")
                    ));
                }
                future.complete(records);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<List<ActiveSession>> getAllActiveSessionsAsync() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database closed"));
        }
        CompletableFuture<List<ActiveSession>> future = new CompletableFuture<>();
        submit(() -> {
            if (closed.get() || connection == null) {
                future.completeExceptionally(new IllegalStateException("Database closed"));
                return;
            }
            List<ActiveSession> sessions = new ArrayList<>();
            String sql = """
                        SELECT s.player_uuid, s.arena_id, s.world_name, s.current_level, s.kills,
                               s.start_time, s.origin_x, s.origin_y, s.origin_z, a.structure_id,
                               s.infinite, s.awaiting_infinite_choice
                        FROM active_sessions s
                        LEFT JOIN arenas a ON s.arena_id = a.arena_id
                    """;
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    String structureId = rs.getString("structure_id");
                    if (structureId == null) structureId = DEFAULT_STRUCTURE_ID;
                    sessions.add(new ActiveSession(
                            rs.getString("player_uuid"),
                            rs.getString("arena_id"),
                            rs.getString("world_name"),
                            structureId,
                            rs.getInt("current_level"),
                            rs.getInt("kills"),
                            rs.getLong("start_time"),
                            rs.getDouble("origin_x"),
                            rs.getDouble("origin_y"),
                            rs.getDouble("origin_z"),
                            rs.getInt("infinite") != 0,
                            rs.getInt("awaiting_infinite_choice") != 0
                    ));
                }
                future.complete(sessions);
            } catch (SQLException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void flushAsyncWrites() {
        if (closed.get()) return;
        CompletableFuture<Void> sentinel = new CompletableFuture<>();
        try {
            dbExecutor.execute(() -> sentinel.complete(null));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return;
        }
        try {
            sentinel.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dbExecutor.shutdownNow();
        }
        Connection c = connection;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private void log(String op, SQLException e) {
        if (plugin != null) {
            plugin.getLogger().warning("DB " + op + " failed: " + e.getMessage());
        }
    }

    private void submit(Runnable task) {
        if (closed.get()) return;
        try {
            dbExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log("submit", new SQLException("Executor rejected task (likely shutdown)"));
        }
    }

    public record GlobalStatsRow(
            int totalSessions,
            int highestLevelEver,
            long totalKills,
            long totalDurationSeconds,
            int reachedL5Count
    ) {
    }

    public record ArenaRecord(String arenaId, String strategyId, String worldName, String ownerUuid) {
    }

    public record ArenaDetails(
            String arenaId,
            String strategyId,
            String worldName,
            String ownerUuid,
            String structureId,
            long createdAt,
            long lastUsed,
            String status
    ) {
    }

    private record LegacyWorldRecord(String worldUuid, String playerUuid, String status,
                                     long createdAt, long lastUsed) {
    }

    private record LegacySessionRecord(String playerUuid, String worldUuid, int currentLevel,
                                       int kills, long startTime,
                                       double originX, double originY, double originZ) {
    }

    private record LegacyHistoryRecord(int id, String playerUuid, int highestLevel, int totalKills,
                                       long durationSeconds, long completedAt, String reason) {
    }
}
