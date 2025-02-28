package com.dfsek.terra.fabric.generation;

import com.dfsek.terra.api.platform.world.World;
import com.dfsek.terra.api.platform.world.generator.ChunkData;
import com.dfsek.terra.api.platform.world.generator.GeneratorWrapper;
import com.dfsek.terra.api.util.FastRandom;
import com.dfsek.terra.api.world.generation.TerraChunkGenerator;
import com.dfsek.terra.api.world.locate.AsyncStructureFinder;
import com.dfsek.terra.config.pack.ConfigPack;
import com.dfsek.terra.fabric.FabricAdapter;
import com.dfsek.terra.fabric.TerraFabricPlugin;
import com.dfsek.terra.world.TerraWorld;
import com.dfsek.terra.world.generation.generators.DefaultChunkGenerator3D;
import com.dfsek.terra.world.generation.math.samplers.Sampler;
import com.dfsek.terra.world.population.items.TerraStructure;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.jafama.FastMath;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.BlockView;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.Heightmap;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.StructuresConfig;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.feature.StructureFeature;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class FabricChunkGeneratorWrapper extends ChunkGenerator implements GeneratorWrapper {
    private final long seed;
    private final DefaultChunkGenerator3D delegate;
    private final TerraBiomeSource biomeSource;
    public static final Codec<ConfigPack> PACK_CODEC = (RecordCodecBuilder.create(config -> config.group(
            Codec.STRING.fieldOf("pack").forGetter(pack -> pack.getTemplate().getID())
    ).apply(config, config.stable(TerraFabricPlugin.getInstance().getConfigRegistry()::get))));
    public static final Codec<FabricChunkGeneratorWrapper> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            TerraBiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
            Codec.LONG.fieldOf("seed").stable().forGetter(generator -> generator.seed),
            PACK_CODEC.fieldOf("pack").stable().forGetter(generator -> generator.pack))
            .apply(instance, instance.stable(FabricChunkGeneratorWrapper::new)));
    private final ConfigPack pack;

    public ConfigPack getPack() {
        return pack;
    }

    public FabricChunkGeneratorWrapper(TerraBiomeSource biomeSource, long seed, ConfigPack configPack) {
        super(biomeSource, new StructuresConfig(false));
        this.pack = configPack;

        this.delegate = new DefaultChunkGenerator3D(pack, TerraFabricPlugin.getInstance());
        delegate.getMain().logger().info("Loading world with config pack " + pack.getTemplate().getID());
        this.biomeSource = biomeSource;

        this.seed = seed;
    }


    @Override
    protected Codec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    @Override
    public ChunkGenerator withSeed(long seed) {
        return new FabricChunkGeneratorWrapper((TerraBiomeSource) this.biomeSource.withSeed(seed), seed, pack);
    }

    @Override
    public void buildSurface(ChunkRegion region, Chunk chunk) {

    }

    @Nullable
    @Override
    public BlockPos locateStructure(ServerWorld world, StructureFeature<?> feature, BlockPos center, int radius, boolean skipExistingChunks) {
        String name = Objects.requireNonNull(Registry.STRUCTURE_FEATURE.getId(feature)).toString();
        TerraWorld terraWorld = TerraFabricPlugin.getInstance().getWorld((World) world);
        TerraStructure located = pack.getStructure(pack.getTemplate().getLocatable().get(name));
        if(located != null) {
            CompletableFuture<BlockPos> result = new CompletableFuture<>();
            AsyncStructureFinder finder = new AsyncStructureFinder(terraWorld.getBiomeProvider(), located, FabricAdapter.adapt(center).toLocation((World) world), 0, 500, location -> {
                result.complete(FabricAdapter.adapt(location));
            }, TerraFabricPlugin.getInstance());
            finder.run(); // Do this synchronously.
            try {
                return result.get();
            } catch(InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        TerraFabricPlugin.getInstance().logger().warning("No overrides are defined for \"" + name + "\"");
        return null;
    }

    @Override
    public void generateFeatures(ChunkRegion region, StructureAccessor accessor) {
        super.generateFeatures(region, accessor);
    }

    @Override
    public void populateNoise(WorldAccess world, StructureAccessor accessor, Chunk chunk) {
        delegate.generateChunkData((World) world, new FastRandom(), chunk.getPos().x, chunk.getPos().z, (ChunkData) chunk);
    }

    @Override
    public void carve(long seed, BiomeAccess access, Chunk chunk, GenerationStep.Carver carver) {
        // No caves
    }

    @Override
    public void setStructureStarts(DynamicRegistryManager dynamicRegistryManager, StructureAccessor structureAccessor, Chunk chunk, StructureManager structureManager, long worldSeed) {

    }



    @Override
    public boolean isStrongholdStartingChunk(ChunkPos chunkPos) {
        return false;
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmapType) {
        TerraWorld world = TerraFabricPlugin.getInstance().getWorld(seed);
        Sampler sampler = world.getConfig().getSamplerCache().getChunk(FastMath.floorDiv(x, 16), FastMath.floorDiv(z, 16));
        int cx = FastMath.floorMod(x, 16);
        int cz = FastMath.floorMod(z, 16);

        int height = world.getWorld().getMaxHeight();

        while (height >= 0 && sampler.sample(cx, height - 1, cz) < 0) {
            height--;
        }

        return height;
    }

    @Override
    public BlockView getColumnSample(int x, int z) {
        int height = 64; // TODO: implementation
        BlockState[] array = new BlockState[256];
        for(int y = 255; y >= 0; y--) {
            if(y > height) {
                if(y > getSeaLevel()) {
                    array[y] = Blocks.AIR.getDefaultState();
                } else {
                    array[y] = Blocks.WATER.getDefaultState();
                }
            } else {
                array[y] = Blocks.STONE.getDefaultState();
            }
        }

        return new VerticalBlockSample(array);
    }

    @Override
    public TerraChunkGenerator getHandle() {
        return delegate;
    }
}
