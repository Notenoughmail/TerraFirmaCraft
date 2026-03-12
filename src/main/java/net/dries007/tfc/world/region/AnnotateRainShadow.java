package net.dries007.tfc.world.region;

import java.util.Arrays;
import java.util.BitSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.util.Mth;
import org.apache.commons.lang3.mutable.MutableFloat;
import org.apache.commons.lang3.mutable.MutableInt;
import org.jetbrains.annotations.NotNull;

import net.dries007.tfc.world.noise.Noise2D;

public enum AnnotateRainShadow implements RegionTask
{
    INSTANCE;

    // TODO: Latitude dependant wind strength? biome altitude/base land height influence?
    @Override
    public void apply(RegionGenerator.Context context)
    {
        final Region region = context.region;
        final Noise2D windDirectionNoise = context.generator().windDirectionNoise;
        final BitSet mountains = new BitSet(region.size());
        final Int2ObjectOpenHashMap<MountainRange> rangesAtIndex = new Int2ObjectOpenHashMap<>();

        for (Region.Point point : region.points())
        {
            final double angle = windDirectionNoise.noise(point.x, point.z);

            point.windAngle = (float) angle;
            point.windDx = (float) Math.cos(angle);
            point.windDz = (float) Math.sin(angle);
            point.windStrength = 1f;
            if (point.mountain() && !mountains.get(point.index))
            {
                developMountainRange(point, mountains, region, rangesAtIndex);
            }
        }

        for (MountainRange range : region.mountainRanges())
        {
            for (int index : range.shape().fill())
            {
                final Region.Point point = region.atIndex(index);
                range.fillInfluence(point);
                point.inRainShadow = true;
            }
        }

        for (Region.Point point : region.points())
        {
            if (point.land())
            {
                baseInfluence(point, region, rangesAtIndex, mountains);
            }
        }

        final MutableFloat sum = new MutableFloat(0f);
        final MutableInt count = new MutableInt(0);
        for (Region.Point point : region.points())
        {
            if (point.inRainShadow)
            {
                sum.add(point.baseRainShadowInfluence); // Double the center influence
                for (int dx = -1 ; dx <= 1 ; dx++)
                {
                    for (int dz = -1 ; dz <= 1 ; dz++)
                    {
                        final Region.Point offset = region.atOffset(point.index, dx, dz);
                        if (offset != null)
                        {
                            count.increment();
                            sum.add(offset.baseRainShadowInfluence);
                        }
                    }
                }
                final float c = count.floatValue();
                final float s = sum.floatValue();
                if (c > 1 && s > 0.05)
                {
                    point.rainfall *= 1 - (s / c);
                    point.smoothedRainShadowInfluence = s / c;
                }
                else
                {
                    point.inRainShadow = false;
                }
                sum.setValue(0f);
                count.setValue(0);
            }
        }
    }

