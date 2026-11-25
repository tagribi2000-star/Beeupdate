package me.beeupdate;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Camera movement, chat and commands count as activity; being pushed by water does not. */
public final class AfkTracker implements Listener {
    private final BeeUpdate plugin;
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    AfkTracker(BeeUpdate plugin) { this.plugin = plugin; }

    private void active(Player p) { last.put(p.getUniqueId(), System.currentTimeMillis()); }

    public boolean isAfk(Player p) {
        long seen = last.computeIfAbsent(p.getUniqueId(), k -> System.currentTimeMillis());
        return System.currentTimeMillis() - seen > plugin.getConfig().getLong("afk.seconds", 120) * 1000;
    }

    @EventHandler public void join(PlayerJoinEvent e) {
        active(e.getPlayer());
        plugin.ledger().touch(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }
    @EventHandler public void quit(PlayerQuitEvent e) { last.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void move(PlayerMoveEvent e) {
        if (e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch())
            active(e.getPlayer());
    }
    @EventHandler public void chat(AsyncChatEvent e) { active(e.getPlayer()); }
    @EventHandler public void command(PlayerCommandPreprocessEvent e) { active(e.getPlayer()); }
}
