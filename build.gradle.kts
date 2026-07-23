import java.util.zip.ZipFile

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
    maven("https://maven.fabricmc.net/") { name = "FabricMC" }   // sponge-mixin
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
//                      [-PagentName=mymod-inject-agent.jar]
//
// The payload's mixin config names, access-widener and client entrypoint are read from its fabric.mod.json at attach
// time (the agent does the derivation); this build only needs the jar(s). `mixinTargets` (the converter's target class
// list) is optional — if omitted the agent derives it from the payload's mixin configs.
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
    doFirst {
        graftProps.parentFile.mkdirs()
        graftProps.writeText(deriveGraftProperties(payloadJar, bootstrapOverride))
    }

    // Optional explicit converter target list (the converter's TARGET class list). Bundled as mixin-targets.txt.
    (project.findProperty("mixinTargets") as String?)?.let { mt ->
        val f = file(mt); if (!f.isFile) throw GradleException("mixinTargets not found: $f")
        from(f) { rename { "mixin-targets.txt" } }
    }

    // Payload mod jar (as agent-libs/payload.jar) + any extra lib jars it owns; the agent stages these onto the live loader.
    into("agent-libs") {
        from(payloadJar) { rename { "payload.jar" } }
        (project.findProperty("payloadLibs") as String?)?.let { libs ->
            val d = file(libs)
            if (d.isDirectory) from(fileTree(d) { include("*.jar") }) else from(file(libs))
        }
    }

    doLast { logger.lifecycle("mc-graft agent -> ${archiveFile.get().asFile} (payload: ${payloadJar.name})") }
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
