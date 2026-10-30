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
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Independent Chill Zone implementation; does not load the old Lifesteal datapack. */
public final class ChillZoneLifesteal implements ModInitializer {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String HEART_MARKER = "chillzone_lifesteal_heart";
    private static MinecraftServer server;
    private static State state = new State();
    private static Path saveFile;
    private static long ticks;
    private static final Set<UUID> handled = new HashSet<>();

    private static class State {
        int startHearts = 10;
        int maxHearts = 20;
        int minHearts = 1;
        boolean pvpLoss = true;
        boolean naturalDeathLoss = false;
        boolean stealOnKill = true;
        Map<String, Integer> hearts = new HashMap<>();
    }

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            saveFile = s.getWorldPath(LevelResource.ROOT).resolve("data/chillzone-lifesteal.json");
            try { if (Files.isRegularFile(saveFile)) state = JSON.fromJson(Files.readString(saveFile), State.class); }
            catch (Exception e) { System.err.println("[ChillZoneLifesteal] Unable to read state: " + e); }
            if (state == null || state.hearts == null) state = new State();
            state.maxHearts = Math.max(1, state.maxHearts);
            for (ServerPlayer p : s.getPlayerList().getPlayers()) applyHealth(p);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            ticks++;
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
                } else {
                    // First prototype deliberately does not ban players: ban/revive integration requires separate verified moderation handling.
                    victim.sendSystemMessage(Component.literal("You reached the Lifesteal minimum. Elimination is pending implementation.").withStyle(ChatFormatting.RED));
                }
            } else if (killer == null && state.naturalDeathLoss && heartCount(victim) > state.minHearts) {
                setHearts(victim, heartCount(victim) - 1);
            }
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayer p)) return InteractionResult.PASS;
            ItemStack item = player.getItemInHand(hand);
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
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) -> {
            dispatcher.register(Commands.literal("withdraw")
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                    if (heartCount(p) - amount < state.minHearts) {
                        p.sendSystemMessage(Component.literal("You must keep at least " + state.minHearts + " heart(s).").withStyle(ChatFormatting.RED));
                        return 0;
                    }
                    setHearts(p, heartCount(p) - amount);
                    for (int i = 0; i < amount; i++) {
                        ItemStack item = makeHeart();
                        if (!p.getInventory().add(item)) p.drop(item, false);
                    }
                    p.sendSystemMessage(Component.literal("Withdrew " + amount + " heart(s); you have " + heartCount(p) + " remaining."));
                    return 1;
                })));
            var root = Commands.literal("lifesteal");
            root.executes(ctx -> { ctx.getSource().sendSuccess(() -> Component.literal("Chill Zone Lifesteal: /withdraw <amount>, /lifesteal hearts"), false); return 1; });
            root.then(Commands.literal("hearts").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                p.sendSystemMessage(Component.literal("Your hearts: " + heartCount(p) + "/" + state.maxHearts)); return 1;
            }));
            root.then(Commands.literal("admin").requires(s -> s.hasPermission(3))
                .then(Commands.literal("giveheart").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "player"); target.getInventory().add(makeHeart()); return 1;
                })))
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
                    .then(Commands.literal("off").executes(ctx -> {state.naturalDeathLoss=false;save();return 1;}))));
            dispatcher.register(root);
        });
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
