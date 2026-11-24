package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Material;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Feeds {@link MosaicWorld.Volume} from the client level: one material class per block (fluids first, then light
 * sources, then block tags, then the block's sound type, which also covers modded blocks) plus the sky and block
 * light levels. Classes are cached per block state. Render thread only.
 */
final class MosaicWorldFeed implements MosaicWorld.Sampler {

    /** Cells rescanned per frame: the 48x32x48 volume refreshes about every 12 frames. */
    static final int BUDGET = 6144;

    private final ClientLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private static final Map<BlockState, Material> CACHE = new IdentityHashMap<>();

    MosaicWorldFeed(ClientLevel level) { this.level = level; }

    @Override public int sample(int x, int y, int z) {
        pos.set(x, y, z);
        BlockState s = level.getBlockState(pos);
        Material m = CACHE.computeIfAbsent(s, MosaicWorldFeed::classify);
        return MosaicWorld.cell(m, level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
    }

    static Material classify(BlockState s) {
        FluidState f = s.getFluidState();
        if (!f.isEmpty()) {
            if (f.is(FluidTags.WATER)) return Material.WATER;
            if (f.is(FluidTags.LAVA)) return Material.LAVA;
        }
        if (s.isAir()) return Material.AIR;
        if (s.getLightEmission() >= 10) return Material.LIGHT;
        if (s.is(BlockTags.LEAVES)) return Material.LEAVES;
        if (s.is(BlockTags.ICE)) return Material.ICE;
        if (s.is(BlockTags.SNOW)) return Material.SNOW;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS)) return Material.WOOD;
        if (s.is(BlockTags.SAND)) return Material.SAND;
        if (s.is(BlockTags.DIRT)) return Material.DIRT;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD)) return Material.STONE;
        if (s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.CROPS) || s.is(BlockTags.REPLACEABLE))
            return Material.PLANT;
        if (s.is(BlockTags.IMPERMEABLE)) return Material.GLASS;
        SoundType sound = s.getSoundType();
        if (sound == SoundType.GLASS || sound == SoundType.AMETHYST) return Material.GLASS;
        if (sound == SoundType.WOOD || sound == SoundType.BAMBOO || sound == SoundType.SCAFFOLDING || sound == SoundType.LADDER)
            return Material.WOOD;
        if (sound == SoundType.GRAVEL || sound == SoundType.SAND || sound == SoundType.SOUL_SAND) return Material.SAND;
        if (sound == SoundType.SNOW || sound == SoundType.POWDER_SNOW) return Material.SNOW;
        if (sound == SoundType.METAL || sound == SoundType.COPPER || sound == SoundType.NETHERITE_BLOCK || sound == SoundType.ANVIL
            || sound == SoundType.CHAIN || sound == SoundType.LANTERN || sound == SoundType.COPPER_GRATE || sound == SoundType.COPPER_BULB)
            return Material.METAL;
        if (sound == SoundType.GRASS || sound == SoundType.WET_GRASS || sound == SoundType.CROP || sound == SoundType.VINE
            || sound == SoundType.MOSS || sound == SoundType.MOSS_CARPET || sound == SoundType.ROOTS || sound == SoundType.AZALEA)
            return s.isSolidRender() ? Material.DIRT : Material.PLANT;
        if (sound == SoundType.STONE || sound == SoundType.TUFF || sound == SoundType.CALCITE || sound == SoundType.BASALT
            || sound == SoundType.NETHERRACK || sound == SoundType.NETHER_BRICKS || sound == SoundType.NETHER_ORE
            || sound == SoundType.DRIPSTONE_BLOCK) return Material.STONE;
        return s.canOcclude() ? Material.OTHER : Material.PLANT;
    }
}
