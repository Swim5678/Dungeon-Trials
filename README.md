# Dungeon Trials

> Minecraft Paper Plugin — Automatically generates trial dungeons, wave-based mob spawning, 5-level progression, and an optional infinite mode.

---

## Introduction

**Dungeon Trials** is a Paper server-side "Trial Dungeon" plugin. When a player executes `/dt start`, the system **generates an isolated trial dungeon** (by default, using a shared world grid where each dungeon is assigned a grid cell), automatically places the **vanilla Minecraft Trial Chambers structure**, and initiates a combat loop consisting of **wave-based mob spawning + level progression**. Players can advance levels by meeting requirements (or manually upgrade inside the dungeon via `/dt next`). After clearing level 5, players can opt into the **infinite mode**.

### Core Features

* **Solo / Co-op** — Owners can invite visitors to their dungeon (up to `settings.max-visitors`, default is 3).
* **5 Defined Levels** (Levels 6 and above use implicit configurations, with `clear-requirement` increasing by 12 per level).
* **Wave-based Spawning** — Triggered when a player moves beyond a distance of `DISTANCE_THRESHOLD=5` blocks from the starting point.
* **Dynamic Difficulty Scaling** — Automatically scales up spawn intensity for levels 6+ and multiplayer sessions.
* **Persistence** — SQLite (WAL) + Caffeine cache, ensuring records are preserved across server restarts.
* **i18n** — Supports English + Traditional Chinese, configurable via `settings.locale`.
* **BossBar Progress** — Real-time display of kill progress with a 3-tier color system (Green / Yellow / Red).
* **Orphan Cleanup** — Automatically scans for residual worlds during startup and disabling phases.

---

## Tech Stack

| Item | Version / Description |
| --- | --- |
| Minecraft | Paper **26.1.2** |
| Java | **25** (locked via toolchain) |
| Build Tool | **Gradle 9.5.1** (configuration-cache enabled) |
| Shadow JAR | 9.4.2 |
| Development Plugin | `xyz.jpenilla.run-paper` 3.0.2 |
| Package | `org.swim.dungeonTrials` |
| Maven group | `org.swim` |
| Version | `1.0-SNAPSHOT` |
| Persistence | **SQLite** (WAL) + **Caffeine** Cache |
| Internationalization | **MiniMessage** (Adventure API) + Custom YAML |
| Annotations | **Lombok** (`@Getter`) + **JSpecify** (`@NonNull`) |
| Permission Compatibility | **LuckPerms API** 5.5 (downloaded at runtime) |

### Dependency Resolution Mechanism

All runtime dependencies (`sqlite-jdbc`, `caffeine`, `luckperms-api`) are declared as `compileOnly` and **are not shaded into the fat jar**. Instead, `DungeonTrialsLoader` implements Paper's `PluginLoader` interface to dynamically download them from Maven Central via `MavenLibraryResolver` during the `classloader(...)` stage.

> Advantage: The jar size remains extremely small, and dependency deduplication is handled by Paper.
> Disadvantage: The server requires an internet connection during its initial startup.

---

## Project Structure

