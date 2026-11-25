package me.beeupdate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public final class BeeUpdate extends JavaPlugin {
    private Ledger ledger;
    private YamlConfiguration mobsYml, shopYml, messagesYml;
    private AfkTracker afk;
    private Items items;
    private BeeMobs mobs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String f : new String[]{"mobs.yml", "shop.yml", "messages.yml"})
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        try {
            loadSettings();
            ledger = new Ledger(getDataFolder().toPath().resolve("balances.tsv"),
                    getConfig().getLong("economy.max-balance", 1_000_000_000L));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Cannot load data/config. Plugin disabled; files were not modified.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        afk = new AfkTracker(this);
        items = new Items(this);
        mobs = new BeeMobs(this);
        Shop shop = new Shop(this);
        Commands commands = new Commands(this, shop);

        var pm = getServer().getPluginManager();
        pm.registerEvents(afk, this);
        pm.registerEvents(mobs, this);
        pm.registerEvents(shop, this);
        pm.registerEvents(new ItemListener(this), this);
        for (String name : new String[]{"honey", "bee", "beeshop"}) {
            PluginCommand c = getCommand(name);
            if (c == null) throw new IllegalStateException("Missing command " + name);
            c.setExecutor(commands);
            c.setTabCompleter(commands);
        }
        long period = Math.max(1, getConfig().getInt("economy.save-interval-seconds", 2)) * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::saveLedger, period, period);
        getServer().getOnlinePlayers().forEach(p -> ledger.touch(p.getUniqueId(), p.getName()));
        mobs.start();
    }

    @Override
    public void onDisable() {
        // Shop inventories must not outlive the plugin, or their icons could be taken.
        for (Player p : getServer().getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Shop.Menu) p.closeInventory();
        if (mobs != null) mobs.removeAll();
        if (ledger != null) saveLedger();
    }

    private void saveLedger() {
        try {
            ledger.save();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Could not save balances", e);
        }
    }

    private YamlConfiguration load(String name) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.load(new File(getDataFolder(), name)); // throws on invalid YAML
        return y;
    }

    private void loadSettings() throws Exception {
        load("config.yml"); // validate before reloadConfig, which swallows errors
        YamlConfiguration m = load("mobs.yml"), s = load("shop.yml"), t = load("messages.yml");
        reloadConfig();
        mobsYml = m;
        shopYml = s;
        messagesYml = t;
    }

    public void reloadAll() throws Exception {
        loadSettings();
        mobs.start();
    }

    public Ledger ledger() { return ledger; }
    public AfkTracker afk() { return afk; }
    public Items items() { return items; }
    public BeeMobs mobs() { return mobs; }
    public YamlConfiguration mobsYml() { return mobsYml; }
    public YamlConfiguration shopYml() { return shopYml; }

    public long price(String id) {
        return Math.max(0, shopYml.getLong("prices." + id, 0));
    }

    public static Component text(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Sends a message from messages.yml; args are placeholder/value pairs. */
    public void msg(CommandSender to, String key, Object... args) {
        String s = messagesYml.getString("prefix", "") + messagesYml.getString(key, key);
        for (int i = 0; i + 1 < args.length; i += 2) s = s.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        to.sendMessage(text(s));
    }
}
