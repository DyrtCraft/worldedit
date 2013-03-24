// $Id$
/*
 * This file is a part of WorldEdit.
 * Copyright (c) sk89q <http://www.sk89q.com>
 * Copyright (c) the WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software 
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
*/

package org.enginehub.worldedit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Stack;

import org.enginehub.common.WorldObjectContainer;
import org.enginehub.worldedit.operation.FreezeArea;
import org.enginehub.worldedit.operation.NaturalizeArea;
import org.enginehub.worldedit.operation.OperationHelper;
import org.enginehub.worldedit.operation.OverlayBlocks;
import org.enginehub.worldedit.operation.ReplaceBlocks;
import org.enginehub.worldedit.operation.ScatterStructures;
import org.enginehub.worldedit.operation.StackArea;
import org.enginehub.worldedit.operation.ThawArea;
import org.enginehub.worldedit.patterns.Pattern;
import org.enginehub.worldedit.patterns.SingleBlockPattern;

import com.sk89q.worldedit.ArbitraryShape;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.BlockVector2D;
import com.sk89q.worldedit.Countable;
import com.sk89q.worldedit.DoubleArrayList;
import com.sk89q.worldedit.LocalWorld;
import com.sk89q.worldedit.PlayerDirection;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.Vector2D;
import com.sk89q.worldedit.bags.BlockBag;
import com.sk89q.worldedit.bags.BlockBagException;
import com.sk89q.worldedit.bags.UnplaceableBlockException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.blocks.BlockType;
import com.sk89q.worldedit.expression.Expression;
import com.sk89q.worldedit.expression.ExpressionException;
import com.sk89q.worldedit.expression.runtime.RValue;
import com.sk89q.worldedit.foundation.Block;
import com.sk89q.worldedit.masks.BlockMask;
import com.sk89q.worldedit.masks.Mask;
import com.sk89q.worldedit.masks.MatchAllMask;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.CylinderRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.structures.FruitPatch;
import com.sk89q.worldedit.structures.TreeGeneratorProxy;
import com.sk89q.worldedit.util.TreeGenerator;

/**
 * Acts as a proxy for changes to the world.
 * 
 * <p>Functions of this object include:</p>
 * 
 * <ul>
 *     <li>Keeping a log of all changes for later undo</li>
 *     <li>Queuing block changes so that attachable blocks do not break</li>
 *     <li>Applying changes in several passes if required (and possible)</li>
 * </ul>
 */
public class EditSession implements WorldObjectContainer {

    private static Random prng = new Random();
    protected LocalWorld world;

    /**
     * Stores the original blocks before modification.
     */
    private DoubleArrayList<BlockVector, BaseBlock> original =
            new DoubleArrayList<BlockVector, BaseBlock>(true);

    /**
     * Stores the current blocks.
     */
    private DoubleArrayList<BlockVector, BaseBlock> current =
            new DoubleArrayList<BlockVector, BaseBlock>(false);

    /**
     * Blocks that should be placed before last.
     */
    private DoubleArrayList<BlockVector, BaseBlock> queueAfter =
            new DoubleArrayList<BlockVector, BaseBlock>(false);

    /**
     * Blocks that should be placed last.
     */
    private DoubleArrayList<BlockVector, BaseBlock> queueLast =
            new DoubleArrayList<BlockVector, BaseBlock>(false);

    /**
     * Blocks that should be placed after all other blocks.
     */
    private DoubleArrayList<BlockVector, BaseBlock> queueFinal =
            new DoubleArrayList<BlockVector, BaseBlock>(false);

    /**
     * The maximum number of blocks to change at a time. If this number is
     * exceeded, a MaxChangedBlocksException exception will be raised. -1
     * indicates no limit.
     */
    private int maxBlocks = -1;

    /**
     * Indicates whether some types of blocks should be queued for best
     * reproduction.
     */
    private boolean queued = false;

    /**
     * Use the fast mode, which may leave chunks not flagged "dirty".
     */
    private boolean fastMode = false;

    /**
     * Block bag to use for getting blocks.
     */
    private BlockBag blockBag;

    /**
     * List of missing blocks;
     */
    private Map<Integer, Integer> missingBlocks = new HashMap<Integer, Integer>();

    /**
     * Mask to cover operations.
     */
    private Mask mask;

    /**
     * Construct the object with a maximum number of blocks.
     *
     * @param world
     * @param maxBlocks
     */
    public EditSession(LocalWorld world, int maxBlocks) {
        if (maxBlocks < -1) {
            throw new IllegalArgumentException("Max blocks must be >= -1");
        }

        this.maxBlocks = maxBlocks;
        this.world = world;
    }

    /**
     * Construct the object with a maximum number of blocks and a block bag.
     *
     * @param world
     * @param maxBlocks
     * @param blockBag
     * @blockBag
     */
    public EditSession(LocalWorld world, int maxBlocks, BlockBag blockBag) {
        if (maxBlocks < -1) {
            throw new IllegalArgumentException("Max blocks must be >= -1");
        }

        this.maxBlocks = maxBlocks;
        this.blockBag = blockBag;
        this.world = world;
    }

    /**
     * Sets a block without changing history.
     *
     * @param pt
     * @param block
     * @return Whether the block changed
     */
    public boolean rawSetBlock(Vector pt, BaseBlock block) {
        final int y = pt.getBlockY();
        final int type = block.getType();
        if (y < 0 || y > world.getMaxY()) {
            return false;
        }

        world.checkLoadedChunk(pt);

        // No invalid blocks
        if (!world.isValidBlockType(type)) {
            return false;
        }

        if (mask != null) {
            if (!mask.matches(this, pt)) {
                return false;
            }
        }

        final int existing = world.getBlockType(pt);

        // Clear the container block so that it doesn't drop items
        if (BlockType.isContainerBlock(existing)) {
            world.clearContainerBlockContents(pt);
            // Ice turns until water so this has to be done first
        } else if (existing == BlockID.ICE) {
            world.setBlockType(pt, BlockID.AIR);
        }

        if (blockBag != null) {
            if (type > 0) {
                try {
                    blockBag.fetchPlacedBlock(type, 0);
                } catch (UnplaceableBlockException e) {
                    return false;
                } catch (BlockBagException e) {
                    if (!missingBlocks.containsKey(type)) {
                        missingBlocks.put(type, 1);
                    } else {
                        missingBlocks.put(type, missingBlocks.get(type) + 1);
                    }
                    return false;
                }
            }

            if (existing > 0) {
                try {
                    blockBag.storeDroppedBlock(existing, world.getBlockData(pt));
                } catch (BlockBagException e) {
                }
            }
        }
        
        boolean result;
        
        if (type == 0) {
            if (fastMode) {
                result = world.setBlockTypeFast(pt, 0);
            } else {
                result = world.setBlockType(pt, 0);
            }
        } else {
            result = world.setBlock(pt, block, !fastMode);
        }
        
        return result;
    }

    /**
     * Sets the block at position x, y, z with a block type. If queue mode is
     * enabled, blocks may not be actually set in world until flushQueue() is
     * called.
     *
     * @param pt
     * @param block
     * @return Whether the block changed -- not entirely dependable
     * @throws MaxChangedBlocksException
     */
    public boolean setBlock(Vector pt, BaseBlock block)
            throws MaxChangedBlocksException {
        BlockVector blockPt = pt.toBlockVector();

        // if (!original.containsKey(blockPt)) {
        original.put(blockPt, getBlock(pt));

        if (maxBlocks != -1 && original.size() > maxBlocks) {
            throw new MaxChangedBlocksException(maxBlocks);
        }
        // }

        current.put(pt.toBlockVector(), block);

        return smartSetBlock(pt, block);
    }

    /**
     * Insert a contrived block change into the history.
     *
     * @param pt
     * @param existing
     * @param block
     */
    public void rememberChange(Vector pt, BaseBlock existing, BaseBlock block) {
        BlockVector blockPt = pt.toBlockVector();

        original.put(blockPt, existing);
        current.put(pt.toBlockVector(), block);
    }

