plugins {
    id("com.android.application")
}

val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("SCROLL_STARVA_RELEASE_STORE_FILE"))
    .orNull
val releaseStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("SCROLL_STARVA_RELEASE_STORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("SCROLL_STARVA_RELEASE_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("SCROLL_STARVA_RELEASE_KEY_PASSWORD"))
    .orNull

android {
    namespace = "com.scrollstarva.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scrollstarva.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null &&
                releaseStorePassword != null &&
                releaseKeyAlias != null &&
                releaseKeyPassword != null
            ) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        val missing = buildList {
            if (releaseStoreFile.isNullOrBlank()) add("SCROLL_STARVA_RELEASE_STORE_FILE")
            if (releaseStorePassword.isNullOrBlank()) add("SCROLL_STARVA_RELEASE_STORE_PASSWORD")
            if (releaseKeyAlias.isNullOrBlank()) add("SCROLL_STARVA_RELEASE_KEY_ALIAS")
            if (releaseKeyPassword.isNullOrBlank()) add("SCROLL_STARVA_RELEASE_KEY_PASSWORD")
        }
        check(missing.isEmpty()) {
            "Release signing is not configured. Set these environment variables or matching " +
                "Gradle properties: ${missing.joinToString()}"
        }
        check(file(requireNotNull(releaseStoreFile)).isFile) {
            "Release keystore does not exist: $releaseStoreFile"
        }
    }
}

tasks.configureEach {
    if (name == "assembleRelease" ||
        name == "bundleRelease" ||
        name == "validateSigningRelease" ||
        name == "packageRelease" ||
        name == "packageReleaseBundle"
    ) {
        dependsOn(validateReleaseSigning)
    }
}
