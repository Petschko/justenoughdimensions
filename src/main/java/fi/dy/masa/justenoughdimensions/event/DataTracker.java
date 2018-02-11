package fi.dy.masa.justenoughdimensions.event;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.GameType;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;
import fi.dy.masa.justenoughdimensions.JustEnoughDimensions;
import fi.dy.masa.justenoughdimensions.config.Configs;
import fi.dy.masa.justenoughdimensions.reference.Reference;
import fi.dy.masa.justenoughdimensions.world.JEDWorldProperties;

public class DataTracker
{
    private static DataTracker instance;
    private Map<UUID, GameType> normalGameModes = new HashMap<>();
    private Map<UUID, Integer> playerDimensions = new HashMap<>();
    private boolean dirty = false;

    public static DataTracker getInstance()
    {
        if (instance == null)
        {
            instance = new DataTracker();
        }

        return instance;
    }

    public void playerLoginOrRespawn(EntityPlayer playerIn)
    {
        if (playerIn.getClass() == EntityPlayerMP.class)
        {
            if (this.playerDimensions.containsKey(playerIn.getUniqueID()))
            {
                int dimFrom = this.playerDimensions.get(playerIn.getUniqueID());
                int dimTo = playerIn.getEntityWorld().provider.getDimension();

                if (dimFrom != dimTo)
                {
                    this.playerChangedDimension(playerIn, dimFrom, dimTo);
                }
            }

            this.storePlayerDimension(playerIn);
        }
    }

    /**
     * The basic idea here is to store the "normal" gamemode of a player in a non-forced-gamemode
     * dimension, so that we know what to set the player's gamemode to when they leave a forced-gamemode-dimension.
     * Note that they may be changing between different forced-gamemode dimension before returning to
     * a non-forced-gamemode dimension.
     */
    public void playerChangedDimension(EntityPlayer playerIn, int dimFrom, int dimTo)
    {
        if (playerIn.getClass() == EntityPlayerMP.class)
        {
            if (Configs.enableForcedGameModes)
            {
                EntityPlayerMP player = (EntityPlayerMP) playerIn;
                boolean forcedFrom = this.dimensionHasForcedGameMode(dimFrom);
                boolean forcedTo = this.dimensionHasForcedGameMode(dimTo);

                if (forcedTo)
                {
                    // The gamemode only needs to be stored when changing to a forced-gamemode dimension
                    // from a non-forced-gamemode dimension. Ie. the gamemode won't be touched when switching
                    // between non-forced-gamemode dimensions, and the stored one won't be changed if switching
                    // between multiple forced ones.
                    if (forcedFrom == false)
                    {
                        this.storeNonForcedGameMode(player);
                    }

                    // The player is in the destination world at this point, so we get the gamemode from there
                    this.setPlayerGameMode(player, player.getEntityWorld().getWorldInfo().getGameType());
                }
                // When switching to a non-forced-gamemode dimension from a forced-gamemode dimension,
                // ie. we have a stored gamemode for the player.
                else if (this.normalGameModes.containsKey(player.getUniqueID()))
                {
                    this.restoreStoredGameMode(player);
                }
            }

            this.storePlayerDimension(playerIn);
        }
    }

    private boolean dimensionHasForcedGameMode(int dimension)
    {
        JEDWorldProperties props = JEDWorldProperties.getPropertiesIfExists(dimension);
        return props != null && props.getForceGameMode();
    }

    private void storeNonForcedGameMode(EntityPlayerMP player)
    {
        this.normalGameModes.put(player.getUniqueID(), player.interactionManager.getGameType());
        this.dirty = true;
    }

    private void restoreStoredGameMode(EntityPlayerMP player)
    {
        this.setPlayerGameMode(player, this.normalGameModes.get(player.getUniqueID()));
        this.normalGameModes.remove(player.getUniqueID());
        this.dirty = true;
    }

    private void setPlayerGameMode(EntityPlayerMP player, GameType type)
    {
        player.setGameType(type);
        player.sendMessage(new TextComponentTranslation("jed.info.gamemode.changed", type.toString()));
    }

    private void storePlayerDimension(EntityPlayer player)
    {
        this.storePlayerDimension(player, player.getEntityWorld().provider.getDimension());
    }

    private void storePlayerDimension(EntityPlayer player, int dimension)
    {
        this.playerDimensions.put(player.getUniqueID(), dimension);
        this.dirty = true;
    }

    @Nullable
    public Integer getPlayersDimension(EntityPlayer player)
    {
        return this.playerDimensions.get(player.getUniqueID());
    }

