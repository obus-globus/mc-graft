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
    // Client entrypoints of APPLIED dependency modules (e.g. indigo's renderer registration), invoked best-effort
    // before the payload's so their setup is ready — a non-essential one that fails must not abort the payload.
    public final List<String> depClientEntrypoints;
    // Mixin configs from the payload's DEPENDENCY mods (e.g. fabric-api's own modules) that must also be applied under
    // late-attach — normally these apply at startup, but a late-attached fabric-api never patched the loaded MC classes.
    public final List<String> depMixinConfigs;
    // built-in registries the payload writes into during init (e.g. minecraft:sound_event) — thawed narrowly and kept
    // open across a resource reload, instead of thawing every registry (which collides with the reload's re-freeze).
    public final List<String> thawRegistries;
    // event-bootstrap override (null when absent -> the agent calls the declared entrypoints instead)
    public final String evtManager, evtManagerInstance, evtBase, evtStart, evtStartInstance, evtCall;
    // optional readiness probe for an ASYNC event-bootstrap (the target retransforms are published only once the mod
    // reports ready); null readyClass -> the bootstrap is treated as synchronous (publish immediately after the kick)
    public final String readyClass, readyInstance, readyMethod;
    // some mods defer their real init to a resource-reload listener registered during the start event; on late-attach
    // the boot-time reload has passed, so trigger one to drive that init. (bootstrap.reloadResources=true)
    public final boolean reloadResources;
    // internal-name prefixes (e.g. "com/example/mod/") that the payload's OWN classes live under — derived by the build
    // from the payload jar. Used to scope the caller-rewrite preflight + the already-loaded-payload snapshot so the
    // engine makes no assumption about any one mod's package. Empty -> the engine falls back to the payload class set.
    public final List<String> payloadPackages;
    // Internal-name prefixes of bundled library mods (e.g. xaero/lib) whose OWN runtime casts to duck interfaces their
    // mixins add to MC classes; those library classes must be caller-rewritten too (payload.callerRewrite.packages).
    public final List<String> callerRewritePackages;
    // Internal-name prefixes of the DEPENDENCY runtime (e.g. net/fabricmc/fabric) to caller-rewrite when dependency
    // mixins are applied — their own code casts MC objects to the duck interfaces those mixins add. Empty -> when
    // dependency mixins are applied, defaults to fabric-api's namespace. (bootstrap.depCallerRewrite.packages)
    public final List<String> depCallerRewritePackages;
    // a system property the payload reads to locate its data/game directory (e.g. a mod that reads "vspike.gameDir").
    // null -> the injector does not set any such property. (bootstrap.gameDirProperty=...)
    public final String gameDirProperty;
    // MC screen internal name whose join path is gated until the payload reports ready, so a multiplayer connect issued
    // mid-attach is deferred rather than racing a half-initialized client. null -> the join-gate feature is disabled.
    // (bootstrap.joinGate.screen=net/minecraft/client/gui/screens/ConnectScreen)
    public final String joinGateScreen;
    // (Fabric) register the payload as a synthetic ModContainer so a mod that reads its own metadata at init (e.g.
    // FabricLoader.getModContainer(id).orElseThrow()) works on late-attach. (bootstrap.registerModContainer=true)
    public final boolean registerModContainer;
    // (Fabric) reopen fabric-api's keybinding registry for late registration. fabric-api's KeyMappingRegistryImpl
    // refuses registerKeyMapping once GameOptions is initialised; a mod that registers keybinds in its (deferred)
    // client init would throw. With this set, mc-graft strips that guard and splices late-registered keybinds into the
    // live options so they function and appear in Controls. (bootstrap.reopenKeyBindings=true)
    public final boolean reopenKeyBindings;
    // (Fabric) bridge fabric-api's PayloadTypeRegistry into MC's live custom-payload codec dispatch, so a mod that
    // registers a custom packet type (e.g. Xaero's xaerominimap:main) can encode/decode it under late-attach without
    // fabric-api's own networking mixins being applied. (bootstrap.bridgePayloadCodecs=true)
    public final boolean bridgePayloadCodecs;

    private PayloadConfig(Properties p) {
        id = p.getProperty("payload.id", "payload").trim();
        String aw = p.getProperty("payload.accessWidener", "").trim();
        accessWidener = aw.isEmpty() ? null : aw;
        mixinConfigs = csv(p.getProperty("payload.mixins", ""));
        depMixinConfigs = csv(p.getProperty("payload.mixins.deps", ""));
        List<String> nf = csv(p.getProperty("payload.mixins.neoforge", ""));
        neoforgeMixins = nf.isEmpty() ? mixinConfigs : nf;   // NeoForge-patched MC needs its own companion set if provided
        mainEntrypoints = csv(p.getProperty("payload.entrypoints.main", ""));
        clientEntrypoints = csv(p.getProperty("payload.entrypoints.client", ""));
        depClientEntrypoints = csv(p.getProperty("payload.entrypoints.depClient", ""));
        evtManager = blankToNull(p.getProperty("bootstrap.event.manager", ""));
        evtManagerInstance = p.getProperty("bootstrap.event.managerInstance", "INSTANCE").trim();
        evtBase = blankToNull(p.getProperty("bootstrap.event.base", ""));
        evtStart = blankToNull(p.getProperty("bootstrap.event.start", ""));
        evtStartInstance = p.getProperty("bootstrap.event.startInstance", "INSTANCE").trim();
        evtCall = p.getProperty("bootstrap.event.call", "callEvent").trim();
        readyClass = blankToNull(p.getProperty("bootstrap.ready.class", ""));
        readyInstance = p.getProperty("bootstrap.ready.instance", "INSTANCE").trim();
        readyMethod = p.getProperty("bootstrap.ready.method", "isInitialized").trim();
        reloadResources = Boolean.parseBoolean(p.getProperty("bootstrap.reloadResources", "false").trim());
        thawRegistries = csv(p.getProperty("bootstrap.thawRegistries", ""));
        payloadPackages = csv(p.getProperty("payload.packages", ""));
        callerRewritePackages = csv(p.getProperty("payload.callerRewrite.packages", ""));
        depCallerRewritePackages = csv(p.getProperty("bootstrap.depCallerRewrite.packages", ""));
        gameDirProperty = blankToNull(p.getProperty("bootstrap.gameDirProperty", ""));
        joinGateScreen = blankToNull(p.getProperty("bootstrap.joinGate.screen", ""));
        registerModContainer = Boolean.parseBoolean(p.getProperty("bootstrap.registerModContainer", "false").trim());
        reopenKeyBindings = Boolean.parseBoolean(p.getProperty("bootstrap.reopenKeyBindings", "false").trim());
        bridgePayloadCodecs = Boolean.parseBoolean(p.getProperty("bootstrap.bridgePayloadCodecs", "false").trim());
    }

    /** True when the payload owns a class under one of its declared package roots. Empty roots -> false (callers then
     *  fall back to the exact payload class set). Internal names ("com/example/mod/Foo"). */
    public boolean ownsClass(String internalName) {
        for (String pkg : payloadPackages) if (internalName.startsWith(pkg)) return true;
        return false;
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
