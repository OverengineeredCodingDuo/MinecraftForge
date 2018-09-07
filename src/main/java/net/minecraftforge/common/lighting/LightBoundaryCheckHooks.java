/*
 * Minecraft Forge
 * Copyright (c) 2016.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.common.lighting;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.Axis;
import net.minecraft.util.EnumFacing.AxisDirection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.util.math.BlockPos.PooledMutableBlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.fml.common.FMLLog;

public class LightBoundaryCheckHooks
{
    public static final String neighborLightChecksKey = "NeighborLightChecks";
    static final int OUT_INDEX_OFFSET = 8;
    static final int FLAG_COUNT_CLIENT = OUT_INDEX_OFFSET + 2;
    private static final int FLAG_COUNT_SERVER = OUT_INDEX_OFFSET + 12;

    public static void flagInnerSecBoundaryForUpdate(final Chunk chunk, final BlockPos pos, final EnumSkyBlock lightType)
    {
        flagInnerChunkBoundaryForUpdate(chunk, pos.getX(), pos.getZ(), 1 << (pos.getY() >> 4), lightType);
    }

    private static void flagChunkBoundaryForUpdate(final Chunk chunk, final int index, final int sectionMask, final EnumSkyBlock lightType)
    {
        initNeighborLightChecks(chunk);
        chunk.neighborLightChecks[index] |= sectionMask << (LightUtils.getIndex(lightType) << 4);
        chunk.markDirty();
    }

    public static void flagInnerChunkBoundaryForUpdate(final Chunk chunk, final int x, final int z, final int sectionMask, final EnumSkyBlock lightType)
    {
        final int xRegion = LightUtils.getBoundaryRegion(x & 15);
        final int zRegion = LightUtils.getBoundaryRegion(z & 15);

        final int index = (xRegion * (zRegion - 2) + 2 * ((xRegion & 1) - 1) * (zRegion - 1) + 1) & 7;

        flagChunkBoundaryForUpdate(chunk, index, sectionMask, lightType);

        if (chunk.getWorld().isRemote)
            chunk.pendingBoundaryChecks = true;
    }

    public static int getFlagIndex(final EnumFacing dir, final EnumBoundaryFacing boundaryFacing)
    {
        return dir.getAxis() == Axis.Y ? getVerticalFlagIndex(dir, boundaryFacing) : getHorizontalFlagIndex(dir, boundaryFacing);
    }

    public static int getVerticalFlagIndex(final EnumFacing dir, final EnumBoundaryFacing boundaryFacing)
    {
        return OUT_INDEX_OFFSET + ((dir.getIndex() + boundaryFacing.verticalOffset) & 1);
    }

    public static int getHorizontalFlagIndex(final EnumFacing dir, final EnumBoundaryFacing boundaryFacing)
    {
        return dir.getHorizontalIndex() * boundaryFacing.indexMultiplier + boundaryFacing.offset + 1;
    }

    public static int getHorizontalFlagIndex(final EnumFacing dir, final EnumBoundaryFacing boundaryFacing, final int region)
    {
        return (getHorizontalFlagIndex(dir, boundaryFacing) + region) & 7;
    }

    public static void flagOuterSecBoundaryForUpdate(final Chunk chunk, final BlockPos pos, final EnumFacing dir, final EnumSkyBlock lightType)
    {
        flagOuterChunkBoundaryForUpdate(chunk, pos.getX(), pos.getZ(), dir, 1 << (pos.getY() >> 4), lightType);
    }

    public static void flagOuterChunkBoundaryForUpdate(final Chunk chunk, final int x, final int z, final EnumFacing dir, final int sectionMask, final EnumSkyBlock lightType)
    {
        if (chunk.getWorld().isRemote)
            return;

        final int index = getHorizontalFlagIndex(dir, EnumBoundaryFacing.OUT) - LightUtils.getBoundaryRegion(x, z, dir);

        flagChunkBoundaryForUpdate(chunk, index, sectionMask, lightType);
    }

    private static void mergeFlags(final Chunk chunk, final Chunk neighborChunk, final EnumFacing dir)
    {
        if (neighborChunk.neighborLightChecks == null)
            return;

        final EnumFacing oppDir = dir.getOpposite();

        final int inIndex = getHorizontalFlagIndex(dir, EnumBoundaryFacing.IN);
        final int outIndex = getHorizontalFlagIndex(oppDir, EnumBoundaryFacing.OUT);

        for (int offset = -1; offset <= 1; ++offset)
        {
            final int neighborFlags = neighborChunk.neighborLightChecks[outIndex + offset];

            if (neighborFlags != 0)
            {
                initNeighborLightChecks(chunk);
                chunk.neighborLightChecks[(inIndex + offset) & 7] |= neighborFlags;
                neighborChunk.neighborLightChecks[outIndex + offset] = 0;

                LightTrackingHooks.initLightTrackings(neighborChunk);
                neighborChunk.lightTrackings[LightTrackingHooks.getHorizontalFlagIndex(oppDir) + offset] |= neighborFlags;
            }
        }

        chunk.markDirty();
        neighborChunk.markDirty();
    }

    public static void scheduleRelightChecksForChunkBoundariesServer(final World world, final Chunk chunk)
    {
        final PooledMutableBlockPos pos = PooledMutableBlockPos.retain();

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
        {
            final int xOffset = dir.getFrontOffsetX();
            final int zOffset = dir.getFrontOffsetZ();

            final Chunk nChunk = world.getChunkProvider().getLoadedChunk(chunk.x + xOffset, chunk.z + zOffset);

            if (nChunk == null)
                continue;

            // Merge flags upon loading of a chunk. This ensures that all flags are always already on the IN boundary below
            mergeFlags(chunk, nChunk, dir);
            mergeFlags(nChunk, chunk, dir.getOpposite());

            scheduleRelightChecksForNeighbor(world, nChunk, dir, pos);
            scheduleRelightChecksForInteriorBoundary(world, chunk, dir, pos);
        }

        for (final AxisDirection xAxis : LightUtils.ENUM_AXIS_DIRECTION_VALUES)
        {
            for (final AxisDirection zAxis : LightUtils.ENUM_AXIS_DIRECTION_VALUES)
            {
                final int xOffset = xAxis.getOffset();
                final int zOffset = zAxis.getOffset();

                if (world.getChunkProvider().getLoadedChunk(chunk.x + xOffset, chunk.z) != null && world.getChunkProvider().getLoadedChunk(chunk.x, chunk.z + zOffset) != null)
                    scheduleRelightChecksForCorner(world, chunk, xOffset, zOffset, pos);
            }
        }

        pos.release();
    }

    static void scheduleRelightChecksForChunkBoundariesClient(final World world, final Chunk chunk)
    {
        final PooledMutableBlockPos pos = PooledMutableBlockPos.retain();

        for (final EnumFacing dir : EnumFacing.HORIZONTALS)
            scheduleRelightChecksForInteriorBoundary(world, chunk, dir, pos);

        for (final AxisDirection xAxis : LightUtils.ENUM_AXIS_DIRECTION_VALUES)
            for (final AxisDirection zAxis : LightUtils.ENUM_AXIS_DIRECTION_VALUES)
            {
                final int xOffset = xAxis.getOffset();
                final int zOffset = zAxis.getOffset();

                scheduleRelightChecksForCorner(world, chunk, xOffset, zOffset, pos);
            }

        for (int i = 0; i <= 1; ++i)
        {
            final int flags = chunk.neighborLightChecks[LightBoundaryCheckHooks.OUT_INDEX_OFFSET + i];

            if (flags != 0)
            {
                chunk.neighborLightChecks[LightBoundaryCheckHooks.OUT_INDEX_OFFSET] = 0;

                for (final EnumSkyBlock lightType : LightUtils.ENUM_SKY_BLOCK_VALUES)
                {
                    final int shift = LightUtils.getIndex(lightType) << 4;
                    final int sectionMask = (flags >> shift) & ((1 << 16) - 1);

                    for (int y = 0; y < 16; ++y)
                    {
                        final int yBase = (y << 4) + (15 & (i - 1));

                        if ((sectionMask & (1 << y)) != 0)
                            LightUtils.scheduleRelightChecksForArea(world, lightType, chunk.x, yBase, chunk.z, chunk.x + 15, yBase, chunk.z + 15, pos);
                    }
                }
            }
        }

        pos.release();
    }

    private static void scheduleRelightChecksForNeighbor(final World world, final Chunk nChunk, final EnumFacing dir, final MutableBlockPos pos)
    {
        scheduleRelightChecksForInteriorBoundary(world, nChunk, dir.getOpposite(), pos);

        final int xOffset = dir.getFrontOffsetX();
        final int zOffset = dir.getFrontOffsetZ();

        for (final AxisDirection axis : LightUtils.ENUM_AXIS_DIRECTION_VALUES)
        {
            final int xOffsetNeighbor = axis.getOffset() * (zOffset & 1);
            final int zOffsetNeighbor = axis.getOffset() * (xOffset & 1);

            if (world.getChunkProvider().getLoadedChunk(nChunk.x + xOffsetNeighbor, nChunk.z + zOffsetNeighbor) != null)
                scheduleRelightChecksForCorner(world, nChunk, -xOffset + xOffsetNeighbor, -zOffset + zOffsetNeighbor, pos);
        }
    }

    static void scheduleRelightChecksForCorner(
        final World world,
        final Chunk chunk,
        final int xOffset,
        final int zOffset,
        final MutableBlockPos pos
    )
    {
        if (chunk.neighborLightChecks == null)
            return;

        final int flagIndex = (xOffset * (zOffset - 2) + 1) & 7;

        final int flags = chunk.neighborLightChecks[flagIndex];

        if (flags == 0)
            return;

        chunk.neighborLightChecks[flagIndex] = 0;

        final int x = (chunk.x << 4) + (((-xOffset) >> 1) & 15);
        final int z = (chunk.z << 4) + (((-zOffset) >> 1) & 15);

        for (final EnumSkyBlock lightType : LightUtils.ENUM_SKY_BLOCK_VALUES)
        {
            final int shift = LightUtils.getIndex(lightType) << 4;
            final int sectionMask = (flags >> shift) & ((1 << 16) - 1);

            for (int y = 0; y < 16; ++y)
            {
                if ((sectionMask & (1 << y)) != 0)
                    LightUtils.scheduleRelightChecksForColumn(world, lightType, x, z, y << 4, (y << 4) + 15, pos);
            }
        }
    }

    static void scheduleRelightChecksForInteriorBoundary(
        final World world,
        final Chunk chunk,
        final EnumFacing dir,
        final MutableBlockPos pos
    )
    {
        if (chunk.neighborLightChecks == null)
            return;

        final int flagIndex = getHorizontalFlagIndex(dir, EnumBoundaryFacing.IN); // OUT checks from neighbor are already merged

        final int flags = chunk.neighborLightChecks[flagIndex];

        if (flags == 0)
            return;

        chunk.neighborLightChecks[flagIndex] = 0;

        final int xOffset = dir.getFrontOffsetX();
        final int zOffset = dir.getFrontOffsetZ();

        // Get the area to check
        // Start in the corner...
        int xMin = chunk.x << 4;
        int zMin = chunk.z << 4;

        //move to other side of chunk if the direction is positive
        if ((xOffset | zOffset) > 0)
        {
            xMin += 15 * xOffset;
            zMin += 15 * zOffset;
        }

        // Shift perpendicular to dir
        final int xShift = zOffset & 1;
        final int zShift = xOffset & 1;

        xMin += xShift;
        zMin += zShift;

        final int xMax = xMin + 13 * xShift;
        final int zMax = zMin + 13 * zShift;

        for (final EnumSkyBlock lightType : LightUtils.ENUM_SKY_BLOCK_VALUES)
        {
            final int shift = LightUtils.getIndex(lightType) << 4;
            final int sectionMask = (flags >> shift) & ((1 << 16) - 1);

            for (int y = 0; y < 16; ++y)
            {
                if ((sectionMask & (1 << y)) != 0)
                    LightUtils.scheduleRelightChecksForArea(world, lightType, xMin, y << 4, zMin, xMax, (y << 4) + 15, zMax, pos);
            }
        }
    }

    static void flagVerticalSecBoundaryForCheckClient(final Chunk chunk, final EnumFacing dir, final int sectionMask)
    {
        flagSecBoundaryForCheckClient(chunk, getVerticalFlagIndex(dir, EnumBoundaryFacing.IN), sectionMask);
    }

    static void flagHorizontalSecBoundaryForCheckClient(final Chunk chunk, final EnumFacing dir, final int region, final int sectionMask)
    {
        flagSecBoundaryForCheckClient(chunk, getHorizontalFlagIndex(dir, EnumBoundaryFacing.IN, region), sectionMask);
    }

    static void flagSecBoundaryForCheckClient(final Chunk chunk, final int index, final int sectionMask)
    {
        initNeighborLightChecks(chunk);
        chunk.neighborLightChecks[index] |= sectionMask;
        chunk.pendingBoundaryChecks = true;
    }

    public static void initNeighborLightChecks(final Chunk chunk)
    {
        if (chunk.neighborLightChecks == null)
            chunk.neighborLightChecks = new int[chunk.getWorld().isRemote ? FLAG_COUNT_CLIENT : FLAG_COUNT_SERVER];
    }

    static void writeNeighborLightChecksToNBT(final Chunk chunk, final NBTTagCompound nbt)
    {
        if (chunk.neighborLightChecks == null)
            return;

        boolean empty = true;
        final NBTTagList list = new NBTTagList();

        for (final int flags : chunk.neighborLightChecks)
        {
            list.appendTag(new NBTTagInt(flags));

            if (flags != 0)
                empty = false;
        }

        if (empty)
            chunk.neighborLightChecks = null;
        else
            nbt.setTag(neighborLightChecksKey, list);
    }

    static void readNeighborLightChecksFromNBT(final Chunk chunk, final NBTTagCompound nbt)
    {
        if (nbt.hasKey(neighborLightChecksKey, 9))
        {
            final NBTTagList list = nbt.getTagList(neighborLightChecksKey, 3);

            if (list.tagCount() == FLAG_COUNT_SERVER)
            {
                initNeighborLightChecks(chunk);

                for (int i = 0; i < FLAG_COUNT_SERVER; ++i)
                    chunk.neighborLightChecks[i] = ((NBTTagInt) list.get(i)).getInt();
            }
            else
                FMLLog.info("Boundary checks for chunk (%s, %s) are discarded. They are probably from an older version.", chunk.x, chunk.z);
        }
    }

    public static void onLoad(final World world, final Chunk chunk)
    {
        final IChunkProvider provider = world.getChunkProvider();

        for (int x = -1; x <= 1; ++x)
            for (int z = -1; z <= 1; ++z)
            {
                if (x == 0 && z == 0)
                    continue;

                final Chunk nChunk = provider.getLoadedChunk(chunk.x + x, chunk.z + z);

                if (nChunk != null)
                {
                    ++chunk.neighborsLoaded;
                    ++nChunk.neighborsLoaded;
                }
            }
    }

    public static void onUnload(final World world, final Chunk chunk)
    {
        if (!world.isRemote)
            return;

        final IChunkProvider provider = world.getChunkProvider();

        for (int x = -1; x <= 1; ++x)
            for (int z = -1; z <= 1; ++z)
            {
                if (x == 0 && z == 0)
                    continue;

                final Chunk nChunk = provider.getLoadedChunk(chunk.x + x, chunk.z + z);

                if (nChunk != null)
                    --nChunk.neighborsLoaded;
            }
    }

    public static void onTick(final World world, final Chunk chunk)
    {
        if (!world.isRemote)
            return;

        if (chunk.pendingBoundaryChecks && chunk.neighborsLoaded == 8)
        {
            scheduleRelightChecksForChunkBoundariesClient(world, chunk);
            chunk.pendingBoundaryChecks = false;
        }
    }

    public enum EnumBoundaryFacing
    {
        IN(2, 0, -1),
        OUT(3, OUT_INDEX_OFFSET, 0);

        final int indexMultiplier;
        final int offset;
        final int verticalOffset;

        EnumBoundaryFacing(final int indexMultiplier, final int offset, final int verticalOffset)
        {
            this.indexMultiplier = indexMultiplier;
            this.offset = offset;
            this.verticalOffset = verticalOffset;
        }
    }
}
