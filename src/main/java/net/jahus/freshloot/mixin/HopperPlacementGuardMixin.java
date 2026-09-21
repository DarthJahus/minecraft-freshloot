package net.jahus.freshloot.mixin;

import net.jahus.freshloot.LootContainerHandler;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Denies placing a hopper on any face (below, above, or sideways) of a chest
 * or barrel that still carries an unclaimed loot table. Both directions
 * (a hopper extracting from the container, or a hopper piping into it) are
 * blocked: either one can trigger vanilla loot generation or drain the
 * container outside the mod's open/close flow.
 */
@Mixin(BlockItem.class)
public abstract class HopperPlacementGuardMixin {

    private static final String DENY_MESSAGE = "\u00a7cYou can't place a hopper next to a loot chest!";

    @Inject(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void freshloot$denyHopperNextToLootChest(ItemPlacementContext context,
                                                        CallbackInfoReturnable<ActionResult> cir) {
        World world = context.getWorld();
        if (world.isClient()) {
            return;
        }

        BlockItem self = (BlockItem) (Object) this;
        if (!(self.getBlock() instanceof HopperBlock)) {
            return;
        }

        ItemPlacementContext resolvedContext = self.getPlacementContext(context);
        if (resolvedContext == null) {
            return;
        }

        BlockPos placedPos = resolvedContext.getBlockPos();

        for (Direction side : Direction.values()) {
            BlockPos neighborPos = placedPos.offset(side);
            BlockEntity neighborBe = world.getBlockEntity(neighborPos);

            if (!LootContainerHandler.isUnclaimedLootContainer(neighborBe)) {
                continue;
            }

            cir.setReturnValue(ActionResult.FAIL);
            if (context.getPlayer() instanceof ServerPlayerEntity serverPlayer) {
                serverPlayer.sendMessage(Text.literal(DENY_MESSAGE), true);
            }
            return;
        }
    }
}
