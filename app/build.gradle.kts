plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pank.vsevodnom"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pank.vsevodnom"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1-dev"
        // телефон владельца — arm64; так APK с yt-dlp и ffmpeg в разы меньше
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // yt-dlp запускает Python из папки нативных библиотек — их нужно распаковывать при установке
    packaging { jniLibs { useLegacyPackaging = true; pickFirsts += "**/libc++_shared.so" } }
    // карта мира GLMap (world.vm) и шрифты — без сжатия, иначе их распаковывают при первом запуске
    androidResources { noCompress += listOf("vm", "ttf", "otf", "kdict") }   // kdict — словари клавиатуры читаются из APK напрямую

    // Подпись релизов: ключ передаётся через переменные окружения (секреты GitHub).
    val ksPath = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":keyboard"))
    implementation("androidx.autofill:autofill:1.1.0")   // встроенные подсказки автозаполнения в клавиатуре
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
    // карты: MapLibre (векторные тайлы, офлайн-области). 12.3.1 — последняя, что собирается с AGP 8.7 / compileSdk 35
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // та же, что у MapLibre: свой User-Agent для онлайн-слоёв
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.github.junrar:junrar:7.5.5")
    // GLMap (движок Guru Maps): карта с 3D-рельефом, офлайн-данные, поиск объекта под пальцем. Ключ — в «Ключах и сервисах».
    // кино из Telegram: TDLib (вход, канал, потоковая загрузка) + плеер Media3
    implementation("com.github.tdlibx:td:1.8.56")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("globus:glmap:2.3.0")
    implementation("globus:glsearch:2.3.0")
    implementation("globus:glroute:2.3.0")
    // RAR5 (и RAR4, 7z): libarchive 3.7.4. Версия 1.1.0 — новее требуют compileSdk 37
    implementation("me.zhanghai.android.libarchive:library:1.1.0")
    implementation("org.apache.commons:commons-compress:1.21")
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.4")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.21")
    testImplementation("org.json:json:20231013")
}
