package com.pratham.optimizeserver.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages conservative safe mode state when unknown platforms, repeated errors, or administrator overrides occur.
 */
public final class SafeModeController {

    private final AtomicBoolean safeModeActive = new AtomicBoolean(false);
    private final List<String> activationReasons = new CopyOnWriteArrayList<String>();

    public SafeModeController(boolean initialSafeMode, String initialReason) {
        if (initialSafeMode) {
            enterSafeMode(initialReason != null ? initialReason : "Initial compatibility policy");
        }
    }

    public void enterSafeMode(String reason) {
        safeModeActive.set(true);
        if (reason != null && !reason.trim().isEmpty() && !activationReasons.contains(reason)) {
            activationReasons.add(reason);
        }
    }

    public void exitSafeMode() {
        safeModeActive.set(false);
        activationReasons.clear();
    }

    public boolean isSafeModeActive() {
        return safeModeActive.get();
    }

    public List<String> getActivationReasons() {
        return Collections.unmodifiableList(new ArrayList<String>(activationReasons));
    }
}

