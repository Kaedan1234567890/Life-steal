package com.chillzone.lifesteal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Independent Chill Zone implementation; does not load the old Lifesteal datapack. */
public final class ChillZoneLifesteal implements ModInitializer {
    public static final String BUILD_VERSION = "0.4.9-alpha.31-command-fix";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String HEART_MARKER = "chillzone_lifesteal_heart";
    private static final String REVIVE_BEACON_MARKER = "chillzone_lifesteal_revive_beacon";
    private static MinecraftServer server;
    private static State state = new State();
    private static Path saveFile;
    private static long ticks;
    // Test implementation: combat tag duration is configurable in the built-in admin menu.
    private static final Map<UUID, Long> combatTaggedUntil = new HashMap<>();
    private static final Map<UUID, UUID> mostRecentCombatOpponent = new HashMap<>();
    private static final Set<UUID> combatLoggedOnDisconnect = new HashSet<>();
    private static final Set<UUID> handled = new HashSet<>();
    private static final Set<UUID> pendingKicks = new HashSet<>();

    private static class State {
        int startHearts = 10;
        int maxHearts = 20;
        int minHearts = 1;
        boolean pvpLoss = true;
        boolean pvpEnabled = true;
        Set<String> pendingReviveSpawns = new HashSet<>();
        boolean naturalDeathLoss = false;
        boolean stealOnKill = true;
        Map<String, Integer> hearts = new HashMap<>();
        boolean deathBans = true;
        int combatTagSeconds = 30; // Configurable 15/30/60/90/120 seconds.
        boolean combatTagEnabled = true;
        boolean containersAllowed = false;
        Map<String, Integer> craftedHeartsInWindow = new HashMap<>();
        Map<String, Long> heartCraftWindowStartedAt = new HashMap<>();
        Map<String, String> knownNames = new HashMap<>();
        Set<String> eliminated = new HashSet<>();
        Map<String, String> eliminatedNames = new HashMap<>();
        Map<String, String> eliminatedKillers = new HashMap<>();
    }

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            saveFile = s.getWorldPath(LevelResource.ROOT).resolve("data/chillzone-lifesteal.json");
            try { if (Files.isRegularFile(saveFile)) state = JSON.fromJson(Files.readString(saveFile), State.class); }
            catch (Exception e) { System.err.println("[ChillZoneLifesteal] Unable to read state: " + e); }
            if (state == null || state.hearts == null) state = new State();
            if (state.eliminated == null) state.eliminated = new HashSet<>();
            if (state.pendingReviveSpawns == null) state.pendingReviveSpawns = new HashSet<>();
            if (state.knownNames == null) state.knownNames = new HashMap<>();
            if (state.eliminatedNames == null) state.eliminatedNames = new HashMap<>();
            if (state.eliminatedKillers == null) state.eliminatedKillers = new HashMap<>();
            if (state.craftedHeartsInWindow == null) state.craftedHeartsInWindow = new HashMap<>();
            if (state.heartCraftWindowStartedAt == null) state.heartCraftWindowStartedAt = new HashMap<>();
            state.maxHearts = Math.max(1, state.maxHearts);
            for (ServerPlayer p : s.getPlayerList().getPlayers()) applyHealth(p);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerPlayer p = handler.player;
            if (!isCombatTagged(p)) return;
            combatTaggedUntil.remove(p.getUUID());
            combatLoggedOnDisconnect.add(p.getUUID());
            s.getPlayerList().broadcastSystemMessage(Component.literal("[COMBAT LOG] " + p.getGameProfile().name() + " has combat logged!")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);
            // Keep the death in the normal game lifecycle so inventories drop and
            // existing AFTER_DEATH Lifesteal processing runs. This is not /kill command execution.
            UUID attackerId = mostRecentCombatOpponent.get(p.getUUID());
            ServerPlayer lastAttacker = attackerId == null ? null : s.getPlayerList().getPlayer(attackerId);
            p.hurtServer((net.minecraft.server.level.ServerLevel) p.level(),
                lastAttacker == null ? p.damageSources().genericKill() : p.damageSources().playerAttack(lastAttacker), Float.MAX_VALUE);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer player = handler.player;
            if (combatLoggedOnDisconnect.remove(player.getUUID())) {
                player.sendSystemMessage(Component.literal("You combat logged, and now you have to pay for it.")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            }
            state.knownNames.put(player.getUUID().toString(), player.getGameProfile().name());
            save();
            if (state.deathBans && state.eliminated.contains(player.getUUID().toString())) {
                handler.disconnect(Component.literal("You have been death banned from Chill Zone SMP. You must be revived to rejoin.").withStyle(ChatFormatting.RED));
            }
            // Pending revival relocation is processed after the player has joined the world.
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
            // Server-side open-screen cleanup. Doesn't yet protect unattended block entities
            // from hopper or other automated transfers; see RELEASE_NOTES.
            for (ServerPlayer player : s.getPlayerList().getPlayers()) {
                if (!state.containersAllowed) ejectRestrictedFromOpenContainer(player);
            }
            combatTaggedUntil.entrySet().removeIf(e -> e.getValue() <= ticks);
            // A pending revival is not cleared until a safe teleport has been implemented and confirmed.

            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                long remainingTicks = combatTaggedUntil.getOrDefault(p.getUUID(), 0L) - ticks;
                if (remainingTicks > 0) {
                    p.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("IN COMBAT - " + ((remainingTicks + 19L) / 20L) + "s")
                            .withStyle(ChatFormatting.RED)));
                }
            }
            if (!pendingKicks.isEmpty()) {
                for (UUID id : new HashSet<>(pendingKicks)) {
                    ServerPlayer target = s.getPlayerList().getPlayer(id);
                    if (target != null) target.connection.disconnect(Component.literal("You have been death banned from Chill Zone SMP. You must be revived to rejoin.").withStyle(ChatFormatting.RED));
                    pendingKicks.remove(id);
                }
            }
            if (ticks % 40 == 0) {
                for (ServerPlayer p : s.getPlayerList().getPlayers()) applyHealth(p);
            }
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, damage, baseDamageTaken, damageTaken, blocked) -> {
            if (!state.combatTagEnabled || !state.pvpEnabled) return;
            if (!(entity instanceof ServerPlayer victim)) return;
            if (!(damage.getEntity() instanceof ServerPlayer attacker)) return;
            if (attacker.getUUID().equals(victim.getUUID())) return;
            long expires = ticks + Math.max(1, state.combatTagSeconds) * 20L;
            combatTaggedUntil.put(victim.getUUID(), expires);
            combatTaggedUntil.put(attacker.getUUID(), expires);
            mostRecentCombatOpponent.put(victim.getUUID(), attacker.getUUID());
            mostRecentCombatOpponent.put(attacker.getUUID(), victim.getUUID());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damage) -> {
            if (!(entity instanceof ServerPlayer victim)) return;
            ServerPlayer killer = damage.getEntity() instanceof ServerPlayer p && !p.getUUID().equals(victim.getUUID()) ? p : null;
            if (killer != null && state.pvpLoss && state.pvpEnabled) {
                int before = heartCount(victim);
                if (before > state.minHearts) {
                    setHearts(victim, before - 1);
                    victim.sendSystemMessage(Component.literal("You lost a heart. " + heartCount(victim) + " hearts remaining.").withStyle(ChatFormatting.RED));
                    if (state.stealOnKill) {
                        if (heartCount(killer) < state.maxHearts) {
                            setHearts(killer, heartCount(killer) + 1);
                        } else {
                            ItemStack reward = makeHeart();
                            if (!killer.getInventory().add(reward)) killer.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(killer.level(), killer.getX(), killer.getY(), killer.getZ(), reward));
                        }
                        killer.sendSystemMessage(Component.literal("You now have " + heartCount(killer) + " hearts!").withStyle(ChatFormatting.GREEN));
                    }
                } else if (state.deathBans) {
                    // Keep the original heart minimum but record elimination separately from moderation bans.
                    state.eliminated.add(victim.getUUID().toString());
                    state.eliminatedNames.put(victim.getUUID().toString(), victim.getGameProfile().name());
                    state.eliminatedKillers.put(victim.getUUID().toString(), killer.getGameProfile().name());
                    save();
                    victim.sendSystemMessage(Component.literal("You lost your final heart. You have been death banned from Chill Zone SMP until revived.").withStyle(ChatFormatting.RED));
                    // Disconnect next tick: do not mutate the connection during the death callback.
                    pendingKicks.add(victim.getUUID());
                    if (state.stealOnKill) {
                        if (heartCount(killer) < state.maxHearts) setHearts(killer, heartCount(killer) + 1);
                        else { ItemStack reward = makeHeart(); if (!killer.getInventory().add(reward)) killer.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(killer.level(), killer.getX(), killer.getY(), killer.getZ(), reward)); }
                    }
                }
            } else if (killer == null && state.naturalDeathLoss && heartCount(victim) > state.minHearts) {
                setHearts(victim, heartCount(victim) - 1);
            }
        });
        // Special Revive Beacons must open the same server-side GUI in every use context.
        // Return SUCCESS before vanilla block placement/interaction; this prevents any
        // transient beacon block and avoids losing the item when the menu is canceled.
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            ItemStack item = player.getItemInHand(hand);
            if (isReviveBeacon(item)) return openReviveFromItem(p);
            if (!isHeart(item)) return InteractionResult.PASS;
            if (heartCount(p) >= state.maxHearts) {
                p.sendSystemMessage(Component.literal("You already have the maximum hearts.").withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            setHearts(p, heartCount(p) + 1);
            if (!p.getAbilities().instabuild) item.shrink(1);
            p.sendSystemMessage(Component.literal("You now have " + heartCount(p) + " hearts!").withStyle(ChatFormatting.GREEN));
            return InteractionResult.SUCCESS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (player instanceof ServerPlayer p && isReviveBeacon(player.getItemInHand(hand))) {
                return openReviveFromItem(p);
            }
            return InteractionResult.PASS;
        });
        // Clicking an entity also counts as right-clicking with the special beacon.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (player instanceof ServerPlayer p && isReviveBeacon(player.getItemInHand(hand))) {
                return openReviveFromItem(p);
            }
            return InteractionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> {
            dispatcher.register(Commands.literal("lifestealbuild").executes(ctx -> {
                ctx.getSource().sendSuccess(() -> Component.literal("Chill Zone Lifesteal " + BUILD_VERSION).withStyle(ChatFormatting.GREEN), false);
                return 1;
            }));
            dispatcher.register(Commands.literal("withdraw")
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                    if (heartCount(p) - amount < state.minHearts) {
                        p.sendSystemMessage(Component.literal("You must keep at least " + state.minHearts + " heart(s).").withStyle(ChatFormatting.RED));
                        return 0;
                    }
                    // Give only what actually fits; never deduct hearts for an item that was not delivered.
                    int delivered = 0;
                    for (int i = 0; i < amount; i++) {
                        ItemStack item = makeHeart();
                        if (!p.getInventory().add(item)) break;
                        delivered++;
                    }
                    if (delivered == 0) {
                        p.sendSystemMessage(Component.literal("Your inventory is full. No hearts were withdrawn.").withStyle(ChatFormatting.RED));
                        return 0;
                    }
                    setHearts(p, heartCount(p) - delivered);
                    p.sendSystemMessage(Component.literal("Withdrew " + delivered + " heart(s); you have " + heartCount(p) + " remaining."));
                    if (delivered < amount) p.sendSystemMessage(Component.literal("Inventory full: " + (amount - delivered) + " heart(s) were not withdrawn.").withStyle(ChatFormatting.YELLOW));
                    return 1;
                })));
            var root = Commands.literal("lifesteal");
            root.executes(ctx -> { ctx.getSource().sendSuccess(() -> Component.literal("Chill Zone Lifesteal: /withdraw <amount>, /lifesteal hearts"), false); return 1; });
            root.then(Commands.literal("hearts").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                p.sendSystemMessage(Component.literal("Your hearts: " + heartCount(p) + "/" + state.maxHearts)); return 1;
            }));
            root.then(Commands.literal("list").executes(ctx -> {
                // Read existing saved heart totals; do not create records for this query.
                List<Map.Entry<String, Integer>> ranking = new ArrayList<>(state.hearts.entrySet());
                ranking.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> e.getValue()).reversed()
                    .thenComparing(e -> state.knownNames.getOrDefault(e.getKey(), e.getKey()).toLowerCase(Locale.ROOT))
                    .thenComparing(Map.Entry::getKey));
                ctx.getSource().sendSuccess(() -> Component.literal("--- Chill Zone Lifesteal Top 10 ---").withStyle(ChatFormatting.GOLD), false);
                if (ranking.isEmpty()) ctx.getSource().sendSuccess(() -> Component.literal("No heart records yet."), false);
                for (int i = 0; i < Math.min(10, ranking.size()); i++) {
                    var entry = ranking.get(i);
                    String name = state.knownNames.getOrDefault(entry.getKey(), state.eliminatedNames.getOrDefault(entry.getKey(), entry.getKey()));
                    int place = i + 1;
                    ctx.getSource().sendSuccess(() -> Component.literal("#" + place + " | " + name + ": " + entry.getValue() + " hearts").withStyle(place <= 3 ? ChatFormatting.YELLOW : ChatFormatting.GRAY), false);
                }
                return 1;
            }));
            root.then(Commands.literal("recipe")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("Usage: /lifesteal recipe heart OR /lifesteal recipe beacon").withStyle(ChatFormatting.YELLOW), false);
                    return 1;
                })
                .then(Commands.literal("heart").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("--- Heart crafting recipe (3x3) ---").withStyle(ChatFormatting.RED), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Trial Key | Ghast Tear | Ominous Trial Key"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Special Ingredient | Enchanted Golden Apple | Special Ingredient"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Ominous Trial Key | Ghast Tear | Trial Key"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Heart crafting limit: 3 per 24h window, beginning with your first craft.").withStyle(ChatFormatting.YELLOW), false);
                    return 1;
                }))
                .then(Commands.literal("beacon").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("--- Revive Beacon crafting recipe (3x3) ---").withStyle(ChatFormatting.GOLD), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Diamond Block | Netherite Ingot | Diamond Block"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Netherite Ingot | Beacon | Netherite Ingot"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("Diamond Block | Lifesteal Heart | Diamond Block"), false);
                    return 1;
                })));
            root.then(Commands.literal("craftstatus").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                refreshHeartCraftWindow(p.getUUID().toString());
                String id = p.getUUID().toString();
                int count = state.craftedHeartsInWindow.getOrDefault(id, 0);
                long start = state.heartCraftWindowStartedAt.getOrDefault(id, 0L);
                long remaining = start == 0 ? 0 : Math.max(0L, 86400000L - (System.currentTimeMillis() - start));
                p.sendSystemMessage(Component.literal("Daily Hearts Crafted: " + count + "/3. Hearts Remaining: " + Math.max(0, 3 - count) + ". " +
                    (start == 0 ? "Your window starts with your first craft." : "Resets in " + (remaining / 3600000) + "h " + ((remaining % 3600000) / 60000) + "m.")).withStyle(ChatFormatting.YELLOW));
                return 1;
            }));
            root.then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("skip")
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        clearHeartCraftWindow(p.getUUID().toString());
                        ctx.getSource().sendSuccess(() -> Component.literal("Heart crafting reset: 0/3 crafted, cooldown 0h 0m."), false);
                        return 1;
                    })
                    .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                        ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                        clearHeartCraftWindow(p.getUUID().toString());
                        ctx.getSource().sendSuccess(() -> Component.literal("Heart crafting reset for " + p.getGameProfile().name() + ": 0/3 crafted, cooldown 0h 0m."), false);
                        return 1;
                    })))
                .then(Commands.literal("craftskip")
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        clearHeartCraftWindow(p.getUUID().toString());
                        ctx.getSource().sendSuccess(() -> Component.literal("Your Heart crafting window was reset."), false);
                        return 1;
                    })
                    .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                        ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
                        clearHeartCraftWindow(p.getUUID().toString());
                        ctx.getSource().sendSuccess(() -> Component.literal("Heart crafting window reset for " + p.getGameProfile().name()), false);
                        return 1;
                    })))
                .then(Commands.literal("giveheart").then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> giveItems(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"), 1, false))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64)).executes(ctx ->
                        giveItems(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"),
                            IntegerArgumentType.getInteger(ctx, "amount"), false)))))
                .then(Commands.literal("giverevivebeacon").then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> giveItems(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"), 1, true))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64)).executes(ctx ->
                        giveItems(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"),
                            IntegerArgumentType.getInteger(ctx, "amount"), true)))))
                .then(Commands.literal("sethearts").then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100)).executes(ctx -> {
                        setHearts(EntityArgument.getPlayer(ctx, "player"), IntegerArgumentType.getInteger(ctx, "amount")); return 1;
                    }))))
                .then(Commands.literal("maxhearts").then(Commands.argument("amount", IntegerArgumentType.integer(1, 100)).executes(ctx -> {
                    state.maxHearts = IntegerArgumentType.getInteger(ctx, "amount"); save(); return 1;
                })))
                .then(Commands.literal("pvp")
                    .then(Commands.literal("on").executes(ctx -> { state.pvpEnabled=true; save(); ctx.getSource().sendSuccess(() -> Component.literal("Lifesteal PvP enabled."),true); return 1;}))
                    .then(Commands.literal("off").executes(ctx -> { state.pvpEnabled=false; combatTaggedUntil.clear(); save(); ctx.getSource().sendSuccess(() -> Component.literal("Lifesteal PvP disabled (heart transfers and tags)."),true); return 1;})))
                .then(Commands.literal("pvploss").then(Commands.literal("on").executes(ctx -> {state.pvpLoss=true;save();return 1;}))
                    .then(Commands.literal("off").executes(ctx -> {state.pvpLoss=false;save();return 1;})))
                .then(Commands.literal("naturalloss").then(Commands.literal("on").executes(ctx -> {state.naturalDeathLoss=true;save();return 1;}))
                    .then(Commands.literal("off").executes(ctx -> {state.naturalDeathLoss=false;save();return 1;})))
                .then(Commands.literal("deathbans").then(Commands.literal("on").executes(ctx -> {state.deathBans=true;save();return 1;}))
                    .then(Commands.literal("off").executes(ctx -> {state.deathBans=false;save();return 1;})))
                .then(Commands.literal("containers")
                    .then(Commands.literal("on").executes(ctx -> { state.containersAllowed = true; save(); ctx.getSource().sendSuccess(() -> Component.literal("Lifesteal container cleanup disabled: special items are allowed."), true); return 1; }))
                    .then(Commands.literal("off").executes(ctx -> { state.containersAllowed = false; save(); ctx.getSource().sendSuccess(() -> Component.literal("Lifesteal container cleanup enabled: special items are disallowed."), true); return 1; })))
                .then(Commands.literal("combat").then(Commands.literal("status").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    long remaining = Math.max(0, (combatTaggedUntil.getOrDefault(p.getUUID(), 0L) - ticks + 19) / 20);
                    p.sendSystemMessage(Component.literal("Combat tag: " + remaining + "s; configured duration: " + state.combatTagSeconds + "s"));
                    return 1;
                })))
                .then(Commands.literal("reset")
                    .executes(ctx -> {
                        ctx.getSource().sendFailure(Component.literal("To erase all Lifesteal hearts and eliminations, use: /lifesteal admin reset CONFIRM").withStyle(ChatFormatting.YELLOW));
                        return 0;
                    })
                    .then(Commands.literal("CONFIRM").executes(ctx -> {
                        // Keep settings; clear only Season-player state and death bans.
                        state.hearts.clear();
                        state.eliminated.clear();
                        state.eliminatedNames.clear();
                        state.eliminatedKillers.clear();
                        pendingKicks.clear();
                        combatTaggedUntil.clear();
                        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                            setHearts(online, state.startHearts);
                        }
                        save();
                        ctx.getSource().sendSuccess(() -> Component.literal("Lifesteal season data reset: online players start at " + state.startHearts + " hearts; all death bans cleared. Offline players will use the starting total when they return.").withStyle(ChatFormatting.GREEN), true);
                        return 1;
                    })))
                .then(Commands.literal("revive").executes(ctx -> {
                    openReviveMenu(ctx.getSource().getPlayerOrException(), true, 0); return 1;
                })));
            dispatcher.register(root);
        });
    }
    /** Return only marked Lifesteal items that have entered the external slots of an open menu. */
    private static void ejectRestrictedFromOpenContainer(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu || menu instanceof ReviveMenu || menu instanceof CombatSettingsMenu
                || menu instanceof net.minecraft.world.inventory.CraftingMenu) return;
        Inventory own = player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || (!isHeart(stack) && !isReviveBeacon(stack))) continue;
            // Never delete items if the player inventory has no room.
            ItemStack copy = stack.copy();
            // Clear the external slot first and insert the marked stack into the player inventory.
            // If it cannot all fit, drop the remainder at the player rather than leaving it in storage.
            slot.set(ItemStack.EMPTY);
            slot.setChanged();
            own.add(copy);
            if (!copy.isEmpty()) {
                net.minecraft.world.entity.item.ItemEntity dropped = new net.minecraft.world.entity.item.ItemEntity(
                    player.level(), player.getX(), player.getY() + 0.5, player.getZ(), copy);
                player.level().addFreshEntity(dropped);
            }
            player.sendSystemMessage(Component.literal("Lifesteal items cannot be stored here.").withStyle(ChatFormatting.YELLOW));
        }
    }

    // Alpha 13: navigation test for the original Season 1 settings categories.
    // Only the Combat Tag controls are functional. Other categories are deliberately
    // read-only previews until the original Combat implementation is available.
    private static final String[] COMBAT_CATEGORIES = {
        "Inventory Limits", "Item Cooldowns", "Combat Log Settings",
        "World Item Limits", "Enchant Level Limits", "Potion Control"
    };
    private static final net.minecraft.world.item.Item[] COMBAT_ICONS = {
        Items.CHEST, Items.CLOCK, Items.IRON_SWORD,
        Items.HOPPER, Items.ENCHANTED_BOOK, Items.POTION
    };
    private static void openCombatSettingsMenu(ServerPlayer player) {
        SimpleContainer chest = new SimpleContainer(27);
        int[] slots = {10, 11, 12, 14, 15, 16};
        for (int i = 0; i < COMBAT_CATEGORIES.length; i++) {
            ItemStack icon = new ItemStack(COMBAT_ICONS[i]);
            icon.set(DataComponents.CUSTOM_NAME, Component.literal(COMBAT_CATEGORIES[i]).withStyle(ChatFormatting.GOLD));
            chest.setItem(slots[i], icon);
        }
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.literal("Chill Zone Combat Settings"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player p) {
                return new CombatSettingsMenu(id, inventory, chest, slot -> {
                    for (int i = 0; i < slots.length; i++) {
                        if (slot == slots[i]) { openCombatCategory(player, i); return; }
                    }
                });
            }
        });
    }
    private static void openCombatCategory(ServerPlayer player, int category) {
        SimpleContainer chest = new SimpleContainer(27);
        ItemStack back = new ItemStack(Items.ARROW);
        back.set(DataComponents.CUSTOM_NAME, Component.literal("Back to Settings").withStyle(ChatFormatting.YELLOW));
        chest.setItem(22, back);
        if (category == 2) {
            ItemStack enabled = new ItemStack(state.combatTagEnabled ? Items.EMERALD : Items.REDSTONE);
            enabled.set(DataComponents.CUSTOM_NAME, Component.literal("Combat Tag: " + (state.combatTagEnabled ? "ON" : "OFF")));
            chest.setItem(10, enabled);
            int[] durations = {15, 30, 60, 90, 120};
            for (int i=0;i<durations.length;i++) {
                ItemStack clock = new ItemStack(Items.CLOCK);
                clock.set(DataComponents.CUSTOM_NAME, Component.literal("Duration: " + durations[i] + " seconds" + (state.combatTagSeconds == durations[i] ? " (Selected)" : "")));
                chest.setItem(12+i, clock);
            }
        } else {
            ItemStack info = new ItemStack(Items.BOOK);
            info.set(DataComponents.CUSTOM_NAME, Component.literal("Preview only - restrictions not implemented").withStyle(ChatFormatting.RED));
            chest.setItem(13, info);
        }
        player.openMenu(new MenuProvider() {
            @Override public Component getDisplayName() { return Component.literal(COMBAT_CATEGORIES[category]); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player p) {
                return new CombatSettingsMenu(id, inventory, chest, slot -> {
                    if (slot == 22) {openCombatSettingsMenu(player); return;}
                    if (category != 2) return;
                    if (slot == 10) state.combatTagEnabled = !state.combatTagEnabled;
                    else if (slot == 12) state.combatTagSeconds = 15;
                    else if (slot == 13) state.combatTagSeconds = 30;
                    else if (slot == 14) state.combatTagSeconds = 60;
                    else if (slot == 15) state.combatTagSeconds = 90;
                    else if (slot == 16) state.combatTagSeconds = 120;
                    else return;
                    save();
                    openCombatCategory(player, category);
                });
            }
        });
    }

    private static final class CombatSettingsMenu extends ChestMenu {
        private final java.util.function.IntConsumer onClick;
        CombatSettingsMenu(int id, Inventory inv, SimpleContainer chest, java.util.function.IntConsumer onClick) {
            super(MenuType.GENERIC_9x3, id, inv, chest, 3);
            this.onClick = onClick;
        }
        @Override public void clicked(int slot, int button, ContainerInput click, Player player) {
            if (slot >= 0 && slot < 27) {
                if (click == ContainerInput.PICKUP && button == 0) onClick.accept(slot);
                return;
            }
            super.clicked(slot, button, click, player);
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    }

    private static boolean isReviveBeacon(ItemStack item) {
        if (!item.is(Items.BEACON)) return false;
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(REVIVE_BEACON_MARKER).orElse(false);
    }

    private static List<String> eliminatedSorted() {
        List<String> ids = new ArrayList<>(state.eliminated);
        ids.sort(Comparator.comparing(id -> state.eliminatedNames.getOrDefault(id,id).toLowerCase(Locale.ROOT)));
        return ids;
    }

    private static InteractionResult openReviveFromItem(ServerPlayer player) {
        // Do not consume the beacon or place a block: confirmation consumes one only
        // when a player was actually revived. Cancel/close therefore costs nothing.
        openReviveMenu(player, false, 0);
        return InteractionResult.SUCCESS;
    }

    private static void openReviveMenu(ServerPlayer player, boolean admin, int page) {
        if (!admin && !hasReviveBeacon(player)) {
            player.sendSystemMessage(Component.literal("You need a Revive Beacon in your inventory.").withStyle(ChatFormatting.RED));
            return;
        }
        List<String> ids = eliminatedSorted();
        int maxPage = Math.max(0,(ids.size()-1)/18);
        int actualPage = Math.max(0,Math.min(maxPage,page));
        SimpleContainer inventory = new SimpleContainer(27);
        for (int i=0;i<18;i++) {
            int index=actualPage*18+i;
            if (index>=ids.size()) break;
            String id=ids.get(index);
            ItemStack head=new ItemStack(Items.PLAYER_HEAD);
            String name=state.eliminatedNames.getOrDefault(id,id);
            head.set(DataComponents.CUSTOM_NAME, Component.literal(name+" — Eliminated by "+state.eliminatedKillers.getOrDefault(id,"Unknown")).withStyle(ChatFormatting.RED));
            inventory.setItem(i,head);
        }
        ItemStack prev=new ItemStack(Items.ARROW);prev.set(DataComponents.CUSTOM_NAME,Component.literal("Previous page"));inventory.setItem(18,prev);
        ItemStack info=new ItemStack(Items.BOOK);info.set(DataComponents.CUSTOM_NAME,Component.literal("Revive players — Page "+(actualPage+1)+"/"+(maxPage+1)));inventory.setItem(22,info);
        ItemStack next=new ItemStack(Items.ARROW);next.set(DataComponents.CUSTOM_NAME,Component.literal("Next page"));inventory.setItem(26,next);
        player.openMenu(new MenuProvider(){
            @Override public Component getDisplayName(){return Component.literal("Revive Players");}
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p){
                return new ReviveMenu(id,inv,inventory,(slot)->{
                    if(slot==18 && actualPage>0) openReviveMenu(player,admin,actualPage-1);
                    else if(slot==26 && actualPage<maxPage) openReviveMenu(player,admin,actualPage+1);
                    else if(slot>=0 && slot<18 && actualPage*18+slot<ids.size()) openConfirmMenu(player,admin,ids.get(actualPage*18+slot));
                });
            }
        });
    }
    private static boolean hasReviveBeacon(ServerPlayer p){
        for(int i=0;i<p.getInventory().getContainerSize();i++) if(isReviveBeacon(p.getInventory().getItem(i))) return true;
        return false;
    }
    private static boolean consumeReviveBeacon(ServerPlayer p) {
        for(int i=0;i<p.getInventory().getContainerSize();i++) {
            ItemStack item=p.getInventory().getItem(i);
            if(isReviveBeacon(item)) {item.shrink(1);return true;}
        }
        return false;
    }
    private static void openConfirmMenu(ServerPlayer player,boolean admin,String targetId){
        if (!state.eliminated.contains(targetId)) {openReviveMenu(player,admin,0);return;}
        SimpleContainer inv=new SimpleContainer(27);
        String name=state.eliminatedNames.getOrDefault(targetId,targetId);
        ItemStack cancel=new ItemStack(Items.REDSTONE);cancel.set(DataComponents.CUSTOM_NAME,Component.literal("Cancel").withStyle(ChatFormatting.RED));inv.setItem(11,cancel);
        ItemStack head=new ItemStack(Items.PLAYER_HEAD);head.set(DataComponents.CUSTOM_NAME,Component.literal(name));inv.setItem(13,head);
        ItemStack confirm=new ItemStack(Items.EMERALD);confirm.set(DataComponents.CUSTOM_NAME,Component.literal("Confirm revival").withStyle(ChatFormatting.GREEN));inv.setItem(15,confirm);
        player.openMenu(new MenuProvider(){
            @Override public Component getDisplayName(){return Component.literal("Revive "+name+"?");}
            @Override public AbstractContainerMenu createMenu(int id,Inventory inventory,Player p){
                return new ReviveMenu(id,inventory,inv,slot->{
                    if(slot==11){openReviveMenu(player,admin,0);return;}
                    if(slot!=15) return;
                    if(!state.eliminated.contains(targetId)){player.sendSystemMessage(Component.literal("Already revived.").withStyle(ChatFormatting.RED));openReviveMenu(player,admin,0);return;}
                    if(!admin && !consumeReviveBeacon(player)){player.sendSystemMessage(Component.literal("Revive Beacon missing; revival canceled.").withStyle(ChatFormatting.RED));player.closeContainer();return;}
                    state.eliminated.remove(targetId);
                    state.pendingReviveSpawns.add(targetId);
                    state.hearts.put(targetId,Math.min(state.maxHearts,4));
                    save();
                    player.sendSystemMessage(Component.literal("Successfully revived "+name+" with 4 hearts!").withStyle(ChatFormatting.GREEN));
                    player.closeContainer();
                });
            }
        });
    }
    private static class ReviveMenu extends ChestMenu {
        private final java.util.function.IntConsumer handler;
        ReviveMenu(int id, Inventory inv, SimpleContainer chest,java.util.function.IntConsumer handler){
            super(MenuType.GENERIC_9x3,id,inv,chest,3);this.handler=handler;
        }
        @Override public void clicked(int slot,int button,ContainerInput click,Player player){
            if(slot>=0 && slot<27){if(click==ContainerInput.PICKUP && button==0) handler.accept(slot);return;}
            super.clicked(slot,button,click,player);
        }
        @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
    }

    private static int heartCount(ServerPlayer p) { return state.hearts.computeIfAbsent(p.getUUID().toString(), ignored -> state.startHearts); }
    private static void setHearts(ServerPlayer p, int amount) {
        state.hearts.put(p.getUUID().toString(), Math.max(1, Math.min(state.maxHearts, amount)));
        applyHealth(p); save();
    }
    private static void applyHealth(ServerPlayer p) {
        var attribute = p.getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null) return;
        double target = heartCount(p) * 2.0;
        if (attribute.getBaseValue() != target) attribute.setBaseValue(target);
        if (p.getHealth() > target) p.setHealth((float) target);
    }
    private static ItemStack makeHeart() {
        ItemStack item = new ItemStack(Items.NETHER_STAR);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Heart").withStyle(style -> style.withColor(ChatFormatting.RED).withBold(true).withItalic(false)));
        CompoundTag tag = new CompoundTag(); tag.putBoolean(HEART_MARKER, true);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return item;
    }
    private static ItemStack makeReviveBeacon() {
        ItemStack item = new ItemStack(Items.BEACON);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Revive Beacon").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        CompoundTag tag = new CompoundTag(); tag.putBoolean(REVIVE_BEACON_MARKER, true);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return item;
    }

    private static int giveItems(ServerPlayer executor, ServerPlayer target, int requested, boolean beacon) {
        int delivered = 0;
        for (int i = 0; i < requested; i++) {
            if (!target.getInventory().add(beacon ? makeReviveBeacon() : makeHeart())) break;
            delivered++;
        }
        executor.sendSystemMessage(Component.literal("Delivered " + delivered + "/" + requested
            + (beacon ? " Revive Beacon(s)" : " Heart(s)") + " to " + target.getName().getString() + "."));
        if (delivered < requested) executor.sendSystemMessage(Component.literal("Remaining items not given: target inventory is full.").withStyle(ChatFormatting.YELLOW));
        return delivered > 0 ? 1 : 0;
    }

    private static void refreshHeartCraftWindow(String id) {
        long started = state.heartCraftWindowStartedAt.getOrDefault(id, 0L);
        if (started > 0 && System.currentTimeMillis() - started >= 86400000L) {
            clearHeartCraftWindow(id);
        }
    }
    private static void clearHeartCraftWindow(String id) {
        // An admin skip must reset both values, not just the window timestamp.
        // These records are persisted together with the rest of the Lifesteal state.
        state.craftedHeartsInWindow.put(id, 0);
        state.heartCraftWindowStartedAt.put(id, 0L);
        save();
    }
    // Accessible from server-only crafting slot mixins.
    public static boolean isCraftedHeartResult(ItemStack stack) { return isHeart(stack); }
    public static boolean canTakeCraftedHeart(Player player, ItemStack stack) {
        if (!isHeart(stack) || !(player instanceof ServerPlayer p)) return true;
        String id = p.getUUID().toString();
        refreshHeartCraftWindow(id);
        return state.craftedHeartsInWindow.getOrDefault(id, 0) + stack.getCount() <= 3;
    }
    public static void recordCraftedHeart(Player player, ItemStack stack) {
        if (!isHeart(stack) || !(player instanceof ServerPlayer p)) return;
        String id = p.getUUID().toString();
        refreshHeartCraftWindow(id);
        long current = System.currentTimeMillis();
        if (state.heartCraftWindowStartedAt.getOrDefault(id, 0L) == 0L) state.heartCraftWindowStartedAt.put(id, current);
        int amount = Math.max(1, stack.getCount());
        state.craftedHeartsInWindow.merge(id, amount, Integer::sum);
        save();
        p.sendSystemMessage(Component.literal("Hearts crafted: " + state.craftedHeartsInWindow.get(id) + "/3. Use /lifesteal craftstatus.")
            .withStyle(ChatFormatting.YELLOW));
    }
    public static boolean isCombatTagged(ServerPlayer p) {
        return state.combatTagEnabled && state.pvpEnabled && combatTaggedUntil.getOrDefault(p.getUUID(), 0L) > ticks;
    }
    public static boolean isCombatEscapeCommand(String input) {
        String c = input.strip().toLowerCase(Locale.ROOT).split("\\s+", 2)[0];
        if (c.startsWith("/")) c = c.substring(1);
        if (c.contains(":")) c = c.substring(c.indexOf(':')+1);
        return Set.of("home","homes","spawn","sethome","tpa","tpahere","tpaccept","tpdeny","tpyes","tpno","tpcancel","tpacancel","tpahereaccept","back","warp","warps","rtp","wild","randomtp","teleport","tp","tphere","tpask","call","return").contains(c);
    }
    // Heart recipe restored from Alpha14. Enforcement occurs at the server crafting result slot.
    private static boolean isHeart(ItemStack item) {
        if (!item.is(Items.NETHER_STAR)) return false;
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(HEART_MARKER).orElse(false);
    }
    private static void save() {
        if (saveFile == null) return;
        try { Files.createDirectories(saveFile.getParent()); Files.writeString(saveFile, JSON.toJson(state)); }
        catch (Exception e) { System.err.println("[ChillZoneLifesteal] Save failed: " + e); }
    }
}
