import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 담당: 3번. Git 제외 local.properties의 데모 키를 주입한다. 값은 로그에 출력하지 않는다.
val weatherLocalProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
val kmaServiceKeyLiteral = "\"" + weatherLocalProperties.getProperty("KMA_SERVICE_KEY", "").trim()
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

android {
    namespace = "com.team.travelmap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.team.travelmap"
        minSdk = 28          // Pleos 요구사항: API 28 이상
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 차량·내비 연동 구현 선택: "auto"(SDK 있으면 Pleos, 없으면 Mock) | "pleos" | "mock"
        buildConfigField("String", "CAR_BACKEND", "\"auto\"")
        buildConfigField("String", "KMA_SERVICE_KEY", kmaServiceKeyLiteral)
    }

    // AAOS CarUxRestrictions(주행 중 UI 제한) 사용
    useLibrary("android.car")

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 제출용 APK도 debug 키로 서명해 바로 설치 가능하게 한다. 마켓 배포 시 교체.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended) // 1번: 확정 디자인의 선 아이콘(테마·재생)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    // 3번: 동행·곧 도착 음성 해설(ON_DEVICE, 신청 없이 사용 가능 — 2026-11-15 가상 기기 확인)
    implementation(libs.pleos.tts)
    // 3번: 동행 질문에 말로 대답(ON_DEVICE, 신청 없이 사용 가능 — 2026-11-15 가상 기기 확인)
    implementation(libs.pleos.stt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
