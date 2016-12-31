package fi.dy.masa.justenoughdimensions.world;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.DimensionType;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class WorldProviderJED extends WorldProvider
{
    protected int dayLength = 12000;
    protected int nightLength = 12000;
    protected int cloudHeight = 128;
    protected Vec3d skyColor = null;
    protected Vec3d cloudColor = null;
    protected Vec3d fogColor = null;

    @Override
    public DimensionType getDimensionType()
    {
        DimensionType type = null;

        try
        {
            type = DimensionManager.getProviderType(this.getDimension());
        }
        catch (IllegalArgumentException e)
        {
        }

        return type != null ? type : DimensionType.OVERWORLD;
    }

    @Override
    public BlockPos getSpawnCoordinate()
    {
        // Override this method because by default it returns null, so if overriding the End
        // with this class, this prevents a crash in the vanilla TP code.
        return this.world.getSpawnPoint();
    }

    @Override
    public boolean canDropChunk(int x, int z)
    {
        return this.world.isSpawnChunk(x, z) == false || this.getDimensionType().shouldLoadSpawn() == false;
    }

    /**
     * Set JED properties on the client side from a synced NBT tag
     */
    public void setJEDPropertiesFromNBT(NBTTagCompound tag)
    {
        if (tag != null)
        {
            if (tag.hasKey("DayLength",     Constants.NBT.TAG_INT))    { this.dayLength   = tag.getInteger("DayLength"); }
            if (tag.hasKey("NightLength",   Constants.NBT.TAG_INT))    { this.nightLength = tag.getInteger("NightLength"); }
            if (tag.hasKey("CloudHeight",   Constants.NBT.TAG_INT))    { this.cloudHeight = tag.getInteger("CloudHeight"); }

            if (tag.hasKey("SkyColor",      Constants.NBT.TAG_STRING)) { this.skyColor   = WorldInfoJED.hexStringToColor(tag.getString("SkyColor")); }
            if (tag.hasKey("CloudColor",    Constants.NBT.TAG_STRING)) { this.cloudColor = WorldInfoJED.hexStringToColor(tag.getString("CloudColor")); }
            if (tag.hasKey("FogColor",      Constants.NBT.TAG_STRING)) { this.fogColor   = WorldInfoJED.hexStringToColor(tag.getString("FogColor")); }
        }

        if (this.dayLength   <= 0) { this.dayLength = 1; }
        if (this.nightLength <= 0) { this.nightLength = 1; }
    }

    /**
     * Set server-side required properties from WorldInfo
     */
    public void setJEDPropertiesFromWorldInfo(WorldInfoJED worldInfo)
    {
        if (worldInfo != null)
        {
            this.dayLength = worldInfo.getDayLength();
            this.nightLength = worldInfo.getNightLength();

            if (this.dayLength   <= 0) { this.dayLength = 1; }
            if (this.nightLength <= 0) { this.nightLength = 1; }
        }
    }

    public int getDayCycleLength()
    {
        return this.dayLength + this.nightLength;
    }

    @Override
    public int getMoonPhase(long worldTime)
    {
        long cycleLength = this.getDayCycleLength();
        return (int)(worldTime / cycleLength % 8L + 8L) % 8;
    }

    @Override
    public float calculateCelestialAngle(long worldTime, float partialTicks)
    {
        int cycleLength = this.getDayCycleLength();
        int i = (int) (worldTime % cycleLength);
        float f;
        int duskOrDawnLength = (int) (0.075f * cycleLength);

        // Day, including dusk (The day part starts duskOrDawnLength before 0, so
        // subtract the duskOrDawnLength length from the day length to get the upper limit
        // of the day part of the cycle.)
        if (i > cycleLength - duskOrDawnLength || i < this.dayLength - duskOrDawnLength)
        {
            // Dawn (1.5 / 20)th of the full day cycle just before the day rolls over to 0 ticks
            if (i > this.dayLength) // this check could also be the "i > cycleLength - duskOrDawnLength"
            {
                i -= cycleLength - duskOrDawnLength;
            }
            // Day, starts from 0 ticks, so we need to add the dawn part
            else
            {
                i += duskOrDawnLength;
            }

            f = (((float) i + partialTicks) / (float) this.dayLength * 0.65f) + 0.675f;
        }
        // Night
        else
        {
            i -= (this.dayLength - duskOrDawnLength);
            f = (((float) i + partialTicks) / (float) this.nightLength * 0.35f) + 0.325f;
        }

        if (f > 1.0F)
        {
            --f;
        }

        float f1 = 1.0F - (float) ((Math.cos(f * Math.PI) + 1.0D) / 2.0D);
        f = f + (f1 - f) / 3.0F;

        return f;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public Vec3d getSkyColor(Entity entity, float partialTicks)
    {
        Vec3d skyColor = this.skyColor;
        if (skyColor == null)
        {
            return super.getSkyColor(entity, partialTicks);
        }

        float f1 = MathHelper.cos(this.world.getCelestialAngle(partialTicks) * ((float)Math.PI * 2F)) * 2.0F + 0.5F;
        f1 = MathHelper.clamp(f1, 0.0F, 1.0F);
        int x = MathHelper.floor(entity.posX);
        int y = MathHelper.floor(entity.posY);
        int z = MathHelper.floor(entity.posZ);
        BlockPos blockpos = new BlockPos(x, y, z);
        int blendColour = net.minecraftforge.client.ForgeHooksClient.getSkyBlendColour(this.world, blockpos);
        float r = (float)((blendColour >> 16 & 255) / 255.0F * skyColor.xCoord);
        float g = (float)((blendColour >>  8 & 255) / 255.0F * skyColor.yCoord);
        float b = (float)((blendColour       & 255) / 255.0F * skyColor.zCoord);
        r = r * f1;
        g = g * f1;
        b = b * f1;

        float rain = this.world.getRainStrength(partialTicks);
        if (rain > 0.0F)
        {
            float f7 = (r * 0.3F + g * 0.59F + b * 0.11F) * 0.6F;
            float f8 = 1.0F - rain * 0.75F;
            r = r * f8 + f7 * (1.0F - f8);
            g = g * f8 + f7 * (1.0F - f8);
            b = b * f8 + f7 * (1.0F - f8);
        }

        float thunder = this.world.getThunderStrength(partialTicks);
        if (thunder > 0.0F)
        {
            float f11 = (r * 0.3F + g * 0.59F + b * 0.11F) * 0.2F;
            float f9 = 1.0F - thunder * 0.75F;
            r = r * f9 + f11 * (1.0F - f9);
            g = g * f9 + f11 * (1.0F - f9);
            b = b * f9 + f11 * (1.0F - f9);
        }

        if (this.world.getLastLightningBolt() > 0)
        {
            float f12 = (float)this.world.getLastLightningBolt() - partialTicks;

            if (f12 > 1.0F)
            {
                f12 = 1.0F;
            }

            f12 = f12 * 0.45F;
            r = r * (1.0F - f12) + 0.8F * f12;
            g = g * (1.0F - f12) + 0.8F * f12;
            b = b * (1.0F - f12) + 1.0F * f12;
        }

        return new Vec3d(r, g, b);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public Vec3d getCloudColor(float partialTicks)
    {
        Vec3d cloudColor = this.cloudColor;
        if (cloudColor == null)
        {
            return super.getCloudColor(partialTicks);
        }

        float f1 = MathHelper.cos(this.world.getCelestialAngle(partialTicks) * ((float)Math.PI * 2F)) * 2.0F + 0.5F;
        f1 = MathHelper.clamp(f1, 0.0F, 1.0F);
        float r = (float) cloudColor.xCoord;
        float g = (float) cloudColor.yCoord;
        float b = (float) cloudColor.zCoord;

        float rain = this.world.getRainStrength(partialTicks);
        if (rain > 0.0F)
        {
            float f6 = (r * 0.3F + g * 0.59F + b * 0.11F) * 0.6F;
            float f7 = 1.0F - rain * 0.95F;
            r = r * f7 + f6 * (1.0F - f7);
            g = g * f7 + f6 * (1.0F - f7);
            b = b * f7 + f6 * (1.0F - f7);
        }

        r = r * (f1 * 0.9F + 0.1F);
        g = g * (f1 * 0.9F + 0.1F);
        b = b * (f1 * 0.85F + 0.15F);

        float thunder = this.world.getThunderStrength(partialTicks);
        if (thunder > 0.0F)
        {
            float f10 = (r * 0.3F + g * 0.59F + b * 0.11F) * 0.2F;
            float f8 = 1.0F - thunder * 0.95F;
            r = r * f8 + f10 * (1.0F - f8);
            g = g * f8 + f10 * (1.0F - f8);
            b = b * f8 + f10 * (1.0F - f8);
        }

        return new Vec3d(r, g, b);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public float getCloudHeight()
    {
        return (float) this.cloudHeight;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public Vec3d getFogColor(float celestialAngle, float partialTicks)
    {
        Vec3d fogColor = this.fogColor;
        if (fogColor == null)
        {
            return super.getFogColor(celestialAngle, partialTicks);
        }

        float f = MathHelper.cos(celestialAngle * ((float)Math.PI * 2F)) * 2.0F + 0.5F;
        f = MathHelper.clamp(f, 0.0F, 1.0F);
        float r = (float) fogColor.xCoord;
        float g = (float) fogColor.yCoord;
        float b = (float) fogColor.zCoord;
        r = r * (f * 0.94F + 0.06F);
        g = g * (f * 0.94F + 0.06F);
        b = b * (f * 0.91F + 0.09F);
        return new Vec3d(r, g, b);
    }
}