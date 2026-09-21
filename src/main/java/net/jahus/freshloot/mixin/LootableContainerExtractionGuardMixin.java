package net.jahus.freshloot.mixin;

import net.jahus.freshloot.LootContainerHandler;
import net.jahus.freshloot.LootRegistry;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Catches any extraction from a still-unclaimed loot chest or barrel that
 * does not go through the mod's own open/close flow: a hopper block, a
 * hopper minecart, a dropper, or anything else pulling items straight out of
 * the Inventory interface. Treated as griefing, same as breaking the container.
 *
 * Legitimate flow (must NOT trigger): a player opens the container
 * (tryOpen -> markOpen), applyLoot consumes the loot table via generateLoot,
 * and the player takes items through the ScreenHandler. In that flow the
 * container is registered as open, and its loot table is already null.
 */
@Mixin(LootableContainerBlockEntity.class)
public abstract class LootableContainerExtractionGuardMixin {

    @Inject(method = "removeStack(I)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"))
    private void freshloot$guardRemoveStack(int slot, CallbackInfoReturnable<ItemStack> cir) {
        freshloot$guard();
    }

    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("HEAD"))
    private void freshloot$guardRemoveStackWithAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        freshloot$guard();
    }

    private void freshloot$guard() {
        LootableContainerBlockEntity self = (LootableContainerBlockEntity) (Object) this;
        World world = self.getWorld();
        if (world == null || world.isClient()) {
            return;
        }
        if (!LootContainerHandler.isUnclaimedLootContainer(self)) {
            return;
        }
        // A player has it open: extraction goes through our own flow.
        if (LootRegistry.isOpen(LootRegistry.DimPos.of(world, self.getPos()))) {
            return;
        }
        LootContainerHandler.reportSpoiledByExtraction(self, world, self.getPos());
    }
}
