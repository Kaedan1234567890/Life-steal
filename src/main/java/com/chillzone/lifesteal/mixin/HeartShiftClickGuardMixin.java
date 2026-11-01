package com.chillzone.lifesteal.mixin;

import com.chillzone.lifesteal.ChillZoneLifesteal;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Safety guard: never allow a bulk craft to bypass the daily Heart quota. */
@Mixin(CraftingMenu.class)
public abstract class HeartShiftClickGuardMixin {
    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void chillzone$guardShiftCraft(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        if (index != 0 || !(player instanceof ServerPlayer p)) return;
        CraftingMenu menu = (CraftingMenu)(Object)this;
        ItemStack result = menu.getSlot(0).getItem();
        if (!ChillZoneLifesteal.isCraftedHeartResult(result)) return;
        p.sendSystemMessage(Component.literal("Heart bulk-crafting is temporarily disabled to protect your 3-per-24h limit. Take Hearts individually."));
        cir.setReturnValue(ItemStack.EMPTY);
    }
}
