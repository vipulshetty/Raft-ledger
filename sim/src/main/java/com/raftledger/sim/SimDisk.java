package com.raftledger.sim;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SimDisk {
    private final Map<String, byte[]> synced = new LinkedHashMap<>();
    private final Map<String, byte[]> unsynced = new LinkedHashMap<>();

    public void write(String key, byte[] value) {
        requireKey(key);
        unsynced.put(key, copy(value));
    }

    public void fsync(String key) {
        requireKey(key);
        byte[] value = unsynced.remove(key);
        if (value != null) synced.put(key, copy(value));
    }

    public byte[] read(String key) {
        byte[] value = unsynced.containsKey(key) ? unsynced.get(key) : synced.get(key);
        return value == null ? null : copy(value);
    }

    public void crash() {
        unsynced.clear();
    }

    public void tearLastWrite(String key, int retainedBytes) {
        requireKey(key);
        byte[] value = unsynced.get(key);
        if (value == null) throw new IllegalArgumentException("No unsynced write for " + key);
        if (retainedBytes < 0 || retainedBytes > value.length) {
            throw new IllegalArgumentException("Invalid retained byte count");
        }
        unsynced.put(key, Arrays.copyOf(value, retainedBytes));
    }

    private static byte[] copy(byte[] value) {
        if (value == null) throw new NullPointerException("Disk values must not be null");
        return Arrays.copyOf(value, value.length);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Disk key is required");
    }
}
