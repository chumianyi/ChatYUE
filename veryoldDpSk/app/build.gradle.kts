plugins {
    id("com.android.application")
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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // 非致命告警不阻断构建；NewApi 由 CI 的独立步骤严格把关。
        isAbortOnError = false
        checkReleaseBuilds = false
        htmlReport = true
        xmlReport = true
        disable += setOf(
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
        )
    }
}
