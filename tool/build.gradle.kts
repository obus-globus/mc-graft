import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    application
    id("com.github.spotbugs") version "6.1.7"   // Java linter (bug-pattern static analysis; see config/spotbugs/)
}

// Standalone injector window / attach launcher. Depends only on the JDK (com.sun.tools.attach when the jdk.attach
// module is present) + the bundled jattach binaries + Swing + JNA (for OS window titles). No LiquidBounce dependency,
// which is why it can live as its own Gradle project.
description = "LiquidBounce injector window: attaches the injector agent to a running Minecraft client JVM."

repositories { mavenCentral() }

dependencies {
    // Window-title lookup: User32 (EnumWindows) on Windows, X11 on Linux — no external tools (tasklist/xdotool) needed.
    implementation("net.java.dev.jna:jna-platform:5.17.0")
}

application {
    mainClass = "InjectorUi"
}

tasks.withType<JavaCompile>().configureEach {
    // Target Java 17 bytecode so the tool runs on any Java 17+ runtime (compiled with whatever JDK 17+ runs Gradle).
    options.release = 17
    options.encoding = "UTF-8"
    // JdkAttacher references com.sun.tools.attach; make the jdk.attach module visible to the unnamed module at compile.
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.attach"))
}

// SpotBugs — the Java linter for the standalone tool (same policy as the root build). Java 17 bytecode, so the stock
// tool ASM reads it fine (no version override needed). Run `./gradlew spotbugsMain`; report in build/reports/spotbugs/.
spotbugs {
    toolVersion.set("4.9.3")
    effort.set(Effort.MAX)
    reportLevel.set(Confidence.MEDIUM)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
    ignoreFailures.set(false)
}
tasks.withType<SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
    reports.create("xml") { required.set(false) }
}
// The tool's own classes are Java 17, but SpotBugs also scans REFERENCED JDK classes (Swing, java.lang.*), which are
// v69 when analysis runs on a JDK 25. Force ASM 9.8 (reads v69) onto the spotbugs classpath so those don't error.
configurations.matching { it.name == "spotbugs" }.configureEach {
    resolutionStrategy.eachDependency { if (requested.group == "org.ow2.asm") useVersion("9.8") }
}

tasks.jar {
    // Self-contained jar: fold JNA (+ its bundled natives) in so `java -jar liquidbounce-injector-tool.jar` runs alone.
    archiveFileName = "liquidbounce-injector-tool.jar"
    // Enable-Native-Access grants JNA native access without a warning on Java 22+ (ignored/harmless on older).
    manifest { attributes("Main-Class" to "InjectorUi", "Enable-Native-Access" to "ALL-UNNAMED") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
