package org.jmc.models;

import org.jmc.*;
import org.jmc.NBT.*;
import org.jmc.geom.Direction;
import org.jmc.geom.Transform;
import org.jmc.geom.UV;
import org.jmc.registry.NamespaceID;
import org.jmc.threading.ChunkProcessor;
import org.jmc.threading.ThreadChunkDeligate;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

/**
 * Most code here is taken or adapted from Bits And Chisels' <a href="https://github.com/CoolMineman/BitsAndChisels/blob/master/src/main/java/io/github/coolmineman/bitsandchisels/BitMeshes.java">original meshing implementation</a>:
 */
public class BitsBlock extends BlockModel {

    @Override
    public void addModel(@Nonnull ChunkProcessor obj, ThreadChunkDeligate chunks, int x, int y, int z, @Nonnull BlockData data, @Nonnull NamespaceID biome) {
        TAG_Compound nbtData = chunks.getTileEntity(x, y, z);
        BlockData[][][] paletteData = read3DBitArray(nbtData);
        createMesh(obj, paletteData, x, y, z, biome);
    }

    /**
     * Extracts the BlockData for each micro block from the tile entity and returns it as a 3D array
     */
    public static BlockData[][][] read3DBitArray(TAG_Compound nbtData) {
        BlockData[][][] blockData = new BlockData[16][16][16];
        ArrayList<BlockData> palette = new ArrayList<>();

        TAG_List paletteTag = (TAG_List) nbtData.getElement("palette");
        if (paletteTag == null) throw new RuntimeException("No palette tag found for bits block");
        // Get each of the palette entries (so the block types making up the micro block collection)
        for (NBT_Tag paletteEntry : paletteTag.elements) {
            TAG_Compound entry = (TAG_Compound) paletteEntry;
            // The id of this palette entry
            TAG_String itemId = (TAG_String) entry.getElement("Name");

            // Query the Properties data which contains the block state of this palette entry
            TAG_Compound stateTag = (TAG_Compound) entry.getElement("Properties");
            Blockstate state = new Blockstate();
            if (stateTag != null) for (NBT_Tag element : stateTag.elements) {
                TAG_String stateEntry = (TAG_String) element;
                state.put(stateEntry.getName(), stateEntry.value);
            }

            // Add this data to the palette
            palette.add(new BlockData(NamespaceID.fromString(itemId.value), state));
        }
        // Read bits data
        TAG_Byte_Array bits2 = (TAG_Byte_Array) nbtData.getElement("bits_v2");
        int index = 0;
        if (bits2 == null) throw new RuntimeException("No bits_v2 tag found for bits block");
        byte[] bitsBytes = bits2.data;
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                for (int k = 0; k < 16; k++) {
                    // Store the BlockData of this micro block in the array
                    blockData[i][j][k] = palette.get((bitsBytes[index + 1] << 8 | bitsBytes[index] & 0xFF));
                    index += 2;
                }
            }
        }
        return blockData;
    }

    /**
     * Sets all values in a 2d boolean array to false
     */
    private static void clear(boolean[][] a) {
        for (boolean[] booleans : a) {
            Arrays.fill(booleans, false);
        }
    }

    /**
     * Checks if a quad is occluded and therefore not needed
     */
    private static boolean quadNeeded(BlockData[][][] states, Direction d, int x, int y, int z) {
        switch (d) {
            case UP:
                if (y <= 14)
                    return states[x][y + 1][z].id.equals(NamespaceID.fromString("minecraft:air")) || BlockTypes.get(states[x][y + 1][z]).getOcclusion() != BlockInfo.Occlusion.FULL && states[x][y][z] != states[x][y + 1][z];
                return true;
            case DOWN:
                if (y >= 1)
                    return states[x][y - 1][z].id.equals(NamespaceID.fromString("minecraft:air")) || BlockTypes.get(states[x][y - 1][z]).getOcclusion() != BlockInfo.Occlusion.FULL && states[x][y][z] != states[x][y - 1][z];
                return true;
            case SOUTH:
                if (z <= 14)
                    return states[x][y][z + 1].id.equals(NamespaceID.fromString("minecraft:air")) || BlockTypes.get(states[x][y][z + 1]).getOcclusion() != BlockInfo.Occlusion.FULL && states[x][y][z] != states[x][y][z + 1];
                return true;
            case NORTH:
                if (z >= 1)
                    return states[x][y][z - 1].id.equals(NamespaceID.fromString("minecraft:air")) || BlockTypes.get(states[x][y][z - 1]).getOcclusion() != BlockInfo.Occlusion.FULL && states[x][y][z] != states[x][y][z - 1];
                return true;
            case EAST:
                if (x <= 14)
                    return states[x + 1][y][z].id.equals(NamespaceID.fromString("minecraft:air")) || BlockTypes.get(states[x + 1][y][z]).getOcclusion() != BlockInfo.Occlusion.FULL && states[x][y][z] != states[x + 1][y][z];
                return true;
            case WEST:
                if (x >= 1)
                    return states[x - 1][y][z].id.equals(NamespaceID.fromString("minecraft:air")) || BlockTypes.get(states[x - 1][y][z]).getOcclusion() != BlockInfo.Occlusion.FULL && states[x][y][z] != states[x - 1][y][z];
                return true;
        }
        return true;
    }


    /**
     * Generates the mesh from the Blockdata
     * It uses a sweeping Greedy Mesh algorithm to render the minimum amount of quads
     */
    private void createMesh(ChunkProcessor obj, BlockData[][][] states, int x, int y, int z, NamespaceID biome) {
        final Direction[] X_DIRECTIONS = {Direction.EAST, Direction.WEST};
        final Direction[] Y_DIRECTIONS = {Direction.UP, Direction.DOWN};
        final Direction[] Z_DIRECTIONS = {Direction.SOUTH, Direction.NORTH};

        boolean[][] used = new boolean[16][16];
        Transform transform = Transform.translation(x - 0.5, y - 0.5, z - 0.5);

        //X
        for (Direction d : X_DIRECTIONS) {
            for (int cx = 0; cx < 16; cx++) {
                clear(used);
                for (int cy = 0; cy < 16; cy++) {
                    for (int cz = 0; cz < 16; cz++) {
                        BlockData state = states[cx][cy][cz];
                        if (state.id.equals(NamespaceID.fromString("minecraft:air")) || used[cy][cz] || !quadNeeded(states, d, cx, cy, cz))
                            continue;
                        int cy2 = cy;
                        int cz2 = cz;
                        //Greed Y
                        for (int ty = cy; ty < 16; ty++) {
                            if (states[cx][ty][cz] == state && !used[ty][cz] && quadNeeded(states, d, cx, ty, cz)) {
                                cy2 = ty;
                            } else {
                                break;
                            }
                        }
                        // Greed Z
                        greedz:
                        for (int tz = cz; tz < 16; tz++) {
                            for (int ty = cy; ty <= cy2; ty++) {
                                if (states[cx][ty][tz] != state || used[ty][tz] || !quadNeeded(states, d, cx, ty, tz)) {
                                    break greedz;
                                }
                            }
                            cz2 = tz;
                        }
                        for (int qy = cy; qy <= cy2; qy++) {
                            for (int qz = cz; qz <= cz2; qz++) {
                                used[qy][qz] = true;
                            }
                        }
                        doQuad(obj, d, cx, cy, cz, cx, cy2, cz2, states[cx][cy][cz], transform, biome);
                    }
                }
            }
        }

        //Y
        for (Direction d : Y_DIRECTIONS) {
            for (int cy = 0; cy < 16; cy++) {
                clear(used);
                for (int cx = 0; cx < 16; cx++) {
                    for (int cz = 0; cz < 16; cz++) {
                        BlockData state = states[cx][cy][cz];
                        if (state.id.equals(NamespaceID.fromString("minecraft:air")) || used[cx][cz] || !quadNeeded(states, d, cx, cy, cz))
                            continue;
                        int cx2 = cx;
                        int cz2 = cz;
                        //Greed X
                        for (int tx = cx; tx < 16; tx++) {
                            if (states[tx][cy][cz] == state && !used[tx][cz] && quadNeeded(states, d, tx, cy, cz)) {
                                cx2 = tx;
                            } else {
                                break;
                            }
                        }
                        // Greed Z
                        greedz:
                        for (int tz = cz; tz < 16; tz++) {
                            for (int tx = cx; tx <= cx2; tx++) {
                                if (states[tx][cy][tz] != state || used[tx][tz] || !quadNeeded(states, d, tx, cy, tz)) {
                                    break greedz;
                                }
                            }
                            cz2 = tz;
                        }
                        for (int qx = cx; qx <= cx2; qx++) {
                            for (int qz = cz; qz <= cz2; qz++) {
                                used[qx][qz] = true;
                            }
                        }

                        doQuad(
                                obj, d, cx, cy, cz, cx2, cy, cz2, states[cx][cy][cz], transform, biome);
                    }
                }
            }
        }

        //Z
        for (Direction d : Z_DIRECTIONS) {
            for (int cz = 0; cz < 16; cz++) {
                clear(used);
                for (int cx = 0; cx < 16; cx++) {
                    for (int cy = 0; cy < 16; cy++) {
                        BlockData state = states[cx][cy][cz];
                        if (state.id.equals(NamespaceID.fromString("minecraft:air")) || used[cx][cy] || !quadNeeded(states, d, cx, cy, cz))
                            continue;
                        int cx2 = cx;
                        int cy2 = cy;
                        //Greed X
                        for (int tx = cx; tx < 16; tx++) {
                            if (states[tx][cy][cz] == state && !used[tx][cz] && quadNeeded(states, d, tx, cy, cz)) {
                                cx2 = tx;
                            } else {
                                break;
                            }
                        }
                        // Greed Y
                        greedy:
                        for (int ty = cy; ty < 16; ty++) {
                            for (int tx = cx; tx <= cx2; tx++) {
                                if (states[tx][ty][cz] != state || used[tx][ty] || !quadNeeded(states, d, tx, ty, cz)) {
                                    break greedy;
                                }
                            }
                            cy2 = ty;
                        }
                        for (int qx = cx; qx <= cx2; qx++) {
                            for (int qy = cy; qy <= cy2; qy++) {
                                used[qx][qy] = true;
                            }
                        }
                        doQuad(obj, d, cx, cy, cz, cx2, cy2, cz, states[cx][cy][cz], transform, biome);
                    }
                }
            }
        }
    }

    /**
     * Adds a quad to the ChunkProcessor using the given parameters
     *
     * @param obj       ChunkProcessor to which the quad is added
     * @param d         Direction of the quad
     * @param xs        Start X
     * @param ys        Start Y
     * @param zs        Start Z
     * @param xe        End X
     * @param ye        End Y
     * @param ze        End Z
     * @param transform Transform of this quad
     */
    public void doQuad(ChunkProcessor obj, Direction d, double xs, double ys, double zs, double xe, double ye, double ze, BlockData data, Transform transform, NamespaceID biome) {
        final float ONE_PIXEL = 1f / 16f;

        System.out.printf("Constructing quad: %s, %f, %f, %f, %f, %f, %f\n", d, xs, ys, zs, xe, ye, zs);

        // Scale down geometry to match micro block
        xs = xs * ONE_PIXEL;
        ys = ys * ONE_PIXEL;
        zs = zs * ONE_PIXEL;
        xe = (xe + 1) * ONE_PIXEL;
        ye = (ye + 1) * ONE_PIXEL;
        ze = (ze + 1) * ONE_PIXEL;

        // Set drawside
        boolean[] drawSides = new boolean[]{
                d == Direction.UP,
                d == Direction.NORTH,
                d == Direction.SOUTH,
                d == Direction.WEST,
                d == Direction.EAST,
                d == Direction.DOWN
        };

        // UV Sides
        UV[][] uvSides = new UV[][]{
                { // UP
                        new UV(xs, -ze + 1),
                        new UV(xe, -ze + 1),
                        new UV(xe, -zs + 1),
                        new UV(xs, -zs + 1)
                },
                { // NORTH
                        new UV(-xe + 1, ys),
                        new UV(-xs + 1, ys),
                        new UV(-xs + 1, ye),
                        new UV(-xe + 1, ye)
                },
                { // SOUTH
                        new UV(xs, ys),
                        new UV(xe, ys),
                        new UV(xe, ye),
                        new UV(xs, ye)
                },
                { // WEST
                        new UV(zs, ys),
                        new UV(ze, ys),
                        new UV(ze, ye),
                        new UV(zs, ye)
                },
                { // EAST
                        new UV(-ze + 1, ys),
                        new UV(-zs + 1, ys),
                        new UV(-zs + 1, ye),
                        new UV(-ze + 1, ye)
                },
                { // DOWN
                        new UV(xe, ze),
                        new UV(xs, ze),
                        new UV(xs, zs),
                        new UV(xe, zs)
                }
        };

        // Take materials from block type of micro block
        BlockMaterial mats = BlockTypes.get(data).getMaterials();
        NamespaceID[] microBlockMtls = mats.get(data.state, biome);
        // Set as materials and convert to Box materials (getMtlSides)
        materials.put(microBlockMtls);
        NamespaceID[] sidesMtl = getMtlSides(data, biome);

        // Construct quad using addBox helper method
        addBox(obj, xs, ys, zs, xe, ye, ze, transform, sidesMtl, uvSides, drawSides);
    }


}
