package me.beeupdate;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class Items {
    enum Kind { SWORD, PICKAXE, HELMET, CHESTPLATE, LEGGINGS, BOOTS, SPECIAL }

    /** stat = attack damage (swords), mining efficiency (picks) or armor points. */
    record Def(String id, String name, Kind kind, int tier, Material material, double stat,
               double toughness, int durability, String ability) {
        boolean armor() { return kind.ordinal() >= Kind.HELMET.ordinal() && kind != Kind.SPECIAL; }
    }

    static final String[] SETS = {"worker", "warrior", "royal", "queen"};

    private final NamespacedKey idKey, levelKey, uidKey;
    private final BeeUpdate plugin;
    private final Map<String, Def> defs = new LinkedHashMap<>();

    Items(BeeUpdate plugin) {
        this.plugin = plugin;
        idKey = new NamespacedKey(plugin, "item_id");
        levelKey = new NamespacedKey(plugin, "level");
        uidKey = new NamespacedKey(plugin, "uid");

        sword("honey_sword", "Honey Sword", 1, Material.DIAMOND_SWORD, 7.5, 1700, "يبطئ الهدف");
        sword("warrior_blade", "Warrior Blade", 2, Material.DIAMOND_SWORD, 9, 2100, "25% ضربة +4 ضرر");
        sword("royal_sword", "Royal Sword", 3, Material.NETHERITE_SWORD, 10.5, 2600, "يمتص 15% من الضرر كصحة");
        sword("queens_blade", "Queen's Blade", 4, Material.NETHERITE_SWORD, 12, 3200, "ضربة واسعة على الوحوش القريبة");
        pick("honey_pickaxe", "Honey Pickaxe", 1, Material.DIAMOND_PICKAXE, 2, 1700, "فرصة Haste أثناء التعدين");
        pick("worker_pickaxe", "Worker Pickaxe", 2, Material.DIAMOND_PICKAXE, 6, 2100, "خبرة مضاعفة من الخامات");
        pick("royal_pickaxe", "Royal Pickaxe", 3, Material.NETHERITE_PICKAXE, 10, 2600, "الغنائم تدخل حقيبتك مباشرة");
        pick("queens_pickaxe", "Queen's Pickaxe", 4, Material.NETHERITE_PICKAXE, 16, 3200, "تعدين 3×3 (انحنِ لإيقافه)");

        String[] names = {"Worker", "Warrior", "Royal", "Queen"};
        String[] perks = {"قطعتان+: Haste", "قطعتان+: عكس جزء من الضرر",
                "قطعتان+: Regeneration عند ضعف الصحة", "قطعتان+: مناعة السم و Absorption"};
        double[][] armor = {{3, 7, 5, 3}, {3, 8, 6, 3}, {4, 9, 7, 3}, {4, 10, 8, 4}};
        double[] tough = {1, 2.5, 3.5, 4};
        String[] pieces = {"Helmet", "Chestplate", "Leggings", "Boots"};
        for (int s = 0; s < 4; s++)
            for (int i = 0; i < 4; i++) {
                Kind k = Kind.valueOf(pieces[i].toUpperCase(Locale.ROOT));
                Material m = Material.valueOf((s < 2 ? "DIAMOND_" : "NETHERITE_") + k.name());
                add(new Def(SETS[s] + "_" + k.name().toLowerCase(Locale.ROOT), names[s] + " " + pieces[i],
                        k, s + 1, m, armor[s][i], tough[s], 500 + s * 150, perks[s]));
            }
        add(new Def("royal_jelly", "Royal Jelly", Kind.SPECIAL, 1, Material.HONEY_BOTTLE, 0, 0, 0, "اشربه: شفاء وقوة"));
        add(new Def("honey_bomb", "Honey Bomb", Kind.SPECIAL, 1, Material.SNOWBALL, 0, 0, 0, "ارمها: تبطئ الوحوش"));
        add(new Def("bee_repellent", "Bee Repellent", Kind.SPECIAL, 1, Material.PAPER, 0, 0, 0, "كليك يمين: يمنع النحل 10 دقائق"));
    }

    private void sword(String id, String name, int tier, Material m, double dmg, int dur, String ability) {
        add(new Def(id, name, Kind.SWORD, tier, m, dmg, 0, dur, ability));
    }

    private void pick(String id, String name, int tier, Material m, double eff, int dur, String ability) {
        add(new Def(id, name, Kind.PICKAXE, tier, m, eff, 0, dur, ability));
    }

    private void add(Def d) { defs.put(d.id(), d); }

    Def def(String id) { return defs.get(id); }
    Collection<Def> all() { return defs.values(); }

    Def of(ItemStack s) {
        if (s == null || s.getType().isAir() || !s.hasItemMeta()) return null;
        String id = s.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? null : defs.get(id);
    }

    /** Repairs custom armor visuals on items created by older plugin builds. */
    void refreshVisual(ItemStack s) {
        Def d = of(s);
        if (d == null || !d.armor()) return;
        ItemMeta m = s.getItemMeta();
        m.setItemModel(new NamespacedKey("beeupdate", d.id()));
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(slot(d.kind()));
        eq.setModel(new NamespacedKey("beeupdate", SETS[d.tier() - 1]));
        m.setEquippable(eq);
        s.setItemMeta(m);
    }

    int level(ItemStack s) {
        return s.getItemMeta().getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 0);
    }

    String uid(ItemStack s) {
        return s.hasItemMeta() ? s.getItemMeta().getPersistentDataContainer().get(uidKey, PersistentDataType.STRING) : null;
    }

    ItemStack create(String id) {
        Def d = defs.get(id);
        ItemStack s = new ItemStack(d.material());
        ItemMeta m = s.getItemMeta();
        m.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        if (d.kind() != Kind.SPECIAL) {
            m.getPersistentDataContainer().set(uidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            if (m instanceof Damageable dm) dm.setMaxDamage(d.durability());
        }
        m.customName(BeeUpdate.text("&6" + d.name()));
        m.setItemModel(new NamespacedKey("beeupdate", id));
        if (d.armor()) {
            EquippableComponent eq = m.getEquippable();
            eq.setSlot(slot(d.kind()));
            eq.setModel(new NamespacedKey("beeupdate", SETS[d.tier() - 1]));
            m.setEquippable(eq);
        }
        apply(m, d, 0);
        s.setItemMeta(m);
        return s;
    }

    /** Changes only level, stats and lore; enchantments, wear and identity are kept. */
    void setLevel(ItemStack s, Def d, int level) {
        ItemMeta m = s.getItemMeta();
        apply(m, d, level);
        s.setItemMeta(m);
    }

    private static EquipmentSlot slot(Kind k) {
        return switch (k) {
            case HELMET -> EquipmentSlot.HEAD;
            case CHESTPLATE -> EquipmentSlot.CHEST;
            case LEGGINGS -> EquipmentSlot.LEGS;
            case BOOTS -> EquipmentSlot.FEET;
            default -> EquipmentSlot.HAND;
        };
    }

    private void apply(ItemMeta m, Def d, int level) {
        var c = plugin.getConfig();
        m.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        List<Component> lore = new ArrayList<>();
        m.setAttributeModifiers(null);
        if (d.kind() == Kind.SWORD) {
            double dmg = d.stat() + level * c.getDouble("upgrades.damage-per-level", 0.5);
            modifier(m, Attribute.ATTACK_DAMAGE, "damage", dmg - 1, EquipmentSlotGroup.MAINHAND);
            modifier(m, Attribute.ATTACK_SPEED, "speed", -2.4, EquipmentSlotGroup.MAINHAND);
            lore.add(BeeUpdate.text("&7Damage: &c" + dmg));
        } else if (d.kind() == Kind.PICKAXE) {
            double eff = d.stat() + level * c.getDouble("upgrades.efficiency-per-level", 2);
            modifier(m, Attribute.MINING_EFFICIENCY, "efficiency", eff, EquipmentSlotGroup.MAINHAND);
            modifier(m, Attribute.ATTACK_DAMAGE, "damage", 3 + d.tier(), EquipmentSlotGroup.MAINHAND);
            modifier(m, Attribute.ATTACK_SPEED, "speed", -2.8, EquipmentSlotGroup.MAINHAND);
            lore.add(BeeUpdate.text("&7Mining bonus: &a+" + eff));
        } else if (d.armor()) {
            EquipmentSlotGroup g = slot(d.kind()).getGroup();
            double armor = d.stat() + level * c.getDouble("upgrades.armor-per-level", 0.5);
            String suffix = d.kind().name().toLowerCase(Locale.ROOT);
            modifier(m, Attribute.ARMOR, "armor_" + suffix, armor, g);
            modifier(m, Attribute.ARMOR_TOUGHNESS, "tough_" + suffix, d.toughness(), g);
            if (d.tier() >= 3) modifier(m, Attribute.KNOCKBACK_RESISTANCE, "kb_" + suffix, 0.1, g);
            if (d.tier() == 4) modifier(m, Attribute.MAX_HEALTH, "hp_" + suffix, 1, g);
            lore.add(BeeUpdate.text("&7Armor: &b" + armor + " &7Toughness: &b" + d.toughness()));
        }
        lore.add(BeeUpdate.text("&e" + d.ability()));
        if (d.kind() != Kind.SPECIAL) {
            lore.add(BeeUpdate.text("&8Level " + level));
            m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }
        m.lore(lore);
    }

    private void modifier(ItemMeta m, Attribute a, String key, double amount, EquipmentSlotGroup group) {
        m.addAttributeModifier(a, new AttributeModifier(new NamespacedKey(plugin, key), amount,
                AttributeModifier.Operation.ADD_NUMBER, group));
    }
}