    private static void developMountainRange(Region.Point initialPoint, BitSet mountains, Region region, Int2ObjectOpenHashMap<MountainRange> ranges)
    {
        final IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
        queue.enqueue(initialPoint.index);
        final IntSet indexes = new IntArraySet();
        int minX = initialPoint.x, maxX = initialPoint.x, minZ = initialPoint.z, maxZ = initialPoint.z;
        int maximumHeight = initialPoint.baseLandHeight;
        while (!queue.isEmpty())
        {
            final int index = queue.dequeueInt();
            final Region.Point point = region.atIndex(index);
            if (point != null && point.mountain() && !mountains.get(index))
            {
                mountains.set(index);
                indexes.add(index);
                minX = Math.min(minX, point.x);
                maxX = Math.max(maxX, point.x);
                minZ = Math.min(minZ, point.z);
                maxZ = Math.max(maxZ, point.z);
                maximumHeight = Math.max(maximumHeight, point.baseLandHeight);

                // Merge ranges separated by only 1 grid
                for (int dx = -2 ; dx <= 2 ; dx++)
                {
                    for (int dz = - 2; dz <= 2 ; dz++)
                    {
                        final Region.Point offset = region.atOffset(index, dx, dz);
                        if (offset != null) queue.enqueue(offset.index);
                    }
                }
            }
        }

        final int dx = maxX - minX, dz = maxZ - minZ;
        if (dx > 3 && dz > 3 && (dx > 10 || dz > 10))
        {
            final MountainRange.Shape shape;
            if (dx > 6 && dz > 6)
            {
                shape = calculateRangeShape(indexes, mountains, region, minX, maxX, minZ, maxZ);
            }
            else
            {
                final int x = dx / 2, z = dz / 2;
                final MutableInt cX = new MutableInt(0), cZ = new MutableInt(0);
                for (int i : indexes)
                {
                    final Region.Point p = region.atIndex(i);
                    cX.add(p.x);
                    cZ.add(p.z);
                }
                final int cx = cX.intValue() / indexes.size(), cz = cZ.intValue() / indexes.size();
                if (x >= z)
                {
                    shape = new MountainRange.Shape(indexes, 0, 0, 1, x, z, cx, cz);
                }
                else
                {
                    shape = new MountainRange.Shape(indexes, Math.PI / 2, 1, 0, z, x, cx, cz);
                }
            }
            final MountainRange range = new MountainRange(indexes, shape, minX, maxX, minZ, maxZ, maximumHeight - 1);
            region.addMountainRange(range);
            for (int i : range.shape().fill()) ranges.put(i, range);
        }
    }

    // Amalgam of code from https://github.com/image-js/monotone-chain-convex-hull/blob/33aa0d25c17e2b9be384eda367d4d1886eec4ec2/src/index.ts
    // and terminology from https://web.archive.org/web/20090107032206/http://softsurfer.com/Archive/algorithm_0109/algorithm_0109.htm
    private static MountainRange.Shape calculateRangeShape(IntSet mountains, BitSet hullMark, Region region, int minX, int maxX, int minZ, int maxZ)
    {
        record Point(int index, int x, int z) implements Comparable<Point>
        {
            @Override
            public int compareTo(@NotNull Point o)
            {
                final int cx = Integer.compare(x, o.x);
                return cx == 0 ? Integer.compare(z, o.z) : cx;
            }

            public static int isLeft(Point p0, Point p1, int x, int z)
            {
                return (p1.x - p0.x) * (z - p0.z) - (x - p0.x) * (p1.z - p0.z);
            }
        }

        final Point[] p = mountains.intStream()
            .mapToObj(i -> {
                final Region.Point a = region.atIndex(i);
                return new Point(a.index, a.x, a.z);
            })
            .sorted()
            .toArray(Point[]::new);
        final int n = p.length;
        Point[] h = new Point[n * 2];

        int k = 0;

        for (final Point point : p)
        {
            while (k >= 2 && Point.isLeft(h[k - 2], h[k - 1], point.x, point.z) <= 0) k--;
            h[k++] = point;
        }

        final int t = k + 1;
        for (int i = n - 2 ; i >= 0 ; i--)
        {
            final Point point = p[i];
            while (k >= t && Point.isLeft(h[k - 2], h[k - 1], point.x, point.z) <= 0) k--;
            h[k++] = point;
        }

        // CCW
        h = Arrays.copyOf(h, k);
        final int v = h.length - 1;
        h[v] = h[0]; // Allow iterations to wrap around the to the first element cleanly

        final MutableInt cX = new MutableInt(0), cZ = new MutableInt(0);
        final IntSet inHull = new IntArraySet();
        for (int x = minX ; x <= maxX ; x++)
        {
            zBreak:
            for (int z = minZ ; z <= maxZ ; z++)
            {
                for (int i = 0 ; i < v ; i++)
                {
                    if (Point.isLeft(h[i], h[i + 1], x, z) < 0) continue zBreak;
                }
                final Region.Point point = region.at(x, z);
                if (point != null)
                {
                    hullMark.set(point.index);
                    inHull.add(point.index);
                    cX.add(point.x);
                    cZ.add(point.z);
                }
            }
        }
        final int cx = cX.intValue() / inHull.size(), cz = cZ.intValue() / inHull.size();

        final Point[] majorAxis = new Point[] { h[0], h[1] };
        double maxDist = 0, minDist = 50000;
        for (Point point : h)
        {
            for (Point nextPoint : h)
            {
                if (nextPoint != point)
                {
                    final double vertexDist = Mth.length(nextPoint.x - point.x, nextPoint.z - point.z);
                    if (vertexDist > maxDist)
                    {
                        maxDist = vertexDist;
                        majorAxis[0] = point;
                        majorAxis[1] = nextPoint;
                    }
                    minDist = Math.min(minDist, vertexDist);
                }
            }
        }
        final double angle = Math.atan2(majorAxis[1].x - majorAxis[0].x, majorAxis[1].z - majorAxis[0].z) + Math.PI / 2;
        final double c = Math.cos(angle), s = Math.sin(angle);
        // Area of ellipse A = π*r_major*r_minor, fatten minor slightly to account for hull being smaller than a true ellipse
        final double ma = (maxDist + minDist) * 0.5, mi = Math.min(ma, inHull.size() * 1.2 / (Math.PI * ma));

        return new MountainRange.Shape(inHull, angle, s, c, ma, mi, cx, cz);
    }

