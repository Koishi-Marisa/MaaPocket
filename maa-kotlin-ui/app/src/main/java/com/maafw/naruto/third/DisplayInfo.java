package com.maafw.naruto.third;

public final class DisplayInfo {
    public final int displayId;
    public final Size size;
    public final int rotation;
    public final int layerStack;
    public final int flags;
    public final int density;
    public final String uniqueId;

    public DisplayInfo(int displayId, Size size, int rotation, int layerStack, int flags, int density, String uniqueId) {
        this.displayId = displayId;
        this.size = size;
        this.rotation = rotation;
        this.layerStack = layerStack;
        this.flags = flags;
        this.density = density;
        this.uniqueId = uniqueId;
    }
}