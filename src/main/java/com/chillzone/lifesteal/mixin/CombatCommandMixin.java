package com.chillzone.lifesteal.mixin;

import com.chillzone.lifesteal.ChillZoneLifesteal;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CombatCommandMixin {
    @Shadow public net.minecraft.server.level.ServerPlayer player;
    @Inject(method = "handleCommand", at = @At("HEAD"), cancellable = true)
    private void chillzone$blockEscape(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (!ChillZoneLifesteal.isCombatTagged(player) || !ChillZoneLifesteal.isCombatEscapeCommand(packet.command())) return;
        player.sendSystemMessage(Component.literal("You cannot teleport or use home commands while in combat!").withStyle(ChatFormatting.RED));
        ci.cancel();
    }
}
