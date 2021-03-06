package me.shedaniel.lightoverlay.common;

import com.google.common.base.Suppliers;
import com.google.common.collect.Maps;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
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
        var thread = new Thread(r, "light-overlay-" + threadNumber++);
        thread.setDaemon(true);
        return thread;
    });
    public final Set<CubicChunkPos> POS = Collections.synchronizedSet(new HashSet<>());
    public final Set<CubicChunkPos> CALCULATING_POS = Collections.synchronizedSet(new HashSet<>());
    public final Map<CubicChunkPos, ChunkData> CHUNK_MAP = Maps.newConcurrentMap();
    private static final Supplier<EntityType<Entity>> TESTING_ENTITY_TYPE = Suppliers.memoize(() ->
            EntityType.Builder.createNothing(MobCategory.MONSTER).sized(0f, 0f).noSave().build(null));
    
    public void queueChunk(CubicChunkPos pos) {
        if (LightOverlay.enabled && !CALCULATING_POS.contains(pos)) {
            if (minecraft.level != null) {
                var chunk = minecraft.level.getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
                if (chunk == null) return;
                var sections = ((LevelChunkAccess) chunk).lightoverlay_getSections();
                var firstSectionIndex = pos.y << 1 - chunk.getMinSection();
                if (firstSectionIndex >= 0 && firstSectionIndex < sections.length) {
                    var section = sections[firstSectionIndex];
                    if (!LevelChunkSection.isEmpty(section)) {
                        POS.add(pos);
                        return;
                    }
                }
                if (firstSectionIndex + 1 >= 0 && firstSectionIndex + 1 < sections.length) {
                    var section = sections[firstSectionIndex + 1];
                    if (!LevelChunkSection.isEmpty(section)) {
                        POS.add(pos);
                    }
                }
            }
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
                for (ChunkData data : CHUNK_MAP.values()) {
                    data.close();
                }
                CHUNK_MAP.clear();
            } else {
                var player = minecraft.player;
                var world = minecraft.level;
                var collisionContext = CollisionContext.of(player);
                
                var height = Mth.ceil(Minecraft.getInstance().level.getHeight() / 32.0);
                var start = Math.floorDiv(Minecraft.getInstance().level.getMinBuildHeight(), 32);
                var playerPosX = ((int) player.getX()) >> 4;
                var playerPosY = ((int) player.getY()) >> 5;
                var playerPosZ = ((int) player.getZ()) >> 4;
                var chunkRange = LightOverlay.getChunkRange();
                for (var chunkX = playerPosX - chunkRange; chunkX <= playerPosX + chunkRange; chunkX++) {
                    for (var chunkY = Math.max(playerPosY - Math.max(1, chunkRange >> 1), start); chunkY <= playerPosY + Math.max(1, chunkRange >> 1) && chunkY <= start + height; chunkY++) {
                        for (var chunkZ = playerPosZ - chunkRange; chunkZ <= playerPosZ + chunkRange; chunkZ++) {
                            if (Mth.abs(chunkX - playerPosX) > chunkRange || Mth.abs(chunkY - playerPosY) > chunkRange || Mth.abs(chunkZ - playerPosZ) > chunkRange)
                                continue;
                            var chunkPos = new CubicChunkPos(chunkX, chunkY, chunkZ);
                            if (!CHUNK_MAP.containsKey(chunkPos))
                                queueChunk(chunkPos);
                        }
                    }
                }
                for (var p = 0; p < 3; p++) {
                    if (EXECUTOR.getQueue().size() >= Runtime.getRuntime().availableProcessors()) break;
                    double d1 = Double.MAX_VALUE, d2 = Double.MAX_VALUE, d3 = Double.MAX_VALUE;
                    CubicChunkPos c1 = null, c2 = null, c3 = null;
                    synchronized (POS) {
                        var iterator = POS.iterator();
                        while (iterator.hasNext()) {
                            var pos = iterator.next();
                            if (Mth.abs(pos.x - playerPosX) > chunkRange || Mth.abs(pos.y - playerPosY) > Math.max(1, chunkRange >> 1) || Mth.abs(pos.z - playerPosZ) > chunkRange || CALCULATING_POS.contains(pos)) {
                                iterator.remove();
                            } else {
                                if (LightOverlay.renderer.isFrustumVisible(pos.getMinBlockX(), pos.getMinBlockY(), pos.getMinBlockZ(), pos.getMaxBlockX(), pos.getMaxBlockY(), pos.getMaxBlockZ())) {
                                    var dx = Math.abs(pos.x - playerPosX);
                                    var dy = Math.abs(pos.y - playerPosY) << 1;
                                    var dz = Math.abs(pos.z - playerPosZ);
                                    var distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
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
                    var finalC1 = c1;
                    var finalC2 = c2;
                    var finalC3 = c3;
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
                            var playerPosX1 = ((int) minecraft.player.getX()) >> 4;
                            var playerPosY1 = ((int) minecraft.player.getY()) >> 5;
                            var playerPosZ1 = ((int) minecraft.player.getZ()) >> 4;
                            var count = 0;
                            if (finalC1 != null) count += processChunk(finalC1, playerPosX1, playerPosY1, playerPosZ1, collisionContext);
                            if (finalC2 != null) count += processChunk(finalC2, playerPosX1, playerPosY1, playerPosZ1, collisionContext);
                            if (finalC3 != null) count += processChunk(finalC3, playerPosX1, playerPosY1, playerPosZ1, collisionContext);
                            synchronized (this) {
                                LightOverlay.blocksScanned += count;
                            }
                        });
                    }
                }
                if (ticks % 50 == 0) {
                    Iterator<Map.Entry<CubicChunkPos, ChunkData>> iterator = CHUNK_MAP.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<CubicChunkPos, ChunkData> entry = iterator.next();
                        if (Mth.abs(entry.getKey().x - playerPosX) > chunkRange * 2 || Mth.abs(entry.getKey().y - playerPosY) > chunkRange * 2 || Mth.abs(entry.getKey().z - playerPosZ) > chunkRange * 2) {
                            entry.getValue().close();
                            iterator.remove();
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            LogManager.getLogger().throwing(throwable);
        }
    }
    
    private int processChunk(CubicChunkPos pos, int playerPosX, int playerPosY, int playerPosZ, CollisionContext context) {
        CALCULATING_POS.remove(pos);
        var chunkRange = LightOverlay.getChunkRange();
        if (Mth.abs(pos.x - playerPosX) > chunkRange || Mth.abs(pos.y - playerPosY) > Math.max(1, chunkRange >> 1) || Mth.abs(pos.z - playerPosZ) > chunkRange || POS.contains(pos)) {
            return 0;
        }
        try {
            return calculateChunk(minecraft.level.getChunkSource().getChunk(pos.x, pos.z, ChunkStatus.FULL, false), minecraft.level, pos, context);
        } catch (Throwable throwable) {
            LogManager.getLogger().throwing(throwable);
        }
        return 0;
    }
    
    private int calculateChunk(LevelChunk chunk, Level world, CubicChunkPos chunkPos, CollisionContext collisionContext) {
        if (world != null && chunk != null) {
            var block = world.getLightEngine().getLayerListener(LightLayer.BLOCK);
            var sky = LightOverlay.showNumber ? null : world.getLightEngine().getLayerListener(LightLayer.SKY);
            ChunkData chunkData = new ChunkData();
            var count = 0;
            var sections = ((LevelChunkAccess) chunk).lightoverlay_getSections();
            var firstSectionIndex = chunkPos.y << 1 - chunk.getMinSection();
            calculateSection(chunkData, block, sky, world, chunk, sections, firstSectionIndex, chunkPos, 0, collisionContext);
            calculateSection(chunkData, block, sky, world, chunk, sections, firstSectionIndex + 1, chunkPos, 16, collisionContext);
            var data = CHUNK_MAP.put(chunkPos, chunkData);
            if (data != null) {
                data.close();
            }
            return count;
        } else {
            var data = CHUNK_MAP.remove(chunkPos);
            if (data != null) {
                data.close();
            }
            return 0;
        }
    }
    
    private void calculateSection(ChunkData chunkData, LayerLightEventListener block, LayerLightEventListener sky, Level world, LevelChunk chunk, LevelChunkSection[] sections,
            int sectionIndex, CubicChunkPos chunkPos, int yOffset, CollisionContext collisionContext) {
        if (sectionIndex >= 0 && sectionIndex < sections.length) {
            var section = sections[sectionIndex];
            if (!LevelChunkSection.isEmpty(section)) {
                var startingYBelow = chunkPos.getMinBlockY() - 1 + yOffset;
                var skipOneLayer = startingYBelow < world.getMinBuildHeight();
                for (var x = 0; x < 16; x++) {
                    for (var z = 0; z < 16; z++) {
                        var lastDownPos = new BlockPos.MutableBlockPos(chunkPos.getMinBlockX() + x, startingYBelow, chunkPos.getMinBlockZ() + z);
                        var lastDown = skipOneLayer ? null : chunk.getBlockState(lastDownPos);
                        var currentPos = new BlockPos.MutableBlockPos(lastDownPos.getX(), lastDownPos.getY() + 1, lastDownPos.getZ());
                        for (var y = 0; y < 16; y++) {
                            var current = section.getBlockState(x, y, z);
                            if (lastDown != null) {
                                if (LightOverlay.showNumber) {
                                    var level = getCrossLevel(current, lastDown, currentPos, lastDownPos, chunk, block, collisionContext);
                                    if (level >= 0) {
                                        chunkData.put(currentPos.asLong(), (byte) level);
                                    }
                                } else {
                                    var type = getCrossType(current, lastDown, currentPos, lastDownPos, chunk, block, sky, collisionContext);
                                    if (type != LightOverlay.CROSS_NONE) {
                                        chunkData.put(currentPos.asLong(), type);
                                    }
                                }
                            }
                            lastDown = current;
                            lastDownPos.set(currentPos);
                            currentPos.move(0, 1, 0);
                        }
                    }
                }
            }
        }
    }
    
    public byte getCrossType(BlockState blockUpperState, BlockState blockBelowState, BlockPos pos, BlockPos down, BlockGetter world,
            LayerLightEventListener block, LayerLightEventListener sky, CollisionContext entityContext) {
        if (!LightOverlay.underwater && !blockUpperState.getFluidState().isEmpty())
            return LightOverlay.CROSS_NONE;
        var upperCollisionShape = blockUpperState.getCollisionShape(world, pos, entityContext);
        var upperCollisionShapeFace = upperCollisionShape.getFaceShape(Direction.UP);
        // Check if the outline is full
        if (upperCollisionShapeFace == Shapes.block() || Block.isShapeFullBlock(upperCollisionShapeFace))
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
        var blockLightLevel = block.getLightValue(pos);
        var skyLightLevel = sky.getLightValue(pos);
        if (blockLightLevel > LightOverlay.higherCrossLevel)
            return LightOverlay.CROSS_NONE;
        if (skyLightLevel > LightOverlay.higherCrossLevel)
            return LightOverlay.CROSS_YELLOW;
        return LightOverlay.lowerCrossLevel >= 0 && blockLightLevel > LightOverlay.lowerCrossLevel ? LightOverlay.CROSS_SECONDARY : LightOverlay.CROSS_RED;
    }
    
    public int getCrossLevel(BlockState blockUpperState, BlockState blockBelowState, BlockPos pos, BlockPos down, BlockGetter world,
            LayerLightEventListener view, CollisionContext collisionContext) {
        if (!LightOverlay.underwater && !blockUpperState.getFluidState().isEmpty())
            return -1;
        if (!blockBelowState.getFluidState().isEmpty())
            return -1;
        if (blockBelowState.isAir())
            return -1;
        var upperCollisionShape = blockUpperState.getCollisionShape(world, pos, collisionContext).getFaceShape(Direction.DOWN);
        if (upperCollisionShape == Shapes.block() || Block.isShapeFullBlock(upperCollisionShape))
            return -1;
        return view.getLightValue(pos);
    }
}