```
src/main/
├── java/org/swim/dungeonTrials/
│   ├── DungeonTrials.java              ← Main Plugin Class
│   ├── DungeonTrialsLoader.java        ← Paper PluginLoader
│   ├── config/
│   │   ├── ConfigManager.java          ← Configuration + i18n
│   │   └── LevelConfig.java            ← record
│   ├── database/
│   │   └── DatabaseManager.java        ← SQLite + Caffeine + schema migration
│   ├── model/
│   │   ├── ActiveSession.java          ← Active session (arenaId, worldName, structureId, origin*)
│   │   ├── SessionHistory.java         ← Historical records (structureId, highestLevel, ...)
│   │   ├── MobSpawnRule.java           ← record(type + profile)
│   │   ├── MobSpawnState.java          ← Spawn state counter
│   │   └── SpawnProfile.java           ← 6 types of spawn rhythm enum
│   ├── service/
│   │   ├── MobService.java             ← Combat loop, waves, visitors (region-scoped)
│   │   ├── WorldService.java           ← Utility service (batch block operations)
│   │   ├── StructureService.java       ← Delegates to StructureType interface
│   │   └── PlayerService.java          ← Bed searching + teleportation
│   ├── structure/
│   │   ├── StructureType.java          ← Structure interface (id/displayName/placeOffsets/scan/...)
│   │   ├── PostProcessStep.java        ← Post-processing step interface
│   │   ├── SpawnPointFinder.java       ← Spawn point finder interface
│   │   ├── StructureRegistry.java      ← Structure registry
│   │   ├── builtin/
│   │   │   ├── TrialChambersStructureType.java
│   │   │   └── WoodlandMansionStructureType.java
│   │   ├── postprocess/
│   │   │   ├── BeardifierShellStep.java       ← Generates a Gaussian-decay bedrock/barrier shell using vanilla Beardifier algorithm
│   │   │   └── ClearBelowStructureStep.java   ← Clears blocks extending down to the void layer into air
│   │   └── spawnpoint/
│   │       └── BedNearSpawnerFinder.java
│   └── world/
│       ├── VoidGenerator.java          ← Completely empty ChunkGenerator
│       ├── AcquiredArena.java          ← record(arenaId, strategyId, world, worldName, regionBounds, arenaCenter)
│       ├── WorldStrategy.java          ← World strategy interface
│       └── strategy/
│           ├── PerPlayerWorldStrategy.java  ← One dedicated world per player
│           └── SharedWorldStrategy.java     ← Shared world + grid allocation (Default)
└── resources/
    ├── paper-plugin.yml                ← Plugin manifest
    ├── config.yml                      ← Default configuration
    ├── messages-en.yml                 ← English messages
    └── messages-zh-tw.yml              ← Traditional Chinese messages

```

---

## Development Commands

```bash
# Compile
./gradlew compileJava

# Generate fat jar (build/libs/DungeonTrials-1.0-SNAPSHOT.jar)
./gradlew shadowJar

# Start Paper 26.1.2 test server (2GB heap, auto-agree to EULA)
./gradlew runServer

```

> ⚠️ **No Test Suite** — Verification is completed via successful builds.

---

## Build Configuration Highlights

```kotlin
plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.4.2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

build { dependsOn(shadowJar) }
named<Jar>("jar") { enabled = false }   // Disable default jar
shadowJar { archiveClassifier.set("") } // Output name = DungeonTrials-1.0-SNAPSHOT.jar

runServer {
    minecraftVersion("26.1.2")
    jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
}

processResources {
    filesMatching("paper-plugin.yml") { expand(mapOf("version" to version)) }
}

```

---

## Plugin Manifest `paper-plugin.yml`

```yaml
name: DungeonTrials
version: '${version}'           # Replaced with 1.0-SNAPSHOT by processResources
main: org.swim.dungeonTrials.DungeonTrials
loader: org.swim.dungeonTrials.DungeonTrialsLoader
api-version: '26.1.2'
load: POSTWORLD                  # Enabled after default worlds have loaded
authors: [ Swim ]

```

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                       DungeonTrials (Main)                   │
│  · onEnable / onDisable                                        │
│  · 12 /dt sub-command registrations & handling                 │
│  · 11 @EventHandler registrations                              │
└────┬────────────┬──────────────┬──────────────┬──────────────┘
     │            │              │              │
     ▼            ▼              ▼              ▼
 ConfigManager  DatabaseMgr   WorldStrategy   MobService
 (Config+i18n)  (SQLite)     (World Lifecycle)(Wave+Visitor)
                                   ▲              ▲
                                   │              │
                              StructureService  PlayerService
                              (Placement+Scan)  (Bed Search+Teleport)
                                   ▲
                                   │
                              StructureRegistry → StructureType
                                                  (Trial Chambers, etc.)

