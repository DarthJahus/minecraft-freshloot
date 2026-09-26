package net.jahus.freshloot.mixin;

import net.jahus.freshloot.LootContainerHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(BarrelBlockEntity.class)
public abstract class BarrelBlockEntityMixin {
    @Inject(method = "stopOpen", at = @At("HEAD"))
    private void freshloot$onClose(ContainerUser user, CallbackInfo ci) {
        if (!(user.getLivingEntity() instanceof Player player)) {
            return;
        }

        BarrelBlockEntity barrel = (BarrelBlockEntity) (Object) this;
        MinecraftServer server = Objects.requireNonNull(player.level().getServer());
        LootContainerHandler.onClose(barrel, player, server);
    }
}
