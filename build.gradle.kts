import java.util.zip.ZipFile

buildscript {
    repositories { mavenCentral() }
    // ASM on the buildscript classpath so the target-derivation below can read @Mixin annotations from the payload jar.
    dependencies {
        classpath("org.ow2.asm:asm:9.10.1")
        classpath("org.ow2.asm:asm-tree:9.10.1")
    }
}

plugins { java }

// ---------------------------------------------------------------------------------------------------------------------
// mc-graft — late-attach injection engine.
//
// Grafts a *pre-built* Fabric mod jar into a running Minecraft (vanilla / Fabric-Knot / NeoForge) through a JDK dynamic
// agent. The engine compiles against ONLY ASM + Sponge-Mixin + the JDK; every Minecraft/mod/loader interaction is by
// string + reflection at run time, so this build needs no Minecraft on its classpath. The payload mod (its classes +
// owned libraries + mixin config) is supplied to `agentJar` as an input, not compiled here.
// ---------------------------------------------------------------------------------------------------------------------

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "FabricMC" }   // sponge-mixin, fabric-api, fabric-language-kotlin
    // Modrinth Maven for `maven.modrinth:<slug>:<version>` payload-dependency coordinates.
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

val asmVersion = "9.10.1"
val spongeMixin = "net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7"
val mixinExtras = "io.github.llamalad7:mixinextras-common:0.5.4"

// A configuration holding just the framework jars that must ride INTO the agent (ASM + Sponge-Mixin + MixinExtras).
val agentInfra: Configuration by configurations.creating { isTransitive = true }

dependencies {
    val asm = listOf("asm", "asm-tree", "asm-commons", "asm-analysis", "asm-util").map { "org.ow2.asm:$it:$asmVersion" }
    asm.forEach { agentInfra(it) }
    agentInfra(spongeMixin)
    agentInfra(mixinExtras)
}

// Two source sets mirroring the original layout: the standalone Sponge-Mixin service, and the engine that uses it.
sourceSets {
    val mixinservice by creating {
        java.srcDir("mixinservice/src/main/java")
        resources.srcDir("mixinservice/src/main/resources")
        compileClasspath += agentInfra
    }
    val engine by creating {
        java.srcDir("engine/src/main/java")
        compileClasspath += agentInfra + mixinservice.output
    }
}

// Compile the engine + mixin service to Java 25 bytecode. The agent runs on the target client's JVM (Java 21+), and
// Sponge-Mixin derives its CompatibilityLevel from the class-file version it sees — targeting an older release makes
// MixinExtras' @Local LVT generation fail on already-loaded targets. Build with a Java 25 JDK (JAVA_HOME=graalvm-25).
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

