import java.net.URI

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "ai.deepdraw"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

// Built against a recent platform, but emitted for the oldest one `sinceBuild`
// claims — 2023.3 runs on Java 17, and bytecode it cannot read would fail there
// at class-loading time rather than at build time. The toolchain stays 21
// because the platform's own classes are Java 21.
kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/**
 * Brings the DeepDraw library into the plugin's resources.
 *
 * It is *downloaded*, not vendored: the library publishes every build to a CDN
 * under an immutable version path (deepdraw CLAUDE.md §3), and that is the
 * supported way to embed it. Pinning `deepdrawVersion` means this plugin builds
 * the same on a laptop and on a release runner, and moving to a new library is
 * one number in `gradle.properties`.
 *
 * `DEEPDRAW_LIB_DIR` points at a local `lib/dist` instead, which is the only
 * way to try a library change and a plugin change in one sitting.
 */
val deepdrawLibDir = layout.buildDirectory.dir("deepdraw-lib")

val fetchDeepDrawLib by tasks.registering {
    val version = providers.gradleProperty("deepdrawVersion")
    val local = providers.environmentVariable("DEEPDRAW_LIB_DIR")
    val out = deepdrawLibDir

    // `template.html` is the standalone page with three holes in it, and it is
    // what makes writing a `.deepdraw.html` a string replacement rather than a
    // second copy of DeepDraw's export page living in this repo. LICENSE and
    // NOTICE travel with the bundle because its licence asks them to.
    val files = listOf("deepdraw.js", "template.html", "LICENSE", "NOTICE")

    inputs.property("version", version)
    inputs.property("local", local.orElse(""))
    outputs.dir(out)

    doLast {
        val target = out.get().asFile
        target.mkdirs()
        val from = local.orNull
        if (from != null) {
            // CI copies the licence and the notice next to the bundle when it
            // publishes; a local `dist` has only what the build wrote, so they
            // are taken from the library directory above it.
            for (name in files) {
                val source = listOf(File(from, name), File(File(from).parentFile, name)).firstOrNull { it.isFile }
                    ?: throw GradleException("$name is not in $from — run `npm run build` in the deepdraw checkout first")
                source.copyTo(File(target, name), overwrite = true)
                logger.lifecycle("copied $name from ${source.parent}")
            }
        } else {
            val base = "https://deepdrawjs.blob.core.windows.net/release/v${version.get()}"
            for (name in files) {
                val bytes = try {
                    URI("$base/$name").toURL().readBytes()
                } catch (e: Exception) {
                    throw GradleException(
                        "$base/$name could not be fetched (${e.message}).\n" +
                            "A version is on the CDN once CI has published it. To build against one that " +
                            "is not yet, point DEEPDRAW_LIB_DIR at a local lib/dist.",
                    )
                }
                File(target, name).writeBytes(bytes)
                logger.lifecycle("fetched $name  ${"%.1f".format(bytes.size / 1024.0)}kb")
            }
        }

        // A template that has lost a mark writes a broken page, and it would be
        // found out by whoever saved a drawing rather than by whoever built the
        // plugin.
        val template = File(target, "template.html").readText()
        for (mark in listOf("__DEEPDRAW_TITLE__", "__DEEPDRAW_DOCUMENT_JSON__", "__DEEPDRAW_CREDIT__")) {
            val found = template.split(mark).size - 1
            if (found != 1) throw GradleException("template.html holds the $mark mark $found times, not once")
        }
    }
}

tasks.processResources {
    dependsOn(fetchDeepDrawLib)
    from(deepdrawLibDir) { into("deepdraw-web/lib") }
}

intellijPlatform {
    pluginConfiguration {
        id = "ai.deepdraw.jetbrains"
        name = "DeepDraw"
        version = providers.gradleProperty("pluginVersion")
        vendor {
            name = "philter87"
            url = "https://deepdraw.ai"
        }
        ideaVersion {
            // JCEF, which the whole editor stands on, and the platform APIs
            // used here are all present from 2023.3 onward.
            sinceBuild = "233"
            untilBuild = provider { null }
        }
    }

    /**
     * `sinceBuild` is a promise about IDEs this build never sees, so it is
     * checked rather than asserted: the verifier reads the plugin against the
     * oldest one it claims and the one it is built on.
     */
    pluginVerification {
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2023.3.8")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.5")
        }
    }
}

tasks.test {
    useJUnit()
}
