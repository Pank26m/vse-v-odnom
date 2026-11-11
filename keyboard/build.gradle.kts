/*
 * Клавиатура «Всё в одном» — на основе Inkwell Keyboard (Goodwy) / FlorisBoard (Patrick Goldinger),
 * Apache License 2.0 (keyboard/LICENSE-Inkwell-FlorisBoard.txt). Покупки (Google Play/RuStore) и Rust-заглушка убраны.
 */
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.goodwy.keyboard"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        // поля, которые в Inkwell давала сборка приложения
        buildConfigField("String", "APPLICATION_ID", "\"com.pank.vsevodnom\"")
        buildConfigField("int", "VERSION_CODE", "31")
        buildConfigField("String", "VERSION_NAME", "\"0.3.1-vse\"")
        buildConfigField("String", "BUILD_COMMIT_HASH", "\"vse\"")
        buildConfigField("String", "FLADDONS_API_VERSION", "\"v~draft2\"")
        buildConfigField("String", "FLADDONS_STORE_URL", "\"beta.addons.florisboard.org\"")
        for (k in listOf("PRODUCT_ID_X1", "PRODUCT_ID_X2", "PRODUCT_ID_X3", "SUBSCRIPTION_ID_X1", "SUBSCRIPTION_ID_X2", "SUBSCRIPTION_ID_X3",
            "SUBSCRIPTION_YEAR_ID_X1", "SUBSCRIPTION_YEAR_ID_X2", "SUBSCRIPTION_YEAR_ID_X3")) buildConfigField("String", k, "\"\"")
    }
    sourceSets { getByName("main") { java.srcDirs("src/main/kotlin") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true; compose = true }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
        arg("room.expandProjection", "true")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=kotlin.contracts.ExperimentalContracts", "-Xjvm-default=all-compatibility")
    }
}

composeCompiler {
    // ИЗ INKWELL: не включать strong skipping — интерфейс клавиатуры рассчитан на перерисовку при смене родителя
    featureFlags.add(ComposeFeatureFlag.StrongSkipping.disabled())
}

dependencies {
    implementation(project(":keyboard-strings"))
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("androidx.collection:collection-ktx:1.4.5")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.emoji2:emoji2:1.5.0")
    implementation("androidx.emoji2:emoji2-views:1.5.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("io.github.reactivecircus.cache4k:cache4k:0.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("dev.patrickgold.compose.tooltip:tooltip:0.1.0")
    implementation("dev.patrickgold.jetpref:jetpref-datastore-model:0.2.0-beta03")
    implementation("dev.patrickgold.jetpref:jetpref-datastore-ui:0.2.0-beta03")
    implementation("dev.patrickgold.jetpref:jetpref-material-ui:0.2.0-beta03")
}
