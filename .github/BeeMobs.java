package me.beeupdate;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class BeeMobs implements Listener {
    record Type(String id, String name, String color, long reward, double health, double damage,
                double speed, float scale, int weight, String time, double aggro, List<String> biomes) {}

    private final BeeUpdate plugin;
    private final NamespacedKey typeKey, naturalKey, rewardedKey, displayKey;
    private final Map<UUID, Bee> alive = new HashMap<>();
    private final Map<UUID, ItemDisplay> displays = new HashMap<>();
    private final Map<UUID, ItemDisplay[]> wings = new HashMap<>();
    private long animationStep;
    private final Map<UUID, Long> lastAttack = new HashMap<>();
    private final Map<UUID, Long> repelled = new HashMap<>();
    private final RewardGuard guard = new RewardGuard();
    private final Map<String, Type> types = new LinkedHashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    BeeMobs(BeeUpdate plugin) {
        this.plugin = plugin;
        typeKey = new NamespacedKey(plugin, "bee_type");
        naturalKey = new NamespacedKey(plugin, "natural");
        rewardedKey = new NamespacedKey(plugin, "rewarded");
        displayKey = new NamespacedKey(plugin, "bee_display");
    }

    /** (Re)reads mobs.yml and restarts the tasks. Living bees are kept. */
    void start() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        types.clear();
        ConfigurationSection root = plugin.mobsYml().getConfigurationSection("mobs");
        if (root != null) for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            types.put(id, new Type(id, s.getString("name", id), s.getString("color", "yellow"),
                    Math.max(0, s.getLong("reward")), Math.max(1, s.getDouble("health", 10)),
                    Math.max(0, s.getDouble("damage", 2)), s.getDouble("speed", 0.3),
                    (float) s.getDouble("scale", 1), Math.max(0, s.getInt("weight", 1)),
                    s.getString("time", "ANY").toUpperCase(Locale.ROOT), s.getDouble("aggro-range", 14),
                    s.getStringList("biomes")));
        }
        var sch = plugin.getServer().getScheduler();
        long interval = Math.max(20, plugin.getConfig().getInt("spawning.interval-ticks", 200));
        tasks.add(sch.runTaskTimer(plugin, this::spawnCycle, interval, interval));
        tasks.add(sch.runTaskTimer(plugin, this::think, 20, 20));
        tasks.add(sch.runTaskTimer(plugin, this::animateWings, 2, 2));
    }

    Set<String> typeIds() { return types.keySet(); }

    void repel(Player p, long millis) { repelled.put(p.getUniqueId(), System.currentTimeMillis() + millis); }

    boolean isCustom(Entity e) { return e.getPersistentDataContainer().has(typeKey); }

    private boolean isProtected(Location l) {
        var c = plugin.getConfig();
        Location s = l.getWorld().getSpawnLocation();
        double r = c.getDouble("spawning.world-spawn-protection-radius", 100);
        if (Math.hypot(l.getX() - s.getX(), l.getZ() - s.getZ()) <= r) return true;
        for (Map<?, ?> m : c.getMapList("spawning.protected-regions")) {
            if (!l.getWorld().getName().equals(String.valueOf(m.get("world")))) continue;
            if (m.get("min-x") instanceof Number a && m.get("max-x") instanceof Number b
                    && m.get("min-z") instanceof Number d && m.get("max-z") instanceof Number e
                    && l.getX() >= a.doubleValue() && l.getX() <= b.doubleValue()
                    && l.getZ() >= d.doubleValue() && l.getZ() <= e.doubleValue()) return true;
        }
        return false;
    }

    private void spawnCycle() {
        var c = plugin.getConfig();
        if (!c.getBoolean("spawning.enabled", true) || types.isEmpty()) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long now = System.currentTimeMillis();
        double nearby = c.getDouble("spawning.nearby-radius", 72);
        double min = Math.max(8, c.getDouble("spawning.min-distance", 20));
        double max = Math.max(min + 1, c.getDouble("spawning.max-distance", 48));
        int maxTotal = Math.max(1, c.getInt("spawning.max-total", 180));
        int maxPerPlayer = Math.max(1, c.getInt("spawning.max-per-player", 14));
        int attempts = Math.max(1, c.getInt("spawning.attempts-per-player", 4));
        List<String> worlds = c.getStringList("spawning.worlds");

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (alive.size() >= maxTotal) return;
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL
                    || (!worlds.isEmpty() && !worlds.contains(w.getName()))
                    || w.getDifficulty() == Difficulty.PEACEFUL
                    || (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE)
                    || plugin.afk().isAfk(p) || repelled.getOrDefault(p.getUniqueId(), 0L) > now
                    || rnd.nextDouble() > c.getDouble("spawning.chance-per-player", 0.98)) continue;

            long close = alive.values().stream().filter(b -> b.getWorld() == w
                    && b.getLocation().distanceSquared(p.getLocation()) < nearby * nearby).count();
            for (int i = 0; i < attempts && close < maxPerPlayer && alive.size() < maxTotal; i++) {
                Location at = findSpawnLocation(p, rnd, min, max, now);
                if (at == null) continue;
                Type t = pick(w, at);
                if (t != null && spawn(t, at, true)) close++;
            }
        }
    }

    /** Finds safe surface or underground air in an already-loaded chunk. */
    private Location findSpawnLocation(Player p, ThreadLocalRandom rnd, double min, double max, long now) {
        World w = p.getWorld();
        double angle = rnd.nextDouble(Math.PI * 2);
        double dist = rnd.nextDouble(min, max);
        int x = (int) Math.floor(p.getLocation().getX() + Math.cos(angle) * dist);
        int z = (int) Math.floor(p.getLocation().getZ() + Math.sin(angle) * dist);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;

        boolean cave = rnd.nextDouble() < plugin.getConfig().getDouble("spawning.cave-chance", 0.55);
        Block floor = null;
        Block feet = null;
        Block head = null;
        if (cave) {
            int playerY = p.getLocation().getBlockY();
            int low = Math.max(w.getMinHeight() + 5, playerY - 26);
            int high = Math.min(w.getMaxHeight() - 3, playerY + 8);
            if (high <= low) return null;
            for (int n = 0; n < 12; n++) {
                int y = rnd.nextInt(low, high + 1);
                Block f = w.getBlockAt(x, y - 1, z);
                Block a = w.getBlockAt(x, y, z);
                Block h = w.getBlockAt(x, y + 1, z);
                if (f.getType().isSolid() && a.isPassable() && h.isPassable()
                        && !a.isLiquid() && !h.isLiquid() && a.getLightFromSky() == 0) {
                    floor = f; feet = a; head = h; break;
                }
            }
        } else {
            floor = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            feet = w.getBlockAt(x, floor.getY() + 1, z);
            head = w.getBlockAt(x, floor.getY() + 2, z);
            if (!floor.getType().isSolid() || !feet.isPassable() || !head.isPassable()
                    || feet.isLiquid() || head.isLiquid()) return null;
        }
        if (floor == null || feet == null || head == null) return null;

        Location at = feet.getLocation().add(0.5, 0.05, 0.5);
        if (!w.getWorldBorder().isInside(at) || isProtected(at)) return null;
        double afkRadius = plugin.getConfig().getDouble("afk.block-radius", 64);
        for (Player other : w.getPlayers()) {
            double d = other.getLocation().distanceSquared(at);
            if (d < min * min || (d < afkRadius * afkRadius
                    && (plugin.afk().isAfk(other) || repelled.getOrDefault(other.getUniqueId(), 0L) > now)))
                return null;
        }
        return at;
    }

    private Type pick(World w, Location at) {
        boolean night = w.getTime() >= 13000 && w.getTime() < 23000;
        String biome = at.getBlock().getBiome().getKey().getKey();
        List<Type> ok = types.values().stream().filter(t -> t.weight() > 0
                && (t.time().equals("ANY") || t.time().equals(night ? "NIGHT" : "DAY"))
                && (t.biomes().isEmpty() || t.biomes().contains(biome))).toList();
        int total = ok.stream().mapToInt(Type::weight).sum();
        if (total <= 0) return null;
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Type t : ok) if ((roll -= t.weight()) < 0) return t;
        return null;
    }

    boolean spawn(String id, Location at) {
        Type t = types.get(id);
        return t != null && spawn(t, at, false);
    }

    private boolean spawn(Type t, Location at, boolean natural) {
        boolean models = plugin.getConfig().getBoolean("models.enabled", true);
        Bee bee = at.getWorld().spawn(at, Bee.class, CreatureSpawnEvent.SpawnReason.CUSTOM, b -> {
            var pdc = b.getPersistentDataContainer();
            pdc.set(typeKey, PersistentDataType.STRING, t.id());
            pdc.set(naturalKey, PersistentDataType.BOOLEAN, natural);
            b.setPersistent(false); // never saved to disk: no duplicates after restart/chunk reload
            b.setAdult();
            b.setAgeLock(true);
            b.setBreed(false);
            b.setCannotEnterHiveTicks(Integer.MAX_VALUE);
            NamedTextColor color = NamedTextColor.NAMES.value(t.color());
            b.customName(Component.text(t.name(), color == null ? NamedTextColor.YELLOW : color));
            b.setCustomNameVisible(true);
            attribute(b, Attribute.MAX_HEALTH, t.health());
            b.setHealth(Math.min(t.health(), b.getAttribute(Attribute.MAX_HEALTH).getValue()));
            attribute(b, Attribute.ATTACK_DAMAGE, t.damage());
            attribute(b, Attribute.MOVEMENT_SPEED, t.speed());
            attribute(b, Attribute.FLYING_SPEED, t.speed() * 2);
            attribute(b, Attribute.SCALE, t.scale());
            if (models) b.setInvisible(true);
        });
        if (!bee.isValid()) return false; // cancelled by a protection plugin
        alive.put(bee.getUniqueId(), bee);
        if (models) attachModel(bee, t);
        return true;
    }

    private void attribute(LivingEntity e, Attribute a, double value) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(value);
    }

    private ItemDisplay createDisplay(Bee bee, Type t, String model, float y, Quaternionf rotation) {
        ItemStack icon = new ItemStack(Material.PAPER);
        icon.editMeta(m -> m.setItemModel(new NamespacedKey("beeupdate", model)));
        ItemDisplay d = bee.getWorld().spawn(bee.getLocation(), ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.getPersistentDataContainer().set(displayKey, PersistentDataType.BOOLEAN, true);
            x.setItemStack(icon);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            x.setInterpolationDuration(2);
            x.setTransformation(new Transformation(new Vector3f(0, y, 0), rotation,
                    new Vector3f(t.scale(), t.scale(), t.scale()), new Quaternionf()));
        });
        bee.addPassenger(d);
        return d;
    }

    private void attachModel(Bee bee, Type t) {
        float y = (float) plugin.getConfig().getDouble("models.y-offset", -0.35) * t.scale();
        String base = "mob/" + t.id();
        displays.put(bee.getUniqueId(), createDisplay(bee, t, base, y, new Quaternionf()));
        ItemDisplay left = createDisplay(bee, t, base + "_wing_left", y, new Quaternionf());
        ItemDisplay right = createDisplay(bee, t, base + "_wing_right", y, new Quaternionf());
        wings.put(bee.getUniqueId(), new ItemDisplay[]{left, right});
    }

    /** Animate only the detached wing displays; the body never receives per-tick rotations. */
    private void animateWings() {
        animationStep++;
        for (Map.Entry<UUID, ItemDisplay[]> entry : new ArrayList<>(wings.entrySet())) {
            UUID id = entry.getKey();
            Bee bee = alive.get(id);
            ItemDisplay[] pair = entry.getValue();
            if (bee == null || !bee.isValid() || !pair[0].isValid() || !pair[1].isValid()) continue;
            Type t = types.get(bee.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING));
            if (t == null) continue;
            float phase = (float) ((id.hashCode() & 255) * 0.02454369);
            float flap = (float) Math.sin(animationStep * 0.55 + phase) * 0.72f;
            float y = (float) plugin.getConfig().getDouble("models.y-offset", -0.35) * t.scale();
            Vector3f scale = new Vector3f(t.scale(), t.scale(), t.scale());
            pair[0].setTransformation(new Transformation(new Vector3f(0, y, 0),
                    new Quaternionf().rotationZ(flap), scale, new Quaternionf()));
            pair[1].setTransformation(new Transformation(new Vector3f(0, y, 0),
                    new Quaternionf().rotationZ(-flap), scale, new Quaternionf()));
        }
    }

    /** Once a second: despawn far bees, drop dead references, and make bees hostile. */
    private void think() {
        double despawn = plugin.getConfig().getDouble("spawning.despawn-distance", 96);
        for (Bee b : new ArrayList<>(alive.values())) {
            if (!b.isValid()) { discard(b); continue; }
            Player target = null;
            double best = Double.MAX_VALUE;
            for (Player p : b.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                double d = p.getLocation().distanceSquared(b.getLocation());
                if (d < best) { best = d; target = p; }
            }
            if (target == null || best > despawn * despawn) { discard(b); continue; }
            Type t = types.get(b.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING));
            b.setHasStung(false);
            if (t != null && best <= t.aggro() * t.aggro() && !plugin.afk().isAfk(target)
                    && (target.getGameMode() == GameMode.SURVIVAL || target.getGameMode() == GameMode.ADVENTURE)
                    && b.hasLineOfSight(target)) {
                b.setAnger(400);
                b.setTarget(target);
            }
        }
    }

    private void discard(Bee b) {
        UUID id = b.getUniqueId();
        alive.remove(id);
        lastAttack.remove(id);
        guard.forget(id);
        ItemDisplay d = displays.remove(id);
        if (d != null) d.remove();
        ItemDisplay[] pair = wings.remove(id);
        if (pair != null) for (ItemDisplay wing : pair) wing.remove();
        if (!b.isDead()) b.remove();
    }

    void removeAll() { new ArrayList<>(alive.values()).forEach(this::discard); }

    @EventHandler(ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Bee b) || !isCustom(b)) return;
        long now = System.currentTimeMillis();
        if (now - lastAttack.getOrDefault(b.getUniqueId(), 0L) < 1500) { e.setCancelled(true); return; }
        lastAttack.put(b.getUniqueId(), now);
        plugin.getServer().getScheduler().runTask(plugin, () -> { if (b.isValid()) b.setHasStung(false); });
    }

    /** Custom bees do not poison: keeps difficulty a bit below medium. */
    @EventHandler(ignoreCancelled = true)
    public void sting(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getCause() != EntityPotionEffectEvent.Cause.ATTACK
                || e.getNewEffect() == null || !e.getNewEffect().getType().equals(PotionEffectType.POISON)) return;
        if (p.getNearbyEntities(4, 4, 4).stream().anyMatch(this::isCustom)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hurt(EntityDamageByEntityEvent e) {
        if (!isCustom(e.getEntity()) || !(e.getEntity() instanceof LivingEntity victim)) return;
        Player p = attacker(e);
        if (p != null) guard.recordDamage(victim.getUniqueId(), p.getUniqueId(),
                Math.min(e.getFinalDamage(), victim.getHealth()));
    }

    private Player attacker(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) return p;
        if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) // a cancelled death pays nothing
    public void death(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (!isCustom(dead)) return;
        e.getDrops().clear();
        var pdc = dead.getPersistentDataContainer();
        Type t = types.get(pdc.get(typeKey, PersistentDataType.STRING));
        UUID id = dead.getUniqueId();
        boolean eligible = !pdc.has(rewardedKey)
                && (pdc.getOrDefault(naturalKey, PersistentDataType.BOOLEAN, false)
                    || plugin.getConfig().getBoolean("rewards.reward-command-spawned", false));
        pdc.set(rewardedKey, PersistentDataType.BOOLEAN, true); // paid at most once
        // The killing blow itself must come from the player (no lava/fall/suffocation farms).
        if (eligible && t != null && dead.getLastDamageCause() instanceof EntityDamageByEntityEvent last) {
            Player p = attacker(last);
            if (p != null && !plugin.afk().isAfk(p)
                    && guard.share(id, p.getUniqueId(), t.health()) >= plugin.getConfig().getDouble("rewards.min-damage-share", 0.5)) {
                if (!guard.tryRate(p.getUniqueId(), System.currentTimeMillis(),
                        plugin.getConfig().getInt("rewards.max-kills-per-minute", 15))) {
                    plugin.msg(p, "reward-limit");
                } else if (plugin.ledger().deposit(p.getUniqueId(), t.reward())) {
                    plugin.msg(p, "reward", "amount", t.reward(), "mob", t.name());
                }
            }
        }
        guard.forget(id);
        alive.remove(id);
        lastAttack.remove(id);
        ItemDisplay d = displays.remove(id);
        if (d != null) d.remove();
        ItemDisplay[] pair = wings.remove(id);
        if (pair != null) for (ItemDisplay wing : pair) wing.remove();
    }

    @EventHandler public void removed(EntityRemoveEvent e) {
        UUID id = e.getEntity().getUniqueId();
        ItemDisplay d = displays.remove(id);
        if (d != null) plugin.getServer().getScheduler().runTask(plugin, d::remove);
        ItemDisplay[] pair = wings.remove(id);
        if (pair != null) for (ItemDisplay wing : pair)
            plugin.getServer().getScheduler().runTask(plugin, wing::remove);
    }

    private boolean isTrackedDisplay(Entity entity) {
        if (!(entity instanceof ItemDisplay display)) return false;
        if (displays.containsValue(display)) return true;
        for (ItemDisplay[] pair : wings.values())
            if (pair[0] == display || pair[1] == display) return true;
        return false;
    }

    /** Safety net for leftovers from a crash. */
    @EventHandler public void loaded(EntitiesLoadEvent e) {
        for (Entity x : e.getEntities())
            if ((x.getPersistentDataContainer().has(displayKey) && !isTrackedDisplay(x))
                    || (isCustom(x) && !alive.containsKey(x.getUniqueId()))) x.remove();
    }

    // Custom bees are monsters, not farm animals.
    @EventHandler public void interact(PlayerInteractEntityEvent e) { if (isCustom(e.getRightClicked())) e.setCancelled(true); }
    @EventHandler public void enter(EntityEnterBlockEvent e) { if (isCustom(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void block(EntityChangeBlockEvent e) { if (isCustom(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void form(EntityBlockFormEvent e) { if (isCustom(e.getEntity())) e.setCancelled(true); }
    @EventHandler public void transform(EntityTransformEvent e) { if (isCustom(e.getEntity())) e.setCancelled(true); }
}
