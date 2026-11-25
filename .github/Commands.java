package me.beeupdate;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class Commands implements TabExecutor {
    private final BeeUpdate plugin;
    private final Shop shop;

    Commands(BeeUpdate plugin, Shop shop) {
        this.plugin = plugin;
        this.shop = shop;
    }

    private boolean denied(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return false;
        plugin.msg(s, "no-permission");
        return true;
    }

    private static long amount(String s) {
        try {
            return Math.max(0, Long.parseLong(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Ledger.Account target(CommandSender s, String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) plugin.ledger().touch(online.getUniqueId(), online.getName());
        var found = plugin.ledger().findByName(name).orElse(null);
        if (found == null) plugin.msg(s, "unknown-player");
        return found;
    }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (command.getName()) {
            case "beeshop" -> {
                if (!(s instanceof Player p)) plugin.msg(s, "players-only");
                else if (!denied(s, "beeupdate.shop")) shop.open(p);
            }
            case "honey" -> honey(s, sub, a);
            case "bee" -> bee(s, sub, a);
            default -> { return false; }
        }
        return true;
    }

    private void honey(CommandSender s, String sub, String[] a) {
        if (sub.equals("top")) { top(s); return; }
        if (!(s instanceof Player p)) { plugin.msg(s, "players-only"); return; }
        if (!sub.equals("pay")) {
            if (!denied(s, "beeupdate.honey"))
                plugin.msg(s, "balance", "amount", plugin.ledger().balance(p.getUniqueId()));
            return;
        }
        if (denied(s, "beeupdate.pay")) return;
        if (a.length != 3) { s.sendMessage("/honey pay <player> <amount>"); return; }
        long n = amount(a[2]);
        if (n <= 0) { plugin.msg(s, "bad-amount"); return; }
        Ledger.Account to = target(s, a[1]);
        if (to == null) return;
        if (to.id().equals(p.getUniqueId())) { plugin.msg(s, "self-pay"); return; }
        if (!plugin.ledger().transfer(p.getUniqueId(), to.id(), n)) { plugin.msg(s, "failed"); return; }
        plugin.msg(s, "paid", "amount", n, "player", to.name());
        Player other = plugin.getServer().getPlayer(to.id());
        if (other != null) plugin.msg(other, "received", "amount", n, "player", p.getName());
    }

    private void top(CommandSender s) {
        if (denied(s, "beeupdate.top")) return;
        s.sendMessage(BeeUpdate.text("&6&lTop 10 Honey Coin"));
        int i = 1;
        for (Ledger.Account acc : plugin.ledger().top(10))
            s.sendMessage(BeeUpdate.text("&e" + i++ + ". &f" + acc.name() + " &7- &6" + acc.balance()));
    }

    private void bee(CommandSender s, String sub, String[] a) {
        switch (sub) {
            case "top" -> top(s);
            case "give", "take" -> {
                if (denied(s, "beeupdate.admin." + sub)) return;
                if (a.length != 3) { s.sendMessage("/bee " + sub + " <player> <amount>"); return; }
                long n = amount(a[2]);
                if (n <= 0) { plugin.msg(s, "bad-amount"); return; }
                Ledger.Account to = target(s, a[1]);
                if (to == null) return;
                boolean ok = sub.equals("give") ? plugin.ledger().deposit(to.id(), n)
                        : plugin.ledger().withdraw(to.id(), n);
                if (!ok) { plugin.msg(s, "failed"); return; }
                plugin.getLogger().info(s.getName() + " /bee " + sub + " " + to.name() + " " + n);
                plugin.msg(s, "done", "player", to.name(), "amount", plugin.ledger().balance(to.id()));
            }
            case "reload" -> {
                if (denied(s, "beeupdate.admin.reload")) return;
                try {
                    plugin.reloadAll();
                    plugin.msg(s, "reloaded");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Reload failed, old settings kept", e);
                    s.sendMessage("Reload failed: " + e.getMessage());
                }
            }
            case "spawn" -> {
                if (denied(s, "beeupdate.admin.spawn")) return;
                if (!(s instanceof Player p)) { plugin.msg(s, "players-only"); return; }
                if (a.length < 2 || !plugin.mobs().typeIds().contains(a[1])) {
                    s.sendMessage("/bee spawn <" + String.join("|", plugin.mobs().typeIds()) + "> [1-10]");
                    return;
                }
                int wanted = a.length > 2 ? (int) Math.clamp(amount(a[2]), 1, 10) : 1, made = 0;
                for (int i = 0; i < wanted; i++)
                    if (plugin.mobs().spawn(a[1], p.getLocation().add(p.getLocation().getDirection().multiply(4)).add(0, 1, 0))) made++;
                plugin.msg(s, "spawned", "amount", made, "mob", a[1]);
            }
            default -> {
                if (denied(s, "beeupdate.help")) return;
                for (String line : new String[]{"/honey", "/honey pay <player> <amount>", "/beeshop", "/bee top",
                        "/bee give <player> <amount>", "/bee take <player> <amount>", "/bee reload",
                        "/bee spawn <type> [count]", "/bee help"})
                    s.sendMessage(BeeUpdate.text("&e" + line));
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String alias, String[] a) {
        List<String> options = new ArrayList<>();
        if (a.length == 1 && command.getName().equals("honey")) options.addAll(List.of("pay", "top"));
        else if (a.length == 1 && command.getName().equals("bee")) {
            options.addAll(List.of("top", "help"));
            for (String x : new String[]{"give", "take", "reload", "spawn"})
                if (s.hasPermission("beeupdate.admin." + x)) options.add(x);
        } else if (a.length == 2 && command.getName().equals("bee") && a[0].equalsIgnoreCase("spawn")) {
            options.addAll(plugin.mobs().typeIds());
        } else if (a.length == 2 && List.of("pay", "give", "take").contains(a[0].toLowerCase(Locale.ROOT))) {
            return null; // default: online player names
        }
        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(prefix)).toList();
    }
}
