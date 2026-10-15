import java.util.Properties

plugins {
    id("com.android.application")
}

/*
 * 固定签名：与主应用 ChatYUE 共用仓库根目录下的同一份 keystore，
 * 保证本地与云端 CI 产出的 APK 签名一致，可以互相覆盖安装。
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("../keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.veryolddpsk.chat"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.veryolddpsk.chat"
        /*
         * 硬约束：最低支持 Android 1.5（API 3），任何情况下都不允许抬高。
         * 所有代码只使用 API 3 已有的接口，CI 用 lint 的 NewApi 检查兜底。
         */
        minSdk = 3
        /*
         * targetSdk 必须 >= 23，否则 Android 14+ 会拒绝安装；
         * 同时 < 28 才能继续使用明文 HTTP 接口（本应用固定走 http）。
         */
        targetSdk = 25
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        // Java 7 字节码：老 Dalvik 只认老格式，不引入 Java 8+ 特性。
        sourceCompatibility = JavaVersion.VERSION_1_7
        targetCompatibility = JavaVersion.VERSION_1_7
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file("../keystore/chatyue-release.jks")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 不开启混淆与资源压缩：老设备上任何压缩带来的不确定性都不值得冒险。
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // 注意 AGP 7.4 的 Lint DSL 属性名是 abortOnError（AGP 8 才改名 isAbortOnError）。
        // 非致命告警不阻断构建；NewApi 由 CI 的独立步骤严格把关。
        abortOnError = false
        checkReleaseBuilds = false
        htmlReport = true
        xmlReport = true
        disable.addAll(
            listOf(
                "MinSdkTooLow",
                "OldTargetApi",
                "ExpiredTargetSdkVersion",
                // manifest 里个别属性（如 allowBackup）在 API 3 上不存在，系统会直接忽略
                "UnusedAttribute",
                "GoogleAppIndexingWarning",
                "MissingApplicationIcon",
                "IconDensities",
                "IconMissingDensityFolder",
                "IconLauncherShape",
                "AllowBackup",
                "UnusedResources",
                "HardcodedText",
                "ContentDescription",
                "ClickableViewAccessibility",
                "LabelFor",
                "Autofill",
                "Deprecated",
                "BatteryLife",
                "LogConditional",
                "SetTextI18n",
                "RtlSymmetry",
                "RtlHardcoded",
                "SmallSp",
                "TypographyQuotes",
                "Overdraw",
                "UselessParent",
                "NestedWeights",
                "TextFields",
                "DuplicateIds",
                "GradleDependency",
                "OldGradleApi",
                "NewerVersionAvailable",
            ),
        )
    }
}
