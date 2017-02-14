package fi.dy.masa.justenoughdimensions.world.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.datafix.FixTypes;
import net.minecraft.world.World;
import net.minecraft.world.storage.SaveHandler;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import net.minecraftforge.fml.relauncher.ReflectionHelper.UnableToFindFieldException;
import fi.dy.masa.justenoughdimensions.JustEnoughDimensions;
import fi.dy.masa.justenoughdimensions.config.Configs;
import fi.dy.masa.justenoughdimensions.config.DimensionConfig;
import fi.dy.masa.justenoughdimensions.config.DimensionConfig.WorldInfoType;
import fi.dy.masa.justenoughdimensions.world.IWorldProviderJED;
import fi.dy.masa.justenoughdimensions.world.WorldInfoJED;

public class WorldInfoUtils
{
    private static Field field_worldInfo = null;

    static
    {
        try
        {
            field_worldInfo = ReflectionHelper.findField(World.class, "field_72986_A", "worldInfo");
        }
        catch (UnableToFindFieldException e)
        {
            JustEnoughDimensions.logger.error("WorldInfoUtils: Reflection failed!!", e);
        }
    }

    public static void loadAndSetCustomWorldInfo(World world, boolean tryFindSpawn)
    {
        int dimension = world.provider.getDimension();

        if (DimensionConfig.instance().useCustomWorldInfoFor(dimension))
        {
            JustEnoughDimensions.logInfo("Using custom WorldInfo for dimension {}", dimension);

            NBTTagCompound nbt = loadWorldInfoFromFile(world, WorldFileUtils.getWorldDirectory(world));
            NBTTagCompound playerNBT = world.getMinecraftServer().getPlayerList().getHostPlayerData();
            boolean isDimensionInit = false;

            // No level.dat exists for this dimension yet, inherit the values from the overworld
            if (nbt == null)
            {
                if (playerNBT == null)
                {
                    playerNBT = new NBTTagCompound();
                }

                // This is just to set the dimension field in WorldInfo, so that
                // the crash report shows the correct dimension ID... maybe
                playerNBT.setInteger("Dimension", dimension);

                // Get the values from the overworld WorldInfo
                nbt = world.getWorldInfo().cloneNBTCompound(playerNBT);

                // Search for a proper suitable spawn position
                isDimensionInit = true;
            }

            // Any tags/properties that are set in the dimensions.json take precedence over the level.dat
            DimensionConfig.instance().setWorldInfoValues(dimension, nbt, WorldInfoType.REGULAR);

            // On the first time this dimension loads (at least with custom WorldInfo),
            // set the worldinfo_onetime values
            if (isDimensionInit)
            {
                DimensionConfig.instance().setWorldInfoValues(dimension, nbt, WorldInfoType.ONE_TIME);
            }

            setWorldInfo(world, new WorldInfoJED(nbt));

            if (Configs.enableOverrideBiomeProvider)
            {
                WorldUtils.overrideBiomeProvider(world);
            }

            if (tryFindSpawn && isDimensionInit)
            {
                WorldUtils.findAndSetWorldSpawn(world, true);
            }
        }
    }

    public static void saveCustomWorldInfoToFile(World world)
    {
        if (Configs.enableSeparateWorldInfo && world.isRemote == false &&
            DimensionConfig.instance().useCustomWorldInfoFor(world.provider.getDimension()))
        {
            saveWorldInfoToFile(world, WorldFileUtils.getWorldDirectory(world));
        }
    }

