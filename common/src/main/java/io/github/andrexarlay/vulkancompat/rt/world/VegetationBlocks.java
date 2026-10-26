package io.github.andrexarlay.vulkancompat.rt.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingMossBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MangroveRootsBlock;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.ShelfMushroomBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which block a cutout quad belongs to, for the leaf transmission of {@code rt_common_surface.glsl}: only a plant or a
 * leaf transmits the key light, so {@code isFoliage} must not be inferred from the render pass. The cutout pass also holds
 * iron bars, fences, panes, ladders, rails and doors; giving them the leaf term made every back-lit one glow warm.
 *
 * <p>The answer also says how thick the caster is. A card (cross model, vine, kelp, cane, bamboo) is cleared by the 1 cm
 * step along the sun; a full leaf cube presents its sun-side face exactly one block in, and its shadow ray must start
 * there instead. Guessing the thickness from an axis-aligned normal does not work: a wall vine has one too.
 *
 * <p>The classes cover the vanilla shapes a model cannot. Modded plants that extend {@link VegetationBlock} are included;
 * anything unknown stays untagged (plain shading, the pre-transmission look), never a false warm glow.
 *
 * <p>Render thread only: the mutable position is shared, and the client level is read per lookup.
 */
final class VegetationBlocks {
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private VegetationBlocks() {
    }

    /** {@link RtWorld.BlockLookup} of the current client world; never allocates. */
    static int foliageKindAt(int x, int y, int z) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null ? foliageKind(level.getBlockState(POS.set(x, y, z))) : RtWorld.FOLIAGE_NONE;
    }

    static int foliageKind(BlockState state) {
        Block block = state.getBlock();
        // Only a leaf block fills its cell (block/leaves: one element 0..16). Mangrove roots look solid but their model is six
        // thin shells plus an internal cross, so a one-block step would be wrong for them.
        if (state.is(BlockTags.LEAVES) || block instanceof LeavesBlock) {
            return RtWorld.FOLIAGE_CUBE;
        }
        // plants, flowers, crops, saplings, grass, ferns, seagrass, leaf litter, flower beds, nether sprouts: thin cards
        if (block instanceof VegetationBlock) {
            return RtWorld.FOLIAGE_CARD;
        }
        if (block instanceof VineBlock
            || block instanceof GrowingPlantBlock      // kelp, cave vines, weeping and twisting vines
            || block instanceof HangingRootsBlock
            || block instanceof HangingMossBlock
            || block instanceof MossyCarpetBlock
            || block instanceof BigDripleafBlock
            || block instanceof BigDripleafStemBlock
            || block instanceof ShelfMushroomBlock
            || block instanceof SugarCaneBlock
            || block instanceof BambooSaplingBlock
            || block instanceof BambooStalkBlock
            || block instanceof MangroveRootsBlock) {
            return RtWorld.FOLIAGE_CARD;
        }
        return RtWorld.FOLIAGE_NONE;
    }
}