```

### Initialization Order at Startup

1. `ConfigManager.load()` — Reads the config and merges missing keys for i18n.
2. `DatabaseManager.init()` — Connects to SQLite, enables WAL, and performs automatic migrations via `PRAGMA user_version`.
3. Instantiates `WorldService` → `StructureRegistry` (registers the built-in `TrialChambersStructureType`) → `StructureService` → `MobService`.
4. Instantiates `WorldStrategy` (defaults to `SharedWorldStrategy`; switching requires modifying the code in `DungeonTrials.createWorldStrategy()`) and injects it into `MobService`.
5. Executes `registerEvents(this)` + `registerCommands()`.
6. Performs **asynchronous** orphan cleanup after a 1-second delay (clears gridIds and DB records in shared mode; deletes world folders in per-player mode).

### Disabling / Unloading Phase

1. `cancelTasks(this)`
2. `mobService.endAll()` — Marks all active sessions with `SHUTDOWN`.
3. Performs a final orphan cleanup.
4. `worldStrategy.shutdown()` (waits up to 30s for the per-player IO thread; treated as a no-op for shared mode).
5. `databaseManager.close()` — Waits up to 10s for the DB thread.

---

## Command System (`/dt`)

Bypasses `plugin.yml` declarations and dynamically registers commands using `Bukkit.getCommandMap().register(...)`.

### Sub-commands Overview

| Command | Usage | Permission |
| --- | --- | --- |
| `start [1-5] [structure]` | Starts a dungeon session. | — |
| `next [1-5]` | Advances the level (restricted to the owner inside the dungeon; can specify any target level from 1-5). | — |
| `stay` | Rejects the level advancement and stays at the current level. | — |
| `exit` | Leaves the dungeon. | — |
| `stats` | Checks personal history (displays the last 20 sessions). | — |
| `invite <player>` | Invites a visitor. | — |
| `visit <owner>` | Accepts an invitation to enter a dungeon. | — |
| `decline` | Declines an invitation. | — |
| `leave` | Visitor leaves the dungeon. | — |
| `kick <visitor>` | Owner kicks a visitor. | — |
| `infinite` | Accepts entering the infinite mode. | — |
| `end` | Declines the infinite mode (stops spawning mobs). | — |
| `reload` | Reloads the configuration file. | `dungeontrials.admin` |

### Tab Completion

* First argument: Lists all available sub-commands.
* Second argument for `start` / `next`: Suggests `1, 2, 3, 4, 5`.
* Second argument for `invite/visit/kick`: Evaluates to `null` (delegated to Bukkit to provide online player lists).
* Third argument for `start`: Suggests all structure IDs registered in `StructureRegistry` (default is `trial_chambers`).

---

## Configuration Files

### `config.yml`

```yaml
levels:
  1:
    clear-requirement: 6
    mobs:
      - type: ZOMBIE
        profile: BASIC
  2:
    clear-requirement: 12
    mobs:
      - type: ZOMBIE
        profile: BASIC
      - type: SKELETON
        profile: BASIC
  # ... L3-L5 ...

settings:
  main-world: overworld          # Target world when exiting the dungeon
  locale: "zh-tw"                # default | en | zh-tw | ...
  default-structure: trial_chambers  # Default structure ID for /dt start

```

### 6 Types of Spawn Rhythms (`SpawnProfile` enum)

| Profile | interval | totalCap(n) | simCap(n) | Usage |
| --- | --- | --- | --- | --- |
| `DEFAULT` | 40t (2.0s) | n+4 | max(1,(n+1)/2+1) | General low-pressure filler |
| `JUVENILE` | 20t (1.0s) | n+4 | max(1,(n+3)/2) | Weaker secondary mobs |
| `BASIC` | 20t (1.0s) | n+4 | max(1,(n+4)/2) | **Main force** |
| `HEAVY` | 20t (1.0s) | n+8 | max(1,(n+6)/2) | Heavy armor / tank mobs |
| `SLOW_RANGED` | 160t (8.0s) | n+4 | n+2 | Slow ranged attackers |
| `VEX` | 20t (1.0s) | n+3 | max(1,(n+3)/2) | Swarm type mobs |

> `n` = Number of active, non-spectator players currently inside the world grid, re-calculated every tick.

### Dynamic Difficulty Scaling `levelIntensityMultiplier(level, n)`

```
level ≤ 5       : Multiplier = 1.0
level ≥ 6, Solo  : Adds +0.10 intensity per level above 5
level ≥ 6, Co-op : Adds an additional +0.05 intensity per additional player (>1) (accumulated onto the level modifier)

