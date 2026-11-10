package com.pratham.optimizeserver.optimizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of all registered {@link OptimizationModule} instances.
 */
public final class OptimizationRegistry {

    private final Map<String, OptimizationModule> modules = new LinkedHashMap<String, OptimizationModule>();

    public synchronized void register(OptimizationModule module) {
        if (module != null && module.getId() != null) {
            modules.put(module.getId().toLowerCase(Locale.ROOT), module);
        }
    }

    public synchronized OptimizationModule getModule(String id) {
        if (id == null) {
            return null;
        }
        return modules.get(id.toLowerCase(Locale.ROOT));
    }

    public synchronized Collection<OptimizationModule> getAllModules() {
        return Collections.unmodifiableList(new ArrayList<OptimizationModule>(modules.values()));
    }

    public synchronized List<OptimizationModule> getActiveModules() {
        List<OptimizationModule> active = new ArrayList<OptimizationModule>();
        for (OptimizationModule mod : modules.values()) {
            if (mod.isEnabled()) {
                active.add(mod);
            }
        }
        return Collections.unmodifiableList(active);
    }

    public synchronized List<String> getModuleIds() {
        return Collections.unmodifiableList(new ArrayList<String>(modules.keySet()));
    }
}
