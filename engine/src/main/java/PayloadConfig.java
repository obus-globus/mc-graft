import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Per-payload configuration, derived by the build from the mod's {@code fabric.mod.json} (plus an optional override
 * file) and bundled into the agent jar as {@code graft.properties}. The engine reads this instead of hardcoding any
 * one mod's config, so grafting a different Fabric mod is a matter of pointing the build at its jar.
 *
 * Keys:
 *   payload.id                 mod id (used to scope the live mixin-config downgrade)
 *   payload.accessWidener      access-widener resource name ("" if the mod has none)
 *   payload.mixins             comma-separated mixin config json names
 *   payload.entrypoints.main   comma-separated ModInitializer classes (fabric "main")
 *   payload.entrypoints.client comma-separated ClientModInitializer classes (fabric "client")
 *
 * Optional event-bootstrap override (for mods with no entrypoint that initialize via a mixin-fired event, e.g. LB):
 *   bootstrap.event.manager           EventManager class
 *   bootstrap.event.managerInstance   static field holding the EventManager singleton (default INSTANCE)
 *   bootstrap.event.base              Event base type (callEvent parameter type)
 *   bootstrap.event.start             the "client start" event class to dispatch
 *   bootstrap.event.startInstance     static field holding that event's singleton (default INSTANCE)
 *   bootstrap.event.call              EventManager method that dispatches an event (default callEvent)
 */
public final class PayloadConfig {
    private static volatile PayloadConfig INSTANCE;

    public final String id, accessWidener;
    public final List<String> mixinConfigs, neoforgeMixins, mainEntrypoints, clientEntrypoints;
    // event-bootstrap override (null when absent -> the agent calls the declared entrypoints instead)
    public final String evtManager, evtManagerInstance, evtBase, evtStart, evtStartInstance, evtCall;
    // optional readiness probe for an ASYNC event-bootstrap (the target retransforms are published only once the mod
    // reports ready); null readyClass -> the bootstrap is treated as synchronous (publish immediately after the kick)
    public final String readyClass, readyInstance, readyMethod;

    private PayloadConfig(Properties p) {
        id = p.getProperty("payload.id", "payload").trim();
        String aw = p.getProperty("payload.accessWidener", "").trim();
        accessWidener = aw.isEmpty() ? null : aw;
        mixinConfigs = csv(p.getProperty("payload.mixins", ""));
        List<String> nf = csv(p.getProperty("payload.mixins.neoforge", ""));
        neoforgeMixins = nf.isEmpty() ? mixinConfigs : nf;   // NeoForge-patched MC needs its own companion set if provided
        mainEntrypoints = csv(p.getProperty("payload.entrypoints.main", ""));
        clientEntrypoints = csv(p.getProperty("payload.entrypoints.client", ""));
        evtManager = blankToNull(p.getProperty("bootstrap.event.manager", ""));
        evtManagerInstance = p.getProperty("bootstrap.event.managerInstance", "INSTANCE").trim();
        evtBase = blankToNull(p.getProperty("bootstrap.event.base", ""));
        evtStart = blankToNull(p.getProperty("bootstrap.event.start", ""));
        evtStartInstance = p.getProperty("bootstrap.event.startInstance", "INSTANCE").trim();
        evtCall = p.getProperty("bootstrap.event.call", "callEvent").trim();
        readyClass = blankToNull(p.getProperty("bootstrap.ready.class", ""));
        readyInstance = p.getProperty("bootstrap.ready.instance", "INSTANCE").trim();
        readyMethod = p.getProperty("bootstrap.ready.method", "isInitialized").trim();
    }

    /** True when an async readiness probe is configured (publish retransforms only after the mod reports ready). */
    public boolean hasReadinessProbe() { return readyClass != null; }

    /** True when the payload declares an event-bootstrap override (dispatch an event) instead of a Fabric entrypoint. */
    public boolean hasEventBootstrap() { return evtManager != null && evtStart != null && evtBase != null; }

    /** Load once from the bundled graft.properties bytes; subsequent calls return the cached instance. */
    public static PayloadConfig init(byte[] props) throws Exception {
        Properties p = new Properties();
        if (props != null) p.load(new ByteArrayInputStream(props));
        PayloadConfig c = new PayloadConfig(p);
        INSTANCE = c;
        return c;
    }

    public static PayloadConfig get() {
        PayloadConfig c = INSTANCE;
        if (c == null) throw new IllegalStateException("PayloadConfig not initialized (graft.properties not loaded)");
        return c;
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split(",")) { t = t.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }
    private static String blankToNull(String s) { s = s.trim(); return s.isEmpty() ? null : s; }
}