```

`simCap / totalCap` calculations use multiplication (rounded to the nearest integer), while `intervalTicks` uses division (rounded down, with a hard minimum floor of 5 ticks).
**The global hard ceiling `MAX_ALIVE = 30` always takes absolute priority**.

### Default Difficulty Curve

| L | clear | Mob Configurations |
| --- | --- | --- |
| 1 | 6 | ZOMBIE × BASIC |
| 2 | 12 | + SKELETON × BASIC |
| 3 | 18 | + BREEZE × BASIC |
| 4 | 30 | + CAVE_SPIDER × **HEAVY** |
| 5 | 48 | + SLIME × BASIC, BREEZE → JUVENILE, SKELETON → SLOW_RANGED |

> Level 6+ automatic extension: `clear-requirement` increases by 12 per level, inheriting the mob configuration list from Level 5.

---

## Spawning and Waves (`MobService`)

### Trigger Conditions

```
PlayerMoveEvent (hasChangedBlock = true)
  AND Active session exists
  AND Distance from starting point > distanceThreshold
  → startWaveCycle()

```

### Per-tick Spawning Logic

```java
int alive = state.values().stream().mapToInt(s -> s.aliveCount).sum();
if (alive >= MAX_ALIVE) return;        // Global hard limit of 30

double mult = levelIntensityMultiplier(level, n);
int simCap   = max(1, round(profile.simultaneousCap(n) * mult));
int totalCap = max(0, round(profile.totalCap(n) * mult));
int interval = max(5, round(profile.intervalTicks() / mult));

// Shuffles the rules order randomly to avoid spawning identical mobs consecutively
for (MobSpawnRule rule : shuffled) {
    if (s.aliveCount >= simCap) continue;
    if (s.totalSpawned >= totalCap) continue;
    if (tick < s.nextSpawnTick) continue;
    if (alive >= MAX_ALIVE) return;
    
    Player target = pickRandomPlayer(world);
    Entity entity = spawnMob(world, target.getLocation(), rule.entityType());
    // 10 attempts: finds a valid standing position using random angles, random radii from 3 to maxSpawnRadius, and y ±3
    // Skips positions marked with TUFF_BRICKS and avoids confined 1×1 spaces
}

```

### Despawn Scheduling

Executes an evaluation every 60 ticks (3 seconds). Mobs that are further than 32 blocks away from all players inside the arena are automatically removed.

### Level Progression

```
Kill count met (kills ≥ clearRequirement)
  AND Killed mob belongs to the current level's configurations
  AND !infinite
  AND !awaitingInfiniteChoice
  → Sends a message prompting "Advance to Level {next}?"
      [YES] clicked → /dt next {next}  → advanceLevel
      [NO]  clicked → /dt stay        → declineLevelUp (resets kills to 0)
  → If Level 5 is cleared: Clears all mobs, stops spawning, and changes state to awaitingInfiniteChoice
      [YES] clicked → /dt infinite  → enterInfiniteMode (infinite loop starting from L6)
      [NO]  clicked → /dt end       → declineInfiniteMode (stops mob spawning permanently)

