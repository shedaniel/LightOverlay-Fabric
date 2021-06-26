package me.shedaniel.lightoverlay.common;

import com.google.common.base.Suppliers;
import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.logging.log4j.LogManager;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

public class LightOverlayTicker {
    private final Minecraft minecraft = Minecraft.getInstance();
    private long ticks = 0;
    private static int threadNumber = 0;
    private static final ThreadPoolExecutor EXECUTOR = (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), r -> {
        Thread thread = new Thread(r, "light-overlay-" + threadNumber++);
        thread.setDaemon(true);
        return thread;
    });
    public final Set<CubicChunkPos> POS = Collections.synchronizedSet(new HashSet<>());
    public final Set<CubicChunkPos> CALCULATING_POS = Collections.synchronizedSet(new HashSet<>());
    public final Map<CubicChunkPos, Long2ByteMap> CHUNK_MAP = Maps.newConcurrentMap();
    private static final Supplier<EntityType<Entity>> TESTING_ENTITY_TYPE = Suppliers.memoize(() ->
            EntityType.Builder.createNothing(MobCategory.MONSTER).sized(0f, 0f).noSave().build(null));
    
    public void queueChunk(CubicChunkPos pos) {
        if (LightOverlay.enabled && LightOverlay.caching && !CALCULATING_POS.contains(pos)) {
            POS.add(pos);
        }
    }
    
    public void tick(Minecraft minecraft) {
        while (LightOverlay.enableOverlay.consumeClick())
            LightOverlay.enabled = !LightOverlay.enabled;
        
        try {
            ticks++;
            if (minecraft.player == null || !LightOverlay.enabled) {
                POS.clear();
                CALCULATING_POS.clear();
                EXECUTOR.getQueue().clear();
                CHUNK_MAP.clear();
            } else {
                LocalPlayer player = minecraft.player;
                ClientLevel world = minecraft.level;
                CollisionContext collisionContext = CollisionContext.of(player);
                
                if (!LightOverlay.caching) {
                    CALCULATING_POS.clear();
                    POS.clear();
                    CHUNK_MAP.clear();
                    BlockPos playerPos = player.blockPosition();
                    LayerLightEventListener block = world.getLightEngine().getLayerListener(LightLayer.BLOCK);
                    LayerLightEventListener sky = LightOverlay.showNumber ? null : world.getLightEngine().getLayerListener(LightLayer.SKY);
                    BlockPos.MutableBlockPos downPos = new BlockPos.MutableBlockPos();
                    Iterable<BlockPos> iterate = BlockPos.betweenClosed(playerPos.getX() - LightOverlay.reach, playerPos.getY() - LightOverlay.reach, playerPos.getZ() - LightOverlay.reach,
                            playerPos.getX() + LightOverlay.reach, playerPos.getY() + LightOverlay.reach, playerPos.getZ() + LightOverlay.reach);
                    Long2ByteMap chunkData = new Long2ByteOpenHashMap();
                    CHUNK_MAP.put(new CubicChunkPos(0, 0, 0), chunkData);
                    for (BlockPos blockPos : iterate) {
                        downPos.set(blockPos.getX(), blockPos.getY() - 1, blockPos.getZ());
                        if (LightOverlay.showNumber) {
                            int level = getCrossLevel(blockPos, downPos, world, block, collisionContext);
                            if (level >= 0) {
                                chunkData.put(blockPos.asLong(), (byte) level);
                            }
                        } else {
                            Biome biome = !LightOverlay.mushroom ? world.getBiome(blockPos) : null;
                            byte type = getCrossType(blockPos, biome, downPos, world, block, sky, collisionContext);
                            if (type != LightOverlay.CROSS_NONE) {
                                chunkData.put(blockPos.asLong(), type);
                            }
                        }
                    }
                } else {
                    var height = Mth.ceil(Minecraft.getInstance().level.getHeight() / 32.0);
                    var start = Math.floorDiv(Minecraft.getInstance().level.getMinBuildHeight(), 32);
                    int playerPosX = ((int) player.getX()) >> 4;
                    int playerPosY = ((int) player.getY()) >> 5;
                    int playerPosZ = ((int) player.getZ()) >> 4;
                    var chunkRange = LightOverlay.getChunkRange();
                    for (int chunkX = playerPosX - chunkRange; chunkX <= playerPosX + chunkRange; chunkX++) {
                        for (int chunkY = Math.max(playerPosY - Math.max(1, chunkRange >> 1), start); chunkY <= playerPosY + Math.max(1, chunkRange >> 1) && chunkY <= start + height; chunkY++) {
                            for (int chunkZ = playerPosZ - chunkRange; chunkZ <= playerPosZ + chunkRange; chunkZ++) {
                                if (Mth.abs(chunkX - playerPosX) > chunkRange || Mth.abs(chunkY - playerPosY) > chunkRange || Mth.abs(chunkZ - playerPosZ) > chunkRange)
                                    continue;
                                CubicChunkPos chunkPos = new CubicChunkPos(chunkX, chunkY, chunkZ);
                                if (!CHUNK_MAP.containsKey(chunkPos))
                                    queueChunk(chunkPos);
                            }
                        }
                    }
                    for (int p = 0; p < 3; p++) {
                        if (EXECUTOR.getQueue().size() >= Runtime.getRuntime().availableProcessors()) break;
                        double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE, d3 = Double.MAX_VALUE;
                        CubicChunkPos c1 = null, c2 = null, c3 = null;
                        synchronized (POS) {
                            Iterator<CubicChunkPos> iterator = POS.iterator();
                            while (iterator.hasNext()) {
                                CubicChunkPos pos = iterator.next();
                                if (Mth.abs(pos.x - playerPosX) > chunkRange || Mth.abs(pos.y - playerPosY) > Math.max(1, chunkRange >> 1) || Mth.abs(pos.z - playerPosZ) > chunkRange || CALCULATING_POS.contains(pos)) {
                                    iterator.remove();
                                } else {
                                    if (LightOverlay.renderer.isFrustumVisible(pos.getMinBlockX(), pos.getMinBlockY(), pos.getMinBlockZ(), pos.getMaxBlockX(), pos.getMaxBlockY(), pos.getMaxBlockZ())) {
                                        int dx = Math.abs(pos.x - playerPosX);
                                        int dy = Math.abs(pos.y - playerPosY) << 1;
                                        int dz = Math.abs(pos.z - playerPosZ);
                                        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                                        if (distance < d1) {
                                            d3 = d2;
                                            d2 = d1;
                                            d1 = distance;
                                            c3 = c2;
                                            c2 = c1;
                                            c1 = pos;
                                        } else if (distance < d2) {
                                            d3 = d2;
                                            d2 = distance;
                                            c3 = c2;
                                            c2 = pos;
                                        } else if (distance < d3) {
                                            d3 = distance;
                                            c3 = pos;
                                        }
                                    }
                                }
                            }
                        }
                        CubicChunkPos finalC1 = c1;
                        CubicChunkPos finalC2 = c2;
                        CubicChunkPos finalC3 = c3;
                        if (finalC1 != null) {
                            CALCULATING_POS.add(finalC1);
                            POS.remove(finalC1);
                            if (finalC2 != null) {
                                CALCULATING_POS.add(finalC2);
                                POS.remove(finalC2);
                                if (finalC3 != null) {
                                    CALCULATING_POS.add(finalC3);
                                    POS.remove(finalC3);
                                }
                            }
                            EXECUTOR.submit(() -> {
                                int playerPosX1 = ((int) minecraft.player.getX()) >> 4;
                                int playerPosY1 = ((int) minecraft.player.getY()) >> 5;
                                int playerPosZ1 = ((int) minecraft.player.getZ()) >> 4;
                                if (finalC1 != null) processChunk(finalC1, playerPosX1, playerPosY1, playerPosZ1, collisionContext);
                                if (finalC2 != null) processChunk(finalC2, playerPosX1, playerPosY1, playerPosZ1, collisionContext);
                                if (finalC3 != null) processChunk(finalC3, playerPosX1, playerPosY1, playerPosZ1, collisionContext);
                            });
                        }
                    }
                    if (ticks % 50 == 0) {
                        CHUNK_MAP.entrySet().removeIf(entry -> Mth.abs(entry.getKey().x - playerPosX) > chunkRange * 2 || Mth.abs(entry.getKey().y - playerPosY) > chunkRange * 2 || Mth.abs(entry.getKey().z - playerPosZ) > chunkRange * 2);
                    }
                }
            }
        } catch (Throwable throwable) {
            LogManager.getLogger().throwing(throwable);
        }
    }
    
    private void processChunk(CubicChunkPos pos, int playerPosX, int playerPosY, int playerPosZ, CollisionContext context) {
        CALCULATING_POS.remove(pos);
        int chunkRange = LightOverlay.getChunkRange();
        if (Mth.abs(pos.x - playerPosX) > chunkRange || Mth.abs(pos.y - playerPosY) > Math.max(1, chunkRange >> 1) || Mth.abs(pos.z - playerPosZ) > chunkRange || POS.contains(pos)) {
            return;
        }
        try {
            calculateChunk(minecraft.level.getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.FULL, false), minecraft.level, pos, context);
        } catch (Throwable throwable) {
            LogManager.getLogger().throwing(throwable);
        }
    }
    
    private void calculateChunk(LevelChunk chunk, Level world, CubicChunkPos chunkPos, CollisionContext collisionContext) {
        if (world != null && chunk != null) {
            Long2ByteMap chunkData = new Long2ByteOpenHashMap();
            LayerLightEventListener block = world.getLightEngine().getLayerListener(LightLayer.BLOCK);
            LayerLightEventListener sky = LightOverlay.showNumber ? null : world.getLightEngine().getLayerListener(LightLayer.SKY);
            for (BlockPos pos : BlockPos.betweenClosed(chunkPos.getMinBlockX(), chunkPos.getMinBlockY(), chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX(), chunkPos.getMaxBlockY(), chunkPos.getMaxBlockZ())) {
                BlockPos down = pos.below();
                if (LightOverlay.showNumber) {
                    int level = getCrossLevel(pos, down, chunk, block, collisionContext);
                    if (level >= 0) {
                        chunkData.put(pos.asLong(), (byte) level);
                    }
                } else {
                    Biome biome = !LightOverlay.mushroom ? world.getBiome(pos) : null;
                    byte type = getCrossType(pos, biome, down, chunk, block, sky, collisionContext);
                    if (type != LightOverlay.CROSS_NONE) {
                        chunkData.put(pos.asLong(), type);
                    }
                }
            }
            CHUNK_MAP.put(chunkPos, chunkData);
        } else {
            CHUNK_MAP.remove(chunkPos);
        }
    }
    
    public byte getCrossType(BlockPos pos, Biome biome, BlockPos down, BlockGetter world, LayerLightEventListener block, LayerLightEventListener sky, CollisionContext entityContext) {
        BlockState blockBelowState = world.getBlockState(down);
        BlockState blockUpperState = world.getBlockState(pos);
        VoxelShape upperCollisionShape = blockUpperState.getCollisionShape(world, pos, entityContext);
        if (!LightOverlay.underwater && !blockUpperState.getFluidState().isEmpty())
            return LightOverlay.CROSS_NONE;
        // Check if the outline is full
        if (Block.isFaceFull(upperCollisionShape, Direction.UP))
            return LightOverlay.CROSS_NONE;
        // TODO: Not to hard code no redstone
        if (blockUpperState.isSignalSource())
            return LightOverlay.CROSS_NONE;
        // Check if the collision has a bump
        if (upperCollisionShape.max(Direction.Axis.Y) > 0)
            return LightOverlay.CROSS_NONE;
        if (blockUpperState.is(BlockTags.RAILS))
            return LightOverlay.CROSS_NONE;
        // Check block state allow spawning (excludes bedrock and barriers automatically)
        if (!blockBelowState.isValidSpawn(world, down, TESTING_ENTITY_TYPE.get()))
            return LightOverlay.CROSS_NONE;
        if (!LightOverlay.mushroom && Biome.BiomeCategory.MUSHROOM == biome.getBiomeCategory())
            return LightOverlay.CROSS_NONE;
        int blockLightLevel = block.getLightValue(pos);
        int skyLightLevel = sky.getLightValue(pos);
        if (blockLightLevel > LightOverlay.higherCrossLevel)
            return LightOverlay.CROSS_NONE;
        if (skyLightLevel > LightOverlay.higherCrossLevel)
            return LightOverlay.CROSS_YELLOW;
        return LightOverlay.lowerCrossLevel >= 0 && blockLightLevel > LightOverlay.lowerCrossLevel ? LightOverlay.CROSS_SECONDARY : LightOverlay.CROSS_RED;
    }
    
    public static int getCrossLevel(BlockPos pos, BlockPos down, BlockGetter world, LayerLightEventListener view, CollisionContext collisionContext) {
        BlockState blockBelowState = world.getBlockState(down);
        Block blockBelow = blockBelowState.getBlock();
        BlockState blockUpperState = world.getBlockState(pos);
        VoxelShape collisionShape = blockBelowState.getCollisionShape(world, down, collisionContext);
        VoxelShape upperCollisionShape = blockUpperState.getCollisionShape(world, pos, collisionContext);
        if (!LightOverlay.underwater && !blockUpperState.getFluidState().isEmpty())
            return -1;
        if (!blockBelowState.getFluidState().isEmpty())
            return -1;
        if (blockBelowState.isAir())
            return -1;
        if (Block.isFaceFull(upperCollisionShape, Direction.DOWN))
            return -1;
        if (collisionShape.isEmpty())
            return -1;
        if (!blockBelowState.canOcclude())
            return -1;
        if (blockBelow instanceof SlabBlock && blockBelowState.getValue(SlabBlock.TYPE) == SlabType.BOTTOM)
            return -1;
        if (blockBelow instanceof StairBlock && blockBelowState.getValue(StairBlock.HALF) == Half.BOTTOM)
            return -1;
        if (blockBelow instanceof FenceBlock
                || blockBelow instanceof FenceGateBlock
                || blockBelow instanceof WallBlock
        )
            return -1;

        return view.getLightValue(pos);
    }
}
