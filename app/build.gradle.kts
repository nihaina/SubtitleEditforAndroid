import groovy.json.JsonSlurper
import com.android.build.api.variant.FilterConfiguration

plugins {
    id("com.android.application")
}

fun registerApkExport(
    variantName: String,
    outputPath: String,
    artifactSuffix: String = "",
    arm64Only: Boolean = false
) {
    val taskName = "export${variantName.replaceFirstChar { it.uppercase() }}Apks"
    val exportTask = tasks.register(taskName) {
        doLast {
            val outputDir = layout.buildDirectory.dir("outputs/apk/$outputPath").get().asFile
            val metadataFile = outputDir.resolve("output-metadata.json")
            if (!metadataFile.isFile) {
                logger.lifecycle("未找到 $variantName APK 元数据，跳过导出")
                return@doLast
            }

            val metadata = JsonSlurper().parse(metadataFile) as Map<*, *>
            val elements = metadata["elements"] as? List<*> ?: return@doLast
            val versionName = android.defaultConfig.versionName ?: "unknown"
            val exportDir = outputDir.resolve("export").apply { mkdirs() }

            elements.forEach { element ->
                val item = element as? Map<*, *> ?: return@forEach
                val sourceName = item["outputFile"] as? String ?: return@forEach
                val filters = item["filters"] as? List<*>
                val architecture = filters
                    ?.mapNotNull { (it as? Map<*, *>)?.get("value") as? String }
                    ?.firstOrNull()
                    ?: "universal"
                if (arm64Only && architecture != "arm64-v8a") return@forEach
                val source = outputDir.resolve(sourceName)
                if (source.isFile) {
                    val suffix = artifactSuffix.takeIf { it.isNotBlank() }?.let { "-$it" } ?: ""
                    source.copyTo(
                        exportDir.resolve("SubtitleEdit-release-$versionName-$architecture$suffix.apk"),
                        overwrite = true
                    )
                }
            }
            logger.lifecycle("已导出 $variantName APK：${exportDir.absolutePath}")
        }
    }
    tasks.matching {
        it.name == "assemble${variantName.replaceFirstChar { char -> char.uppercase() }}"
    }.configureEach {
        finalizedBy(exportTask)
    }
}

registerApkExport("standardDebug", "standard/debug")
registerApkExport("qnnDebug", "qnn/debug", artifactSuffix = "qnn", arm64Only = true)
registerApkExport("standardRelease", "standard/release")
registerApkExport("qnnRelease", "qnn/release", artifactSuffix = "qnn", arm64Only = true)

val archiveLicenseAssetsDir = layout.buildDirectory.dir("generated/archiveLicenseAssets")
val generateArchiveLicenseAssets by tasks.registering(Sync::class) {
    from("src/main/cpp/third_party/7zip/7zip-LICENSE.txt") {
        into("licenses")
    }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
        into("licenses")
    }
    from(rootProject.file("LICENSE")) {
        into("licenses")
        rename { "GPL-3.0.txt" }
    }
    from(rootProject.file("native/mpv/mpv-android-LICENSE.txt")) {
        into("licenses")
    }
    into(archiveLicenseAssetsDir)
}

android {
    namespace = "com.subtitleedit"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.subtitleedit"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.1.5"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                targets("subtitleedit_7zip")
                arguments("-DANDROID_STL=c++_static")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }

    sourceSets.getByName("main").assets.srcDir(archiveLicenseAssetsDir.get().asFile)

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Keep native libraries uncompressed in the APK to minimize installed size.
            // QNN HTP Skel libraries are extracted to codeCacheDir only while NPU is active.
            useLegacyPackaging = false
            keepDebugSymbols += setOf("**/libQnn*.so")
            // sherpa-onnx and the standalone demixing runtime both provide ORT/libc++.
            // Keep a single copy in the APK; the demixing code remains isolated at API level.
            pickFirsts += setOf("**/libonnxruntime.so", "**/libc++_shared.so")
        }
    }

    flavorDimensions += "qnnRuntime"
    productFlavors {
        create("standard") {
            dimension = "qnnRuntime"
        }
        create("qnn") {
            dimension = "qnnRuntime"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

androidComponents {
    onVariants(selector().withFlavor("qnnRuntime", "qnn")) { variant ->
        variant.outputs.forEach { output ->
            val isArm64 = output.filters.any { filter ->
                filter.filterType == FilterConfiguration.FilterType.ABI &&
                    filter.identifier == "arm64-v8a"
            }
            output.enabled.set(isArm64)
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateArchiveLicenseAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // DocumentFile - for file operations in selected directories
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // FFmpegKitNext is built locally because upstream does not publish Android artifacts.
    implementation("com.arthenica:ffmpeg-kit-next:8.1.0-mpv1")
    implementation("com.subtitleedit.native:mpv-android-runtime:0.41.0-ffmpeg8.1.2-1")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Standalone ONNX Runtime Java API for HTDemucs vocal separation.
    // Match the ONNX Runtime shared library already shipped with sherpa-onnx.
    implementation(files("libs/onnxruntime-java-1.27.1.jar"))

    // sherpa-onnx for Whisper speech recognition
    // Kotlin API 源码已集成到 app/src/main/java/com/k2fsa/sherpa/onnx/
    // Native 库已放置到 app/src/main/jniLibs/
    // 无需额外依赖配置

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
}
