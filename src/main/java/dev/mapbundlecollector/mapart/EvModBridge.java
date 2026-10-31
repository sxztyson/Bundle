package dev.mapbundlecollector.mapart;

import java.lang.reflect.Method;

// Optional bridge to Ev's Mod: when it is installed, its MapStateCacher holds every map
// packet the player has ever received (per server, on disk) and can inject them into the
// client world. Maps inside bundles never get their data sent by the server on their own,
// so this is the difference between "renders" and "checkerboard" for previously-seen tiles.
// Pure reflection: no compile-time dependency, and a missing/changed Ev's Mod is a no-op.
public final class EvModBridge {
    private static final String CACHER_CLASS = "net.evmodder.evmod.apis.MapStateCacher";
    private static Method loadMethod;
    private static boolean resolved = false;

    private EvModBridge() {}

    // Returns true if Ev's Mod states were (possibly) injected into the client world.
    public static boolean injectCachedMapStates() {
        try {
            if (!resolved) {
                resolved = true;
                loadMethod = Class.forName(CACHER_CLASS).getMethod("loadMapStatesById");
            }
            if (loadMethod == null) return false;
            loadMethod.invoke(null);
            return true;
        } catch (ClassNotFoundException notInstalled) {
            loadMethod = null;
        } catch (Throwable incompatible) {
            // Ev's Mod internals changed or refused; disable for this session.
            loadMethod = null;
        }
        return false;
    }
}
