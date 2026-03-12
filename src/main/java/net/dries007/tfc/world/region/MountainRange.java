package net.dries007.tfc.world.region;

import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Mountain ranges possess a local, rotated coordinate grid with an origin at Shape#(cX,cZ)
 */
public record MountainRange(IntSet mountains, Shape shape, int minX, int maxX, int minZ, int maxZ, int characteristicHeight)
{
    public double toRangeX(int dx, int dz)
    {
        return dx * shape.cosAngle() - dz * shape.sinAngle();
    }
    public double toRangeZ(int dx, int dz)
    {
        return dx * shape.sinAngle() + dz * shape.cosAngle();
    }

    // TODO: Merge into AnnotateRainShadow#baseInfluence?
    public void fillInfluence(Region.Point point)
    {
        final int wdx = point.x - shape.cX(), wdz = point.z - shape.cZ();
        final double dx = toRangeX(wdx, wdz) / shape.rMa();
        final double dz = toRangeZ(wdx, wdz) / shape.rMi();
        final double d = dx * dx + dz * dz;
        final double influence = d > 1.4 ? 0.43 : 1 - d * d + 0.512 * d * d * d; // ∈ [0.43, 1]
        point.baseRainShadowInfluence = (float) influence;
    }

    public record Shape(IntSet fill, double angle, double sinAngle, double cosAngle, double rMa, double rMi, int cX, int cZ) {}

    // For drawing
    public boolean isDebugX(int x)
    {
        return x == minX || x == shape.cX() || x == maxX;
    }
    public boolean isDebugZ(int z)
    {
        return z == minZ || z == shape.cZ() || z == maxZ;
    }
    public boolean isDebugBound(int x, int z)
    {
        return isDebugX(x) && isDebugZ(z);
    }
    public boolean isDebugVec(int x, int z)
    {
        final int dx = x - shape.cX(), dz = z - shape.cZ();
        final int rx = (int) Math.round(toRangeX(dx, dz)), rz = (int) Math.round(toRangeZ(dx, dz));
        return (rx == Math.round(shape.rMa()) && rz == 0) || (rz == Math.round(shape.rMi()) && rx == 0);
    }
}