```

---

## Visitor System

* Owner side: `/dt invite <player>` → Registers into `pendingInvites[visitor] = owner`.
* Visitor side: Receives invitation notice accompanied by clickable `[Join][Decline]` text buttons.
* `/dt visit <owner>` → Validates `maxVisitors`, caches the original respawn location, and teleports the visitor to the designated bed.
* `/dt decline` → Wipes the pending invitation and notifies the owner.


* Owners can issue `/dt kick <visitor>` to forcibly return visitors back to their original respawn points.
* When an owner triggers `endSession`, `kickAllVisitors` executes to return all current visitors back to their original respawn points.
* When a visitor triggers `endSession`, it only clears their own visitor status **without affecting the owner's session**.

---

## Event Handling

| Event | Action |
| --- | --- |
| `PlayerMoveEvent` | Block changed + distance from origin > threshold → Triggers spawn wave. |
| `EntityDeathEvent` | Registers tracking inside `onKill` + purges spawn tracking references. |
| `EntityRemoveFromWorldEvent` | Paper-exclusive event; purges spawn tracking references. |
| `PlayerDeathEvent` | Executes `endSession("DIED")`. |
| `PlayerQuitEvent` | Executes `endSession("QUIT")` + clears visitor states (utilizes `tryBeginEnd` to prevent re-entrancy issues). |
| `PlayerJoinEvent` | Applies `applyPendingRespawn` (restores cached respawn points stored while offline). |
| `PlayerKickEvent` | Executes `endSession("KICKED")`. |
| `PlayerChangedWorldEvent` | Player steps out of a dungeon world → Executes `endSession("LEFT")` (teleportation is bypassed). |
| `PlayerRespawnEvent` | If the respawn destination target points inside a dungeon world → Overrides it to the cached original respawn point. |
| `PlayerInteractEvent` | Clicking on beds inside a dungeon world → Cancelled (prevents overriding original spawn positions). |

### Re-entrancy Prevention

```java
public boolean tryBeginEnd(UUID uuid) { return ending.add(uuid); }
public void endEnd(UUID uuid) { ending.remove(uuid); }

```

Uses `ConcurrentHashMap.newKeySet()` as an optimistic lock, ensuring that internal triggers during a `PlayerQuitEvent` do not inadvertently cause duplicate `endSession` executions via a nested `PlayerChangedWorldEvent`.

---

## Database (`DatabaseManager`)

### v2 Schema (Since 1.1)

```sql
arenas (
  arena_id TEXT PRIMARY KEY,    -- uuid for per-player mode; "shared:gx:gz" for shared mode
  strategy_id TEXT NOT NULL,    -- "per-player" or "shared"
  world_name TEXT NOT NULL,
  owner_uuid TEXT NOT NULL,
  structure_id TEXT NOT NULL,   -- trial_chambers / future expansions
  created_at INTEGER NOT NULL,
  last_used INTEGER NOT NULL
)

active_sessions (
  player_uuid TEXT PRIMARY KEY,
  arena_id TEXT NOT NULL,       -- maps to arenas.arena_id
  world_name TEXT NOT NULL,
  structure_id TEXT NOT NULL,
  current_level INTEGER NOT NULL DEFAULT 1,
  kills INTEGER NOT NULL DEFAULT 0,
  start_time INTEGER NOT NULL,
  origin_x/y/z REAL NOT NULL
)

session_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  player_uuid TEXT NOT NULL,
  structure_id TEXT NOT NULL,   -- utilized for run statistics tracking (e.g., Trial Chambers count)
  highest_level INTEGER NOT NULL,
  total_kills INTEGER NOT NULL,
  duration_seconds INTEGER NOT NULL,
  completed_at INTEGER NOT NULL,
  reason TEXT NOT NULL
)

```

### Schema Migration

Automatically detects versions using `PRAGMA user_version` to perform upgrades from v0/v1 to v2:

* 0 → 2: Reads data from the obsolete `worlds` table to construct `arenas` (injects strategy_id, sets structure_id default to 'trial_chambers', maps the original world_uuid to arena_id).
* Renames columns in `active_sessions` (world_uuid → arena_id, adds world_name) and removes orphaned records.
* Appends the `structure_id` field to `session_history` (backfilled with a default value of 'trial_chambers').
* 1 → 2: Follows a similar strategy since it already matches the updated structure, focusing primarily on backfilling missing fields.

### Connection Configurations

* `PRAGMA journal_mode=WAL` — Concurrently handles read and write operations.
* `PRAGMA busy_timeout=3000` — Sets a 3-second busy timeout.
* `PRAGMA user_version` — Tracks schema versions.

### Asynchronous Model

* A dedicated daemon thread `DungeonTrials-DB` handles all SQL statements.
* Database write operations return immediately, while read queries are wrapped using `CompletableFuture`.
* `getPlayerHistoryAsync` utilizes a Caffeine cache (capped at 50 entries, with a 30-minute idle expiration period), invalidated upon subsequent database writes.

### Orphan Identification Query

```sql
SELECT a.arena_id FROM arenas a
LEFT JOIN active_sessions s ON a.arena_id = s.arena_id
WHERE s.player_uuid IS NULL

