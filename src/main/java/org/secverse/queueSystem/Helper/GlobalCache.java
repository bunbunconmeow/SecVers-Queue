package org.secverse.queueSystem.Helper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


public final class GlobalCache {

    private static final AtomicReference<ConfigData> CURRENT = new AtomicReference<>();

    private GlobalCache() { }


    public static void initialize(ConfigData config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        if (!CURRENT.compareAndSet(null, config)) {
            throw new IllegalStateException("GlobalCache has already been initialized");
        }
    }

    public static void replace(ConfigData config) {
        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }
        CURRENT.set(config);
    }

    public static ConfigData get() {
        ConfigData cfg = CURRENT.get();
        if (cfg == null) {
            throw new IllegalStateException("GlobalCache not initialized");
        }
        return cfg;
    }


    public static List<String> getTargetServers() {
        return get().getTargetServers();
    }
}