    public void readFromDisk(@Nullable File worldDir)
    {
        // Clear the data structures when reading the data for a world/save, so that data
        // from another world won't carry over to a world/save that doesn't have the file yet.
        this.normalGameModes.clear();

        if (worldDir != null)
        {
            try
            {
                File file = new File(new File(new File(worldDir, "data"), Reference.MOD_ID), "data_tracker.dat");

                if (file.exists() && file.isFile() && file.canRead())
                {
                    this.readFromNBT(CompressedStreamTools.readCompressed(new FileInputStream(file)));
                }
            }
            catch (Exception e)
            {
                JustEnoughDimensions.logger.warn("Failed to read DataTracker data from file");
            }
        }
    }

    public void writeToDisk()
    {
        if (this.dirty)
        {
            try
            {
                File saveDir = DimensionManager.getCurrentSaveRootDirectory();

                if (saveDir == null)
                {
                    return;
                }

                saveDir = new File(new File(saveDir, "data"), Reference.MOD_ID);

                if (saveDir.exists() == false && saveDir.mkdirs() == false)
                {
                    JustEnoughDimensions.logger.warn("Failed to create the save directory '{}'", saveDir.toString());
                    return;
                }

                File fileTmp = new File(saveDir, "data_tracker.dat.tmp");
                File fileReal = new File(saveDir, "data_tracker.dat");
                CompressedStreamTools.writeCompressed(this.writeToNBT(new NBTTagCompound()), new FileOutputStream(fileTmp));

                if (fileReal.exists())
                {
                    fileReal.delete();
                }

                fileTmp.renameTo(fileReal);
                this.dirty = false;
            }
            catch (Exception e)
            {
                JustEnoughDimensions.logger.warn("Failed to write GameModeTracker data to file", e);
            }
        }
    }

    private void readFromNBT(NBTTagCompound nbt)
    {
        if (nbt != null)
        {
            if (nbt.hasKey("PlayerGameModes", Constants.NBT.TAG_LIST))
            {
                NBTTagList tagList = nbt.getTagList("PlayerGameModes", Constants.NBT.TAG_COMPOUND);
                final int count = tagList.tagCount();

                for (int i = 0; i < count; ++i)
                {
                    NBTTagCompound tag = tagList.getCompoundTagAt(i);

                    if (tag.hasKey("UUIDM", Constants.NBT.TAG_LONG) &&
                        tag.hasKey("UUIDL", Constants.NBT.TAG_LONG) &&
                        tag.hasKey("GameMode", Constants.NBT.TAG_BYTE))
                    {
                        GameType type = GameType.getByID(tag.getByte("GameMode"));

                        if (type != GameType.NOT_SET)
                        {
                            this.normalGameModes.put(new UUID(tag.getLong("UUIDM"), tag.getLong("UUIDL")), type);
                        }
                    }
                }
            }

            if (nbt.hasKey("PlayerDimensions", Constants.NBT.TAG_LIST))
            {
                NBTTagList tagList = nbt.getTagList("PlayerDimensions", Constants.NBT.TAG_COMPOUND);
                final int count = tagList.tagCount();

                for (int i = 0; i < count; ++i)
                {
                    NBTTagCompound tag = tagList.getCompoundTagAt(i);

                    if (tag.hasKey("UUIDM", Constants.NBT.TAG_LONG) &&
                        tag.hasKey("UUIDL", Constants.NBT.TAG_LONG) &&
                        tag.hasKey("Dimension", Constants.NBT.TAG_INT))
                    {
                        this.playerDimensions.put(new UUID(tag.getLong("UUIDM"), tag.getLong("UUIDL")), tag.getInteger("Dimension"));
                    }
                }
            }
        }
    }

    private NBTTagCompound writeToNBT(NBTTagCompound nbt)
    {
        NBTTagList tagList = new NBTTagList();

        for (Map.Entry<UUID, GameType> entry : this.normalGameModes.entrySet())
        {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("UUIDM", entry.getKey().getMostSignificantBits());
            tag.setLong("UUIDL", entry.getKey().getLeastSignificantBits());
            tag.setByte("GameMode", (byte) entry.getValue().getID());

            tagList.appendTag(tag);
        }

        nbt.setTag("PlayerGameModes", tagList);

        tagList = new NBTTagList();

        for (Map.Entry<UUID, Integer> entry : this.playerDimensions.entrySet())
        {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("UUIDM", entry.getKey().getMostSignificantBits());
            tag.setLong("UUIDL", entry.getKey().getLeastSignificantBits());
            tag.setInteger("Dimension", entry.getValue());

            tagList.appendTag(tag);
        }

        nbt.setTag("PlayerDimensions", tagList);

        return nbt;
    }
}
