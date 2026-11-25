package me.beeupdate;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.function.Consumer;

public final class Shop implements Listener {
    /** Identifies our inventories and maps slots to actions. */
    static final class Menu implements InventoryHolder {
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private final BeeUpdate plugin;

    Shop(BeeUpdate plugin) { this.plugin = plugin; }

    private ItemStack icon(Material m, String name, String... lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        meta.customName(BeeUpdate.text(name));
        meta.lore(Arrays.stream(lore).map(BeeUpdate::text).toList());
        s.setItemMeta(meta);
        return s;
    }

    private Menu menu(Player p, String title) {
        Menu m = new Menu();
        m.inventory = Bukkit.createInventory(m, 54, BeeUpdate.text(title));
        for (int i = 0; i < 54; i++)
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8)
                m.inventory.setItem(i, icon((i % 2 == 0) ? Material.YELLOW_STAINED_GLASS_PANE
                        : Material.ORANGE_STAINED_GLASS_PANE, " "));
        m.inventory.setItem(4, icon(Material.HONEYCOMB, "&6&lHoney Coin",
                "&eرصيدك: " + plugin.ledger().balance(p.getUniqueId())));
        return m;
    }

    private void button(Menu m, int slot, ItemStack icon, Consumer<Player> action) {
        m.inventory.setItem(slot, icon);
        m.actions.put(slot, action);
    }

    void open(Player p) {
        Menu m = menu(p, "&6&l⬢ Bee Shop ⬢");
        button(m, 20, icon(Material.GOLDEN_SWORD, "&6Weapons"), x -> category(x, "Weapons", Items.Kind.SWORD));
        button(m, 22, icon(Material.GOLDEN_CHESTPLATE, "&6Armor"), x -> armor(x));
        button(m, 24, icon(Material.GOLDEN_PICKAXE, "&6Tools"), x -> category(x, "Tools", Items.Kind.PICKAXE));
        button(m, 30, icon(Material.HONEY_BOTTLE, "&6Special Items"), x -> category(x, "Special Items", Items.Kind.SPECIAL));
        button(m, 32, icon(Material.ANVIL, "&6Upgrades"), this::upgrades);
        p.openInventory(m.inventory);
    }

    private ItemStack priced(String id) {
        ItemStack s = plugin.items().create(id);
        ItemMeta meta = s.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>(Objects.requireNonNullElse(meta.lore(), List.of()));
        lore.add(BeeUpdate.text("&6السعر: " + plugin.price(id) + " Honey Coin"));
        meta.lore(lore);
        s.setItemMeta(meta);
        return s;
    }

    private void back(Menu m) {
        button(m, 49, icon(Material.ARROW, "&eرجوع"), this::open);
    }

    private void category(Player p, String title, Items.Kind kind) {
        Menu m = menu(p, "&6Bee Shop: " + title);
        int slot = 20;
        for (Items.Def d : plugin.items().all())
            if (d.kind() == kind) {
                button(m, slot, priced(d.id()), x -> buy(x, d.name(), d.id(), List.of(d.id())));
                slot += slot == 24 ? 5 : 1;
            }
        back(m);
        p.openInventory(m.inventory);
    }

    private void armor(Player p) {
        Menu m = menu(p, "&6Bee Shop: Armor");
        String[] pieces = {"helmet", "chestplate", "leggings", "boots"};
        for (int s = 0; s < 4; s++) {
            String set = Items.SETS[s];
            List<String> ids = Arrays.stream(pieces).map(x -> set + "_" + x).toList();
            for (int i = 0; i < 4; i++) {
                String id = ids.get(i);
                button(m, 10 + s * 9 + i, priced(id), x -> buy(x, plugin.items().def(id).name(), id, List.of(id)));
            }
            String name = Character.toUpperCase(set.charAt(0)) + set.substring(1) + " Armor Set";
            button(m, 10 + s * 9 + 5, icon(Material.CHEST, "&6" + name, "&eالطقم الكامل (4 قطع)",
                    "&6السعر: " + plugin.price(set + "_set")), x -> buy(x, name, set + "_set", ids));
        }
        back(m);
        p.openInventory(m.inventory);
    }

