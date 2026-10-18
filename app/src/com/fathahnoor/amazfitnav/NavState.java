package com.fathahnoor.amazfitnav;

/** Shared, lock-free snapshot of what the bridge is seeing and doing. */
public final class NavState {
    public static volatile boolean listenerConnected = false;
    public static volatile String instruction = "";
    public static volatile String distance = "";
    public static volatile String eta = "";
    public static volatile String rawDump = "";
    public static volatile long lastUpdate = 0L;
    public static volatile int published = 0;
    public static volatile String lastEvent = "(idle)";
    public static volatile boolean mediaBridgeOn = false;
    public static volatile boolean loudChannel = false;

    private NavState() {}
}
