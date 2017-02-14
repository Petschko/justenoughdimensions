package fi.dy.masa.justenoughdimensions.world.util;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;
import javax.annotation.Nonnull;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeProvider;
import net.minecraft.world.biome.BiomeProviderSingle;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkGenerator;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.feature.WorldGeneratorBonusChest;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.relauncher.ReflectionHelper.UnableToFindFieldException;
import fi.dy.masa.justenoughdimensions.JustEnoughDimensions;
import fi.dy.masa.justenoughdimensions.config.DimensionConfig;
import fi.dy.masa.justenoughdimensions.network.MessageSyncWorldProviderProperties;
import fi.dy.masa.justenoughdimensions.network.PacketHandler;
import fi.dy.masa.justenoughdimensions.world.WorldInfoJED;

public class WorldUtils
{
    private static Field field_WorldProvider_biomeProvider = null;
    private static Field field_ChunkProviderServer_chunkGenerator = null;

    static
    {
        try
        {
            field_WorldProvider_biomeProvider = ReflectionHelper.findField(WorldProvider.class, "field_76578_c", "biomeProvider");
            field_ChunkProviderServer_chunkGenerator = ReflectionHelper.findField(ChunkProviderServer.class, "field_186029_c", "chunkGenerator");
        }
        catch (UnableToFindFieldException e)
        {
            JustEnoughDimensions.logger.error("WorldUtils: Reflection failed!!", e);
        }
    }

    public static int getLoadedChunkCount(WorldServer world)
    {
        return world.getChunkProvider().getLoadedChunkCount();
    }

    /**
     * Unloads all empty dimensions (with no chunks loaded)
     * @param tryUnloadChunks if true, then tries to first save and unload all non-player-loaded and non-force-loaded chunks
     * @return the number of dimensions successfully unloaded
     */
    public static int unloadEmptyDimensions(boolean tryUnloadChunks)
    {
        int count = 0;
        Integer[] dims = DimensionManager.getIDs();

        for (int dim : dims)
        {
            WorldServer world = DimensionManager.getWorld(dim);
            if (world == null)
            {
                continue;
            }

            ChunkProviderServer chunkProviderServer = world.getChunkProvider();

            if (tryUnloadChunks && chunkProviderServer.getLoadedChunkCount() > 0)
            {
                boolean disable = world.disableLevelSaving;
                world.disableLevelSaving = false;

                try
                {
                    // This also tries to unload all chunks that are not loaded by players
                    world.saveAllChunks(true, (IProgressUpdate) null);
                }
                catch (MinecraftException e)
                {
                    JustEnoughDimensions.logger.warn("Exception while trying to save chunks for dimension {}", world.provider.getDimension(), e);
                }

                // This would flush the chunks to disk from the AnvilChunkLoader. Probably not what we want to do.
                //world.saveChunkData();

                world.disableLevelSaving = disable;

                // This will unload the dimension, if it unloaded at least one chunk, and it has no loaded chunks anymore
                chunkProviderServer.tick();

                if (chunkProviderServer.getLoadedChunkCount() == 0)
                {
                    count++;
                }
            }
            else if (chunkProviderServer.getLoadedChunkCount() == 0 &&
                world.provider.getDimensionType().shouldLoadSpawn() == false &&
                ForgeChunkManager.getPersistentChunksFor(world).size() == 0)
            {
                DimensionManager.unloadWorld(world.provider.getDimension());
                count++;
            }
        }

        return count;
    }

    public static void syncWorldProviderProperties(EntityPlayer player)
    {
        World world = player.getEntityWorld();

        if (world.getWorldInfo() instanceof WorldInfoJED && player instanceof EntityPlayerMP)
        {
            PacketHandler.INSTANCE.sendTo(new MessageSyncWorldProviderProperties((WorldInfoJED) world.getWorldInfo()), (EntityPlayerMP) player);
        }
    }