    private static NBTTagCompound loadWorldInfoFromFile(World world, @Nullable File worldDir)
    {
        if (worldDir == null)
        {
            JustEnoughDimensions.logger.warn("loadWorldInfo(): No worldDir found");
            return null;
        }

        File levelFile = new File(worldDir, "level.dat");

        if (levelFile.exists())
        {
            try
            {
                NBTTagCompound nbt = CompressedStreamTools.readCompressed(new FileInputStream(levelFile));
                nbt = world.getMinecraftServer().getDataFixer().process(FixTypes.LEVEL, nbt.getCompoundTag("Data"));
                //FMLCommonHandler.instance().handleWorldDataLoad((SaveHandler) world.getSaveHandler(), info, nbt);
                return nbt;
            }
            catch (Exception e)
            {
                JustEnoughDimensions.logger.error("Exception reading " + levelFile.getName(), (Throwable) e);
                return null;
            }

            //return SaveFormatOld.loadAndFix(fileLevel, world.getMinecraftServer().getDataFixer(), (SaveHandler) world.getSaveHandler());
        }

        return null;
    }

    private static void setWorldInfo(World world, WorldInfoJED info)
    {
        if (field_worldInfo != null)
        {
            try
            {
                field_worldInfo.set(world, info);

                if (world.provider instanceof IWorldProviderJED)
                {
                    ((IWorldProviderJED) world.provider).setJEDPropertiesFromWorldInfo(info);
                }

                WorldBorderUtils.setWorldBorderValues(world);
                WorldUtils.setChunkProvider(world);
            }
            catch (Exception e)
            {
                JustEnoughDimensions.logger.error("JEDEventHandler: Failed to override WorldInfo for dimension {}", world.provider.getDimension());
            }
        }
    }

    private static void saveWorldInfoToFile(World world, @Nullable File worldDir)
    {
        if (worldDir == null)
        {
            JustEnoughDimensions.logger.error("saveWorldInfo(): No worldDir found");
            return;
        }

        WorldInfo info = world.getWorldInfo();
        info.setBorderSize(world.getWorldBorder().getDiameter());
        info.getBorderCenterX(world.getWorldBorder().getCenterX());
        info.getBorderCenterZ(world.getWorldBorder().getCenterZ());
        info.setBorderSafeZone(world.getWorldBorder().getDamageBuffer());
        info.setBorderDamagePerBlock(world.getWorldBorder().getDamageAmount());
        info.setBorderWarningDistance(world.getWorldBorder().getWarningDistance());
        info.setBorderWarningTime(world.getWorldBorder().getWarningTime());
        info.setBorderLerpTarget(world.getWorldBorder().getTargetSize());
        info.setBorderLerpTime(world.getWorldBorder().getTimeUntilTarget());

        NBTTagCompound rootTag = new NBTTagCompound();
        NBTTagCompound playerNBT = world.getMinecraftServer().getPlayerList().getHostPlayerData();
        if (playerNBT == null)
        {
            playerNBT = new NBTTagCompound();
        }

        // This is just to set the dimension field in WorldInfo, so that
        // the crash report shows the correct dimension ID... maybe
        playerNBT.setInteger("Dimension", world.provider.getDimension());

        rootTag.setTag("Data", info.cloneNBTCompound(playerNBT));

        if (world.getSaveHandler() instanceof SaveHandler)
        {
            FMLCommonHandler.instance().handleWorldDataSave((SaveHandler) world.getSaveHandler(), info, rootTag);
        }

        try
        {
            File fileNew = new File(worldDir, "level.dat_new");
            File fileOld = new File(worldDir, "level.dat_old");
            File fileCurrent = new File(worldDir, "level.dat");
            CompressedStreamTools.writeCompressed(rootTag, new FileOutputStream(fileNew));

            if (fileOld.exists())
            {
                fileOld.delete();
            }

            fileCurrent.renameTo(fileOld);

            if (fileCurrent.exists())
            {
                JustEnoughDimensions.logger.error("Failed to rename {} to {}", fileCurrent.getName(), fileOld.getName());
                return;
            }

            fileNew.renameTo(fileCurrent);

            if (fileNew.exists())
            {
                JustEnoughDimensions.logger.error("Failed to rename {} to {}", fileNew.getName(), fileCurrent.getName());
                return;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}