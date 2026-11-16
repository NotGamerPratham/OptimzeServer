package com.pratham.optimizeserver.compatibility;

import java.lang.reflect.Method;

/**
 * Module-safe reflective access to the extended HotSpot/OpenJ9 management beans
 * ({@code com.sun.management.OperatingSystemMXBean}, {@code com.sun.management.ThreadMXBean}).
 *
 * <p>The beans' implementation classes live in non-exported JDK packages on Java 9+, so methods looked
 * up on {@code bean.getClass()} throw {@code IllegalAccessException} on invoke (and {@code setAccessible}
 * throws on Java 16+). Resolving the same methods on the exported interface works on every Java version.</p>
 */
public final class ManagementBeans {

    public static final String OS_BEAN_INTERFACE = "com.sun.management.OperatingSystemMXBean";
    public static final String THREAD_BEAN_INTERFACE = "com.sun.management.ThreadMXBean";

    private ManagementBeans() {
    }

    /**
     * Returns the exported management interface when the bean implements it, else the bean's own class.
     */
    public static Class<?> accessibleType(Object bean, String interfaceName) {
        if (bean == null) {
            return null;
        }
        ClassLoader[] loaders = {bean.getClass().getClassLoader(), ManagementBeans.class.getClassLoader()};
        for (ClassLoader loader : loaders) {
            try {
                Class<?> itf = Class.forName(interfaceName, false, loader);
                if (itf.isInstance(bean)) {
                    return itf;
                }
            } catch (Throwable ignored) {
            }
        }
        return bean.getClass();
    }

    /**
     * Resolves a public method and, for no-arg accessors, proves it is invocable once so a broken
     * accessor is discarded up front instead of throwing on every sample.
     *
     * @return the usable method, or {@code null}
     */
    public static Method findUsable(Object bean, Class<?> type, String name, Class<?>... params) {
        if (bean == null || type == null) {
            return null;
        }
        try {
            Method m = type.getMethod(name, params);
            if (type == bean.getClass()) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
            }
            if (params.length == 0) {
                m.invoke(bean);
            }
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
