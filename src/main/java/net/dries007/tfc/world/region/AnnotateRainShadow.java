package net.dries007.tfc.world.region;

import net.minecraft.util.Mth;
import org.apache.commons.lang3.mutable.MutableFloat;
import org.apache.commons.lang3.mutable.MutableInt;

import net.dries007.tfc.client.overworld.SolarCalculator;
import net.dries007.tfc.world.noise.Noise2D;

public enum AnnotateRainShadow implements RegionTask
{
    INSTANCE;

    // TODO: Latitude dependant wind strength? Mountain centroid idea?
    @Override
    public void apply(RegionGenerator.Context context)
    {
        final float temperatureScale = context.generator().settings.temperatureScale();
        final Noise2D latitudeNoise = context.generator().latitudeNoise;
        for (Region.Point point : context.region.points())
        {
            final boolean isNorthernHemisphere = SolarCalculator.getInNorthernHemisphere(point.z * Units.GRID_WIDTH_IN_BLOCK, temperatureScale);
            point.latitude = (float) latitudeNoise.noise(point.x, point.z);
            characterizeWindDir(point, isNorthernHemisphere);
        }
        for (Region.Point point : context.region.points())
        {
            if (point.land() && point.distanceToOcean > 0)
            {
                Region.Point p = point;
                Region.Point nextP = context.region.at(p.x + p.xWind(), p.z + p.zWind());
                int i = 0;
                while (nextP != null && nextP.hasWind() && nextP.land() && !p.mountain())
                {
                    i++;
                    p = nextP;
                    nextP = context.region.at(p.x - p.xWind(), p.z - p.zWind());
                }
                if (p.mountain() && i > 2 && i < 16)
                {
                    point.rainfall = Mth.clampedMap(i, 0, 15, 0, point.rainfall);
                    point.inRainShadow = true;
                }
            }
        }
        final MutableFloat sum = new MutableFloat(0f);
        final MutableInt count = new MutableInt(0);
        for (Region.Point point : context.region.points())
        {
            if (point.inRainShadow)
            {
                sum.add(point.rainfall);
                count.increment();
                softenShadowEdge(point, context.region, 0, 1, count, sum);
                softenShadowEdge(point, context.region, 0, -1, count, sum);
                softenShadowEdge(point, context.region, 1, 0, count, sum);
                softenShadowEdge(point, context.region, -1, 0, count, sum);
                final float c = count.floatValue();
                if (c > 1) point.rainfall = sum.floatValue() / c;
                sum.setValue(0f);
                count.setValue(0);
            }
        }
    }

    private static void characterizeWindDir(Region.Point point, boolean isNorthernHemisphere)
    {
        final float latitude = point.latitude;
        if (latitude >= 5) // < 5 ~ ITCZ
        {
            if (latitude > 63) // 59...63 Polar Jet Stream
            {
                point.setWestWind();
                if (latitude < 85)
                {
                    point.setNorthWind();
                    if (isNorthernHemisphere) point.swapNorthSouthWind();
                }
            }
            else if (latitude > 28) // 28...32 Subtropical Jet Stream
            {
                point.setEastWind();
                if (latitude > 32 && latitude < 59)
                {
                    point.setNorthWind();
                    if (!isNorthernHemisphere) point.swapNorthSouthWind();
                }
            }
            else
            {
                point.setWestWind();
                point.setNorthWind();
                if (isNorthernHemisphere) point.swapNorthSouthWind();
            }
        }
    }

    private static void softenShadowEdge(Region.Point point, Region region, int dx, int dz, MutableInt count, MutableFloat sum)
    {
        final Region.Point p = region.at(point.x + dx, point.z + dz);
        if (p != null && !p.inRainShadow)
        {
            if ((p.distanceToOcean < 1 && p.rainfall - point.rainfall  > 15) || p.mountain())
            {
                p.rainfall = (p.rainfall + point.rainfall) * 0.5f;
            }
            else
            {
                sum.add((p.mountain() ? point : p).rainfall);
                count.increment();
            }
        }
    }
}