    /**
     * Set a block with a pattern.
     *
     * @param pt
     * @param pat
     * @return Whether the block changed -- not entirely dependable
     * @throws MaxChangedBlocksException
     */
    public boolean setBlock(Vector pt, Pattern pat)
            throws MaxChangedBlocksException {
        return setBlock(pt, pat.next(pt));
    }

    @Override
    public boolean setBlock(Vector location, Block block, boolean notifyAdjacent) {
        return setBlock(location, (BaseBlock) block);
        
        // @TODO: Update
    }

    /**
     * Set a block only if there's no block already there.
     *
     * @param pt
     * @param block
     * @return if block was changed
     * @throws MaxChangedBlocksException
     */
    public boolean setBlockIfAir(Vector pt, BaseBlock block)
            throws MaxChangedBlocksException {
        if (!getBlock(pt).isAir()) {
            return false;
        } else {
            return setBlock(pt, block);
        }
    }

    /**
     * Actually set the block. Will use queue.
     *
     * @param pt
     * @param block
     * @return
     */
    public boolean smartSetBlock(Vector pt, BaseBlock block) {
        if (queued) {
            if (BlockType.shouldPlaceLast(block.getType())) {
                // Place torches, etc. last
                queueLast.put(pt.toBlockVector(), block);
                return !(getBlockType(pt) == block.getType() && getBlockData(pt) == block.getData());
            } else if (BlockType.shouldPlaceFinal(block.getType())) {
                // Place signs, reed, etc even later
                queueFinal.put(pt.toBlockVector(), block);
                return !(getBlockType(pt) == block.getType() && getBlockData(pt) == block.getData());
            } else if (BlockType.shouldPlaceLast(getBlockType(pt))) {
                // Destroy torches, etc. first
                rawSetBlock(pt, new BaseBlock(BlockID.AIR));
            } else {
                queueAfter.put(pt.toBlockVector(), block);
                return !(getBlockType(pt) == block.getType() && getBlockData(pt) == block.getData());
            }
        }

        return rawSetBlock(pt, block);
    }

    /**
     * Gets the block type at a position x, y, z.
     *
     * @param pt
     * @return Block type
     */
    public BaseBlock getBlock(Vector pt) {
        // In the case of the queue, the block may have not actually been
        // changed yet
        if (queued) {
            /*
             * BlockVector blockPt = pt.toBlockVector();
             *
             * if (current.containsKey(blockPt)) { return current.get(blockPt);
             * }
             */
        }

        return rawGetBlock(pt);
    }

    /**
     * Gets the block type at a position x, y, z.
     *
     * @param pt
     * @return Block type
     */
    public int getBlockType(Vector pt) {
        // In the case of the queue, the block may have not actually been
        // changed yet
        if (queued) {
            /*
             * BlockVector blockPt = pt.toBlockVector();
             *
             * if (current.containsKey(blockPt)) { return current.get(blockPt);
             * }
             */
        }

        return world.getBlockType(pt);
    }

    public int getBlockData(Vector pt) {
        // In the case of the queue, the block may have not actually been
        // changed yet
        if (queued) {
            /*
             * BlockVector blockPt = pt.toBlockVector();
             *
             * if (current.containsKey(blockPt)) { return current.get(blockPt);
             * }
             */
        }

        return world.getBlockData(pt);
    }

    /**
     * Gets the block type at a position x, y, z.
     *
     * @param pt
     * @return BaseBlock
     */
    public BaseBlock rawGetBlock(Vector pt) {
        return world.getBlock(pt);
    }

    /**
     * Restores all blocks to their initial state.
     *
     * @param sess
     */
    public void undo(EditSession sess) {
        for (Map.Entry<BlockVector, BaseBlock> entry : original) {
            BlockVector pt = entry.getKey();
            sess.smartSetBlock(pt, entry.getValue());
        }
        sess.flushQueue();
    }

    /**
     * Sets to new state.
     *
     * @param sess
     */
    public void redo(EditSession sess) {
        for (Map.Entry<BlockVector, BaseBlock> entry : current) {
            BlockVector pt = entry.getKey();
            sess.smartSetBlock(pt, entry.getValue());
        }
        sess.flushQueue();
    }

    /**
     * Get the number of changed blocks.
     *
     * @return
     */
    public int size() {
        return original.size();
    }

    /**
     * Get the maximum number of blocks that can be changed. -1 will be returned
     * if disabled.
     *
     * @return block change limit
     */
    public int getBlockChangeLimit() {
        return maxBlocks;
    }

    /**
     * Set the maximum number of blocks that can be changed.
     *
     * @param maxBlocks -1 to disable
     */
    public void setBlockChangeLimit(int maxBlocks) {
        if (maxBlocks < -1) {
            throw new IllegalArgumentException("Max blocks must be >= -1");
        }
        this.maxBlocks = maxBlocks;
    }

    /**
     * Returns queue status.
     *
     * @return whether the queue is enabled
     */
    public boolean isQueueEnabled() {
        return queued;
    }

    /**
     * Queue certain types of block for better reproduction of those blocks.
     */
    public void enableQueue() {
        queued = true;
    }

    /**
     * Disable the queue. This will flush the queue.
     */
    public void disableQueue() {
        if (queued) {
            flushQueue();
        }
        queued = false;
    }

    /**
     * Set fast mode.
     *
     * @param fastMode
     */
    public void setFastMode(boolean fastMode) {
        this.fastMode = fastMode;
    }

    /**
     * Return fast mode status.
     *
     * @return
     */
    public boolean hasFastMode() {
        return fastMode;
    }

    /**
     * Set a block by chance.
     *
     * @param pos
     * @param block
     * @param c 0-1 chance
     * @return whether a block was changed
     * @throws MaxChangedBlocksException
     */
    public boolean setChanceBlockIfAir(Vector pos, BaseBlock block, double c)
            throws MaxChangedBlocksException {
        if (Math.random() <= c) {
            return setBlockIfAir(pos, block);
        }
        return false;
    }

    public int countBlock(Region region, Set<Integer> searchIDs) {
        Set<BaseBlock> passOn = new HashSet<BaseBlock>();
        for (Integer i : searchIDs) {
            passOn.add(new BaseBlock(i, -1));
        }
        return countBlocks(region, passOn);
    }

