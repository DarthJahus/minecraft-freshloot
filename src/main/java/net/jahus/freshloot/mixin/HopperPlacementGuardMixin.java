package net.jahus.freshloot.mixin;

import net.jahus.freshloot.LootContainerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class HopperPlacementGuardMixin {

    private static final String DENY_MESSAGE = "\u00a7cYou can't place a hopper next to a loot chest!";

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"), cancellable = true)
    private void freshloot$denyHopperNextToLootChest(BlockPlaceContext context,
                                                        CallbackInfoReturnable<InteractionResult> cir) {
        Level world = context.getLevel();
        if (world.isClientSide()) {
            return;
        }

        BlockItem self = (BlockItem) (Object) this;
        if (!(self.getBlock() instanceof HopperBlock)) {
            return;
        }

        BlockPlaceContext resolvedContext = self.updatePlacementContext(context);
        if (resolvedContext == null) {
            return;
        }

        BlockPos placedPos = resolvedContext.getClickedPos();

        for (Direction side : Direction.values()) {
            BlockPos neighborPos = placedPos.relative(side);
            BlockEntity neighborBe = world.getBlockEntity(neighborPos);

            if (!LootContainerHandler.isUnclaimedLootContainer(neighborBe)) {
                continue;
            }

            cir.setReturnValue(InteractionResult.FAIL);
            if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.literal(DENY_MESSAGE));
            }
            return;
        }
    }
}
