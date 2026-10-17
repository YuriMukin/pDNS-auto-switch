import java.util.Properties

plugins {
    id("com.android.application")
}


// Credentials live outside the repository; no debug-key fallback for release builds.
val releaseSigningFile = providers.environmentVariable("PDNS_SIGNING_PROPERTIES")
    .orNull?.let { file(it) }
val releaseSigning = releaseSigningFile?.let { configFile ->
    require(configFile.isFile) { "PDNS_SIGNING_PROPERTIES must point to an existing properties file." }
    Properties().apply { configFile.inputStream().use { load(it) } }.also { properties ->
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
            require(!properties.getProperty(key).isNullOrBlank()) { "Missing signing property: $key" }
        }
    }
}
android {
    namespace = "io.github.sorjo.pdnsautoswitch"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.sorjo.pdnsautoswitch"
        minSdk = 33
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        create("release") {
            releaseSigning?.let { properties ->
                storeFile = releaseSigningFile!!.parentFile.resolve(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(releaseSigning != null) {
            "Release requires PDNS_SIGNING_PROPERTIES pointing to external signing credentials."
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}


dependencies {
    implementation("com.google.android.material:material:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
