package net.jahus.freshloot.mixin;

import net.jahus.freshloot.LootContainerHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(ChestBlockEntity.class)
public abstract class ChestBlockEntityMixin {
    @Inject(method = "stopOpen", at = @At("HEAD"))
    private void freshloot$onClose(ContainerUser user, CallbackInfo ci) {
        if (!(user.getLivingEntity() instanceof Player player)) {
            return;
        }

        ChestBlockEntity chest = (ChestBlockEntity) (Object) this;

        BlockState state = chest.getBlockState();
        if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return;
        }

        MinecraftServer server = Objects.requireNonNull(player.level().getServer());
        LootContainerHandler.onClose(chest, player, server);
    }
}
