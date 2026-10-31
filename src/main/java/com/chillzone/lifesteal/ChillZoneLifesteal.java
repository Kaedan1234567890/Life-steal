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
import net.minecraft.world.inventory.ClickType;
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
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String HEART_MARKER = "chillzone_lifesteal_heart";
    private static final String REVIVE_BEACON_MARKER = "chillzone_lifesteal_revive_beacon";
    private static MinecraftServer server;
    private static State state = new State();
    private static Path saveFile;
    private static long ticks;
    private static final Set<UUID> handled = new HashSet<>();
    private static final Set<UUID> pendingKicks = new HashSet<>();

    private static class State {
        int startHearts = 10;
        int maxHearts = 20;
        int minHearts = 1;
        boolean pvpLoss = true;
        boolean naturalDeathLoss = false;
        boolean stealOnKill = true;
        Map<String, Integer> hearts = new HashMap<>();
        boolean deathBans = true;
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
            if (state.eliminatedNames == null) state.eliminatedNames = new HashMap<>();
            if (state.eliminatedKillers == null) state.eliminatedKillers = new HashMap<>();
            state.maxHearts = Math.max(1, state.maxHearts);
            for (ServerPlayer p : s.getPlayerList().getPlayers()) applyHealth(p);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer player = handler.player;
            if (state.deathBans && state.eliminated.contains(player.getUUID().toString())) {
                handler.disconnect(Component.literal("You have been death banned from Chill Zone SMP. You must be revived to rejoin.").withStyle(ChatFormatting.RED));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
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
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damage) -> {
            if (!(entity instanceof ServerPlayer victim)) return;
            ServerPlayer killer = damage.getEntity() instanceof ServerPlayer p && !p.getUUID().equals(victim.getUUID()) ? p : null;
            if (killer != null && state.pvpLoss) {
                int before = heartCount(victim);
                if (before > state.minHearts) {
                    setHearts(victim, before - 1);
                    victim.sendSystemMessage(Component.literal("You lost a heart. " + heartCount(victim) + " hearts remaining.").withStyle(ChatFormatting.RED));
                    if (state.stealOnKill) {
                        if (heartCount(killer) < state.maxHearts) {
                            setHearts(killer, heartCount(killer) + 1);
                        } else {
                            killer.getInventory().add(makeHeart());
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
                        else killer.getInventory().add(makeHeart());
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
            root.then(Commands.literal("craftstatus").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                p.sendSystemMessage(Component.literal("Heart crafting eligibility: " +
                    (mayCraftHeart(p) ? "allowed (below 15 hearts)" : "blocked (15 or more hearts)") +
                    ". Crafting output enforcement will be added after the special recipes are implemented."));
                return 1;
            }));
            root.then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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
                .then(Commands.literal("pvploss").then(Commands.literal("on").executes(ctx -> {state.pvpLoss=true;save();return 1;}))
                    .then(Commands.literal("off").executes(ctx -> {state.pvpLoss=false;save();return 1;})))
                .then(Commands.literal("naturalloss").then(Commands.literal("on").executes(ctx -> {state.naturalDeathLoss=true;save();return 1;}))
                    .then(Commands.literal("off").executes(ctx -> {state.naturalDeathLoss=false;save();return 1;})))
                .then(Commands.literal("deathbans").then(Commands.literal("on").executes(ctx -> {state.deathBans=true;save();return 1;}))
                    .then(Commands.literal("off").executes(ctx -> {state.deathBans=false;save();return 1;})))
                .then(Commands.literal("revive").executes(ctx -> {
                    openReviveMenu(ctx.getSource().getPlayerOrException(), true, 0); return 1;
                }));
            dispatcher.register(root);
        });
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
        ItemStack cancel=new ItemStack(Items.RED_CONCRETE);cancel.set(DataComponents.CUSTOM_NAME,Component.literal("Cancel").withStyle(ChatFormatting.RED));inv.setItem(11,cancel);
        ItemStack head=new ItemStack(Items.PLAYER_HEAD);head.set(DataComponents.CUSTOM_NAME,Component.literal(name));inv.setItem(13,head);
        ItemStack confirm=new ItemStack(Items.LIME_CONCRETE);confirm.set(DataComponents.CUSTOM_NAME,Component.literal("Confirm revival").withStyle(ChatFormatting.GREEN));inv.setItem(15,confirm);
        player.openMenu(new MenuProvider(){
            @Override public Component getDisplayName(){return Component.literal("Revive "+name+"?");}
            @Override public AbstractContainerMenu createMenu(int id,Inventory inventory,Player p){
                return new ReviveMenu(id,inventory,inv,slot->{
                    if(slot==11){openReviveMenu(player,admin,0);return;}
                    if(slot!=15) return;
                    if(!state.eliminated.contains(targetId)){player.sendSystemMessage(Component.literal("Already revived.").withStyle(ChatFormatting.RED));openReviveMenu(player,admin,0);return;}
                    if(!admin && !consumeReviveBeacon(player)){player.sendSystemMessage(Component.literal("Revive Beacon missing; revival canceled.").withStyle(ChatFormatting.RED));player.closeContainer();return;}
                    state.eliminated.remove(targetId);
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
        @Override public void clicked(int slot,int button,ClickType click,Player player){
            if(slot>=0 && slot<27){if(click==ClickType.PICKUP && button==0) handler.accept(slot);return;}
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
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Heart").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
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

    private static boolean mayCraftHeart(ServerPlayer player) {
        // Recipe execution hook still pending: use this predicate when implementing the crafting output.
        return heartCount(player) < 15;
    }

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
