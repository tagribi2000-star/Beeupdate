package me.beeupdate;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class ItemListener implements Listener {
    private final BeeUpdate plugin;
    private final Items items;
    private final NamespacedKey bombKey;
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> splashing = new HashSet<>(), mining = new HashSet<>();

    ItemListener(BeeUpdate plugin) {
        this.plugin = plugin;
        this.items = plugin.items();
        this.bombKey = new NamespacedKey(plugin, "honey_bomb");
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::armorTick, 40, 40);
        plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> plugin.getServer().getOnlinePlayers().forEach(this::dedupe), 600, 600);
    }

    private static void effect(LivingEntity e, PotionEffectType type, int ticks, int amplifier) {
        e.addPotionEffect(new PotionEffect(type, ticks, amplifier, true, false, true));
    }

    private static void heal(LivingEntity e, double amount) {
        var max = e.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && !e.isDead()) e.setHealth(Math.min(max.getValue(), e.getHealth() + amount));
    }

    /** Number of worn pieces of each set tier (index 1..4). */
    private int[] worn(Player p) {
        int[] n = new int[5];
        for (ItemStack s : p.getInventory().getArmorContents()) {
            Items.Def d = items.of(s);
            if (d != null && d.armor()) n[d.tier()]++;
        }
        return n;
    }

    private void armorTick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            int[] n = worn(p);
            if (n[1] >= 2) effect(p, PotionEffectType.HASTE, 60, 0);
            if (n[3] >= 2 && p.getHealth() < 10) effect(p, PotionEffectType.REGENERATION, 60, 0);
            // Long duration, so absorption hearts are not refilled every tick of this task.
            if (n[4] >= 2 && !p.hasPotionEffect(PotionEffectType.ABSORPTION))
                effect(p, PotionEffectType.ABSORPTION, 1200, 0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void combat(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player victim && e.getDamager() instanceof LivingEntity attacker
                && !(attacker instanceof Player && splashing.contains(attacker.getUniqueId())) // no reflect ping-pong
                && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && worn(victim)[2] >= 2) {
            double reflect = Math.min(4, e.getDamage() * 0.25);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!attacker.isValid() || attacker.isDead() || !victim.isOnline()) return;
                // Credit the wearer, so a reflected killing blow still pays; skip sword abilities.
                splashing.add(victim.getUniqueId());
                try {
                    attacker.damage(reflect, victim);
                } finally {
                    splashing.remove(victim.getUniqueId());
                }
            });
        }
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity target)
                || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || splashing.contains(p.getUniqueId())) return;
        Items.Def d = items.of(p.getInventory().getItemInMainHand());
        // Weak spam-clicks do not trigger abilities.
        if (d == null || d.kind() != Items.Kind.SWORD || p.getAttackCooldown() < 0.8f) return;
        switch (d.tier()) {
            case 1 -> effect(target, PotionEffectType.SLOWNESS, 40, 0);
            case 2 -> { if (ThreadLocalRandom.current().nextDouble() < 0.25) e.setDamage(e.getDamage() + 4); }
            case 3 -> heal(p, Math.min(e.getFinalDamage(), target.getHealth()) * 0.15);
            case 4 -> {
                splashing.add(p.getUniqueId());
                try {
                    for (Entity near : target.getNearbyEntities(3, 2, 3))
                        if ((near instanceof Monster || plugin.mobs().isCustom(near)) && near != target) {
                            ((LivingEntity) near).damage(4, p); // fires events: protections apply
                            effect((LivingEntity) near, PotionEffectType.SLOWNESS, 40, 1);
                        }
                } finally {
                    splashing.remove(p.getUniqueId());
                }
            }
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void poison(EntityPotionEffectEvent e) {
        if (e.getEntity() instanceof Player p && e.getNewEffect() != null
                && e.getNewEffect().getType().equals(PotionEffectType.POISON) && worn(p)[4] >= 2) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void mine(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        Items.Def d = items.of(p.getInventory().getItemInMainHand());
        if (d == null || d.kind() != Items.Kind.PICKAXE) return;
        if (d.tier() == 1 && ThreadLocalRandom.current().nextDouble() < 0.08) effect(p, PotionEffectType.HASTE, 100, 1);
        if (d.tier() == 2) e.setExpToDrop(e.getExpToDrop() * 2);
        if (d.tier() == 4 && !p.isSneaking() && !mining.contains(p.getUniqueId())) area(p, e.getBlock());
    }

    private void area(Player p, Block center) {
        BlockFace face = p.getTargetBlockFace(6);
        if (face == null || !Tag.MINEABLE_PICKAXE.isTagged(center.getType())) return;
        float hardness = center.getType().getHardness();
        mining.add(p.getUniqueId());
        try {
            for (int a = -1; a <= 1; a++)
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) continue;
                    Block x = face.getModY() != 0 ? center.getRelative(a, 0, b)
                            : face.getModX() != 0 ? center.getRelative(0, a, b) : center.getRelative(a, b, 0);
                    float h = x.getType().getHardness();
                    Items.Def held = items.of(p.getInventory().getItemInMainHand());
                    if (held == null || held.tier() != 4) return; // tool broke
                    if (h >= 0 && h <= hardness + 1 && Tag.MINEABLE_PICKAXE.isTagged(x.getType())
                            && !(x.getState() instanceof TileState))
                        p.breakBlock(x); // normal break: events, protections and durability apply
                }
        } finally {
            mining.remove(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void magnet(BlockDropItemEvent e) {
        Items.Def d = items.of(e.getPlayer().getInventory().getItemInMainHand());
        if (d == null || d.kind() != Items.Kind.PICKAXE || d.tier() != 3
                || e.getBlockState() instanceof TileState) return;
        e.getItems().removeIf(item -> {
            var left = e.getPlayer().getInventory().addItem(item.getItemStack());
            if (left.isEmpty()) return true;
            item.setItemStack(left.values().iterator().next());
            return false;
        });
    }

    @EventHandler public void drink(PlayerItemConsumeEvent e) {
        Items.Def d = items.of(e.getItem());
        if (d == null || !d.id().equals("royal_jelly")) return;
        heal(e.getPlayer(), 8);
        effect(e.getPlayer(), PotionEffectType.REGENERATION, 200, 0);
        effect(e.getPlayer(), PotionEffectType.STRENGTH, 600, 0);
    }

    @EventHandler(ignoreCancelled = true) public void throwBomb(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof Snowball ball)) return;
        Items.Def d = items.of(ball.getItem());
        if (d != null && d.id().equals("honey_bomb"))
            ball.getPersistentDataContainer().set(bombKey, PersistentDataType.BOOLEAN, true);
    }

    @EventHandler public void bombHit(ProjectileHitEvent e) {
        if (!e.getEntity().getPersistentDataContainer().has(bombKey)) return;
        Location at = e.getEntity().getLocation();
        at.getWorld().spawnParticle(Particle.WAX_ON, at, 60, 2, 1, 2);
        for (Entity x : e.getEntity().getNearbyEntities(4, 3, 4))
            if (x instanceof Monster || plugin.mobs().isCustom(x)) effect((LivingEntity) x, PotionEffectType.SLOWNESS, 100, 2);
    }

    @EventHandler public void use(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack s = e.getItem();
        Items.Def d = items.of(s);
        if (d == null) return;
        Player p = e.getPlayer();
        if (d.id().equals("honey_bomb")) {
            e.setCancelled(true);
            Snowball bomb = p.launchProjectile(Snowball.class);
            bomb.setItem(new ItemStack(Material.SNOWBALL));
            bomb.getPersistentDataContainer().set(bombKey, PersistentDataType.BOOLEAN, true);
            bomb.setVelocity(p.getEyeLocation().getDirection().multiply(1.4));
            ItemStack hand = p.getInventory().getItem(e.getHand());
            if (hand != null && !hand.getType().isAir()) {
                hand.setAmount(hand.getAmount() - 1);
                p.getInventory().setItem(e.getHand(), hand.getAmount() <= 0 ? null : hand);
            }
            p.swingHand(e.getHand());
            p.playSound(p.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 0.8f);
            return;
        }
        if (!d.id().equals("bee_repellent")) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now < cooldown.getOrDefault(p.getUniqueId(), 0L)) { plugin.msg(p, "cooldown"); return; }
        cooldown.put(p.getUniqueId(), now + 5000);
        plugin.mobs().repel(p, 600_000);
        ItemStack held = p.getInventory().getItem(e.getHand());
        held.setAmount(held.getAmount() - 1);
        p.getInventory().setItem(e.getHand(), held);
        plugin.msg(p, "repellent");
    }

    // ---- Anti-abuse: custom items cannot be turned into or merged with other items. ----
    private boolean anyCustom(ItemStack... stacks) {
        for (ItemStack s : stacks) if (items.of(s) != null) return true;
        return false;
    }

    @EventHandler public void craft(PrepareItemCraftEvent e) {
        if (anyCustom(e.getInventory().getMatrix())) e.getInventory().setResult(null);
    }
    @EventHandler public void crafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (e.getBlock().getState() instanceof org.bukkit.block.Crafter c && anyCustom(c.getInventory().getContents()))
            e.setCancelled(true);
    }
    @EventHandler public void smith(PrepareSmithingEvent e) {
        if (anyCustom(e.getInventory().getContents())) e.setResult(null);
    }
    @EventHandler public void anvil(PrepareAnvilEvent e) {
        // Enchanted books are fine; combining two custom items is not.
        if (items.of(e.getInventory().getItem(1)) != null) e.setResult(null);
    }
    @EventHandler public void grindstone(PrepareGrindstoneEvent e) {
        ItemStack a = e.getInventory().getItem(0), b = e.getInventory().getItem(1);
        if (a != null && b != null && anyCustom(a, b)) e.setResult(null);
    }

    private void refreshArmor(Player p) {
        for (ItemStack stack : p.getInventory().getContents()) items.refreshVisual(stack);
        for (ItemStack stack : p.getInventory().getArmorContents()) items.refreshVisual(stack);
        for (ItemStack stack : p.getEnderChest().getContents()) items.refreshVisual(stack);
    }

    @EventHandler public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        refreshArmor(p);
        dedupe(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> refreshArmor(p));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void inventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> refreshArmor(p));
    }

    /** Two stacks with one unique id in the same player's storage means a copy was made. */
    private void dedupe(Player p) {
        if (!plugin.getConfig().getBoolean("anti-dupe.remove-duplicates", true)
                || p.getGameMode() == GameMode.CREATIVE) return;
        Set<String> seen = new HashSet<>();
        for (var inv : List.of(p.getInventory(), p.getEnderChest()))
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (items.of(s) == null) continue;
                String uid = items.uid(s);
                if (uid != null && !seen.add(uid)) {
                    inv.setItem(i, null);
                    plugin.getLogger().warning("Removed duplicate item " + uid + " from " + p.getName());
                    plugin.msg(p, "dupe-removed");
                }
            }
    }
}
