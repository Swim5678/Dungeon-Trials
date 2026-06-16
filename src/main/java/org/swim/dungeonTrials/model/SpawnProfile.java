package org.swim.dungeonTrials.model;

import java.util.function.IntFunction;

public enum SpawnProfile {

    /**
     * Default pacing.
     * <ul>
     *   <li>interval: 40 ticks (2.0s)</li>
     *   <li>totalCap: n + 4</li>
     *   <li>simCap: max(1, (n + 1) / 2 + 1)</li>
     * </ul>
     * Generic low-pressure filler.
     */
    DEFAULT(40, n -> n + 4, n -> (n + 1) / 2 + 1),

    /**
     * Juvenile / low-tier variant.
     * <ul>
     *   <li>interval: 20 ticks (1.0s)</li>
     *   <li>totalCap: n + 4</li>
     *   <li>simCap: max(1, (n + 3) / 2)</li>
     * </ul>
     * Faster cadence than DEFAULT with modest caps; used for weaker mid-tier mobs.
     */
    JUVENILE(20, n -> n + 4, n -> (n + 3) / 2),

    /**
     * Standard combat-tier mob.
     * <ul>
     *   <li>interval: 20 ticks (1.0s)</li>
     *   <li>totalCap: n + 4</li>
     *   <li>simCap: max(1, (n + 4) / 2)</li>
     * </ul>
     * Backbone profile for most levels.
     */
    BASIC(20, n -> n + 4, n -> (n + 4) / 2),

    /**
     * Heavy / tank-tier mob.
     * <ul>
     *   <li>interval: 20 ticks (1.0s)</li>
     *   <li>totalCap: n + 8</li>
     *   <li>simCap: max(1, (n + 6) / 2)</li>
     * </ul>
     * Same speed as BASIC but higher caps to keep pressure up.
     */
    HEAVY(20, n -> n + 8, n -> (n + 6) / 2),

    /**
     * Slow, ranged attacker.
     * <ul>
     *   <li>interval: 160 ticks (8.0s)</li>
     *   <li>totalCap: n + 4</li>
     *   <li>simCap: n + 2</li>
     * </ul>
     * Long cooldown; stacks of ranged pressure rather than spam.
     */
    SLOW_RANGED(160, n -> n + 4, n -> n + 2),

    /**
     * Vex-style swarmer.
     * <ul>
     *   <li>interval: 20 ticks (1.0s)</li>
     *   <li>totalCap: n + 3</li>
     *   <li>simCap: max(1, (n + 3) / 2)</li>
     * </ul>
     * Tight, persistent swarm cap that does not grow with level scaling.
     */
    VEX(20, n -> n + 3, n -> (n + 3) / 2);

    private final int intervalTicks;
    private final IntFunction<Integer> totalCap;
    private final IntFunction<Integer> simultaneousCap;

    SpawnProfile(int intervalTicks,
                 IntFunction<Integer> totalCap,
                 IntFunction<Integer> simultaneousCap) {
        this.intervalTicks = intervalTicks;
        this.totalCap = totalCap;
        this.simultaneousCap = simultaneousCap;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public int totalCap(int n) {
        return Math.max(0, totalCap.apply(Math.max(1, n)));
    }

    public int simultaneousCap(int n) {
        return Math.max(1, simultaneousCap.apply(Math.max(1, n)));
    }
}
