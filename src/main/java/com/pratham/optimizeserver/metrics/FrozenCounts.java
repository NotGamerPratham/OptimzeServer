package com.pratham.optimizeserver.metrics;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable count maps that {@link MetricSnapshot} can share by reference instead of copying.
 *
 * <p>Monitors publish a fresh breakdown only every world sample (5-20 s) while snapshots are taken every
 * second and up to 900 are retained, so sharing one frozen instance across consecutive snapshots removes
 * hundreds of redundant HashMap copies from the plugin's steady-state heap.</p>
 */
public final class FrozenCounts {

    private FrozenCounts() {
    }

    /**
     * Freezes a map the caller has just built and will never touch again (no defensive copy).
     */
    public static Map<String, Integer> adopt(Map<String, Integer> ownedFreshMap) {
        if (ownedFreshMap == null || ownedFreshMap.isEmpty()) {
            return Collections.emptyMap();
        }
        if (ownedFreshMap instanceof Frozen) {
            return ownedFreshMap;
        }
        return new Frozen(ownedFreshMap);
    }

    /**
     * Returns {@code source} itself when it is already frozen (or the shared empty map),
     * otherwise a frozen defensive copy.
     */
    public static Map<String, Integer> share(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        if (source instanceof Frozen) {
            return source;
        }
        return new Frozen(new HashMap<String, Integer>(source));
    }

    public static boolean isFrozen(Map<String, Integer> map) {
        return map instanceof Frozen;
    }

    private static final class Frozen extends AbstractMap<String, Integer> {
        private final Map<String, Integer> view;

        Frozen(Map<String, Integer> owned) {
            this.view = Collections.unmodifiableMap(owned);
        }

        @Override
        public Set<Entry<String, Integer>> entrySet() {
            return view.entrySet();
        }

        @Override
        public Integer get(Object key) {
            return view.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return view.containsKey(key);
        }

        @Override
        public int size() {
            return view.size();
        }
    }
}