    public static void overrideBiomeProvider(World world)
    {
        int dimension = world.provider.getDimension();
        String biomeName = DimensionConfig.instance().getBiomeFor(dimension);
        Biome biome = biomeName != null ? Biome.REGISTRY.getObject(new ResourceLocation(biomeName)) : null;

        if (biome != null && ((world.provider.getBiomeProvider() instanceof BiomeProviderSingle) == false ||
            world.provider.getBiomeProvider().getBiome(BlockPos.ORIGIN) != biome))
        {
            BiomeProvider biomeProvider = new BiomeProviderSingle(biome);

            JustEnoughDimensions.logInfo("Overriding the BiomeProvider for dimension {} with {}" +
                " using the biome '{}' ('{}')", dimension, biomeProvider.getClass().getName(), biomeName, biome.getBiomeName());

            try
            {
                field_WorldProvider_biomeProvider.set(world.provider, biomeProvider);
            }
            catch (Exception e)
            {
                JustEnoughDimensions.logger.error("Failed to override the BiomeProvider of dimension {}", dimension);
            }
        }
    }

    public static void setChunkProvider(World world)
    {
        if (world instanceof WorldServer && world.getChunkProvider() instanceof ChunkProviderServer)
        {
            // This sets the new WorldType to the WorldProvider
            world.provider.registerWorld(world);

            // Always override the ChunkProvider when using overridden WorldInfo, otherwise
            // the ChunkProvider will be using the settings from the overworld, because
            // WorldEvent.Load obviously only happens after the world has been constructed...
            ChunkProviderServer chunkProviderServer = (ChunkProviderServer) world.getChunkProvider();
            IChunkGenerator newChunkProvider = world.provider.createChunkGenerator();

            if (newChunkProvider == null)
            {
                JustEnoughDimensions.logger.warn("Failed to re-create the ChunkProvider");
                return;
            }

            int dimension = world.provider.getDimension();
            JustEnoughDimensions.logInfo("Attempting to override the ChunkProvider (of type {}) in dimension {} with {}",
                    chunkProviderServer.chunkGenerator.getClass().getName(), dimension, newChunkProvider.getClass().getName());

            try
            {
                field_ChunkProviderServer_chunkGenerator.set(chunkProviderServer, newChunkProvider);
            }
            catch (Exception e)
            {
                JustEnoughDimensions.logger.warn("Failed to override the ChunkProvider for dimension {} with {}",
                        dimension, newChunkProvider.getClass().getName(), e);
            }
        }
    }

    public static void findAndSetWorldSpawn(World world, boolean fireEvent)
    {
        WorldInfo info = world.getWorldInfo();
        WorldSettings worldSettings = new WorldSettings(info);
        WorldProvider provider = world.provider;
        BlockPos pos = null;

        JustEnoughDimensions.logInfo("Trying to find a world spawn for dimension {}...", provider.getDimension());

        if (fireEvent && net.minecraftforge.event.ForgeEventFactory.onCreateWorldSpawn(world, worldSettings))
        {
            JustEnoughDimensions.logInfo("Exiting due to a canceled WorldEvent.CreateSpawnPosition event!");
            return;
        }

        if (provider.canRespawnHere() == false)
        {
            if (provider.getDimensionType() == DimensionType.THE_END || provider instanceof WorldProviderEnd)
            {
                pos = provider.getSpawnCoordinate();

                if (pos == null)
                {
                    pos = BlockPos.ORIGIN.up(provider.getAverageGroundLevel());
                }
            }
            // Most likely nether type dimensions
            else
            {
                pos = findNetherSpawnpoint(world);
            }
        }
        else if (info.getTerrainType() == WorldType.DEBUG_WORLD)
        {
            pos = BlockPos.ORIGIN.up(64);
        }
        // Mostly overworld type dimensions
        else
        {
            pos = findOverworldSpawnpoint(world, worldSettings);
        }

        info.setSpawn(pos);
        JustEnoughDimensions.logInfo("Set the world spawnpoint of dimension {} to {}", provider.getDimension(), pos);

        WorldBorder border = world.getWorldBorder();

        if (border.contains(pos) == false)
        {
            border.setCenter(pos.getX(), pos.getZ());
            JustEnoughDimensions.logInfo("Moved the WorldBorder of dimension {} to the world's spawn, because the spawn was outside the border", provider.getDimension());
        }
    }

