package com.chillzone.lifesteal.mixin;

import com.chillzone.lifesteal.ChillZoneLifesteal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ResultSlot.class)
public abstract class HeartCraftTakeMixin {
    @Inject(method = "onTake", at = @At("TAIL"))
    private void chillzone$recordCraft(Player player, ItemStack item, CallbackInfo ci) {
        ChillZoneLifesteal.recordCraftedHeart(player, item);
    }
}