```

* Per-player mode: The world matching an orphaned arena is unloaded and its corresponding file directory is deleted.
* Shared mode: The gridId of the orphaned arena is recycled back into the pool and its DB record is deleted (the parent world remains loaded).

---

## Internationalization (`messages*.yml`)

### Loading Sequence

```
1. Loads fallback messages-en.yml from inside the jar (defaultMessages).
2. If the local file does not exist on disk → Copies it from the jar resources.
3. Appends missing keys from defaultMessages into the active configuration map (only introduces missing keys, leaving custom user entries untouched).
4. If adjustments were made during backfilling → Flushes modifications back to disk storage.

```

### Locale Resolution Rules

```
locale assigned to "default" | "en" | "english" | empty string "" → maps to messages-en.yml
Any other inputs → maps to messages-{locale}.yml (e.g., "messages-zh-tw.yml")

```

### Placeholders

Uses the `{name}` placeholder pattern (avoids using raw MiniMessage tags directly within text to prevent parsing conflicts with standard `<...>` tags):

```java
configManager.getMessage("command.invite.sent", "target", "Steve");
// Swaps out {target} → Steve → Processes via MiniMessage.deserialize → Returns Component

```

### Interactive Buttons (MiniMessage Click Events)

```yaml
"<gold>Reached {kills} kills! Advance to Level {next}?</gold>
 <click:run_command:/dt next {next}><green>[YES]</green></click>
 <click:run_command:/dt stay><red>[NO]</red></click>"

```

---

## Structure Placement (`StructureService` + `StructureType`)

### Interface Responsibilities

The `StructureType` interface contains all mutable parameters for a structure (`id`, `displayName`, `namespacedId`, `placeX/Y/ZOffset`, `platformSize`, `keepLoadedRadius`, `spawnerBlocks`, `unsafeSpawnFloors`, `postProcessSteps`, `spawnPointFinder`).

`StructureService.placeStructure(world, player, type, arenaCenter, onComplete)` serves as the unified external entry point for structure generation, delegating steps internally to components bound under the `StructureType` interface.

### Placement Flow Diagram

```
1. Forcibly loads chunks encompassing the keepLoadedRadius area surrounding arenaCenter.
2. Yields execution for 1 second (allows chunk generations to finalize).
3. Accesses NMS APIs: level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(Identifier.parse(type.namespacedId())) to retrieve Holder<Structure> and Structure instances.
4. Executes structure.generate(...): Synchronously builds a StructureStart object.
   - Evaluates vanilla findGenerationPoint + expands jigsaw pools.
   - Computes the true BoundingBox (incorporating the +12 inflate derived from terrain_adaptation encapsulate).
5. Invokes start.placeInChunk(...) across every chunk intersecting the BoundingBox to physically place blocks.
6. Scans across the BoundingBox boundary to catalog spawner locations matching spawnerBlocks.
7. Executes the chained type.postProcessSteps(): sequentially processes BeardifierShellStep (ENCAPSULATE shell, Gaussian decay) → ClearBelowStructureStep (purges blocks to AIR, restricted to Woodland Mansions).

```

> ⚠️ Structure generation leverages NMS APIs directly (provided via the mojang-mapped NMS classpath via `paperweight-userdev`) instead of using the raw `place structure` console command. It directly triggers vanilla `Structure.generate` + `StructureStart.placeInChunk` simultaneously, securing the authentic `BoundingBox` (including encapsulate inflate) natively.