    @Nonnull
    private static BlockPos findNetherSpawnpoint(World world)
    {
        Random random = new Random(world.getSeed());
        int x = 0;
        int z = 0;
        int iterations = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 110, z);

        while (iterations < 100)
        {
            while (pos.getY() >= 10)
            {
                if (world.isAirBlock(pos) && world.isAirBlock(pos.down(1)) && world.getBlockState(pos.down(2)).getMaterial().blocksMovement())
                {
                    return pos.down();
                }

                pos.setY(pos.getY() - 1);
            }

            x += random.nextInt(32) - random.nextInt(32);
            z += random.nextInt(32) - random.nextInt(32);
            pos.setPos(x, 110, z);
            iterations++;
        }

        JustEnoughDimensions.logger.warn("Unable to find a nether spawn point for dimension {}", world.provider.getDimension());

        return new BlockPos(0, 70, 0);
    }

    @Nonnull
    private static BlockPos findOverworldSpawnpoint(World world, WorldSettings worldSettings)
    {
        WorldProvider provider = world.provider;
        BiomeProvider biomeProvider = provider.getBiomeProvider();
        List<Biome> list = biomeProvider.getBiomesToSpawnIn();
        Random random = new Random(world.getSeed());
        int x = 8;
        int z = 8;

        // This will not generate chunks, but only check the biome ID from the genBiomes.getInts() output
        BlockPos pos = biomeProvider.findBiomePosition(0, 0, 512, list, random);

        if (pos != null)
        {
            x = pos.getX();
            z = pos.getZ();
        }
        else
        {
            JustEnoughDimensions.logger.warn("Unable to find spawn biome for dimension {}", provider.getDimension());
        }

        int iterations = 0;

        // Note: The canCoordinateBeSpawn() call will actually generate chunks!
        while (provider.canCoordinateBeSpawn(x, z) == false)
        {
            x += random.nextInt(64) - random.nextInt(64);
            z += random.nextInt(64) - random.nextInt(64);
            iterations++;

            if (iterations >= 100)
            {
                break;
            }
        }

        pos = getTopSolidOrLiquidBlock(world, new BlockPos(x, 70, z)).up();

        if (worldSettings.isBonusChestEnabled())
        {
            createBonusChest(world);
        }

        return pos;
    }

    // The one in world returns the position above the top solid block... >_>
    @Nonnull
    public static BlockPos getTopSolidOrLiquidBlock(World world, @Nonnull BlockPos posIn)
    {
        Chunk chunk = world.getChunkFromBlockCoords(posIn);
        BlockPos pos = new BlockPos(posIn.getX(), chunk.getTopFilledSegment() + 16, posIn.getZ());

        while (pos.getY() >= 0)
        {
            if (isSuitableSpawnBlock(world, chunk, pos))
            {
                return pos;
            }

            pos = pos.down();
        }

        return posIn;
    }

    private static boolean isSuitableSpawnBlock(World world, Chunk chunk, BlockPos pos)
    {
        IBlockState state = chunk.getBlockState(pos);

        return state.getMaterial().blocksMovement() &&
               state.getBlock().isLeaves(state, world, pos) == false &&
               state.getBlock().isFoliage(world, pos) == false;
    }

    private static void createBonusChest(World world)
    {
        WorldInfo info = world.getWorldInfo();
        WorldGeneratorBonusChest gen = new WorldGeneratorBonusChest();

        for (int i = 0; i < 10; ++i)
        {
            int x = info.getSpawnX() + world.rand.nextInt(6) - world.rand.nextInt(6);
            int z = info.getSpawnZ() + world.rand.nextInt(6) - world.rand.nextInt(6);
            BlockPos pos = world.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z)).up();

            if (gen.generate(world, world.rand, pos))
            {
                break;
            }
        }
    }
}