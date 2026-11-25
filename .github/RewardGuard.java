package me.beeupdate;

import java.util.*;

/** Anti-exploit bookkeeping for kill rewards. Pure Java. */
public final class RewardGuard {
    private final Map<UUID, Map<UUID, Double>> damage = new HashMap<>();
    private final Map<UUID, Deque<Long>> recent = new HashMap<>();

    public void recordDamage(UUID mob, UUID player, double amount) {
        damage.computeIfAbsent(mob, k -> new HashMap<>()).merge(player, Math.max(0, amount), Double::sum);
    }

    public double share(UUID mob, UUID player, double maxHealth) {
        return damage.getOrDefault(mob, Map.of()).getOrDefault(player, 0.0) / Math.max(1, maxHealth);
    }

    /** Sliding one-minute window per player. */
    public boolean tryRate(UUID player, long nowMillis, int maxPerMinute) {
        Deque<Long> q = recent.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!q.isEmpty() && nowMillis - q.peekFirst() >= 60_000) q.pollFirst();
        if (q.size() >= maxPerMinute) return false;
        q.addLast(nowMillis);
        return true;
    }

    public void forget(UUID mob) { damage.remove(mob); }
}
