package fr.lolmc.manager;

public class SavedBlock {
    private final int relX;
    private final int relY;
    private final int relZ;
    private final String blockData;

    public SavedBlock(int relX, int relY, int relZ, String blockData) {
        this.relX = relX;
        this.relY = relY;
        this.relZ = relZ;
        this.blockData = blockData;
    }

    public int getRelX() { return relX; }
    public int getRelY() { return relY; }
    public int getRelZ() { return relZ; }
    public String getBlockData() { return blockData; }
}