    /**
     * Count the number of blocks of a list of types in a region.
     *
     * @param region
     * @param searchBlocks
     * @return
     */
    public int countBlocks(Region region, Set<BaseBlock> searchBlocks) {
        int count = 0;

        // allow -1 data in the searchBlocks to match any type
        Set<BaseBlock> newSet = new HashSet<BaseBlock>() {
            @Override
            public boolean contains(Object o) {
                for (BaseBlock b : this.toArray(new BaseBlock[this.size()])) {
                    if (o instanceof BaseBlock) {
                        if (b.equalsFuzzy((BaseBlock) o)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };
        newSet.addAll(searchBlocks);

        if (region instanceof CuboidRegion) {
            // Doing this for speed
            Vector min = region.getMinimumPoint();
            Vector max = region.getMaximumPoint();

            int minX = min.getBlockX();
            int minY = min.getBlockY();
            int minZ = min.getBlockZ();
            int maxX = max.getBlockX();
            int maxY = max.getBlockY();
            int maxZ = max.getBlockZ();

            for (int x = minX; x <= maxX; ++x) {
                for (int y = minY; y <= maxY; ++y) {
                    for (int z = minZ; z <= maxZ; ++z) {
                        Vector pt = new Vector(x, y, z);

                        BaseBlock compare = new BaseBlock(getBlockType(pt), getBlockData(pt));
                        if (newSet.contains(compare)) {
                            ++count;
                        }
                    }
                }
            }
        } else {
            for (Vector pt : region) {
                BaseBlock compare = new BaseBlock(getBlockType(pt), getBlockData(pt));
                if (newSet.contains(compare)) {
                    ++count;
                }
            }
        }

        return count;
    }

    /**
     * Returns the highest solid 'terrain' block which can occur naturally.
     *
     * @param x
     * @param z
     * @param minY minimal height
     * @param maxY maximal height
     * @param naturalOnly look at natural blocks or all blocks
     * @return height of highest block found or 'minY'
     */
    public int getHighestTerrainBlock(int x, int z, int minY, int maxY) {
        return getHighestTerrainBlock(x, z, minY, maxY, false);
    }

    /**
     * Returns the highest solid 'terrain' block which can occur naturally.
     *
     * @param x
     * @param z
     * @param minY minimal height
     * @param maxY maximal height
     * @param naturalOnly look at natural blocks or all blocks
     * @return height of highest block found or 'minY'
     */
    public int getHighestTerrainBlock(int x, int z, int minY, int maxY, boolean naturalOnly) {
        for (int y = maxY; y >= minY; --y) {
            Vector pt = new Vector(x, y, z);
            int id = getBlockType(pt);
            if (naturalOnly ? BlockType.isNaturalTerrainBlock(id) : !BlockType.canPassThrough(id)) {
                return y;
            }
        }
        return minY;
    }

    /**
     * Gets the list of missing blocks and clears the list for the next
     * operation.
     *
     * @return
     */
    public Map<Integer, Integer> popMissingBlocks() {
        Map<Integer, Integer> missingBlocks = this.missingBlocks;
        this.missingBlocks = new HashMap<Integer, Integer>();
        return missingBlocks;
    }

    /**
     * @return the blockBag
     */
    public BlockBag getBlockBag() {
        return blockBag;
    }

    /**
     * @param blockBag the blockBag to set
     */
    public void setBlockBag(BlockBag blockBag) {
        this.blockBag = blockBag;
    }

    /**
     * Get the world.
     *
     * @return
     */
    public LocalWorld getWorld() {
        return world;
    }

    /**
     * Get the number of blocks changed, including repeated block changes.
     *
     * @return
     */
    public int getBlockChangeCount() {
        return original.size();
    }

    /**
     * Get the mask.
     *
     * @return mask, may be null
     */
    public Mask getMask() {
        return mask;
    }

    /**
     * Set a mask.
     *
     * @param mask mask or null
     */
    public void setMask(Mask mask) {
        this.mask = mask;
    }

    /**
     * Finish off the queue.
     */
    public void flushQueue() {
        if (!queued) {
            return;
        }

        final Set<BlockVector2D> dirtyChunks = new HashSet<BlockVector2D>();

        for (Map.Entry<BlockVector, BaseBlock> entry : queueAfter) {
            BlockVector pt = entry.getKey();
            rawSetBlock(pt, entry.getValue());

            // TODO: use ChunkStore.toChunk(pt) after optimizing it.
            if (fastMode) {
                dirtyChunks.add(new BlockVector2D(pt.getBlockX() >> 4, pt.getBlockZ() >> 4));
            }
        }

        // We don't want to place these blocks if other blocks were missing
        // because it might cause the items to drop
        if (blockBag == null || missingBlocks.size() == 0) {
            for (Map.Entry<BlockVector, BaseBlock> entry : queueLast) {
                BlockVector pt = entry.getKey();
                rawSetBlock(pt, entry.getValue());

                // TODO: use ChunkStore.toChunk(pt) after optimizing it.
                if (fastMode) {
                    dirtyChunks.add(new BlockVector2D(pt.getBlockX() >> 4, pt.getBlockZ() >> 4));
                }
            }

            final Set<BlockVector> blocks = new HashSet<BlockVector>();
            final Map<BlockVector, BaseBlock> blockTypes = new HashMap<BlockVector, BaseBlock>();
            for (Map.Entry<BlockVector, BaseBlock> entry : queueFinal) {
                final BlockVector pt = entry.getKey();
                blocks.add(pt);
                blockTypes.put(pt, entry.getValue());
            }

            while (!blocks.isEmpty()) {
                BlockVector current = blocks.iterator().next();
                if (!blocks.contains(current)) {
                    continue;
                }

                final Deque<BlockVector> walked = new LinkedList<BlockVector>();

                while (true) {
                    walked.addFirst(current);

                    assert(blockTypes.containsKey(current));

                    final BaseBlock baseBlock = blockTypes.get(current);

                    final int type = baseBlock.getType();
                    final int data = baseBlock.getData();

                    switch (type) {
                    case BlockID.WOODEN_DOOR:
                    case BlockID.IRON_DOOR:
                        if ((data & 0x8) == 0) {
                            // Deal with lower door halves being attached to the floor AND the upper half
                            BlockVector upperBlock = current.add(0, 1, 0).toBlockVector();
                            if (blocks.contains(upperBlock) && !walked.contains(upperBlock)) {
                                walked.addFirst(upperBlock);
                            }
                        }
                    }

                    final PlayerDirection attachment = BlockType.getAttachment(type, data);
                    if (attachment == null) {
                        // Block is not attached to anything => we can place it
                        break;
                    }

                    current = current.add(attachment.vector()).toBlockVector();

                    if (!blocks.contains(current)) {
                        // We ran outside the remaing set => assume we can place blocks on this
                        break;
                    }

                    if (walked.contains(current)) {
                        // Cycle detected => This will most likely go wrong, but there's nothing we can do about it.
                        break;
                    }
                }

                for (BlockVector pt : walked) {
                    rawSetBlock(pt, blockTypes.get(pt));
                    blocks.remove(pt);

                    // TODO: use ChunkStore.toChunk(pt) after optimizing it.
                    if (fastMode) {
                        dirtyChunks.add(new BlockVector2D(pt.getBlockX() >> 4, pt.getBlockZ() >> 4));
                    }
                }
            }
        }

        if (!dirtyChunks.isEmpty()) world.fixAfterFastMode(dirtyChunks);

        queueAfter.clear();
        queueLast.clear();
        queueFinal.clear();
    }

    /**
     * Fills an area recursively in the X/Z directions.
     *
     * @param origin
     * @param block
     * @param radius
     * @param depth
     * @param recursive
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int fillXZ(Vector origin, BaseBlock block, double radius, int depth,
            boolean recursive) throws MaxChangedBlocksException {

        int affected = 0;
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();

        HashSet<BlockVector> visited = new HashSet<BlockVector>();
        Stack<BlockVector> queue = new Stack<BlockVector>();

        queue.push(new BlockVector(originX, originY, originZ));

        while (!queue.empty()) {
            BlockVector pt = queue.pop();
            int cx = pt.getBlockX();
            int cy = pt.getBlockY();
            int cz = pt.getBlockZ();

            if (cy < 0 || cy > originY || visited.contains(pt)) {
                continue;
            }

            visited.add(pt);

            if (recursive) {
                if (origin.distance(pt) > radius) {
                    continue;
                }

                if (getBlock(pt).isAir()) {
                    if (setBlock(pt, block)) {
                        ++affected;
                    }
                } else {
                    continue;
                }

                queue.push(new BlockVector(cx, cy - 1, cz));
                queue.push(new BlockVector(cx, cy + 1, cz));
            } else {
                double dist = Math.sqrt(Math.pow(originX - cx, 2)
                        + Math.pow(originZ - cz, 2));
                int minY = originY - depth + 1;

                if (dist > radius) {
                    continue;
                }

                if (getBlock(pt).isAir()) {
                    affected += fillY(cx, originY, cz, block, minY);
                } else {
                    continue;
                }
            }

            queue.push(new BlockVector(cx + 1, cy, cz));
            queue.push(new BlockVector(cx - 1, cy, cz));
            queue.push(new BlockVector(cx, cy, cz + 1));
            queue.push(new BlockVector(cx, cy, cz - 1));
        }

        return affected;
    }

    /**
     * Recursively fills a block and below until it hits another block.
     *
     * @param x
     * @param cy
     * @param z
     * @param block
     * @param minY
     * @throws MaxChangedBlocksException
     * @return
     */
    private int fillY(int x, int cy, int z, BaseBlock block, int minY)
            throws MaxChangedBlocksException {
        int affected = 0;

        for (int y = cy; y >= minY; --y) {
            Vector pt = new Vector(x, y, z);

            if (getBlock(pt).isAir()) {
                setBlock(pt, block);
                ++affected;
            } else {
                break;
            }
        }

        return affected;
    }

    /**
     * Fills an area recursively in the X/Z directions.
     *
     * @param origin
     * @param pattern
     * @param radius
     * @param depth
     * @param recursive
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int fillXZ(Vector origin, Pattern pattern, double radius, int depth,
            boolean recursive) throws MaxChangedBlocksException {

        int affected = 0;
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();

        HashSet<BlockVector> visited = new HashSet<BlockVector>();
        Stack<BlockVector> queue = new Stack<BlockVector>();

        queue.push(new BlockVector(originX, originY, originZ));

        while (!queue.empty()) {
            BlockVector pt = queue.pop();
            int cx = pt.getBlockX();
            int cy = pt.getBlockY();
            int cz = pt.getBlockZ();

            if (cy < 0 || cy > originY || visited.contains(pt)) {
                continue;
            }

            visited.add(pt);

            if (recursive) {
                if (origin.distance(pt) > radius) {
                    continue;
                }

                if (getBlock(pt).isAir()) {
                    if (setBlock(pt, pattern.next(pt))) {
                        ++affected;
                    }
                } else {
                    continue;
                }

                queue.push(new BlockVector(cx, cy - 1, cz));
                queue.push(new BlockVector(cx, cy + 1, cz));
            } else {
                double dist = Math.sqrt(Math.pow(originX - cx, 2)
                        + Math.pow(originZ - cz, 2));
                int minY = originY - depth + 1;

                if (dist > radius) {
                    continue;
                }

                if (getBlock(pt).isAir()) {
                    affected += fillY(cx, originY, cz, pattern, minY);
                } else {
                    continue;
                }
            }

            queue.push(new BlockVector(cx + 1, cy, cz));
            queue.push(new BlockVector(cx - 1, cy, cz));
            queue.push(new BlockVector(cx, cy, cz + 1));
            queue.push(new BlockVector(cx, cy, cz - 1));
        }

        return affected;
    }

    /**
     * Recursively fills a block and below until it hits another block.
     *
     * @param x
     * @param cy
     * @param z
     * @param pattern
     * @param minY
     * @throws MaxChangedBlocksException
     * @return
     */
    private int fillY(int x, int cy, int z, Pattern pattern, int minY)
            throws MaxChangedBlocksException {
        int affected = 0;

        for (int y = cy; y >= minY; --y) {
            Vector pt = new Vector(x, y, z);

            if (getBlock(pt).isAir()) {
                setBlock(pt, pattern.next(pt));
                ++affected;
            } else {
                break;
            }
        }

        return affected;
    }

    /**
     * Remove blocks above the position.
     *
     * @param pos position to start at
     * @param size radius of the square area
     * @param height height to remove
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks were changed
     */
    public int removeAbove(Vector pos, int size, int height) throws MaxChangedBlocksException {
        int maxY = Math.min(world.getMaxY(), pos.getBlockY() + height - 1);
        --size; // Legacy
        Region region = new CuboidRegion(pos.add(-size, 0, -size), pos.add(size, 0, size).setY(maxY));
        Pattern pattern = new SingleBlockPattern(new BaseBlock(0));
        ReplaceBlocks op = new ReplaceBlocks(this, region, pattern);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Remove blocks below the position.
     *
     * @param pos position to start at
     * @param size radius of the square area
     * @param height height to remove
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks were changed
     */
    public int removeBelow(Vector pos, int size, int height) throws MaxChangedBlocksException {
        int minY = Math.max(0, pos.getBlockY() - height);
        --size; // Legacy
        Region region = new CuboidRegion(pos.add(-size, 0, -size).setY(minY), pos.add(size, 0, size));
        Pattern pattern = new SingleBlockPattern(new BaseBlock(0));
        ReplaceBlocks op = new ReplaceBlocks(this, region, pattern);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Remove nearby blocks of a type.
     *
     * @param pos
     * @param blockType
     * @param size
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int removeNear(Vector pos, int blockType, int size)
            throws MaxChangedBlocksException {
        int affected = 0;
        BaseBlock air = new BaseBlock(BlockID.AIR);

        int minX = pos.getBlockX() - size;
        int maxX = pos.getBlockX() + size;
        int minY = Math.max(0, pos.getBlockY() - size);
        int maxY = Math.min(world.getMaxY(), pos.getBlockY() + size);
        int minZ = pos.getBlockZ() - size;
        int maxZ = pos.getBlockZ() + size;

        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                for (int z = minZ; z <= maxZ; ++z) {
                    Vector p = new Vector(x, y, z);

                    if (getBlockType(p) == blockType) {
                        if (setBlock(p, air)) {
                            ++affected;
                        }
                    }
                }
            }
        }

        return affected;
    }

    /**
     * Sets all the blocks inside a region to a certain block type.
     *
     * @param region region to apply
     * @param block block to change to
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks were changed
     */
    public int setBlocks(Region region, BaseBlock block) throws MaxChangedBlocksException {
        return setBlocks(region, new SingleBlockPattern(block));
    }

    /**
     * Sets all the blocks inside a region to a certain block type.
     *
     * @param region region to apply
     * @param pattern pattern of blocks to set
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks were changed
     */
    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        ReplaceBlocks op = new ReplaceBlocks(this, region, pattern);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Replaces all the blocks of a type inside a region to another block type.
     * 
     * @param region region to apply to
     * @param fromBlockTypes -1 for non-air
     * @param toBlock block to change to
     * @return number of blocks affected
     * @throws MaxChangedBlocksException too many blocks modified
     */
    public int replaceBlocks(Region region, Set<BaseBlock> fromBlockTypes,
            BaseBlock toBlock) throws MaxChangedBlocksException {
        
        Mask mask = fromBlockTypes != null ? new BlockMask(fromBlockTypes) : new MatchAllMask();
        Pattern pattern = new SingleBlockPattern(toBlock);
        ReplaceBlocks op = new ReplaceBlocks(this, region, mask, pattern);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Replaces all the blocks of a type inside a region to another block type.
     * 
     * @param region region to apply to
     * @param fromBlockTypes -1 for non-air
     * @param pattern pattern to apply
     * @return number of blocks affected
     * @throws MaxChangedBlocksException too many blocks modified
     */
    public int replaceBlocks(Region region, Set<BaseBlock> fromBlockTypes,
            Pattern pattern) throws MaxChangedBlocksException {
        
        Mask mask = fromBlockTypes != null ? new BlockMask(fromBlockTypes) : new MatchAllMask();
        ReplaceBlocks op = new ReplaceBlocks(this, region, mask, pattern);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    public int center(Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        Vector center = region.getCenter();
        int x2 = center.getBlockX();
        int y2 = center.getBlockY();
        int z2 = center.getBlockZ();

        int affected = 0;
        for (int x = (int) center.getX(); x <= x2; x++) {
            for (int y = (int) center.getY(); y <= y2; y++) {
                for (int z = (int) center.getZ(); z <= z2; z++) {
                    if (setBlock(new Vector(x, y, z), pattern)) {
                        affected++;
                    }
                }
            }
        }

        return affected;
    }

    /**
     * Make faces of the region (as if it was a cuboid if it's not).
     *
     * @param region
     * @param block
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int makeCuboidFaces(Region region, BaseBlock block)
            throws MaxChangedBlocksException {
        int affected = 0;

        Vector min = region.getMinimumPoint();
        Vector max = region.getMaximumPoint();

        int minX = min.getBlockX();
        int minY = min.getBlockY();
        int minZ = min.getBlockZ();
        int maxX = max.getBlockX();
        int maxY = max.getBlockY();
        int maxZ = max.getBlockZ();

        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                if (setBlock(new Vector(x, y, minZ), block)) {
                    ++affected;
                }
                if (setBlock(new Vector(x, y, maxZ), block)) {
                    ++affected;
                }
                ++affected;
            }
        }

        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                if (setBlock(new Vector(minX, y, z), block)) {
                    ++affected;
                }
                if (setBlock(new Vector(maxX, y, z), block)) {
                    ++affected;
                }
            }
        }

        for (int z = minZ; z <= maxZ; ++z) {
            for (int x = minX; x <= maxX; ++x) {
                if (setBlock(new Vector(x, minY, z), block)) {
                    ++affected;
                }
                if (setBlock(new Vector(x, maxY, z), block)) {
                    ++affected;
                }
            }
        }

        return affected;
    }

    /**
     * Make faces of the region (as if it was a cuboid if it's not).
     *
     * @param region
     * @param pattern
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int makeCuboidFaces(Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        int affected = 0;

        Vector min = region.getMinimumPoint();
        Vector max = region.getMaximumPoint();

        int minX = min.getBlockX();
        int minY = min.getBlockY();
        int minZ = min.getBlockZ();
        int maxX = max.getBlockX();
        int maxY = max.getBlockY();
        int maxZ = max.getBlockZ();

        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                Vector minV = new Vector(x, y, minZ);
                if (setBlock(minV, pattern.next(minV))) {
                    ++affected;
                }
                Vector maxV = new Vector(x, y, maxZ);
                if (setBlock(maxV, pattern.next(maxV))) {
                    ++affected;
                }
                ++affected;
            }
        }

        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                Vector minV = new Vector(minX, y, z);
                if (setBlock(minV, pattern.next(minV))) {
                    ++affected;
                }
                Vector maxV = new Vector(maxX, y, z);
                if (setBlock(maxV, pattern.next(maxV))) {
                    ++affected;
                }
            }
        }

        for (int z = minZ; z <= maxZ; ++z) {
            for (int x = minX; x <= maxX; ++x) {
                Vector minV = new Vector(x, minY, z);
                if (setBlock(minV, pattern.next(minV))) {
                    ++affected;
                }
                Vector maxV = new Vector(x, maxY, z);
                if (setBlock(maxV, pattern.next(maxV))) {
                    ++affected;
                }
            }
        }

        return affected;
    }

    /**
     * Make walls of the region (as if it was a cuboid if it's not).
     *
     * @param region
     * @param block
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int makeCuboidWalls(Region region, BaseBlock block)
            throws MaxChangedBlocksException {
        int affected = 0;

        Vector min = region.getMinimumPoint();
        Vector max = region.getMaximumPoint();

        int minX = min.getBlockX();
        int minY = min.getBlockY();
        int minZ = min.getBlockZ();
        int maxX = max.getBlockX();
        int maxY = max.getBlockY();
        int maxZ = max.getBlockZ();

        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                if (setBlock(new Vector(x, y, minZ), block)) {
                    ++affected;
                }
                if (setBlock(new Vector(x, y, maxZ), block)) {
                    ++affected;
                }
                ++affected;
            }
        }

        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                if (setBlock(new Vector(minX, y, z), block)) {
                    ++affected;
                }
                if (setBlock(new Vector(maxX, y, z), block)) {
                    ++affected;
                }
            }
        }

        return affected;
    }

    /**
     * Make walls of the region (as if it was a cuboid if it's not).
     *
     * @param region
     * @param block
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int makeCuboidWalls(Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        int affected = 0;

        Vector min = region.getMinimumPoint();
        Vector max = region.getMaximumPoint();

        int minX = min.getBlockX();
        int minY = min.getBlockY();
        int minZ = min.getBlockZ();
        int maxX = max.getBlockX();
        int maxY = max.getBlockY();
        int maxZ = max.getBlockZ();

        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                Vector minV = new Vector(x, y, minZ);
                if (setBlock(minV, pattern.next(minV))) {
                    ++affected;
                }
                Vector maxV = new Vector(x, y, maxZ);
                if (setBlock(maxV, pattern.next(maxV))) {
                    ++affected;
                }
                ++affected;
            }
        }

        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                Vector minV = new Vector(minX, y, z);
                if (setBlock(minV, pattern.next(minV))) {
                    ++affected;
                }
                Vector maxV = new Vector(maxX, y, z);
                if (setBlock(maxV, pattern.next(maxV))) {
                    ++affected;
                }
            }
        }

        return affected;
    }

    /**
     * Overlays a layer of blocks over a cuboid area.
     *
     * @param region
     * @param block
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int overlayCuboidBlocks(Region region, BaseBlock block)
            throws MaxChangedBlocksException {
        return overlayCuboidBlocks(region, new SingleBlockPattern(block));
    }

    /**
     * Overlays a layer of blocks over a cuboid area.
     *
     * @param region
     * @param pattern
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int overlayCuboidBlocks(Region region, Pattern pattern)
            throws MaxChangedBlocksException {
        OverlayBlocks op = new OverlayBlocks(this, region, pattern);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }


    /**
     * Turns the first 3 layers into dirt/grass and the bottom layers
     * into rock, like a natural Minecraft mountain.
     *
     * @param region regions to affect
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    public int naturalizeCuboidBlocks(Region region) throws MaxChangedBlocksException {
        NaturalizeArea op = new NaturalizeArea(this, region);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Stack a cuboid region.
     *
     * @param region region to apply it to
     * @param dir direction to stack the region
     * @param count the number of times to stack
     * @param copyAir true to copy air blocks
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    public int stackCuboidRegion(Region region, Vector dir, int count,
            boolean copyAir) throws MaxChangedBlocksException {
        StackArea op = new StackArea(this, region);
        op.setCopyAir(copyAir);
        op.setDirection(dir);
        op.setCount(count);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Move a cuboid region.
     *
     * @param region
     * @param dir
     * @param distance
     * @param copyAir
     * @param replace
     * @return number of blocks moved
     * @throws MaxChangedBlocksException
     */
    public int moveCuboidRegion(Region region, Vector dir, int distance,
            boolean copyAir, BaseBlock replace)
            throws MaxChangedBlocksException {
        int affected = 0;

        Vector shift = dir.multiply(distance);
        Vector min = region.getMinimumPoint();
        Vector max = region.getMaximumPoint();

        int minX = min.getBlockX();
        int minY = min.getBlockY();
        int minZ = min.getBlockZ();
        int maxX = max.getBlockX();
        int maxY = max.getBlockY();
        int maxZ = max.getBlockZ();

        Vector newMin = min.add(shift);
        Vector newMax = min.add(shift);

        Map<Vector, BaseBlock> delayed = new LinkedHashMap<Vector, BaseBlock>();

        for (int x = minX; x <= maxX; ++x) {
            for (int z = minZ; z <= maxZ; ++z) {
                for (int y = minY; y <= maxY; ++y) {
                    Vector pos = new Vector(x, y, z);
                    BaseBlock block = getBlock(pos);

                    if (!block.isAir() || copyAir) {
                        Vector newPos = pos.add(shift);

                        delayed.put(newPos, getBlock(pos));

                        // Don't want to replace the old block if it's in
                        // the new area
                        if (x >= newMin.getBlockX() && x <= newMax.getBlockX()
                                && y >= newMin.getBlockY()
                                && y <= newMax.getBlockY()
                                && z >= newMin.getBlockZ()
                                && z <= newMax.getBlockZ()) {
                        } else {
                            setBlock(pos, replace);
                        }
                    }
                }
            }
        }

        for (Map.Entry<Vector, BaseBlock> entry : delayed.entrySet()) {
            setBlock(entry.getKey(), entry.getValue());
            ++affected;
        }

        return affected;
    }

    /**
     * Drain nearby pools of water or lava.
     *
     * @param pos
     * @param radius
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int drainArea(Vector pos, double radius)
            throws MaxChangedBlocksException {
        int affected = 0;

        HashSet<BlockVector> visited = new HashSet<BlockVector>();
        Stack<BlockVector> queue = new Stack<BlockVector>();

        for (int x = pos.getBlockX() - 1; x <= pos.getBlockX() + 1; ++x) {
            for (int z = pos.getBlockZ() - 1; z <= pos.getBlockZ() + 1; ++z) {
                for (int y = pos.getBlockY() - 1; y <= pos.getBlockY() + 1; ++y) {
                    queue.push(new BlockVector(x, y, z));
                }
            }
        }

        while (!queue.empty()) {
            BlockVector cur = queue.pop();

            int type = getBlockType(cur);

            // Check block type
            if (type != BlockID.WATER && type != BlockID.STATIONARY_WATER
                    && type != BlockID.LAVA && type != BlockID.STATIONARY_LAVA) {
                continue;
            }

            // Don't want to revisit
            if (visited.contains(cur)) {
                continue;
            }

            visited.add(cur);

            // Check radius
            if (pos.distance(cur) > radius) {
                continue;
            }

            for (int x = cur.getBlockX() - 1; x <= cur.getBlockX() + 1; ++x) {
                for (int z = cur.getBlockZ() - 1; z <= cur.getBlockZ() + 1; ++z) {
                    for (int y = cur.getBlockY() - 1; y <= cur.getBlockY() + 1; ++y) {
                        BlockVector newPos = new BlockVector(x, y, z);

                        if (!cur.equals(newPos)) {
                            queue.push(newPos);
                        }
                    }
                }
            }

            if (setBlock(cur, new BaseBlock(BlockID.AIR))) {
                ++affected;
            }
        }

        return affected;
    }

    /**
     * Level water.
     *
     * @param pos
     * @param radius
     * @param moving
     * @param stationary
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int fixLiquid(Vector pos, double radius, int moving, int stationary)
            throws MaxChangedBlocksException {
        int affected = 0;

        HashSet<BlockVector> visited = new HashSet<BlockVector>();
        Stack<BlockVector> queue = new Stack<BlockVector>();

        for (int x = pos.getBlockX() - 1; x <= pos.getBlockX() + 1; ++x) {
            for (int z = pos.getBlockZ() - 1; z <= pos.getBlockZ() + 1; ++z) {
                for (int y = pos.getBlockY() - 1; y <= pos.getBlockY() + 1; ++y) {
                    int type = getBlock(new Vector(x, y, z)).getType();

                    // Check block type
                    if (type == moving || type == stationary) {
                        queue.push(new BlockVector(x, y, z));
                    }
                }
            }
        }

        BaseBlock stationaryBlock = new BaseBlock(stationary);

        while (!queue.empty()) {
            BlockVector cur = queue.pop();

            int type = getBlockType(cur);

            // Check block type
            if (type != moving && type != stationary && type != BlockID.AIR) {
                continue;
            }

            // Don't want to revisit
            if (visited.contains(cur)) {
                continue;
            }

            visited.add(cur);

            if (setBlock(cur, stationaryBlock)) {
                ++affected;
            }

            // Check radius
            if (pos.distance(cur) > radius) {
                continue;
            }

            queue.push(cur.add(1, 0, 0).toBlockVector());
            queue.push(cur.add(-1, 0, 0).toBlockVector());
            queue.push(cur.add(0, 0, 1).toBlockVector());
            queue.push(cur.add(0, 0, -1).toBlockVector());
        }

        return affected;
    }

    /**
     * Makes a cylinder.
     *
     * @param pos Center of the cylinder
     * @param block The block pattern to use
     * @param radius The cylinder's radius
     * @param height The cylinder's up/down extent. If negative, extend downward.
     * @param filled If false, only a shell will be generated.
     * @return number of blocks changed
     * @throws MaxChangedBlocksException
     */
    public int makeCylinder(Vector pos, Pattern block, double radius, int height, boolean filled) throws MaxChangedBlocksException {
        return makeCylinder(pos, block, radius, radius, height, filled);
    }

    /**
     * Makes a cylinder.
     *
     * @param pos Center of the cylinder
     * @param block The block pattern to use
     * @param radiusX The cylinder's largest north/south extent
     * @param radiusZ The cylinder's largest east/west extent
     * @param height The cylinder's up/down extent. If negative, extend downward.
     * @param filled If false, only a shell will be generated.
     * @return number of blocks changed
     * @throws MaxChangedBlocksException
     */
    public int makeCylinder(Vector pos, Pattern block, double radiusX, double radiusZ, int height, boolean filled) throws MaxChangedBlocksException {
        int affected = 0;

        radiusX += 0.5;
        radiusZ += 0.5;

        if (height == 0) {
            return 0;
        } else if (height < 0) {
            height = -height;
            pos = pos.subtract(0, height, 0);
        }

        if (pos.getBlockY() < 0) {
            pos = pos.setY(0);
        } else if (pos.getBlockY() + height - 1 > world.getMaxY()) {
            height = world.getMaxY() - pos.getBlockY() + 1;
        }

        final double invRadiusX = 1 / radiusX;
        final double invRadiusZ = 1 / radiusZ;

        final int ceilRadiusX = (int) Math.ceil(radiusX);
        final int ceilRadiusZ = (int) Math.ceil(radiusZ);

        double nextXn = 0;
        forX: for (int x = 0; x <= ceilRadiusX; ++x) {
            final double xn = nextXn;
            nextXn = (x + 1) * invRadiusX;
            double nextZn = 0;
            forZ: for (int z = 0; z <= ceilRadiusZ; ++z) {
                final double zn = nextZn;
                nextZn = (z + 1) * invRadiusZ;

                double distanceSq = lengthSq(xn, zn);
                if (distanceSq > 1) {
                    if (z == 0) {
                        break forX;
                    }
                    break forZ;
                }

                if (!filled) {
                    if (lengthSq(nextXn, zn) <= 1 && lengthSq(xn, nextZn) <= 1) {
                        continue;
                    }
                }

                for (int y = 0; y < height; ++y) {
                    if (setBlock(pos.add(x, y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(x, y, -z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, y, -z), block)) {
                        ++affected;
                    }
                }
            }
        }

        return affected;
    }

    /**
    * Makes a sphere.
    *
    * @param pos Center of the sphere or ellipsoid
    * @param block The block pattern to use
    * @param radius The sphere's radius
    * @param filled If false, only a shell will be generated.
    * @return number of blocks changed
    * @throws MaxChangedBlocksException
    */
    public int makeSphere(Vector pos, Pattern block, double radius, boolean filled) throws MaxChangedBlocksException {
        return makeSphere(pos, block, radius, radius, radius, filled);
    }

    /**
     * Makes a sphere or ellipsoid.
     *
     * @param pos Center of the sphere or ellipsoid
     * @param block The block pattern to use
     * @param radiusX The sphere/ellipsoid's largest north/south extent
     * @param radiusY The sphere/ellipsoid's largest up/down extent
     * @param radiusZ The sphere/ellipsoid's largest east/west extent
     * @param filled If false, only a shell will be generated.
     * @return number of blocks changed
     * @throws MaxChangedBlocksException
     */
    public int makeSphere(Vector pos, Pattern block, double radiusX, double radiusY, double radiusZ, boolean filled) throws MaxChangedBlocksException {
        int affected = 0;

        radiusX += 0.5;
        radiusY += 0.5;
        radiusZ += 0.5;

        final double invRadiusX = 1 / radiusX;
        final double invRadiusY = 1 / radiusY;
        final double invRadiusZ = 1 / radiusZ;

        final int ceilRadiusX = (int) Math.ceil(radiusX);
        final int ceilRadiusY = (int) Math.ceil(radiusY);
        final int ceilRadiusZ = (int) Math.ceil(radiusZ);

        double nextXn = 0;
        forX: for (int x = 0; x <= ceilRadiusX; ++x) {
            final double xn = nextXn;
            nextXn = (x + 1) * invRadiusX;
            double nextYn = 0;
            forY: for (int y = 0; y <= ceilRadiusY; ++y) {
                final double yn = nextYn;
                nextYn = (y + 1) * invRadiusY;
                double nextZn = 0;
                forZ: for (int z = 0; z <= ceilRadiusZ; ++z) {
                    final double zn = nextZn;
                    nextZn = (z + 1) * invRadiusZ;

                    double distanceSq = lengthSq(xn, yn, zn);
                    if (distanceSq > 1) {
                        if (z == 0) {
                            if (y == 0) {
                                break forX;
                            }
                            break forY;
                        }
                        break forZ;
                    }

                    if (!filled) {
                        if (lengthSq(nextXn, yn, zn) <= 1 && lengthSq(xn, nextYn, zn) <= 1 && lengthSq(xn, yn, nextZn) <= 1) {
                            continue;
                        }
                    }

                    if (setBlock(pos.add(x, y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(x, -y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(x, y, -z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, -y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(x, -y, -z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, y, -z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, -y, -z), block)) {
                        ++affected;
                    }
                }
            }
        }

        return affected;
    }

    private static final double lengthSq(double x, double y, double z) {
        return (x * x) + (y * y) + (z * z);
    }

    private static final double lengthSq(double x, double z) {
        return (x * x) + (z * z);
    }

    /**
     * Makes a pyramid.
     *
     * @param pos
     * @param block
     * @param size
     * @param filled
     * @return number of blocks changed
     * @throws MaxChangedBlocksException
     */
    public int makePyramid(Vector pos, Pattern block, int size,
            boolean filled) throws MaxChangedBlocksException {
        int affected = 0;

        int height = size;

        for (int y = 0; y <= height; ++y) {
            size--;
            for (int x = 0; x <= size; ++x) {
                for (int z = 0; z <= size; ++z) {

                    if ((filled && z <= size && x <= size) || z == size || x == size) {

                        if (setBlock(pos.add(x, y, z), block)) {
                            ++affected;
                        }
                        if (setBlock(pos.add(-x, y, z), block)) {
                            ++affected;
                        }
                        if (setBlock(pos.add(x, y, -z), block)) {
                            ++affected;
                        }
                        if (setBlock(pos.add(-x, y, -z), block)) {
                            ++affected;
                        }
                    }
                }
            }
        }

        return affected;
    }

    /**
     * Thaw a region, removing snow from the top layers.
     *
     * @param pos center position
     * @param radius radius
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks were changed
     */
    public int thaw(Vector pos, double radius) throws MaxChangedBlocksException {
        CylinderRegion region = new CylinderRegion(
                getWorld(), pos, new Vector2D(radius, radius), 1, world.getMaxY());
        ThawArea op = new ThawArea(this, region);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Make snow.
     *
     * @param pos
     * @param radius
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int simulateSnow(Vector pos, double radius)
            throws MaxChangedBlocksException {
        CylinderRegion region = new CylinderRegion(
                getWorld(), pos, new Vector2D(radius, radius), 1, world.getMaxY());
        FreezeArea op = new FreezeArea(this, region);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Green.
     *
     * @param pos
     * @param radius
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int green(Vector pos, double radius)
            throws MaxChangedBlocksException {
        int affected = 0;
        final double radiusSq = radius * radius;

        final int ox = pos.getBlockX();
        final int oy = pos.getBlockY();
        final int oz = pos.getBlockZ();

        final BaseBlock grass = new BaseBlock(BlockID.GRASS);

        final int ceilRadius = (int) Math.ceil(radius);
        for (int x = ox - ceilRadius; x <= ox + ceilRadius; ++x) {
            for (int z = oz - ceilRadius; z <= oz + ceilRadius; ++z) {
                if ((new Vector(x, oy, z)).distanceSq(pos) > radiusSq) {
                    continue;
                }

                loop: for (int y = world.getMaxY(); y >= 1; --y) {
                    final Vector pt = new Vector(x, y, z);
                    final int id = getBlockType(pt);

                    switch (id) {
                    case BlockID.DIRT:
                        if (setBlock(pt, grass)) {
                            ++affected;
                        }
                        break loop;

                    case BlockID.WATER:
                    case BlockID.STATIONARY_WATER:
                    case BlockID.LAVA:
                    case BlockID.STATIONARY_LAVA:
                        // break on liquids...
                        break loop;

                    default:
                        // ...and all non-passable blocks
                        if (!BlockType.canPassThrough(id)) {
                            break loop;
                        }
                    }
                }
            }
        }

        return affected;
    }
    
    /**
     * Makes pumpkin patches.
     *
     * @param pos center position
     * @param radius radius of area
     * @return number of trees created
     * @throws MaxChangedBlocksException thrown if too many blocks were changed
     */
    public int makePumpkinPatches(Vector pos, int radius) throws MaxChangedBlocksException {
        CylinderRegion region = new CylinderRegion(getWorld(), pos,
                new Vector2D(radius, radius), pos.getBlockY() - 10, pos.getBlockY());
        Pattern fruit = new SingleBlockPattern(new BaseBlock(BlockID.PUMPKIN));
        FruitPatch structure = new FruitPatch(fruit);
        ScatterStructures op = new ScatterStructures(this, region, structure);
        op.setDensity(1 - 0.98);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Makes a forest.
     *
     * @param pos center position
     * @param radius radius of area
     * @param density density of the forest as a number between 0 and 1
     * @param treeGenerator the tree generator
     * @return number of trees created
     * @throws MaxChangedBlocksException thrown if too many blocks were changed
     */
    public int makeForest(Vector pos, int radius, double density,
            TreeGenerator treeGenerator) throws MaxChangedBlocksException {
        CylinderRegion region = new CylinderRegion(getWorld(), pos,
                new Vector2D(radius, radius), pos.getBlockY() - 10, pos.getBlockY());
        TreeGeneratorProxy structure = new TreeGeneratorProxy(treeGenerator);
        ScatterStructures op = new ScatterStructures(this, region, structure);
        op.setDensity(density);
        OperationHelper.completeLegacy(op);
        return op.getChangeCount();
    }

    /**
     * Get the block distribution inside a region.
     *
     * @param region
     * @return
     */
    public List<Countable<Integer>> getBlockDistribution(Region region) {
        List<Countable<Integer>> distribution = new ArrayList<Countable<Integer>>();
        Map<Integer, Countable<Integer>> map = new HashMap<Integer, Countable<Integer>>();

        if (region instanceof CuboidRegion) {
            // Doing this for speed
            Vector min = region.getMinimumPoint();
            Vector max = region.getMaximumPoint();

            int minX = min.getBlockX();
            int minY = min.getBlockY();
            int minZ = min.getBlockZ();
            int maxX = max.getBlockX();
            int maxY = max.getBlockY();
            int maxZ = max.getBlockZ();

            for (int x = minX; x <= maxX; ++x) {
                for (int y = minY; y <= maxY; ++y) {
                    for (int z = minZ; z <= maxZ; ++z) {
                        Vector pt = new Vector(x, y, z);

                        int id = getBlockType(pt);

                        if (map.containsKey(id)) {
                            map.get(id).increment();
                        } else {
                            Countable<Integer> c = new Countable<Integer>(id, 1);
                            map.put(id, c);
                            distribution.add(c);
                        }
                    }
                }
            }
        } else {
            for (Vector pt : region) {
                int id = getBlockType(pt);

                if (map.containsKey(id)) {
                    map.get(id).increment();
                } else {
                    Countable<Integer> c = new Countable<Integer>(id, 1);
                    map.put(id, c);
                }
            }
        }

        Collections.sort(distribution);
        // Collections.reverse(distribution);

        return distribution;
    }

    /**
     * Get the block distribution (with data values) inside a region.
     *
     * @param region
     * @return
     */
    // TODO reduce code duplication - probably during ops-redux
    public List<Countable<BaseBlock>> getBlockDistributionWithData(Region region) {
        List<Countable<BaseBlock>> distribution = new ArrayList<Countable<BaseBlock>>();
        Map<BaseBlock, Countable<BaseBlock>> map = new HashMap<BaseBlock, Countable<BaseBlock>>();

        if (region instanceof CuboidRegion) {
            // Doing this for speed
            Vector min = region.getMinimumPoint();
            Vector max = region.getMaximumPoint();

            int minX = min.getBlockX();
            int minY = min.getBlockY();
            int minZ = min.getBlockZ();
            int maxX = max.getBlockX();
            int maxY = max.getBlockY();
            int maxZ = max.getBlockZ();

            for (int x = minX; x <= maxX; ++x) {
                for (int y = minY; y <= maxY; ++y) {
                    for (int z = minZ; z <= maxZ; ++z) {
                        Vector pt = new Vector(x, y, z);

                        BaseBlock blk = new BaseBlock(getBlockType(pt), getBlockData(pt));

                        if (map.containsKey(blk)) {
                            map.get(blk).increment();
                        } else {
                            Countable<BaseBlock> c = new Countable<BaseBlock>(blk, 1);
                            map.put(blk, c);
                            distribution.add(c);
                        }
                    }
                }
            }
        } else {
            for (Vector pt : region) {
                BaseBlock blk = new BaseBlock(getBlockType(pt), getBlockData(pt));

                if (map.containsKey(blk)) {
                    map.get(blk).increment();
                } else {
                    Countable<BaseBlock> c = new Countable<BaseBlock>(blk, 1);
                    map.put(blk, c);
                }
            }
        }

        Collections.sort(distribution);
        // Collections.reverse(distribution);

        return distribution;
    }

    public int makeShape(final Region region, final Vector zero, final Vector unit, final Pattern pattern, final String expressionString, final boolean hollow) throws ExpressionException, MaxChangedBlocksException {
        final Expression expression = Expression.compile(expressionString, "x", "y", "z", "type", "data");
        expression.optimize();

        final RValue typeVariable = expression.getVariable("type", false);
        final RValue dataVariable = expression.getVariable("data", false);

        final ArbitraryShape shape = new ArbitraryShape(region) {
            @Override
            protected BaseBlock getMaterial(int x, int y, int z, BaseBlock defaultMaterial) {
                final Vector scaled = new Vector(x, y, z).subtract(zero).divide(unit);

                try {
                    if (expression.evaluate(scaled.getX(), scaled.getY(), scaled.getZ(), defaultMaterial.getType(), defaultMaterial.getData()) <= 0) {
                        return null;
                    }

                    return new BaseBlock((int) typeVariable.getValue(), (int) dataVariable.getValue());
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }
        };

        return shape.generate(this, pattern, hollow);
    }

    public int deformRegion(final Region region, final Vector zero, final Vector unit, final String expressionString) throws ExpressionException, MaxChangedBlocksException {
        final Expression expression = Expression.compile(expressionString, "x", "y", "z");
        expression.optimize();

        final RValue x = expression.getVariable("x", false);
        final RValue y = expression.getVariable("y", false);
        final RValue z = expression.getVariable("z", false);

        Vector zero2 = zero.add(0.5, 0.5, 0.5);

        final DoubleArrayList<BlockVector, BaseBlock> queue = new DoubleArrayList<BlockVector, BaseBlock>(false);

        for (BlockVector position : region) {
            // offset, scale
            final Vector scaled = position.subtract(zero).divide(unit);

            // transform
            expression.evaluate(scaled.getX(), scaled.getY(), scaled.getZ());

            final Vector sourceScaled = new Vector(x.getValue(), y.getValue(), z.getValue());

            // unscale, unoffset, round-nearest
            final BlockVector sourcePosition = sourceScaled.multiply(unit).add(zero2).toBlockPoint();

            // read block from world
            BaseBlock material = new BaseBlock(world.getBlockType(sourcePosition), world.getBlockData(sourcePosition));

            // queue operation
            queue.put(position, material);
        }

        int affected = 0;
        for (Map.Entry<BlockVector, BaseBlock> entry : queue) {
            BlockVector position = entry.getKey();
            BaseBlock material = entry.getValue();

            // set at new position
            if (setBlock(position, material)) {
                ++affected;
            }
        }

        return affected;
    }

    Vector[] recurseDirections = {
        PlayerDirection.NORTH.vector(),
        PlayerDirection.EAST.vector(),
        PlayerDirection.SOUTH.vector(),
        PlayerDirection.WEST.vector(),
        PlayerDirection.UP.vector(),
        PlayerDirection.DOWN.vector(),
    };

    /**
     * Hollows out the region (Semi-well-defined for non-cuboid selections).
     *
     * @param region the region to hollow out.
     * @param thickness the thickness of the shell to leave (manhattan distance)
     * @param pattern The block pattern to use
     *
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int hollowOutRegion(Region region, int thickness, Pattern pattern) throws MaxChangedBlocksException {
        int affected = 0;

        final Set<BlockVector> outside = new HashSet<BlockVector>();

        final Vector min = region.getMinimumPoint();
        final Vector max = region.getMaximumPoint();

        final int minX = min.getBlockX();
        final int minY = min.getBlockY();
        final int minZ = min.getBlockZ();
        final int maxX = max.getBlockX();
        final int maxY = max.getBlockY();
        final int maxZ = max.getBlockZ();

        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                recurseHollow(region, new BlockVector(x, y, minZ), outside);
                recurseHollow(region, new BlockVector(x, y, maxZ), outside);
            }
        }

        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                recurseHollow(region, new BlockVector(minX, y, z), outside);
                recurseHollow(region, new BlockVector(maxX, y, z), outside);
            }
        }

        for (int z = minZ; z <= maxZ; ++z) {
            for (int x = minX; x <= maxX; ++x) {
                recurseHollow(region, new BlockVector(x, minY, z), outside);
                recurseHollow(region, new BlockVector(x, maxY, z), outside);
            }
        }

        for (int i = 1; i < thickness; ++i) {
            final Set<BlockVector> newOutside = new HashSet<BlockVector>();
            outer: for (BlockVector position : region) {
                for (Vector recurseDirection: recurseDirections) {
                    BlockVector neighbor = position.add(recurseDirection).toBlockVector();

                    if (outside.contains(neighbor)) {
                        newOutside.add(position);
                        continue outer;
                    }
                }
            }

            outside.addAll(newOutside);
        }

        outer: for (BlockVector position : region) {
            for (Vector recurseDirection: recurseDirections) {
                BlockVector neighbor = position.add(recurseDirection).toBlockVector();

                if (outside.contains(neighbor)) {
                    continue outer;
                }
            }

            if (setBlock(position, pattern.next(position))) {
                ++affected;
            }
        }

        return affected;
    }

    private void recurseHollow(Region region, BlockVector origin, Set<BlockVector> outside) {
        final LinkedList<BlockVector> queue = new LinkedList<BlockVector>();
        queue.addLast(origin);

        while (!queue.isEmpty()) {
            final BlockVector current = queue.removeFirst();
            if (!BlockType.canPassThrough(getBlockType(current))) {
                continue;
            }

            if (!outside.add(current)) {
                continue;
            }

            if (!region.contains(current)) {
                continue;
            }

            for (Vector recurseDirection: recurseDirections) {
                queue.addLast(current.add(recurseDirection).toBlockVector());
            }
        } // while
    }

}