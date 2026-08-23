package net.jahus.freshloot.mixin;

import net.jahus.freshloot.LootContainerHandler;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(BarrelBlockEntity.class)
public abstract class BarrelBlockEntityMixin {
    @Inject(method = "onClose", at = @At("HEAD"))
    private void freshloot$onClose(PlayerEntity player, CallbackInfo ci) {
        BarrelBlockEntity barrel = (BarrelBlockEntity) (Object) this;
        MinecraftServer server = Objects.requireNonNull(player.getServer());
        LootContainerHandler.onClose(barrel, player, server);
    }
}
