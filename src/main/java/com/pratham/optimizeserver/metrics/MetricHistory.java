package com.pratham.optimizeserver.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded circular history of immutable {@link MetricSnapshot} records.
 */
public final class MetricHistory {

    private MetricSnapshot[] ring;
    private int capacity;
    private int head = 0;
    private int size = 0;
    private volatile MetricSnapshot latest;

    public MetricHistory(int capacity) {
        this.capacity = Math.max(16, capacity);
        this.ring = new MetricSnapshot[this.capacity];
    }

    public synchronized void append(MetricSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        ring[head] = snapshot;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
        this.latest = snapshot;
    }

    public MetricSnapshot getLatest() {
        return latest;
    }

    public synchronized List<MetricSnapshot> getRecent(int maxCount) {
        if (size == 0 || maxCount <= 0) {
            return Collections.emptyList();
        }
        int count = Math.min(size, maxCount);
        List<MetricSnapshot> result = new ArrayList<MetricSnapshot>(count);
        int startIdx = (head - count + capacity) % capacity;
        for (int i = 0; i < count; i++) {
            int idx = (startIdx + i) % capacity;
            MetricSnapshot snap = ring[idx];
            if (snap != null) {
                result.add(snap);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized void resizeCapacity(int newCapacity) {
        int safeCap = Math.max(16, newCapacity);
        if (safeCap == this.capacity) {
            return;
        }
        List<MetricSnapshot> existing = getRecent(safeCap);
        this.capacity = safeCap;
        this.ring = new MetricSnapshot[safeCap];
        this.head = 0;
        this.size = 0;
        for (MetricSnapshot snap : existing) {
            append(snap);
        }
    }

    public synchronized int getSize() {
        return size;
    }

    public synchronized int getCapacity() {
        return capacity;
    }

    public synchronized void clear() {
        for (int i = 0; i < capacity; i++) {
            ring[i] = null;
        }
        head = 0;
        size = 0;
        latest = null;
    }
}

