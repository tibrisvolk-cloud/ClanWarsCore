package com.yourname.indeprofilebot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ClanWarsCore extends JavaPlugin implements Listener, CommandExecutor {

    private String botToken;
    private String guildId;

    private JDA jda;
    private final Map<String, String> linkCodes = new ConcurrentHashMap<>();
    private final Map<UUID, String> linkedAccounts = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemStack>> soulboundItems = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingInvites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> radarCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDailyReward = new ConcurrentHashMap<>();
    private final Map<UUID, Long> scrollCooldowns = new ConcurrentHashMap<>();

    private NamespacedKey trackerKey, guildItemKey, nexusKey, pawboxKey, scrollInfernoKey, scrollPlagueKey, c4Key;

    private File linkedFile;
    private FileConfiguration linkedConfig;

    public final Map<UUID, ClanPlayer> players = new ConcurrentHashMap<>();
    public final Map<String, Clan> clans = new ConcurrentHashMap<>();
    private final Map<String, Zone> zones = new ConcurrentHashMap<>();
    private final Map<UUID, QuestProgress> playerQuests = new ConcurrentHashMap<>();

    private NPCManager npcManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        botToken = getConfig().getString("discord.bot-token");
        guildId = getConfig().getString("discord.guild-id");

        trackerKey = new NamespacedKey(this, "blood_tracker");
        guildItemKey = new NamespacedKey(this, "guild_item");
        nexusKey = new NamespacedKey(this, "nexus_block");
        pawboxKey = new NamespacedKey(this, "pawbox");
        scrollInfernoKey = new NamespacedKey(this, "scroll_inferno");
        scrollPlagueKey = new NamespacedKey(this, "scroll_plague");
        c4Key = new NamespacedKey(this, "c4_charge");

        loadLinks();
        loadGameData();
        loadZones();

        if (botToken != null && !botToken.isEmpty() && !botToken.equals("ВСТАВЬТЕ_ТОКЕН_БОТА")) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    jda = JDABuilder.createDefault(botToken)
                            .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                            .addEventListeners(new DiscordListener())
                            .build();
                    jda.awaitReady();
                } catch (Exception ignored) {}
            });
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("clan").setExecutor(this);

        npcManager = new NPCManager(this);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) applyGuildPassives(p);
            }
        }.runTaskTimer(this, 20L, 20L);

        new BukkitRunnable() {
            @Override
            public void run() {
                saveData();
            }
        }.runTaskTimerAsynchronously(this, 6000L, 6000L);

        // Спавн мобов в зонах
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Zone zone : zones.values()) {
                    if (zone.getMobType() != null && zone.getCenter().getWorld() != null) {
                        for (int i = 0; i < zone.getMobCount(); i++) {
                            Location spawnLoc = zone.getRandomLocation();
                            if (spawnLoc != null) {
                                EntityType type = EntityType.valueOf(zone.getMobType());
                                if (type != null && type.getEntityClass() != null) {
                                    spawnLoc.getWorld().spawnEntity(spawnLoc, type);
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 1200L, 1200L); // каждые минуту (1200 тиков = 60 сек)

        getLogger().info("ClanWars Season 2: Запущен!");
    }

    @Override
    public void onDisable() {
        saveData();
        saveZones();
        if (jda != null) jda.shutdown();
    }

    // ==========================================
    //           ЕЖЕДНЕВНЫЙ БОНУС
    // ==========================================
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        ClanPlayer cp = getClanPlayer(p.getUniqueId());
        long now = System.currentTimeMillis();
        long last = lastDailyReward.getOrDefault(p.getUniqueId(), 0L);
        if (now - last > 24 * 60 * 60 * 1000L) {
            lastDailyReward.put(p.getUniqueId(), now);
            int bonus = 50;
            cp.addPersonalPoints(bonus);
            p.sendMessage("§6[Ежедневный бонус] §aВы получили " + bonus + " очков за вход!");
        }
        applyGuildPassives(p);
    }

    // ==========================================
    //           СИСТЕМА ЧАТА И ПРЕФИКСОВ
    // ==========================================
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        ClanPlayer cp = getClanPlayer(p.getUniqueId());
        Clan clan = cp.getClanId() != null ? clans.get(cp.getClanId()) : null;

        if (cp.isClanChatEnabled()) {
            event.setCancelled(true);
            if (clan == null) {
                cp.setClanChatEnabled(false);
                p.sendMessage("§cВы не в клане! Чат переключен на глобальный.");
                return;
            }
            String msg = "§b[Клан] §f" + p.getName() + " §8» §7" + event.getMessage();
            for (UUID memId : clan.getMembers()) {
                Player mem = Bukkit.getPlayer(memId);
                if (mem != null) mem.sendMessage(msg);
            }

            if (jda != null && clan.getDiscordTextChannelId() != null) {
                TextChannel channel = jda.getTextChannelById(clan.getDiscordTextChannelId());
                if (channel != null) channel.sendMessage("**" + p.getName() + "**: " + event.getMessage()).queue();
            }
        } else {
            if (clan != null) {
                event.setFormat("§8[§e" + clan.getId() + " §8| §6" + clan.getRank().getDisplayName() + "§8] §f%1$s §8» §7%2$s");
            } else {
                event.setFormat("§8[§7Без Клана§8] §f%1$s §8» §7%2$s");
            }
        }
    }

    // ==========================================
    //           МЕНЮ УЛУЧШЕНИЙ (GUI)
    // ==========================================
    public void openUpgradeMenu(Player player) {
        Clan clan = getClanPlayer(player.getUniqueId()).getClanId() != null ? clans.get(getClanPlayer(player.getUniqueId()).getClanId()) : null;
        if (clan == null) { player.sendMessage("§cВы не в клане!"); return; }

        Inventory inv = Bukkit.createInventory(null, 27, "§8Улучшения Клана");
        inv.setItem(11, getCustomItem(Material.GOLDEN_APPLE, "§c❤ Улучшить Здоровье", null,
                "§7Цена: §e15,000 Очков", "§7Дает +1 сердце всему клану. Максимум 10 уровней."));

        if (clan.getGuildType() == GuildType.MAGE) {
            inv.setItem(13, getCustomItem(Material.PAPER, "§5Свитки Магии (многоразовые)", null,
                    "§7Цена: §e5,000 Очков", "§7Выдает Свиток Инферно и Чумы (перезарядка 30 сек)"));
        } else if (clan.getGuildType() == GuildType.ENGINEER) {
            inv.setItem(13, getCustomItem(Material.TNT, "§4Рейдовый заряд (C4)", null,
                    "§7Цена: §e25,000 Очков", "§7Пробивает базу врагов"));
        } else if (clan.getGuildType() == GuildType.BLACKSMITH) {
            inv.setItem(13, getCustomItem(Material.IRON_SWORD, "§6Молот Тора", null,
                    "§7Цена: §e10,000 Очков", "§7Неразрушимый меч с Knockback II"));
        } else if (clan.getGuildType() == GuildType.SMUGGLER) {
            inv.setItem(13, getCustomItem(Material.FISHING_ROD, "§7Крюк-кошка", null,
                    "§7Цена: §e8,000 Очков", "§7Притягивает цель"));
        }

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals("§8Улучшения Клана")) {
            event.setCancelled(true);
            Player p = (Player) event.getWhoClicked();
            
            String clanId = getClanPlayer(p.getUniqueId()).getClanId();
            if (clanId == null || !clans.containsKey(clanId)) return;
            Clan clan = clans.get(clanId);
            
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            if (clicked.getItemMeta().getDisplayName().contains("Здоровье")) {
                if (clan.getBankPoints() >= 15000) {
                    boolean canUpgrade = true;
                    for (UUID uid : clan.getMembers()) {
                        ClanPlayer cp = getClanPlayer(uid);
                        if (cp.getMaxHealthLevel() >= 10) {
                            canUpgrade = false;
                            break;
                        }
                    }
                    if (!canUpgrade) {
                        p.sendMessage("§cКто-то из членов клана уже достиг максимального уровня здоровья (10)!");
                        return;
                    }
                    clan.addBankPoints(-15000);
                    for (UUID uid : clan.getMembers()) {
                        ClanPlayer cp = getClanPlayer(uid);
                        cp.setMaxHealthLevel(cp.getMaxHealthLevel() + 1);
                        Player mem = Bukkit.getPlayer(uid);
                        if (mem != null) applyGuildPassives(mem);
                    }
                    p.sendMessage("§aЗдоровье клана увеличено!");
                } else p.sendMessage("§cНедостаточно Очков!");
            }
            else if (clicked.getItemMeta().getDisplayName().contains("Свитки") && clan.getBankPoints() >= 5000) {
                if (hasScroll(p)) {
                    p.sendMessage("§cУ вас уже есть свитки магии!");
                    return;
                }
                clan.addBankPoints(-5000);
                p.getInventory().addItem(getCustomItem(Material.PAPER, "§cСвиток Инферно", scrollInfernoKey));
                p.getInventory().addItem(getCustomItem(Material.PAPER, "§2Свиток Чумы", scrollPlagueKey));
                p.sendMessage("§aСвитки куплены!");
            }
            else if (clicked.getItemMeta().getDisplayName().contains("C4") && clan.getBankPoints() >= 25000) {
                if (hasItem(p, c4Key)) {
                    p.sendMessage("§cУ вас уже есть C4!");
                    return;
                }
                clan.addBankPoints(-25000);
                p.getInventory().addItem(getCustomItem(Material.TNT, "§4Рейдовый Заряд (C4)", c4Key));
                p.sendMessage("§aЗаряд C4 приобретен!");
            }
            else if (clicked.getItemMeta().getDisplayName().contains("Молот Тора") && clan.getBankPoints() >= 10000) {
                if (hasItem(p, guildItemKey)) {
                    p.sendMessage("§cУ вас уже есть артефакт гильдии!");
                    return;
                }
                clan.addBankPoints(-10000);
                ItemStack hammer = new ItemStack(Material.IRON_SWORD);
                ItemMeta hm = hammer.getItemMeta();
                hm.setDisplayName("§6Молот Тора");
                hm.setUnbreakable(true);
                hm.addEnchant(org.bukkit.enchantments.Enchantment.KNOCKBACK, 2, true);
                hammer.setItemMeta(hm);
                markAsGuildItem(hammer);
                p.getInventory().addItem(hammer);
                p.sendMessage("§aМолот Тора скован!");
            }
            else if (clicked.getItemMeta().getDisplayName().contains("Крюк-кошка") && clan.getBankPoints() >= 8000) {
                if (hasItem(p, guildItemKey)) {
                    p.sendMessage("§cУ вас уже есть артефакт гильдии!");
                    return;
                }
                clan.addBankPoints(-8000);
                ItemStack hook = new ItemStack(Material.FISHING_ROD);
                ItemMeta hm2 = hook.getItemMeta();
                hm2.setDisplayName("§7Крюк-кошка");
                hm2.setUnbreakable(true);
                hook.setItemMeta(hm2);
                markAsGuildItem(hook);
                p.getInventory().addItem(hook);
                p.sendMessage("§aКрюк-кошка получен!");
            }
            p.closeInventory();
        }
    }

    private boolean hasScroll(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.hasItemMeta()) {
                if (item.getItemMeta().getPersistentDataContainer().has(scrollInfernoKey, PersistentDataType.BYTE) ||
                    item.getItemMeta().getPersistentDataContainer().has(scrollPlagueKey, PersistentDataType.BYTE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasItem(Player p, NamespacedKey key) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                return true;
            }
        }
        return false;
    }

    // ==========================================
    //      КАСТОМНЫЕ ПРЕДМЕТЫ И МЕХАНИКИ
    // ==========================================
    private ItemStack getCustomItem(Material mat, String name, NamespacedKey key, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (key != null) meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        if (lore != null && lore.length > 0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onCustomItemUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        if (item.getItemMeta().getPersistentDataContainer().has(scrollInfernoKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            if (now - scrollCooldowns.getOrDefault(p.getUniqueId(), 0L) < 30000) {
                p.sendMessage("§cСвиток перезаряжается! Подождите " + ((30000 - (now - scrollCooldowns.getOrDefault(p.getUniqueId(), 0L))) / 1000) + " сек.");
                return;
            }
            scrollCooldowns.put(p.getUniqueId(), now);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GHAST_SHOOT, 1f, 1f);
            p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0,1,0), 50, 0.5, 0.5, 0.5, 0.1);
            for (Entity e : p.getNearbyEntities(7, 7, 7)) if (e instanceof LivingEntity && e != p) e.setFireTicks(200);
            p.sendMessage("§cВы активировали Инферно!");
        }
        else if (item.getItemMeta().getPersistentDataContainer().has(scrollPlagueKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            if (now - scrollCooldowns.getOrDefault(p.getUniqueId(), 0L) < 30000) {
                p.sendMessage("§cСвиток перезаряжается! Подождите " + ((30000 - (now - scrollCooldowns.getOrDefault(p.getUniqueId(), 0L))) / 1000) + " сек.");
                return;
            }
            scrollCooldowns.put(p.getUniqueId(), now);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITCH_THROW, 1f, 1f);
            // ИСПРАВЛЕНИЕ: SLIME заменен на DRAGON_BREATH
            p.getWorld().spawnParticle(Particle.DRAGON_BREATH, p.getLocation().add(0,1,0), 50, 0.5, 0.5, 0.5, 0.1);
            for (Entity e : p.getNearbyEntities(7, 7, 7)) if (e instanceof LivingEntity && e != p) ((LivingEntity) e).addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 1));
            p.sendMessage("§2Вы выпустили Чуму!");
        }
        else if (item.getItemMeta().getPersistentDataContainer().has(trackerKey, PersistentDataType.BYTE)) {
            Player richest = null; int maxPts = 0;
            for (Player t : Bukkit.getOnlinePlayers()) {
                if (t.equals(p)) continue;
                int pts = (int) getClanPlayer(t.getUniqueId()).getPersonalPoints();
                if (pts > maxPts) { maxPts = pts; richest = t; }
            }
            if (richest != null) { p.setCompassTarget(richest.getLocation()); p.sendMessage("§cЦель: " + richest.getName()); }
            else { p.sendMessage("§7Целей не найдено."); }
        }
        else if (item.getItemMeta().getPersistentDataContainer().has(pawboxKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            item.setAmount(item.getAmount() - 1);
            openPawBox(p);
        }
        else if (item.getType() == Material.FISHING_ROD && item.getItemMeta().getDisplayName().contains("Крюк-кошка")) {
            Entity target = p.getTargetEntity(30);
            if (target != null && target instanceof LivingEntity) {
                target.teleport(p.getLocation().add(0, 1, 0));
                p.sendMessage("§aЦель притянута!");
            }
        }
    }

    private void openPawBox(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        int roll = new Random().nextInt(100);

        if (roll < 20) {
            ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
            ItemMeta sm = sword.getItemMeta();
            sm.setDisplayName("§6Клинок Падшего Короля");
            sword.setItemMeta(sm);
            markAsGuildItem(sword);
            player.getInventory().addItem(sword);
            Bukkit.broadcastMessage("§e§l[PawBox] §fИгрок §6" + player.getName() + " §fвыбил Легендарный Артефакт!");
        } else if (roll < 60) {
            grantPoints(player, 32);
            player.sendMessage("§aВыбили 32 очка! Они автоматически добавлены на ваш баланс.");
        } else {
            player.getInventory().addItem(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
            player.getInventory().addItem(new ItemStack(Material.ANCIENT_DEBRIS, 2));
        }
    }

    // ==========================================
    //           ГЛАВНЫЕ КОМАНДЫ (/CLAN)
    // ==========================================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        ClanPlayer cp = getClanPlayer(p.getUniqueId());

        if (args.length == 0) {
            sendHelp(p);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help":
                sendHelp(p);
                break;

            case "create":
                if (cp.getClanId() != null) { p.sendMessage("§cВы уже состоите в клане!"); return true; }
                if (System.currentTimeMillis() - cp.getLastClanCreation() < 7 * 24 * 60 * 60 * 1000L) {
                    p.sendMessage("§cВы недавно создавали клан. Подождите 7 дней.");
                    return true;
                }
                if (args.length < 3) { p.sendMessage("§cИспользование: /clan create <Название> <Тэг>"); return true; }
                String clanTag = args[2].toUpperCase();
                if (clanTag.length() != 2 || !clanTag.matches("[A-Z]{2}")) {
                    p.sendMessage("§cТэг должен состоять из 2 английских букв.");
                    return true;
                }
                if (clans.containsKey(clanTag)) { p.sendMessage("§cЭтот тэг уже занят!"); return true; }

                int baseCost = getConfig().getInt("economy.creation.base-cost", 2000);
                int secretCost = getConfig().getInt("economy.creation.secret-cost", 500);
                String secretRoleId = getConfig().getString("discord.secret-role-id", "");

                int finalCost = baseCost;
                boolean hasSecretRole = false;

                String discordId = linkedAccounts.get(p.getUniqueId());
                if (discordId != null && jda != null && secretRoleId != null && !secretRoleId.isEmpty()) {
                    try {
                        Guild guild = jda.getGuildById(guildId);
                        if (guild != null) {
                            net.dv8tion.jda.api.entities.Member member = guild.retrieveMemberById(discordId).complete();
                            if (member != null && member.getRoles().stream().anyMatch(r -> r.getId().equals(secretRoleId))) {
                                hasSecretRole = true;
                                finalCost = secretCost;
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (cp.getPersonalPoints() < finalCost) {
                    p.sendMessage("§cДля создания клана нужно §e" + finalCost + " очков§c на личном балансе!");
                    if (!hasSecretRole) p.sendMessage("§7(С ролью Secret скидка до " + secretCost + ")");
                    return true;
                }

                cp.spendPersonalPoints(finalCost);
                cp.setLastClanCreation(System.currentTimeMillis());

                Clan newClan = new Clan(clanTag, args[1], p.getUniqueId());
                clans.put(clanTag, newClan);
                cp.setClanId(clanTag);

                p.sendMessage("§aКлан успешно создан за §e" + finalCost + " очков§a!");
                if (hasSecretRole) p.sendMessage("§dПрименена скидка роли Secret!");

                if (jda != null) createDiscordRoleAndChannel(newClan, p);
                break;

            case "invite":
                if (cp.getClanId() == null) { p.sendMessage("§cВы не в клане!"); return true; }
                Clan invClan = clans.get(cp.getClanId());
                if (!invClan.getLeader().equals(p.getUniqueId())) { p.sendMessage("§cТолько лидер может приглашать!"); return true; }
                if (args.length < 2) { p.sendMessage("§cУкажите ник: /clan invite <ник>"); return true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { p.sendMessage("§cИгрок не найден!"); return true; }
                if (invClan.getMembers().size() >= getConfig().getInt("clan.max-size", 5)) {
                    p.sendMessage("§cКлан заполнен!");
                    return true;
                }
                pendingInvites.put(target.getUniqueId(), invClan.getId());
                p.sendMessage("§aПриглашение отправлено!");
                target.sendMessage("§eВас пригласили в клан " + invClan.getName() + "! Введите §f/clan confirm " + p.getName() + "§e для вступления.");
                break;

            case "confirm":
                if (cp.getClanId() != null) { p.sendMessage("§cВы уже в клане!"); return true; }
                if (args.length < 2) { p.sendMessage("§cУкажите ник лидера: /clan confirm <ник>"); return true; }
                Player inviter = Bukkit.getPlayer(args[1]);
                if (inviter == null) { p.sendMessage("§cЛидер не найден!"); return true; }
                
                String inviteId = pendingInvites.get(p.getUniqueId());
                if (inviteId == null) {
                    p.sendMessage("§cУ вас нет активных приглашений от этого лидера!");
                    return true;
                }
                
                Clan targetClan = clans.get(inviteId);
                if (targetClan == null || !targetClan.getLeader().equals(inviter.getUniqueId())) {
                    p.sendMessage("§cПриглашение недействительно или лидер изменился!");
                    return true;
                }
                
                if (targetClan.getMembers().size() >= getConfig().getInt("clan.max-size", 5)) {
                    p.sendMessage("§cКлан уже заполнен!");
                    return true;
                }
                targetClan.getMembers().add(p.getUniqueId());
                cp.setClanId(targetClan.getId());
                pendingInvites.remove(p.getUniqueId());
                p.sendMessage("§aВы вступили в клан " + targetClan.getName() + "!");
                if (jda != null && targetClan.getDiscordRoleId() != null) {
                    Guild g = jda.getGuildById(guildId);
                    String dId = linkedAccounts.get(p.getUniqueId());
                    if (g != null && dId != null) g.retrieveMemberById(dId).queue(m -> g.addRoleToMember(m, g.getRoleById(targetClan.getDiscordRoleId())).queue());
                }
                break;

            case "chat":
                cp.setClanChatEnabled(!cp.isClanChatEnabled());
                p.sendMessage(cp.isClanChatEnabled() ? "§aКлановый чат включен!" : "§eКлановый чат выключен.");
                break;

            case "top":
                if (cp.getClanId() == null) { p.sendMessage("§cВы не в клане!"); return true; }
                Clan tClan = clans.get(cp.getClanId());
                p.sendMessage("§e--- Топ вкладчиков в казну ---");
                tClan.getMembers().stream()
                        .map(this::getClanPlayer)
                        .sorted((a, b) -> Long.compare(b.getContributedPoints(), a.getContributedPoints()))
                        .forEach(m -> {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(m.getUuid());
                            p.sendMessage("§f" + (op.getName() != null ? op.getName() : "Unknown") + " §7- §e" + m.getContributedPoints() + " Очков");
                        });
                break;

            case "topclans":
                p.sendMessage("§e--- Топ кланов по казне ---");
                clans.values().stream()
                        .sorted((a, b) -> Long.compare(b.getBankPoints(), a.getBankPoints()))
                        .limit(10)
                        .forEach(c -> p.sendMessage("§6" + c.getName() + " §7- §e" + c.getBankPoints() + " очков"));
                break;

            case "stats":
                p.sendMessage("§e--- Ваша статистика ---");
                p.sendMessage("§fЛичные очки: §6" + cp.getPersonalPoints());
                p.sendMessage("§fОчки в казне клана: §6" + (cp.getClanId() != null ? clans.get(cp.getClanId()).getBankPoints() : 0));
                p.sendMessage("§fВаш вклад: §6" + cp.getContributedPoints());
                p.sendMessage("§fДневной лимит PvE: §6" + cp.getDailyPvEPoints() + "/" + getConfig().getInt("limits.daily-pve-points", 500));
                p.sendMessage("§fДневной лимит PvP: §6" + cp.getDailyPvpPoints() + "/" + getConfig().getInt("limits.daily-pvp-points", 300));
                break;

            case "info":
                Clan infoClan = cp.getClanId() != null ? clans.get(cp.getClanId()) : null;
                if (infoClan == null) { p.sendMessage("§cВы не в клане!"); return true; }
                p.sendMessage("§e=== Информация о клане ===");
                p.sendMessage("§fНазвание: §b" + infoClan.getName());
                p.sendMessage("§fТэг: §b" + infoClan.getId());
                p.sendMessage("§fРанг: §6" + infoClan.getRank().getDisplayName());
                p.sendMessage("§fСпециализация: §d" + infoClan.getGuildType().getDisplayName());
                p.sendMessage("§fКазна: §e" + infoClan.getBankPoints() + " очков");
                p.sendMessage("§fСостав (" + infoClan.getMembers().size() + "/5):");
                for (UUID uid : infoClan.getMembers()) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
                    p.sendMessage("  §7- " + (op.getName() != null ? op.getName() : "Unknown"));
                }
                break;

            case "kick":
                if (cp.getClanId() == null) { p.sendMessage("§cВы не в клане!"); return true; }
                Clan kClan = clans.get(cp.getClanId());
                if (!kClan.getLeader().equals(p.getUniqueId())) { p.sendMessage("§cТолько лидер может выгонять!"); return true; }
                if (args.length < 2) { p.sendMessage("§cУкажите ник: /clan kick <ник>"); return true; }
                OfflinePlayer kTarget = Bukkit.getOfflinePlayer(args[1]);
                if (kTarget == null || !kClan.getMembers().contains(kTarget.getUniqueId()) || kTarget.getUniqueId().equals(p.getUniqueId())) {
                    p.sendMessage("§cИгрок не найден в клане или это вы!");
                    return true;
                }
                kClan.getMembers().remove(kTarget.getUniqueId());
                ClanPlayer kcp = getClanPlayer(kTarget.getUniqueId());
                kcp.setClanId(null);
                kcp.setMaxHealthLevel(0);
                removeDiscordRole(kTarget.getUniqueId(), kClan.getDiscordRoleId());
                Player onlineKick = Bukkit.getPlayer(kTarget.getUniqueId());
                if (onlineKick != null) {
                    onlineKick.setHealth(0);
                    onlineKick.sendMessage("§cВы были изгнаны из клана!");
                }
                p.sendMessage("§aИгрок изгнан!");
                break;

            case "exit":
                if (cp.getClanId() == null) { p.sendMessage("§cВы не в клане!"); return true; }
                Clan lClan = clans.get(cp.getClanId());
                if (lClan.getLeader().equals(p.getUniqueId())) {
                    p.sendMessage("§cЛидер не может выйти! Используйте /clan disband.");
                    return true;
                }
                if (lClan.getGuildType() != GuildType.NONE) {
                    cp.setMaxHealthLevel(0);
                    p.sendMessage("§cВы потеряли все улучшения, купленные за клановые очки!");
                }
                lClan.getMembers().remove(p.getUniqueId());
                cp.setClanId(null);
                cp.setLastClanExit(System.currentTimeMillis());
                removeDiscordRole(p.getUniqueId(), lClan.getDiscordRoleId());
                p.setHealth(0);
                p.sendMessage("§cВы покинули клан! Вступать в другой можно через 24 часа.");
                break;

            case "disband":
                if (cp.getClanId() == null) { p.sendMessage("§cВы не в клане!"); return true; }
                Clan dClan = clans.get(cp.getClanId());
                if (!dClan.getLeader().equals(p.getUniqueId())) { p.sendMessage("§cТолько лидер может расформировать клан!"); return true; }
                if (dClan.getBankPoints() > getConfig().getInt("anti-abuse.max-bank-for-disband", 50000)) {
                    p.sendMessage("§cНельзя распустить клан с казной более " + getConfig().getInt("anti-abuse.max-bank-for-disband", 50000) + " очков.");
                    return true;
                }
                for (UUID mId : dClan.getMembers()) {
                    ClanPlayer mcp = getClanPlayer(mId);
                    mcp.setClanId(null);
                    mcp.setMaxHealthLevel(0);
                    removeDiscordRole(mId, dClan.getDiscordRoleId());
                    if (mcp.getUuid().equals(p.getUniqueId())) {
                        mcp.setLastClanCreation(System.currentTimeMillis());
                    }
                }
                deleteDiscordClanSetup(dClan);
                clans.remove(dClan.getId());
                p.sendMessage("§cКлан распущен!");
                break;

            case "spec":
                if (cp.getClanId() == null) { p.sendMessage("§cВы не в клане!"); return true; }
                Clan specClan = clans.get(cp.getClanId());
                if (!specClan.getLeader().equals(p.getUniqueId())) { p.sendMessage("§cТолько лидер выбирает специализацию!"); return true; }
                if (specClan.getRank().ordinal() < ClanRank.D.ordinal()) {
                    p.sendMessage("§cНужен ранг D или выше для выбора специализации!");
                    return true;
                }
                if (specClan.getGuildType() != GuildType.NONE) {
                    p.sendMessage("§cСпециализация уже выбрана и не может быть изменена!");
                    return true;
                }
                if (args.length < 2) { p.sendMessage("§cУкажите: BLACKSMITH, MAGE, ENGINEER, SMUGGLER"); return true; }
                try {
                    GuildType type = GuildType.valueOf(args[1].toUpperCase());
                    specClan.setGuildType(type);
                    Bukkit.broadcastMessage("§e§l[КЛАНЫ] §fКлан §6" + specClan.getName() + " §fвыбрал специализацию: §b" + type.getDisplayName());
                } catch (Exception e) {
                    p.sendMessage("§cДоступные: BLACKSMITH, MAGE, ENGINEER, SMUGGLER");
                }
                break;

            case "upgrade":
                openUpgradeMenu(p);
                break;

            case "bank":
                if (args.length > 1 && args[1].equalsIgnoreCase("deposit") && cp.getClanId() != null) {
                    if (args.length < 3) { p.sendMessage("§cУкажите количество: /clan bank deposit <кол-во>"); return true; }
                    try {
                        int amt = Integer.parseInt(args[2]);
                        if (amt <= 0) { p.sendMessage("§cЧисло должно быть положительным!"); return true; }
                        if (cp.getPersonalPoints() < amt) { p.sendMessage("§cУ вас недостаточно личных очков!"); return true; }
                        cp.spendPersonalPoints(amt);
                        clans.get(cp.getClanId()).addBankPoints(amt);
                        cp.addContributedPoints(amt);
                        p.sendMessage("§aСдано §e" + amt + " §aочков в казну!");
                        checkRankUp(clans.get(cp.getClanId()));
                    } catch (NumberFormatException e) {
                        p.sendMessage("§cВведите число!");
                    }
                } else {
                    p.sendMessage("§cИспользование: /clan bank deposit <кол-во>");
                }
                break;

            case "forge":
                if (cp.getClanId() != null && clans.get(cp.getClanId()).getGuildType() == GuildType.BLACKSMITH) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getType() != Material.AIR && cp.getPersonalPoints() >= 500) {
                        cp.spendPersonalPoints(500);
                        ItemMeta m = hand.getItemMeta();
                        m.setUnbreakable(true);
                        hand.setItemMeta(m);
                        markAsGuildItem(hand);
                        p.sendMessage("§aАртефакт скован!");
                    } else {
                        p.sendMessage("§cНужен предмет в руке и 500 личных очков!");
                    }
                } else {
                    p.sendMessage("§cДоступно только клану Кузнецов!");
                }
                break;

            case "radar":
                if (cp.getClanId() != null && clans.get(cp.getClanId()).getGuildType() == GuildType.SMUGGLER && cp.getPersonalPoints() >= 100) {
                    cp.spendPersonalPoints(100);
                    p.getInventory().addItem(getCustomItem(Material.COMPASS, "§cТрекер Крови", trackerKey));
                    p.sendMessage("§aТрекер получен!");
                } else {
                    p.sendMessage("§cДоступно только Контрабандистам и стоит 100 личных очков.");
                }
                break;

            case "nexus":
                if (cp.getClanId() != null && clans.get(cp.getClanId()).getLeader().equals(p.getUniqueId())) {
                    p.getInventory().addItem(getCustomItem(Material.BEACON, "§d§lСердце Клана", nexusKey));
                    p.sendMessage("§aСердце клана выдано!");
                }
                break;

            case "pawbox":
                if (p.isOp()) p.getInventory().addItem(getCustomItem(Material.CHEST, "§e§lPawBox 2.0", pawboxKey));
                break;

            case "npc":
                if (p.isOp() && args.length >= 3) {
                    String npcName = args[1];
                    String npcType = args[2].toUpperCase();
                    String guildName = args.length >= 4 ? args[3].toUpperCase() : "NONE";
                    npcManager.createNPC(npcName, npcType, p.getLocation(), guildName);
                    p.sendMessage("§aNPC создан!");
                } else {
                    p.sendMessage("§cИспользование: /clan npc <имя> <тип: QUEST/UPGRADE/BANK/SHOP> [гильдия]");
                }
                break;

            case "zone":
                if (p.isOp() && args.length >= 3) {
                    String zoneName = args[1];
                    int radius = Integer.parseInt(args[2]);
                    String mobType = args.length >= 4 ? args[3].toUpperCase() : "ZOMBIE";
                    int mobCount = args.length >= 5 ? Integer.parseInt(args[4]) : 3;
                    Zone zone = new Zone(zoneName, p.getLocation(), radius, mobType, mobCount);
                    zones.put(zoneName, zone);
                    saveZones();
                    p.sendMessage("§aЗона '" + zoneName + "' создана с мобами " + mobType + " (" + mobCount + " шт.)");
                } else {
                    p.sendMessage("§cИспользование: /clan zone <имя> <радиус> [моб] [кол-во]");
                }
                break;

            case "addpoints":
                if (p.isOp() && args.length >= 3) {
                    Player targetAdd = Bukkit.getPlayer(args[1]);
                    if (targetAdd != null) {
                        try {
                            int amt = Integer.parseInt(args[2]);
                            grantPoints(targetAdd, amt);
                            p.sendMessage("§aВыдано " + amt + " очков игроку " + targetAdd.getName());
                        } catch (NumberFormatException ignored) {}
                    }
                }
                break;

            case "setrank":
                if (p.isOp() && args.length >= 3) {
                    Clan setClan = clans.get(args[1].toUpperCase());
                    if (setClan != null) {
                        try {
                            setClan.setRank(ClanRank.valueOf(args[2].toUpperCase()));
                            p.sendMessage("§aРанг клана " + setClan.getId() + " установлен на " + setClan.getRank().getDisplayName());
                        } catch (Exception e) {
                            p.sendMessage("§cНеверный ранг");
                        }
                    } else p.sendMessage("§cКлан не найден");
                }
                break;
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage("§e=== КЛАНЫ: СЕЗОН 2 ===");
        p.sendMessage("§f/clan create <Название> <Тэг> §7- создать клан (2 буквы)");
        p.sendMessage("§f/clan invite <Ник> §7- пригласить игрока");
        p.sendMessage("§f/clan confirm <Ник_лидера> §7- принять приглашение");
        p.sendMessage("§f/clan exit §7- выйти из клана (штраф!)");
        p.sendMessage("§f/clan chat §7- вкл/выкл клановый чат");
        p.sendMessage("§f/clan top §7- топ вкладчиков");
        p.sendMessage("§f/clan topclans §7- топ кланов");
        p.sendMessage("§f/clan stats §7- ваша статистика");
        p.sendMessage("§f/clan info §7- информация о клане");
        p.sendMessage("§f/clan bank deposit <кол-во> §7- сдать личные очки в казну");
        p.sendMessage("§f/clan spec <КЛАСС> §7- выбрать специализацию (лидер, ранг D+)");
        p.sendMessage("§f/clan upgrade §7- меню улучшений клана");
        p.sendMessage("§f/clan forge §7- сковать артефакт (Кузнецы)");
        p.sendMessage("§f/clan radar §7- получить трекер (Контрабандисты)");
        p.sendMessage("§f/clan nexus §7- получить сердце клана (лидер)");
        p.sendMessage("§f/clan zone <имя> <радиус> [моб] [кол-во] §7- создать зону спавна (админ)");
        p.sendMessage("§f/clan npc <имя> <тип> [гильдия] §7- создать NPC (админ)");
    }

    // ==========================================
    //           ЛОГИКА PVP, PVE И ЛИМИТОВ
    // ==========================================
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        Player killer = event.getEntity().getKiller();
        ClanPlayer cp = getClanPlayer(killer.getUniqueId());

        // Обновление прогресса квестов
        if (playerQuests.containsKey(killer.getUniqueId())) {
            QuestProgress qp = playerQuests.get(killer.getUniqueId());
            if (qp.getType() == QuestType.KILL_MOBS) {
                qp.incrementProgress();
                if (qp.isComplete()) {
                    grantPoints(killer, 50);
                    killer.sendMessage("§aКвест выполнен! Вы получили 50 очков.");
                    playerQuests.remove(killer.getUniqueId());
                }
            }
        }

        if (!isSpecialMob(event.getEntity())) return;

        int limit = getConfig().getInt("limits.daily-pve-points", 500);
        String today = LocalDate.now().toString();
        if (!today.equals(cp.getLastFarmDate())) {
            cp.setLastFarmDate(today);
            cp.setDailyPvEPoints(0);
        }
        if (cp.getDailyPvEPoints() < limit) {
            cp.setDailyPvEPoints(cp.getDailyPvEPoints() + 1);
            int points = getPointsForMob(event.getEntity().getType());
            grantPoints(killer, points);
        }
    }

    private boolean isSpecialMob(Entity entity) {
        List<String> allowedTypes = getConfig().getStringList("special-mobs.types");
        if (allowedTypes.isEmpty()) {
            allowedTypes = Arrays.asList("WITHER", "ELDER_GUARDIAN", "RAVAGER", "ENDER_DRAGON");
        }
        if (allowedTypes.contains(entity.getType().name())) return true;
        if (getConfig().getBoolean("special-mobs.require-custom-name", false)) {
            if (entity.getCustomName() != null && entity.getCustomName().equals(getConfig().getString("special-mobs.custom-name", "§6Элитный моб"))) {
                return true;
            }
        }
        return false;
    }

    private int getPointsForMob(EntityType type) {
        return getConfig().getInt("special-mobs.base-points", 15);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        List<ItemStack> saved = new ArrayList<>();
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            if (drop.hasItemMeta() && drop.getItemMeta().getPersistentDataContainer().has(guildItemKey, PersistentDataType.BYTE)) {
                saved.add(drop);
                it.remove();
            }
        }
        if (!saved.isEmpty()) soulboundItems.put(victim.getUniqueId(), saved);

        if (killer != null && !killer.equals(victim)) {
            ClanPlayer vcp = getClanPlayer(victim.getUniqueId());
            ClanPlayer kcp = getClanPlayer(killer.getUniqueId());

            String today = LocalDate.now().toString();
            if (!today.equals(kcp.getLastPvpDate())) {
                kcp.setLastPvpDate(today);
                kcp.setDailyPvpPoints(0);
            }
            int pvpLimit = getConfig().getInt("limits.daily-pvp-points", 300);
            if (kcp.getDailyPvpPoints() >= pvpLimit) return;

            Clan vClan = vcp.getClanId() != null ? clans.get(vcp.getClanId()) : null;
            Clan kClan = kcp.getClanId() != null ? clans.get(kcp.getClanId()) : null;

            int points = 50;
            int vRank = vClan != null ? vClan.getRank().ordinal() : -1;
            int kRank = kClan != null ? kClan.getRank().ordinal() : -1;

            if (vRank >= 0 && kRank >= 0) {
                int diff = vRank - kRank;
                if (diff > 0) {
                    points = (int)(points * Math.min(1 + 0.25 * diff, 3.0));
                } else if (diff < 0) {
                    points = (int)(points * Math.max(1 - 0.5 * Math.abs(diff), 0));
                }
            } else if (vRank < 0 && kRank >= 0) {
                points = (int)(points * 0.5);
            }

            if (points > 0) {
                grantPoints(killer, points);
                kcp.setDailyPvpPoints(kcp.getDailyPvpPoints() + points);
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID u = event.getPlayer().getUniqueId();
        if (soulboundItems.containsKey(u)) {
            for (ItemStack i : soulboundItems.get(u)) event.getPlayer().getInventory().addItem(i);
            soulboundItems.remove(u);
        }
        applyGuildPassives(event.getPlayer());
    }

    @EventHandler
    public void onC4Place(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(c4Key, PersistentDataType.BYTE)) {
            Player p = event.getPlayer();
            LocalTime now = LocalTime.now();
            LocalTime start = LocalTime.parse(getConfig().getString("raids.prime-time-start", "19:00"));
            LocalTime end = LocalTime.parse(getConfig().getString("raids.prime-time-end", "22:00"));
            if (now.isBefore(start) || now.isAfter(end)) { event.setCancelled(true); return; }

            for (Clan c : clans.values()) {
                if (c.getNexusLocation() != null && c.getNexusLocation().distance(event.getBlock().getLocation()) < 10 && !c.getMembers().contains(p.getUniqueId())) {
                    c.addBankPoints(-1000);
                    Bukkit.broadcastMessage("§4ВНИМАНИЕ! База клана " + c.getName() + " подрывается C4!");
                    if (c.getBankPoints() < 0) {
                        c.setBankPoints(0);
                        c.setNexusLocation(null);
                        Bukkit.broadcastMessage("§c§lСЕРДЦЕ КЛАНА УНИЧТОЖЕНО!");
                    }
                }
            }
        }
    }

    // ==========================================
    //           ЗАЩИТА ЗОН
    // ==========================================
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getPlayer().isOp()) return;
        for (Zone zone : zones.values()) {
            if (zone.isInside(event.getBlock().getLocation())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cЭта территория защищена!");
                break;
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getPlayer().isOp()) return;
        for (Zone zone : zones.values()) {
            if (zone.isInside(event.getBlock().getLocation())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cЭта территория защищена!");
                break;
            }
        }
    }

    // ==========================================
    //           ЗАПРЕТ ВЫБРАСЫВАНИЯ АРТЕФАКТОВ
    // ==========================================
    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(guildItemKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cАртефакт гильдии нельзя выбрасывать!");
        }
    }

    // ==========================================
    //           МОНОПОЛИИ (Ограничения по специализациям)
    // ==========================================
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        Player p = (Player) event.getPlayer();
        Clan clan = getClanPlayer(p.getUniqueId()).getClanId() != null ? clans.get(getClanPlayer(p.getUniqueId()).getClanId()) : null;
        GuildType type = clan != null ? clan.getGuildType() : GuildType.NONE;

        if (event.getInventory().getType() == InventoryType.BREWING && type != GuildType.MAGE) {
            event.setCancelled(true);
            p.sendMessage("§cТолько Маги могут использовать зельеварение!");
        }
        if (event.getInventory().getType() == InventoryType.ANVIL && type != GuildType.BLACKSMITH && type != GuildType.MAGE) {
            event.setCancelled(true);
            p.sendMessage("§cТолько Кузнецы и Маги могут использовать наковальню!");
        }
        if (event.getInventory().getType() == InventoryType.SMITHING && type != GuildType.BLACKSMITH) {
            event.setCancelled(true);
            p.sendMessage("§cТолько Кузнецы могут использовать кузницу!");
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        Player p = (Player) event.getView().getPlayer();
        Clan clan = getClanPlayer(p.getUniqueId()).getClanId() != null ? clans.get(getClanPlayer(p.getUniqueId()).getClanId()) : null;
        GuildType type = clan != null ? clan.getGuildType() : GuildType.NONE;
        ItemStack slot2 = event.getInventory().getItem(1);

        if (type == GuildType.BLACKSMITH && slot2 != null && slot2.getType() == Material.ENCHANTED_BOOK) {
            event.setResult(null);
        }
        if (type == GuildType.MAGE && slot2 != null && slot2.getType() != Material.ENCHANTED_BOOK) {
            event.setResult(null);
        }
    }

    // ==========================================
    //           ПАССИВНЫЕ ЭФФЕКТЫ СПЕЦИАЛИЗАЦИЙ
    // ==========================================
    @SuppressWarnings("deprecation")
    private void applyGuildPassives(Player player) {
        ClanPlayer cp = getClanPlayer(player.getUniqueId());
        Clan clan = cp.getClanId() != null ? clans.get(cp.getClanId()) : null;
        if (clan == null) return;

        double baseHp = 20;
        switch (clan.getGuildType()) {
            case MAGE:
                baseHp = 16;
                break;
            case BLACKSMITH:
                baseHp = 22;
                break;
            case ENGINEER:
                baseHp = 20;
                break;
            case SMUGGLER:
                baseHp = 18;
                break;
            default:
                baseHp = 20;
        }

        double maxHp = baseHp + (cp.getMaxHealthLevel() * 2);
        
        player.setMaxHealth(maxHp);

        switch (clan.getGuildType()) {
            case BLACKSMITH:
                player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 0, false, false, false));
                break;
            case MAGE:
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, false, false, false));
                break;
            case ENGINEER:
                player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, 0, false, false, false));
                break;
            case SMUGGLER:
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 0, false, false, false));
                break;
        }
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            Clan clan = clans.get(getClanPlayer(p.getUniqueId()).getClanId());
            if (clan != null && clan.getGuildType() == GuildType.SMUGGLER) {
                event.setCancelled(true);
            }
        }
    }

    // ==========================================
    //           ЭКОНОМИКА: НАЧИСЛЕНИЕ ОЧКОВ
    // ==========================================
    public void grantPoints(Player player, int amount) {
        if (amount <= 0) return;
        ClanPlayer cp = getClanPlayer(player.getUniqueId());
        int personal = amount / 2;
        int clanPart = amount - personal;

        cp.addPersonalPoints(personal);
        if (cp.getClanId() != null) {
            Clan clan = clans.get(cp.getClanId());
            clan.addBankPoints(clanPart);
            cp.addContributedPoints(clanPart);
            checkRankUp(clan);
        } else {
            cp.addPersonalPoints(clanPart);
        }
    }

    private void checkRankUp(Clan clan) {
        for (ClanRank rank : ClanRank.values()) {
            if (clan.getRank().ordinal() < rank.ordinal() && clan.getBankPoints() >= rank.getRequiredPoints()) {
                clan.setRank(rank);
                BossBar bar = Bukkit.createBossBar("§aКлан " + clan.getName() + " достиг ранга " + rank.getDisplayName(),
                        BarColor.GREEN, BarStyle.SOLID);
                for (UUID uid : clan.getMembers()) {
                    Player member = Bukkit.getPlayer(uid);
                    if (member != null) bar.addPlayer(member);
                }
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        bar.removeAll();
                    }
                }.runTaskLater(this, 200L);

                Bukkit.broadcastMessage("§e§l[КЛАНЫ] §fКлан §6" + clan.getName() + " §fапнул Ранг §b" + rank.getDisplayName() + "§f!");
            }
        }
    }

    // ==========================================
    //           УТИЛИТЫ И ДИСКОРД
    // ==========================================
    private void removeDiscordRole(UUID playerUuid, String roleId) {
        if (jda == null || roleId == null || guildId == null) return;
        Guild g = jda.getGuildById(guildId);
        String dId = linkedAccounts.get(playerUuid);
        if (g != null && dId != null) g.retrieveMemberById(dId).queue(m -> g.removeRoleFromMember(m, g.getRoleById(roleId)).queue());
    }

    private void deleteDiscordClanSetup(Clan clan) {
        if (jda == null || guildId == null) return;
        Guild g = jda.getGuildById(guildId);
        if (g != null) {
            if (clan.getDiscordRoleId() != null) {
                Role r = g.getRoleById(clan.getDiscordRoleId());
                if (r != null) r.delete().queue();
            }
            if (clan.getDiscordTextChannelId() != null) {
                TextChannel tc = g.getTextChannelById(clan.getDiscordTextChannelId());
                if (tc != null) {
                    Category cat = tc.getParentCategory();
                    tc.delete().queue();
                    if (cat != null) {
                        for (GuildChannel gc : cat.getChannels()) gc.delete().queue();
                        cat.delete().queue();
                    }
                }
            }
            if (clan.getDiscordVoiceChannelId() != null) {
                VoiceChannel vc = g.getVoiceChannelById(clan.getDiscordVoiceChannelId());
                if (vc != null) vc.delete().queue();
            }
        }
    }

    private void createDiscordRoleAndChannel(Clan clan, Player leader) {
        if (jda == null || guildId == null) return;
        Guild guild = jda.getGuildById(guildId);
        if (guild != null) {
            guild.createRole().setName(clan.getName()).queue(role -> {
                clan.setDiscordRoleId(role.getId());
                String dId = linkedAccounts.get(leader.getUniqueId());
                if (dId != null) guild.retrieveMemberById(dId).queue(m -> guild.addRoleToMember(m, role).queue());
                guild.createCategory("🛡️ " + clan.getName()).queue(cat -> {
                    cat.upsertPermissionOverride(guild.getPublicRole())
                       .deny(Permission.VIEW_CHANNEL)
                       .queue();
                    Role clanRole = guild.getRoleById(clan.getDiscordRoleId());
                    if (clanRole != null) {
                        cat.upsertPermissionOverride(clanRole)
                           .grant(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.VOICE_CONNECT)
                           .queue();
                    }
                    cat.createTextChannel("штаб").queue(txt -> clan.setDiscordTextChannelId(txt.getId()));
                    cat.createVoiceChannel("голосовой").queue(vc -> clan.setDiscordVoiceChannelId(vc.getId()));
                });
            });
        }
    }

    private class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;
            String msg = event.getMessage().getContentRaw();

            if (msg.startsWith("!link ")) {
                String code = msg.substring(6).trim();
                String uuidStr = linkCodes.remove(code);
                if (uuidStr != null) {
                    UUID uuid = UUID.fromString(uuidStr);
                    linkedAccounts.put(uuid, event.getAuthor().getId());
                    linkedConfig.set(uuid.toString(), event.getAuthor().getId());
                    try { linkedConfig.save(linkedFile); } catch (IOException ignored) {}
                    event.getChannel().sendMessage("✅ Аккаунт привязан!").queue();
                }
                return;
            }

            if (event.isFromGuild()) {
                String chId = event.getChannel().getId();
                for (Clan c : clans.values()) {
                    if (chId.equals(c.getDiscordTextChannelId())) {
                        String mcMsg = "§9[DS] §b[Клан] §f" + event.getAuthor().getName() + " §8» §7" + event.getMessage().getContentDisplay();
                        for (UUID memId : c.getMembers()) {
                            Player m = Bukkit.getPlayer(memId);
                            if (m != null) m.sendMessage(mcMsg);
                        }
                        break;
                    }
                }

                if (msg.startsWith("!clan info")) {
                    for (Clan c : clans.values()) {
                        if (c.getDiscordRoleId() != null) {
                            Role r = event.getGuild().getRoleById(c.getDiscordRoleId());
                            if (r != null && event.getMember() != null && event.getMember().getRoles().contains(r)) {
                                event.getChannel().sendMessage("**" + c.getName() + "** | Ранг: " + c.getRank().getDisplayName() +
                                        " | Казна: " + c.getBankPoints() + " | Специализация: " + c.getGuildType().getDisplayName()).queue();
                            }
                        }
                    }
                } else if (msg.startsWith("!clan top")) {
                    for (Clan c : clans.values()) {
                        if (c.getDiscordRoleId() != null) {
                            Role r = event.getGuild().getRoleById(c.getDiscordRoleId());
                            if (r != null && event.getMember() != null && event.getMember().getRoles().contains(r)) {
                                StringBuilder sb = new StringBuilder("**Топ вкладчиков " + c.getName() + ":**\n");
                                c.getMembers().stream()
                                        .map(ClanWarsCore.this::getClanPlayer)
                                        .sorted((a,b) -> Long.compare(b.getContributedPoints(), a.getContributedPoints()))
                                        .forEach(cp -> {
                                            OfflinePlayer op = Bukkit.getOfflinePlayer(cp.getUuid());
                                            sb.append(op.getName()).append(": ").append(cp.getContributedPoints()).append("\n");
                                        });
                                event.getChannel().sendMessage(sb.toString()).queue();
                            }
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    //           NPC МЕНЕДЖЕР
    // ==========================================
    public class NPCManager implements Listener {
        private final Map<String, NPCEntry> npcs = new ConcurrentHashMap<>();
        private final ClanWarsCore plugin;

        public NPCManager(ClanWarsCore plugin) {
            this.plugin = plugin;
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            loadNPCs();
        }

        private void loadNPCs() {
            if (plugin.getConfig().contains("npcs")) {
                for (String key : plugin.getConfig().getConfigurationSection("npcs").getKeys(false)) {
                    String path = "npcs." + key;
                    String name = plugin.getConfig().getString(path + ".name");
                    String type = plugin.getConfig().getString(path + ".type");
                    Location loc = plugin.getConfig().getLocation(path + ".location");
                    String guild = plugin.getConfig().getString(path + ".guild", "NONE");
                    if (name != null && type != null && loc != null) {
                        NPCEntry entry = new NPCEntry(name, type, loc, guild);
                        npcs.put(name, entry);
                        spawnNPC(entry);
                    }
                }
            }
        }

        public void createNPC(String name, String type, Location location, String guildName) {
            if (npcs.containsKey(name)) return;
            NPCEntry entry = new NPCEntry(name, type, location, guildName);
            npcs.put(name, entry);
            spawnNPC(entry);
            saveNPC(entry);
        }

        private void spawnNPC(NPCEntry entry) {
            World world = entry.getLocation().getWorld();
            if (world == null) return;
            Villager villager = (Villager) world.spawnEntity(entry.getLocation(), EntityType.VILLAGER);
            villager.setCustomName(entry.getName());
            villager.setCustomNameVisible(true);
            villager.setProfession(Villager.Profession.NONE);
            villager.setAI(false);
            villager.setInvulnerable(true);
            entry.setEntity(villager);
        }

        @EventHandler
        public void onNPCClick(PlayerInteractEntityEvent event) {
            if (event.getRightClicked() instanceof Villager) {
                Villager villager = (Villager) event.getRightClicked();
                String npcName = villager.getCustomName();
                if (npcName != null && npcs.containsKey(npcName)) {
                    NPCEntry entry = npcs.get(npcName);
                    Player player = event.getPlayer();

                    ClanPlayer cp = plugin.getClanPlayer(player.getUniqueId());
                    Clan clan = cp.getClanId() != null ? plugin.clans.get(cp.getClanId()) : null;
                    GuildType playerGuild = clan != null ? clan.getGuildType() : GuildType.NONE;
                    if (!entry.getRequiredGuild().equals("NONE") && !entry.getRequiredGuild().equals(playerGuild.name())) {
                        player.sendMessage("§cЭтот NPC доступен только для гильдии " + entry.getRequiredGuild());
                        return;
                    }

                    switch (entry.getType()) {
                        case "QUEST":
                            if (!plugin.playerQuests.containsKey(player.getUniqueId())) {
                                plugin.playerQuests.put(player.getUniqueId(), new QuestProgress(QuestType.KILL_MOBS, 0, 20));
                                player.sendMessage("§aПолучен квест: убейте 20 мобов.");
                            } else {
                                QuestProgress qp = plugin.playerQuests.get(player.getUniqueId());
                                player.sendMessage("§eПрогресс квеста: " + qp.getProgress() + "/" + qp.getTarget());
                            }
                            break;
                        case "UPGRADE":
                            plugin.openUpgradeMenu(player);
                            break;
                        case "BANK":
                            player.sendMessage("§eБанк: используйте /clan bank deposit <кол-во>");
                            break;
                        case "SHOP":
                            Inventory shop = Bukkit.createInventory(null, 9, "§8Магазин");
                            shop.setItem(0, plugin.getCustomItem(Material.PAPER, "§cСвиток Инферно", scrollInfernoKey, "§7Цена: 100 очков"));
                            shop.setItem(1, plugin.getCustomItem(Material.PAPER, "§2Свиток Чумы", scrollPlagueKey, "§7Цена: 100 очков"));
                            player.openInventory(shop);
                            break;
                    }
                    event.setCancelled(true);
                }
            }
        }

        private void saveNPC(NPCEntry entry) {
            String path = "npcs." + entry.getName();
            plugin.getConfig().set(path + ".name", entry.getName());
            plugin.getConfig().set(path + ".type", entry.getType());
            plugin.getConfig().set(path + ".location", entry.getLocation());
            plugin.getConfig().set(path + ".guild", entry.getRequiredGuild());
            plugin.saveConfig();
        }

        private class NPCEntry {
            private final String name;
            private final String type;
            private final Location location;
            private final String requiredGuild;
            private Villager entity;

            public NPCEntry(String name, String type, Location location, String requiredGuild) {
                this.name = name;
                this.type = type;
                this.location = location;
                this.requiredGuild = requiredGuild;
            }
            public String getName() { return name; }
            public String getType() { return type; }
            public Location getLocation() { return location; }
            public String getRequiredGuild() { return requiredGuild; }
            public Villager getEntity() { return entity; }
            public void setEntity(Villager entity) { this.entity = entity; }
        }
    }

    // ==========================================
    //           ЗОНЫ
    // ==========================================
    public static class Zone {
        private final String name;
        private final Location center;
        private final int radius;
        private final String mobType;
        private final int mobCount;

        public Zone(String name, Location center, int radius, String mobType, int mobCount) {
            this.name = name;
            this.center = center;
            this.radius = radius;
            this.mobType = mobType;
            this.mobCount = mobCount;
        }

        public boolean isInside(Location loc) {
            return loc.getWorld().equals(center.getWorld()) && loc.distance(center) <= radius;
        }

        public Location getRandomLocation() {
            Random rand = new Random();
            double angle = rand.nextDouble() * 2 * Math.PI;
            double distance = rand.nextDouble() * radius;
            double dx = distance * Math.cos(angle);
            double dz = distance * Math.sin(angle);
            Location loc = center.clone().add(dx, 0, dz);
            loc.setY(center.getWorld().getHighestBlockYAt(loc));
            return loc;
        }

        public String getName() { return name; }
        public Location getCenter() { return center; }
        public int getRadius() { return radius; }
        public String getMobType() { return mobType; }
        public int getMobCount() { return mobCount; }
    }

    private void saveZones() {
        FileConfiguration config = getConfig();
        config.set("zones", null);
        for (Zone zone : zones.values()) {
            String path = "zones." + zone.getName();
            config.set(path + ".center", zone.getCenter());
            config.set(path + ".radius", zone.getRadius());
            config.set(path + ".mobType", zone.getMobType());
            config.set(path + ".mobCount", zone.getMobCount());
        }
        saveConfig();
    }

    private void loadZones() {
        if (getConfig().contains("zones")) {
            for (String key : getConfig().getConfigurationSection("zones").getKeys(false)) {
                String path = "zones." + key;
                Location center = getConfig().getLocation(path + ".center");
                int radius = getConfig().getInt(path + ".radius");
                String mobType = getConfig().getString(path + ".mobType", "ZOMBIE");
                int mobCount = getConfig().getInt(path + ".mobCount", 3);
                if (center != null) {
                    zones.put(key, new Zone(key, center, radius, mobType, mobCount));
                }
            }
        }
    }

    // ==========================================
    //           КВЕСТЫ
    // ==========================================
    public enum QuestType { KILL_MOBS, COLLECT_RESOURCES, KILL_BOSS }

    public static class QuestProgress {
        private final QuestType type;
        private int progress;
        private final int target;

        public QuestProgress(QuestType type, int progress, int target) {
            this.type = type;
            this.progress = progress;
            this.target = target;
        }

        public void incrementProgress() { progress++; }
        public boolean isComplete() { return progress >= target; }
        public QuestType getType() { return type; }
        public int getProgress() { return progress; }
        public int getTarget() { return target; }
    }

    // ==========================================
    //           ДАННЫЕ И УТИЛИТЫ
    // ==========================================
    public void markAsGuildItem(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(guildItemKey, PersistentDataType.BYTE, (byte) 1);
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.add("§b⚜ Артефакт Гильдии (Не выпадает)");
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private void loadLinks() {
        linkedFile = new File(getDataFolder(), "linked.yml");
        if (!linkedFile.exists()) { getDataFolder().mkdirs(); try { linkedFile.createNewFile(); } catch (IOException ignored) {} }
        linkedConfig = YamlConfiguration.loadConfiguration(linkedFile);
        for (String uuidStr : linkedConfig.getKeys(false)) linkedAccounts.put(UUID.fromString(uuidStr), linkedConfig.getString(uuidStr));
    }

    private void loadGameData() {
        File dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) return;
        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        if (data.contains("clans")) {
            for (String clanId : data.getConfigurationSection("clans").getKeys(false)) {
                String path = "clans." + clanId;
                Clan clan = new Clan(clanId, data.getString(path + ".name"), UUID.fromString(data.getString(path + ".leader")));
                clan.setRank(ClanRank.valueOf(data.getString(path + ".rank", "F")));
                clan.setGuildType(GuildType.valueOf(data.getString(path + ".guild", "NONE")));
                clan.addBankPoints(data.getLong(path + ".bank", 0));
                clan.setDiscordRoleId(data.getString(path + ".discordRole"));
                clan.setDiscordTextChannelId(data.getString(path + ".discordChannel"));
                clan.setDiscordVoiceChannelId(data.getString(path + ".discordVoice"));
                for (String m : data.getStringList(path + ".members")) clan.getMembers().add(UUID.fromString(m));
                clans.put(clanId, clan);
            }
        }
        if (data.contains("players")) {
            for (String uuidStr : data.getConfigurationSection("players").getKeys(false)) {
                ClanPlayer cp = new ClanPlayer(UUID.fromString(uuidStr));
                cp.setClanId(data.getString("players." + uuidStr + ".clanId"));
                cp.setMaxHealthLevel(data.getInt("players." + uuidStr + ".hpLevel", 0));
                cp.addContributedPoints(data.getLong("players." + uuidStr + ".contributed", 0));
                cp.setDailyPvEPoints(data.getInt("players." + uuidStr + ".dailyPoints", 0));
                cp.setLastFarmDate(data.getString("players." + uuidStr + ".farmDate", ""));
                cp.setPersonalPoints(data.getLong("players." + uuidStr + ".personalPoints", 0));
                cp.setLastClanCreation(data.getLong("players." + uuidStr + ".lastClanCreation", 0));
                cp.setLastClanExit(data.getLong("players." + uuidStr + ".lastClanExit", 0));
                players.put(cp.getUuid(), cp);
            }
        }
    }

    private void saveData() {
        File dataFile = new File(getDataFolder(), "data.yml");
        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        data.set("clans", null);
        for (Clan c : clans.values()) {
            String path = "clans." + c.getId();
            data.set(path + ".name", c.getName());
            data.set(path + ".leader", c.getLeader().toString());
            data.set(path + ".rank", c.getRank().name());
            data.set(path + ".guild", c.getGuildType().name());
            data.set(path + ".bank", c.getBankPoints());
            data.set(path + ".discordRole", c.getDiscordRoleId());
            data.set(path + ".discordChannel", c.getDiscordTextChannelId());
            data.set(path + ".discordVoice", c.getDiscordVoiceChannelId());
            data.set(path + ".members", c.getMembers().stream().map(UUID::toString).collect(Collectors.toList()));
        }
        data.set("players", null);
        for (ClanPlayer cp : players.values()) {
            String path = "players." + cp.getUuid().toString();
            data.set(path + ".clanId", cp.getClanId());
            data.set(path + ".hpLevel", cp.getMaxHealthLevel());
            data.set(path + ".contributed", cp.getContributedPoints());
            data.set(path + ".dailyPoints", cp.getDailyPvEPoints());
            data.set(path + ".farmDate", cp.getLastFarmDate());
            data.set(path + ".personalPoints", cp.getPersonalPoints());
            data.set(path + ".lastClanCreation", cp.getLastClanCreation());
            data.set(path + ".lastClanExit", cp.getLastClanExit());
        }
        try { data.save(dataFile); } catch (IOException ignored) {}
    }

    public ClanPlayer getClanPlayer(UUID uuid) {
        return players.computeIfAbsent(uuid, k -> new ClanPlayer(uuid));
    }

    // ==========================================
    //           ENUMS И ВНУТРЕННИЕ КЛАССЫ
    // ==========================================
    public enum GuildType {
        NONE("Без гильдии"),
        BLACKSMITH("Железный Легион"),
        MAGE("Тайный Орден"),
        ENGINEER("Гильдия Инженеров"),
        SMUGGLER("Синдикат");

        private final String displayName;
        GuildType(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    public enum ClanRank {
        F(0, "F"),
        F_PLUS(1500, "F+"),
        E(3500, "E"),
        E_PLUS(6000, "E+"),
        D(10000, "D"),
        D_PLUS(15000, "D+"),
        C(22000, "C"),
        C_PLUS(32000, "C+"),
        B(45000, "B"),
        B_PLUS(60000, "B+"),
        A(80000, "A"),
        A_PLUS(110000, "A+"),
        S(150000, "S"),
        S_PLUS(200000, "S+");

        private final long requiredPoints;
        private final String displayName;

        ClanRank(long requiredPoints, String displayName) {
            this.requiredPoints = requiredPoints;
            this.displayName = displayName;
        }
        public long getRequiredPoints() { return requiredPoints; }
        public String getDisplayName() { return displayName; }
    }

    public static class ClanPlayer {
        private final UUID uuid;
        private String clanId;
        private int maxHealthLevel = 0;
        private boolean clanChatEnabled = false;
        private long contributedPoints = 0;
        private int dailyPvEPoints = 0;
        private String lastFarmDate = "";
        private long personalPoints = 0;
        private long lastClanCreation = 0;
        private long lastClanExit = 0;
        private int dailyPvpPoints = 0;
        private String lastPvpDate = "";

        public ClanPlayer(UUID uuid) { this.uuid = uuid; }
        public UUID getUuid() { return uuid; }
        public String getClanId() { return clanId; }
        public void setClanId(String c) { this.clanId = c; }
        public int getMaxHealthLevel() { return maxHealthLevel; }
        public void setMaxHealthLevel(int l) { this.maxHealthLevel = l; }
        public boolean isClanChatEnabled() { return clanChatEnabled; }
        public void setClanChatEnabled(boolean b) { this.clanChatEnabled = b; }
        public long getContributedPoints() { return contributedPoints; }
        public void addContributedPoints(long amt) { this.contributedPoints += amt; }
        public int getDailyPvEPoints() { return dailyPvEPoints; }
        public void setDailyPvEPoints(int p) { this.dailyPvEPoints = p; }
        public String getLastFarmDate() { return lastFarmDate; }
        public void setLastFarmDate(String d) { this.lastFarmDate = d; }
        public long getPersonalPoints() { return personalPoints; }
        public void addPersonalPoints(long amt) { this.personalPoints += amt; }
        public boolean spendPersonalPoints(long amt) {
            if (personalPoints >= amt) {
                personalPoints -= amt;
                return true;
            }
            return false;
        }
        public void setPersonalPoints(long p) { this.personalPoints = p; }
        public long getLastClanCreation() { return lastClanCreation; }
        public void setLastClanCreation(long t) { this.lastClanCreation = t; }
        public long getLastClanExit() { return lastClanExit; }
        public void setLastClanExit(long t) { this.lastClanExit = t; }
        public int getDailyPvpPoints() { return dailyPvpPoints; }
        public void setDailyPvpPoints(int p) { this.dailyPvpPoints = p; }
        public String getLastPvpDate() { return lastPvpDate; }
        public void setLastPvpDate(String d) { this.lastPvpDate = d; }
    }

    public static class Clan {
        private String id;
        private String name;
        private UUID leader;
        private Set<UUID> members = new HashSet<>();
        private ClanRank rank = ClanRank.F;
        private GuildType selectedGuild = GuildType.NONE;
        private long bankPoints = 0;
        private Location nexusLocation;
        private String discordRoleId;
        private String discordTextChannelId;
        private String discordVoiceChannelId;

        public Clan(String id, String name, UUID leader) {
            this.id = id;
            this.name = name;
            this.leader = leader;
            this.members.add(leader);
        }
        public String getId() { return id; }
        public String getName() { return name; }
        public UUID getLeader() { return leader; }
        public Set<UUID> getMembers() { return members; }
        public ClanRank getRank() { return rank; }
        public void setRank(ClanRank r) { this.rank = r; }
        public GuildType getGuildType() { return selectedGuild; }
        public void setGuildType(GuildType t) { this.selectedGuild = t; }
        public long getBankPoints() { return bankPoints; }
        public void addBankPoints(long p) { this.bankPoints += p; }
        public void setBankPoints(long p) { this.bankPoints = p; }
        public Location getNexusLocation() { return nexusLocation; }
        public void setNexusLocation(Location l) { this.nexusLocation = l; }
        public String getDiscordRoleId() { return discordRoleId; }
        public void setDiscordRoleId(String i) { this.discordRoleId = i; }
        public String getDiscordTextChannelId() { return discordTextChannelId; }
        public void setDiscordTextChannelId(String i) { this.discordTextChannelId = i; }
        public String getDiscordVoiceChannelId() { return discordVoiceChannelId; }
        public void setDiscordVoiceChannelId(String i) { this.discordVoiceChannelId = i; }
    }

    public static class PendingVerification {
        public String ip;
        public long timestamp;
        public PendingVerification(String ip, long t) { this.ip = ip; this.timestamp = t; }
    }
}
