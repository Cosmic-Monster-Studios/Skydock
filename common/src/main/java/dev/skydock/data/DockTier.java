package dev.skydock.data;

public enum DockTier {
    SCOUT(32, 20, 32, 2048), BRIG(48, 28, 48, 6144),
    CRUISER(64, 36, 64, 12288), DREADNOUGHT(96, 48, 96, 24576);

    public final int width, height, length, defaultCap;
    DockTier(int width, int height, int length, int cap) {
        this.width = width; this.height = height; this.length = length; this.defaultCap = cap;
    }
    public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
    // Berth geometry lives in DockFrame: the controller is centred on the front edge, one block below the interior floor.
}
