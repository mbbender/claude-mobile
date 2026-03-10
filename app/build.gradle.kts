plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val gitTag: String = providers.exec {
    commandLine("git", "describe", "--tags", "--exact-match")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

val gitBranch: String = providers.exec {
    commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
}.standardOutput.asText.get().trim()

val gitShortHash: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.get().trim()

val tailscaleIp: String = providers.exec {
    commandLine("sh", "-c", "ifconfig | grep -A1 utun | grep 'inet ' | awk '{print \$2}' | head -1")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

android {
    namespace = "com.claudemobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claudemobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 97
        versionName = "2.81"

        val buildLabel = "$gitBranch-$versionName-$gitShortHash"
        buildConfigField("String", "BUILD_LABEL", "\"$buildLabel\"")
        buildConfigField("String", "UPDATE_SERVER_URL", "\"http://${tailscaleIp.ifEmpty { "localhost" }}:8888\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("com.jcraft:jsch:0.1.55")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("io.coil-kt:coil-compose:2.6.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
