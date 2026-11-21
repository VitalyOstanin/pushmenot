buildscript {
    configurations.named("classpath") {
        // Upgrade affected dependencies loaded by the build plugins.
        resolutionStrategy.force(
            "com.google.guava:guava:33.7.2-jre",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcprov-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85",
            "org.jdom:jdom2:2.0.6.1",
        )
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
    kotlin("jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
