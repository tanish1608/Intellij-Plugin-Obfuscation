import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import proguard.gradle.ProGuardTask // Import ProGuardTask

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(17)
}
buildscript {
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.3.2")
    }
}
// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(libs.junit)

    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
        instrumentationTools()
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

// Gradle tasks
tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    // ProGuard task
    register<ProGuardTask>("proguard") {
        group = "build"
        description = "Obfuscate plugin JAR using ProGuard"

        // Declare explicit dependency on the `jar` task or `composedJar` task
        dependsOn("composedJar")

        // Specify input JAR from `composedJar` output
        val inputJar = file("build/libs/IntelliJ Platform Plugin Template-1.0.0.jar")
        injars(inputJar)

        // Specify output JAR
        val outputObfuscatedJar = file("build/obfuscated/output/IntelliJ Platform Plugin Template-1.0.0.jar-obfuscated.jar")
        outjars(outputObfuscatedJar)

        //Java Config
        libraryjars(configurations.compileClasspath.get())

        dontshrink()
        dontoptimize()

        adaptclassstrings("**.xml")
        adaptresourcefilecontents("**.xml")

        // Allow methods with the same signature, except for the return type,
        // to get the same obfuscation name.
        overloadaggressively()

        // Put all obfuscated classes into the nameless root package.
        repackageclasses("")
        dontwarn()

        printmapping("build/obfuscated/output/IntelliJ Platform Plugin Template-1.0.0-ProGuard-ChangeLog.txt")

        target("1.0.1")

        adaptresourcefilenames()
        optimizationpasses(9)
        allowaccessmodification()

        keepattributes("Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod")

        keep("""
            class * implements com.intellij.openapi.components.PersistentStateComponent {*;}
             """.trimIndent()
        )

        keepclassmembers("""
            class * {public static ** INSTANCE;}
             """.trimIndent()
        )
        keep("class com.intellij.util.* {*;}")
    }

    prepareSandbox {

            dependsOn("proguard")
            pluginJar.set(File("build/obfuscated/output/instrumented-IntelliJ Platform Plugin Template-1.0.0.jar"))
        }



    publishPlugin {
        dependsOn(patchChangelog)
        dependsOn("proguard") // Ensure ProGuard runs before publishing
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        // Extract plugin description from README.md
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}