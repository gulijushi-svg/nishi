import java.util.Properties

val waterProperties = Properties().apply {
    val config = rootProject.file("water.properties")
    if (config.exists()) config.inputStream().use { load(it) }
}
// 与原版 life-798 客户端一致的内置慧生活798配置；water.properties 可按部署覆盖。
val builtInWaterDefaults = mapOf(
    "API_GATEWAY" to "https://i.ilife798.com/api/v1",
    "API_CID" to "drinkwaterapp123456789",
)
fun waterConfig(name: String): String = "\"" +
    (waterProperties.getProperty(name) ?: builtInWaterDefaults[name].orEmpty())
        .replace("\\", "\\\\").replace("\"", "\\\"") + "\""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.fengkao.timetable"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.fengkao.timetable"
        minSdk = 26
        targetSdk = 36
        versionCode = 48
versionName = "1.0.4"
        buildConfigField("String", "WATER_API_GATEWAY", waterConfig("API_GATEWAY"))
        buildConfigField("String", "WATER_API_CID", waterConfig("API_CID"))
        manifestPlaceholders["appName"] = "泥石"

        ndk {
            // debug 保留 x86_64 以便跑模拟器；release 只发 arm64-v8a 真机包。
            // （不能和 splits.abi 同时配置，AGP 会直接报冲突，所以这里按 task 名动态选。）
            val isRelease = gradle.startParameter.taskNames.any { it.contains("Release") || it.contains("Qa") }
            abiFilters.addAll(
                if (isRelease) listOf("arm64-v8a")
                else listOf("arm64-v8a", "x86_64")
            )
        }
    }

    // 签名：正式密钥库放在项目根的 timetable-release.jks，凭据在 keystore.properties。
    // 未提供密钥时生成未签名 release，绝不使用调试签名冒充正式发布。
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file(
                providers.gradleProperty("releaseSigningProperties").getOrElse("keystore.properties")
            )
            val props = Properties()
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            val sf = props.getProperty("storeFile")?.let { p -> rootProject.file(p) }
            if (sf != null && sf.exists()) {
                storeFile = sf
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else {
                logger.lifecycle("未找到 release 密钥库，将生成未签名 release 包")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 私有签名凭据只从本机文件读取；没有密钥库时保持未签名，避免打包阶段报缺 storeFile。
            signingConfigs.getByName("release").takeIf { it.storeFile != null }?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "WATER_API_GATEWAY", "\"\"")
            buildConfigField("String", "WATER_API_CID", "\"\"")
        }
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".testrepo"
            versionNameSuffix = "-test"
            manifestPlaceholders["appName"] = "泥石 Test"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // 注：ABI 分包（splits.abi）与 defaultConfig.ndk.abiFilters 互斥，不能并存。
    // 这里走更直接的"按 buildType 选 ABI"路线：debug 双 ABI，release 只 arm64-v8a。
    // 如果日后需要给模拟器出 release，把上面 defaultConfig.ndk 改成 isRelease 时仍含两个 ABI，
    // 并解除这行注释启用 splits + androidComponents 的 versionCode 偏移。

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 旧版 Chaquopy/Python 教务直连已移除；默认课表来源为学习通，网页导入由 WebView 完成。
// 构建不再需要 Python 3.13、vendor wheel 或 Chaquopy 的 pip 补丁脚本。

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // 液态玻璃：SDF 折射 + 色散 + 高光（参考 QWEA0/Liquid-Glass-Android，其 Compose 版为 Kyant0/backdrop）
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)

    // 网络与序列化
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.quickie.bundled)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // 洗衣服务（整合自 Night-stars-1/wash）：接口协议、签名与支付宝支付
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.alipaysdk.android)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json.test)
}