    private static void baseInfluence(Region.Point origin, Region region, Int2ObjectOpenHashMap<MountainRange> ranges, BitSet mountains)
    {
        if (mountains.get(origin.index))
        {
            final MountainRange range = ranges.get(origin.index);
            if (!range.mountains().contains(origin.index))
            {
                double tx = origin.x, tz = origin.z;
                Region.Point p = origin;
                while (p != null && !p.mountain() && mountains.get(p.index))
                {
                    tx -= p.windDx;
                    tz -= p.windDz;
                    p = region.at((int) tx, (int) tz);
                }
                if (p != null)
                {
                    if (!p.mountain())
                    {
                        p.inRainShadow = false;
                        p.baseRainShadowInfluence = -1f;
                    }
                }
            }
        }
        else
        {
            final boolean[] states = {
                false, // Encountered hull
                false, // In rain shadow
                false // In mountain
            };
            final int[] distances = {
                0, // Distance to hull
                0, // Partial distance through hull
                0 // Distance over ocean
            };
            final double interceptInfluence = influenceDistances(states, distances, origin, region, mountains);
            if (states[1])
            {
                final double distanceInfluence =  1 - (double) distances[0] / MAX_OVERLAND_DISTANCE;
                final double mountainInfluence = Mth.clampedMap(distances[1], 0, 10, 0, 1);
                final double influence = interceptInfluence * distanceInfluence * mountainInfluence;
                origin.baseRainShadowInfluence = (float) influence;
                origin.inRainShadow = true;
            }
        }
    }

    private static final int MAX_OVERLAND_DISTANCE = 35, MAX_OVER_SEA_DISTANCE = 3;

    private static double influenceDistances(boolean[] states, int[] distances, Region.Point origin, Region region, BitSet mountains)
    {
        double tx = origin.x, tz = origin.z, intercept = -1;
        Region.Point p = origin;
        while (p != null)
        {
            distances[2] = p.land() ? 0 : distances[2] + 1;
            if (distances[2] > MAX_OVER_SEA_DISTANCE) return -1; // Too far over an ocean
            if (states[0] && !mountains.get(p.index)) return -1; // Left the mountain hulls
            if (distances[0] > MAX_OVERLAND_DISTANCE) return -1; // Distance to a mountain too large
            if (mountains.get(p.index))
            {
                if (!states[0])
                {
                    states[0] = true;
                    intercept = p.baseRainShadowInfluence;
                }
                distances[1]++;
                if (states[2] && !p.mountain())
                {
                    states[1] = true;
                    return intercept; // Found the distance of the occluding mountain
                }
                states[2] = p.mountain();
            }
            else
            {
                distances[0]++;
            }

            tx -= p.windDx;
            tz -= p.windDz;
            p = region.at((int) tx, (int) tz);
        }
        return -1;
    }
}