    private void buy(Player p, String name, String priceId, List<String> ids) {
        long price = plugin.price(priceId);
        if (price <= 0) return; // missing price: never give items for free
        long free = Arrays.stream(p.getInventory().getStorageContents())
                .filter(s -> s == null || s.getType().isAir()).count();
        if (free < ids.size()) { plugin.msg(p, "no-space"); return; }
        if (!plugin.ledger().withdraw(p.getUniqueId(), price)) { plugin.msg(p, "no-money"); return; }
        for (String id : ids) p.getInventory().addItem(plugin.items().create(id));
        plugin.msg(p, "bought", "item", name, "amount", price);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
        p.getOpenInventory().getTopInventory().setItem(4, icon(Material.HONEYCOMB, "&6&lHoney Coin",
                "&eرصيدك: " + plugin.ledger().balance(p.getUniqueId())));
    }

    private void upgrades(Player p) {
        Menu m = menu(p, "&6Bee Shop: Upgrades");
        EquipmentSlot[] slots = {EquipmentSlot.HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET};
        int max = plugin.getConfig().getInt("upgrades.max-level", 5);
        for (int i = 0; i < slots.length; i++) {
            EquipmentSlot where = slots[i];
            ItemStack s = p.getInventory().getItem(where);
            Items.Def d = plugin.items().of(s);
            if (d == null || d.kind() == Items.Kind.SPECIAL) {
                m.inventory.setItem(20 + i, icon(Material.GRAY_DYE, "&7" + where.name(), "&8لا يوجد عنصر قابل للترقية"));
                continue;
            }
            int level = plugin.items().level(s);
            ItemStack show = s.clone();
            ItemMeta meta = show.getItemMeta();
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(Objects.requireNonNullElse(meta.lore(), List.of()));
            lore.add(BeeUpdate.text(level >= max ? "&aالمستوى الأقصى" : "&6ترقية: " + upgradePrice(d, level) + " Honey Coin"));
            meta.lore(lore);
            show.setItemMeta(meta);
            button(m, 20 + i, show, x -> upgrade(x, where));
        }
        back(m);
        p.openInventory(m.inventory);
    }

    private long upgradePrice(Items.Def d, int level) {
        return Math.max(1, plugin.getConfig().getLong("upgrades.base-price", 200)) * d.tier() * (level + 1);
    }

    private void upgrade(Player p, EquipmentSlot where) {
        ItemStack s = p.getInventory().getItem(where); // re-read at click time
        Items.Def d = plugin.items().of(s);
        if (d == null || d.kind() == Items.Kind.SPECIAL
                || plugin.items().level(s) >= plugin.getConfig().getInt("upgrades.max-level", 5)) {
            plugin.msg(p, "cannot-upgrade");
            return;
        }
        int level = plugin.items().level(s);
        if (!plugin.ledger().withdraw(p.getUniqueId(), upgradePrice(d, level))) { plugin.msg(p, "no-money"); return; }
        plugin.items().setLevel(s, d, level + 1);
        p.getInventory().setItem(where, s);
        plugin.msg(p, "upgraded", "level", level + 1);
        upgrades(p);
    }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu m)) return;
        e.setCancelled(true); // nothing moves while a shop is open
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != m.inventory
                || !(e.isLeftClick() || e.isRightClick())) return;
        Consumer<Player> action = m.actions.get(e.getSlot());
        if (action == null || !p.hasPermission("beeupdate.shop")) return;
        // Opening or changing inventories inside a click event is unsafe: run next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.getOpenInventory().getTopInventory().getHolder(false) == m) action.accept(p);
        });
    }

    @EventHandler
    public void drag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }
}