// ---------------------------------------------------------------------------------------------------------------------
// agentJar — assemble the self-contained injection agent for a given payload.
//
//   ./gradlew agentJar -Ppayload=/path/to/mod.jar [-PpayloadLibs=/dir/of/lib/jars] [-PmixinTargets=/path/targets.txt]
//                      [-PbootstrapConfig=/path/override.properties] [-PagentName=mymod-inject-agent.jar]
//
// The payload's mixin config names, access-widener and entrypoints are read from its fabric.mod.json; the converter's
// TARGET-class list is auto-derived from the @Mixin annotations of the classes those configs declare. So a plain
// `agentJar -Ppayload=mod.jar` needs nothing else. `-PmixinTargets` overrides the derived list (for a mod whose
// targets need refmap remapping this build can't resolve).
// ---------------------------------------------------------------------------------------------------------------------
tasks.register<Jar>("agentJar") {
    group = "mc-graft"
    description = "Build the late-attach injection agent bundling engine + mixin service + the -Ppayload mod jar."
    dependsOn("mixinserviceClasses", "engineClasses")

    val payloadPath = (project.findProperty("payload") as String?)
        ?: throw GradleException("agentJar requires -Ppayload=/path/to/mod.jar (the Fabric mod to inject)")
    val payloadJar = file(payloadPath)
    if (!payloadJar.isFile) throw GradleException("payload jar not found: $payloadJar")

    archiveFileName.set((project.findProperty("agentName") as String?) ?: "mc-graft-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("agent"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val asmVer = asmVersion
    manifest {
        // Agent-Class ONLY -> dynamic attach only (never usable as an on-load -javaagent).
        attributes("Agent-Class" to "FullInjectAgent", "Can-Retransform-Classes" to "true", "Can-Redefine-Classes" to "true")
        // Mixin's CompatibilityLevel check reads ASM's manifest Implementation-Version; pin it.
        attributes(mapOf("Implementation-Title" to "ASM", "Implementation-Version" to asmVer), "org/objectweb/asm/")
    }

    // Framework at the jar root (system loader); strip rival service files / signatures / module-info.
    from(agentInfra.map { zipTree(it) }) {
        exclude("META-INF/services/**", "module-info.class", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF")
    }
    from(sourceSets["mixinservice"].output)   // vspike.* + Mixin-service SPI registrations
    from(sourceSets["engine"].output)         // FullInjectAgent + converter + lbrt.* at root

    // Derive graft.properties from the payload's fabric.mod.json (mixin configs, access-widener, id, entrypoints), then
    // merge any optional override file (-PbootstrapConfig, e.g. an event-bootstrap for a mod with no entrypoint).
    // Generated in doFirst (execution time) so it survives a `clean agentJar` in the same invocation.
    val graftProps = layout.buildDirectory.file("agent-tmp/graft.properties").get().asFile
    val bootstrapOverride = (project.findProperty("bootstrapConfig") as String?)?.let { file(it) }
    from(graftProps)

    // Converter TARGET-class list, bundled as mixin-targets.txt. By DEFAULT it is derived from the payload's @Mixin
    // annotations (see deriveMixinTargets); pass -PmixinTargets=<file> to override with an explicit list.
    val mixinTargetsOverride = (project.findProperty("mixinTargets") as String?)?.let { f ->
        file(f).also { if (!it.isFile) throw GradleException("mixinTargets not found: $it") }
    }
    val targetsOut = layout.buildDirectory.file("agent-tmp/mixin-targets.txt").get().asFile
    from(targetsOut)

    doFirst {
        graftProps.parentFile.mkdirs()
        graftProps.writeText(deriveGraftProperties(payloadJar, bootstrapOverride))
        val targets = if (mixinTargetsOverride != null) mixinTargetsOverride.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                      else deriveMixinTargets(payloadJar, mixinConfigsOf(payloadJar))
        targetsOut.writeText(targets.joinToString("\n") + "\n")
        println("mc-graft targets: ${targets.size} class(es)" + if (mixinTargetsOverride != null) " (from -PmixinTargets)" else " (auto-derived from @Mixin annotations)")
    }

    // Payload dependencies declared in the properties — `payload.dependencies=<coords>` in the -PbootstrapConfig file
    // (comma-separated Maven coordinates; use maven.modrinth:<slug>:<version> for Modrinth mods) and/or -PpayloadDeps.
    // Resolved from Maven (Central / FabricMC / Modrinth) and bundled non-transitively; the agent stages them AND their
    // jar-in-jar contents onto the loader, so a payload gets the dependency mods it needs even on a bare instance.
    val depCoords: List<String> = buildList {
        (project.findProperty("payloadDeps") as String?)?.split(",")?.forEach { c -> c.trim().takeIf(String::isNotEmpty)?.let { add(it) } }
        bootstrapOverride?.takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.trim().startsWith("payload.dependencies=") }
            ?.substringAfter("=")?.split(",")?.forEach { c -> c.trim().takeIf(String::isNotEmpty)?.let { add(it) } }
    }.distinct()
    val depFiles: FileCollection =
        if (depCoords.isEmpty()) files()
        else configurations.detachedConfiguration(*depCoords.map { dependencies.create(it) }.toTypedArray())
            .also { it.isTransitive = false }
    if (depCoords.isNotEmpty()) logger.lifecycle("mc-graft payload.dependencies: $depCoords")

    // Payload mod jar (as agent-libs/payload.jar) + declared dependency jars + any extra lib jars it owns.
    into("agent-libs") {
        from(payloadJar) { rename { "payload.jar" } }
        from(depFiles)
        (project.findProperty("payloadLibs") as String?)?.let { libs ->
            val d = file(libs)
            if (d.isDirectory) from(fileTree(d) { include("*.jar") }) else from(file(libs))
        }
    }

    doLast { logger.lifecycle("mc-graft agent -> ${archiveFile.get().asFile} (payload: ${payloadJar.name})") }
}

// Reusable, payload-INDEPENDENT agent base: engine + mixin service + ASM/Mixin, with the agent manifest — but NO
// payload, graft.properties, or mixin-targets.txt. The `ctl` Rust packer splices a payload jar + its derived
// graft.properties/mixin-targets + Maven deps into this to produce a full agent jar WITHOUT a JDK (build this once).
tasks.register<Jar>("agentBase") {
    group = "mc-graft"
    description = "Build the payload-independent agent base (for the JDK-free ctl packer)."
    dependsOn("mixinserviceClasses", "engineClasses")
    archiveFileName.set("mc-graft-base.jar")
    destinationDirectory.set(layout.buildDirectory.dir("agent"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val asmVer = asmVersion
    manifest {
        attributes("Agent-Class" to "FullInjectAgent", "Can-Retransform-Classes" to "true", "Can-Redefine-Classes" to "true")
        attributes(mapOf("Implementation-Title" to "ASM", "Implementation-Version" to asmVer), "org/objectweb/asm/")
    }
    from(agentInfra.map { zipTree(it) }) {
        exclude("META-INF/services/**", "module-info.class", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF")
    }
    from(sourceSets["mixinservice"].output)
    from(sourceSets["engine"].output)
    doLast { logger.lifecycle("mc-graft base -> ${archiveFile.get().asFile}") }
}

// ---------------------------------------------------------------------------------------------------------------------
// Read the payload's fabric.mod.json and emit graft.properties (the engine's PayloadConfig). Any optional override file
// (an event-bootstrap / readiness probe for a mod with no entrypoint, e.g. LiquidBounce) is appended verbatim.
// ---------------------------------------------------------------------------------------------------------------------
fun deriveGraftProperties(payloadJar: File, bootstrapOverride: File?): String {
    val json = ZipFile(payloadJar).use { zf ->
        val e = zf.getEntry("fabric.mod.json")
            ?: throw GradleException("payload ${payloadJar.name} has no fabric.mod.json — is it a Fabric mod?")
        zf.getInputStream(e).bufferedReader().readText()
    }
    @Suppress("UNCHECKED_CAST")
    val fmj = groovy.json.JsonSlurper().parseText(json) as Map<String, Any?>
    val id = (fmj["id"] as? String) ?: "payload"
    val aw = (fmj["accessWidener"] as? String) ?: ""
    val mixins = (fmj["mixins"] as? List<*>)?.mapNotNull { m ->
        when (m) { is String -> m; is Map<*, *> -> m["config"] as? String; else -> null }
    } ?: emptyList()
    val eps = fmj["entrypoints"] as? Map<*, *> ?: emptyMap<String, Any?>()
    fun epList(key: String): List<String> = (eps[key] as? List<*>)?.mapNotNull { e ->
        when (e) { is String -> e; is Map<*, *> -> e["value"] as? String; else -> null }
    } ?: emptyList()

    val sb = StringBuilder()
    sb.appendLine("# generated by mc-graft from ${payloadJar.name} :: fabric.mod.json")
    sb.appendLine("payload.id=$id")
    sb.appendLine("payload.accessWidener=$aw")
    sb.appendLine("payload.mixins=${mixins.joinToString(",")}")
    sb.appendLine("payload.entrypoints.main=${epList("main").joinToString(",")}")
    sb.appendLine("payload.entrypoints.client=${epList("client").joinToString(",")}")
    if (bootstrapOverride != null) {
        if (!bootstrapOverride.isFile) throw GradleException("bootstrapConfig not found: $bootstrapOverride")
        sb.appendLine("# --- override: ${bootstrapOverride.name} ---")
        sb.append(bootstrapOverride.readText())
        if (!bootstrapOverride.readText().endsWith("\n")) sb.appendLine()
    }
    println("mc-graft config: id=$id aw=${if (aw.isEmpty()) "(none)" else aw} mixins=$mixins " +
        "entrypoints(main=${epList("main")}, client=${epList("client")})" +
        if (bootstrapOverride != null) " +override(${bootstrapOverride.name})" else "")
    return sb.toString()
}

// ---------------------------------------------------------------------------------------------------------------------
// Auto-derive the converter's TARGET-class list from the payload's mixins: for each mixin config, read the mixin
// classes it declares and pull the target types out of their @Mixin(value=..., targets=...) annotation. Covers the
// common case (mods that use @Mixin(Target.class) and/or official-mapping target strings); pass -PmixinTargets to
// override for mods whose targets need refmap remapping the build can't resolve.
// ---------------------------------------------------------------------------------------------------------------------
fun mixinConfigsOf(payloadJar: File): List<String> {
    val json = ZipFile(payloadJar).use { zf ->
        val e = zf.getEntry("fabric.mod.json") ?: return emptyList()
        zf.getInputStream(e).bufferedReader().readText()
    }
    @Suppress("UNCHECKED_CAST")
    val fmj = groovy.json.JsonSlurper().parseText(json) as Map<String, Any?>
    return (fmj["mixins"] as? List<*>)?.mapNotNull { m ->
        when (m) { is String -> m; is Map<*, *> -> m["config"] as? String; else -> null }
    } ?: emptyList()
}

fun deriveMixinTargets(payloadJar: File, mixinConfigs: List<String>): List<String> {
    val targets = linkedSetOf<String>()
    ZipFile(payloadJar).use { zf ->
        for (cfg in mixinConfigs) {
            val ce = zf.getEntry(cfg) ?: continue
            @Suppress("UNCHECKED_CAST")
            val json = groovy.json.JsonSlurper().parse(zf.getInputStream(ce)) as Map<String, Any?>
            val pkg = (json["package"] as? String)?.replace('.', '/') ?: continue
            val names = listOf("mixins", "client", "server").flatMap { k ->
                (json[k] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            }
            for (mn in names) {
                val me = zf.getEntry("$pkg/${mn.replace('.', '/')}.class") ?: continue
                val cn = org.objectweb.asm.tree.ClassNode()
                org.objectweb.asm.ClassReader(zf.getInputStream(me).readBytes())
                    .accept(cn, org.objectweb.asm.ClassReader.SKIP_CODE or org.objectweb.asm.ClassReader.SKIP_DEBUG or org.objectweb.asm.ClassReader.SKIP_FRAMES)
                val anns = (cn.invisibleAnnotations ?: emptyList()) + (cn.visibleAnnotations ?: emptyList())
                val mixin = anns.firstOrNull { it.desc == "Lorg/spongepowered/asm/mixin/Mixin;" } ?: continue
                val vals = mixin.values ?: continue
                var i = 0
                while (i + 1 < vals.size) {
                    val key = vals[i] as? String; val v = vals[i + 1]; i += 2
                    when (key) {
                        "value" -> (v as? List<*>)?.forEach { t ->
                            if (t is org.objectweb.asm.Type && t.sort == org.objectweb.asm.Type.OBJECT) targets.add(t.className)
                        }
                        "targets" -> (v as? List<*>)?.forEach { t ->
                            if (t is String && t.isNotBlank()) targets.add(t.replace('/', '.'))   // official-mapping / already-production names
                        }
                    }
                }
            }
        }
    }
    return targets.sorted()
}
