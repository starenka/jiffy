import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gitRev = providers.exec {
    commandLine("git", "describe", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

android {
    namespace = "cz.starenka.jiffy"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "cz.starenka.jiffy"
        minSdk = 30
        targetSdk = 36
        // Overridden by the release workflow (see .github/workflows/release.yml).
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
        buildConfigField("String", "GIT_REV", "\"${gitRev.get()}\"")
        buildConfigField("String", "BUILD_TIME",
            "\"${OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}\"")
    }
    buildFeatures { buildConfig = true }
    // Release signing comes from the environment so no secrets live in the repo:
    // JIFFY_KEYSTORE (path), JIFFY_KEYSTORE_PASSWORD, JIFFY_KEY_ALIAS, JIFFY_KEY_PASSWORD.
    val releaseKeystore = System.getenv("JIFFY_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("JIFFY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("JIFFY_KEY_ALIAS")
            keyPassword = System.getenv("JIFFY_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*",
                "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/*.kotlin_module",
                "META-INF/BCKEY.*", "META-INF/AL2.0", "META-INF/LGPL2.1",
            )
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's org.json is stubbed in JVM unit tests
    implementation("org.apache.sshd:sshd-core:2.15.0")
    implementation("org.apache.sshd:sshd-sftp:2.15.0")
    implementation("org.apache.sshd:sshd-mina:2.20.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("org.slf4j:slf4j-android:1.7.36")
}
