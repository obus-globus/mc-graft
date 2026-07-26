import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One injected payload's identity, plus that payload's own copy of the tables the converter and the CFT read.
 *
 * <p>Before this existed, every generated name was a pure function of (target class, member) and every global table was
 * keyed by target alone, so a second payload targeting the same Minecraft class silently masked or corrupted the first
 * (docs/multi-payload.md §1.3/§1.4). {@code key} is the component that makes those names and keys per-payload.
 *
 * <p>{@link RetransformConverter} keeps the registry of live scopes and materializes the UNION of every scope's tables
 * into its own static fields, so the hot CFT read paths stay a single map/set lookup rather than a fan-out over scopes.
 * The per-scope tables here are the source of truth; the unions are derived (see {@link RetransformConverter#republish}).
 */
public final class PayloadScope {
    /** The payload's own id, verbatim from {@code payload.id} in its {@code graft.properties}. */
    public final String id;
    /** {@link #id} reduced to characters that are legal inside a JVM class name, and made unique across live scopes.
     *  Mod ids legally contain characters a class name cannot (`fabric-api`, `do-a-barrel-roll`), so the raw id cannot
     *  be pasted into a sidecar name. Assigned by {@link RetransformConverter#newScope}, never by a caller. */
    public final String key;

    // ---- this payload's tables ------------------------------------------------------------------------------------
    // Each is assigned wholesale (never mutated in place after publication) and then unioned by
    // RetransformConverter.republish(). null means "this payload has not produced this table yet", which the union
    // preserves so RetransformConverter.GADDED/GFIELD/GIFACE keep their "not populated yet" null contract.
    volatile Map<String,String[]> gadded, gfield;                  // keys are scope-qualified (RetransformConverter.gkey)
    volatile Map<String,List<String[]>> giface;                    // iface -> [target, sidecar, scope key]
    volatile Set<String> payloadClasses = Set.of();
    volatile Set<String> callerRewritePrefixes = Set.of();
    volatile Set<String> sharedIfaces = Set.of();

    PayloadScope(String id, String key) { this.id = id; this.key = key; }

    /** Replace every character outside {@code [A-Za-z0-9_]} with {@code _} so the result can appear inside a class
     *  name. NOT injective on its own ({@code a-b} and {@code a_b} both map to {@code a_b}); uniqueness across live
     *  payloads is enforced separately by {@link RetransformConverter#newScope}. */
    public static String sanitize(String id) {
        if (id == null || id.isBlank()) return "payload";
        StringBuilder b = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            b.append(ok ? c : '_');
        }
        return b.toString();
    }

    @Override public String toString() { return id.equals(key) ? id : id + " (" + key + ")"; }
}
