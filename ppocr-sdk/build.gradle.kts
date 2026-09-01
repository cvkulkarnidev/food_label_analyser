import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.paddle.ocr"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val verifyPaddleModels by tasks.registering {
    val required = listOf(
        file("src/main/assets/models/det/inference.onnx") to 9_000_000L,
        file("src/main/assets/models/rec/inference.onnx") to 20_000_000L,
        file("src/main/assets/models/rec/inference.yml") to 100_000L,
    )
    inputs.files(required.map { it.first })
    doLast {
        val missing = required.filter { (model, minimumBytes) -> !model.isFile || model.length() < minimumBytes }
        check(missing.isEmpty()) {
            "PaddleOCR model assets are missing or incomplete. Run scripts/download_paddle_models.sh before building."
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyPaddleModels)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    implementation("org.opencv:opencv:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
