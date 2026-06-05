plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningProperties = loadReleaseSigningProperties()

fun loadReleaseSigningProperties(): Map<String, String>? {
    val explicitSigningFile = providers.gradleProperty("SOLARIS_SIGNING_FILE").orNull?.let(::file)
    val signingFile = listOfNotNull(
        explicitSigningFile,
        file("${System.getProperty("user.home")}/.android/solaris-release-signing.txt"),
    ).firstOrNull { it.isFile } ?: return null

    return signingFile.readLines()
        .mapNotNull { line ->
            val trimmed = line.trim().removePrefix("\uFEFF")
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                null
            } else {
                val separatorIndex = listOf(trimmed.indexOf('='), trimmed.indexOf(':'))
                    .filter { it >= 0 }
                    .minOrNull()
                    ?: return@mapNotNull null
                trimmed.substring(0, separatorIndex).trim() to
                    trimmed.substring(separatorIndex + 1).trim()
            }
        }
        .toMap()
}

fun signingValue(props: Map<String, String>, vararg keys: String): String {
    keys.forEach { key ->
        props[key]?.takeIf { it.isNotBlank() }?.let { return it }
    }
    error("Missing release signing property: ${keys.joinToString("/")}")
}

android {
    namespace = "com.apsu.gamestream"
    compileSdk = 36

    signingConfigs {
        releaseSigningProperties?.let { props ->
            create("release") {
                storeFile = file(signingValue(props, "keystore", "storeFile"))
                storePassword = signingValue(props, "storePassword")
                keyAlias = signingValue(props, "alias", "keyAlias")
                keyPassword = props["keyPassword"]?.takeIf { it.isNotBlank() } ?: storePassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.apsu.gamestream"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.3.13750724"

    buildTypes {
        release {
            releaseSigningProperties?.let {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    testImplementation("junit:junit:4.13.2")
}
