package mcgraft.rt;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Make the payload's own {@code assets/<ns>/lang/*.json} visible to the running client.
 *
 *  <p>Registering a synthetic ModContainer (bootstrap.registerModContainer) is what lets the mod's self-metadata
 *  lookups resolve, but it does NOT put the mod's resources in front of the game: the client's PackRepository was
 *  scanned, and its MultiPackResourceManager built, long before the payload existed. So the payload's namespace is
 *  absent from the live resource manager and every translation key it ships renders raw — a late-registered keybind
 *  shows up in Controls as {@code zoomify.key.zoom} instead of "Zoom (Hold)".
 *
 *  <p>The obvious repair — drive a full resource reload — is the one thing we must not do. A reload under late attach
 *  necessarily runs before the payload's mixins are published, so it tears down state the payload's own reload hooks
 *  would have rebuilt; that is what wiped LiquidBounce's compiled render pipelines. This instead builds a THROWAWAY
 *  resource manager from the live packs plus the payload's pack and runs only the language reload listener against it.
 *  The live resource manager is never mutated and no other listener (shaders, models, atlases) runs, so there is
 *  nothing for it to destroy.
 *
 *  <p>Scope, stated plainly: this fixes translations only. The payload's textures still do not resolve from the live
 *  resource manager, because that would require rebuilding it — the expensive, destructive operation this exists to
 *  avoid. A payload that needs its textures live still has to ask for {@code bootstrap.reloadResources=true}. */
public final class PayloadResources {
    private PayloadResources() {}

    /** Queue a language refresh onto the client thread. Called right after the synthetic ModContainer is registered,
     *  which happens before the payload's bootstrap is scheduled — so this lands ahead of the payload's entrypoints. */
    public static void refreshLanguage(Path payloadJar, String payloadId) {
        try {
            ClassLoader cl = Platform.LOADER;
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft", false, cl);
            Object mc = mcCls.getMethod("getInstance").invoke(null);
            if (mc == null) return;   // dedicated server or pre-client attach: no language to refresh
            mcCls.getMethod("execute", Runnable.class).invoke(mc,
                (Runnable) () -> { try { refreshOnClientThread(cl, mcCls, mc, payloadJar, payloadId); }
                                   catch (Throwable t) { warn(payloadId, t); } });
        } catch (Throwable t) { warn(payloadId, t); }
    }

    private static void refreshOnClientThread(ClassLoader cl, Class<?> mcCls, Object mc, Path payloadJar, String id)
            throws Exception {
        Class<?> packResources = Class.forName("net.minecraft.server.packs.PackResources", false, cl);
        Class<?> pathPack      = Class.forName("net.minecraft.server.packs.PathPackResources", false, cl);
        Class<?> locInfo       = Class.forName("net.minecraft.server.packs.PackLocationInfo", false, cl);
        Class<?> packSource    = Class.forName("net.minecraft.server.packs.repository.PackSource", false, cl);
        Class<?> packTypeCls   = Class.forName("net.minecraft.server.packs.PackType", false, cl);
        Class<?> component     = Class.forName("net.minecraft.network.chat.Component", false, cl);
        Class<?> multiMgr      = Class.forName("net.minecraft.server.packs.resources.MultiPackResourceManager", false, cl);
        Class<?> resMgr        = Class.forName("net.minecraft.server.packs.resources.ResourceManager", false, cl);
        Object clientResources = enumConst(packTypeCls, "CLIENT_RESOURCES");

        // Expose the payload jar as a pack rooted at its jar-filesystem root, so assets/<ns>/... is where MC expects.
        // The FileSystem is deliberately left open: the pack reads lazily and the jar stays mapped for the process
        // lifetime anyway (Knot has it staged), so closing it here would only risk a use-after-close.
        FileSystem fs = FileSystems.newFileSystem(payloadJar, (ClassLoader) null);
        Object title = component.getMethod("literal", String.class).invoke(null, "mc-graft payload: " + id);
        Object info = locInfo.getConstructor(String.class, component, packSource, Optional.class)
            .newInstance("mcgraft/" + id, title, packSourceConst(packSource), Optional.empty());
        Object pack = pathPack.getConstructor(locInfo, Path.class).newInstance(info, fs.getPath("/"));

        @SuppressWarnings("unchecked")
        Set<String> payloadNs = (Set<String>) packResources.getMethod("getNamespaces", packTypeCls)
            .invoke(pack, clientResources);
        if (payloadNs == null || payloadNs.isEmpty()) return;   // payload ships no assets/ — nothing to translate

        Object live = mcCls.getMethod("getResourceManager").invoke(mc);
        @SuppressWarnings("unchecked")
        List<Object> packs = new ArrayList<>(((Stream<Object>) live.getClass().getMethod("listPacks").invoke(live)).toList());
        packs.add(pack);   // last wins, so the payload cannot shadow a resource pack the user actually selected

        // NOTE: never close this manager. MultiPackResourceManager#close closes every pack it was given, which here
        // includes the game's live packs.
        Object temp = multiMgr.getConstructor(packTypeCls, List.class).newInstance(clientResources, packs);
        Object lm = mcCls.getMethod("getLanguageManager").invoke(mc);
        lm.getClass().getMethod("onResourceManagerReload", resMgr).invoke(lm, temp);
        InjectionLogger.info("refreshed language data from the payload's resource pack (namespaces=" + payloadNs
            + "); its translation keys now resolve in-game");
    }

    private static Object enumConst(Class<?> enumCls, String name) throws Exception {
        Field f = enumCls.getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }

    /** Prefer the named BUILT_IN source; fall back to any PackSource constant so a rename does not break the feature. */
    private static Object packSourceConst(Class<?> packSource) throws Exception {
        try { return enumConst(packSource, "BUILT_IN"); } catch (NoSuchFieldException ignored) { }
        for (Field f : packSource.getDeclaredFields())
            if (Modifier.isStatic(f.getModifiers()) && packSource.isAssignableFrom(f.getType())) {
                f.setAccessible(true); return f.get(null);
            }
        throw new NoSuchFieldException("no PackSource constant on " + packSource.getName());
    }

    private static void warn(String id, Throwable t) {
        InjectionLogger.warn("language refresh for '" + id + "' failed (its translation keys will render raw): " + t);
    }
}
