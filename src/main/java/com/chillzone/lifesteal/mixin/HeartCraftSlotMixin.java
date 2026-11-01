package com.chillzone.lifesteal.mixin;

import com.chillzone.lifesteal.ChillZoneLifesteal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block all result-slot pickup paths, including shift-click. */
@Mixin(Slot.class)
public abstract class HeartCraftSlotMixin {
    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void chillzone$heartQuota(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object)this instanceof ResultSlot)) return;
        ItemStack result = ((Slot)(Object)this).getItem();
        if (!ChillZoneLifesteal.canTakeCraftedHeart(player, result)) {
            if (player instanceof net.minecraft.server.level.ServerPlayer p)
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Daily Heart crafting limit reached. Use /lifesteal craftstatus."));
            cir.setReturnValue(false);
        }
    }
}
