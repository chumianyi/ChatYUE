plugins {
    id("com.android.application")
}

android {
    namespace = "com.veryolddpsk.chat"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.veryolddpsk.chat"
        // 目标：Android 1.5（API 3）及以上全部系统。
        minSdk = 3
        // targetSdk 不能过低，否则 Android 14+/15+ 会拒绝安装；
        // 25 既不触发分区存储，也能在最新系统上安装。
        targetSdk = 25
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        // Java 7 字节码，避免在 Dalvik 上引入不必要的新特性。
        sourceCompatibility = JavaVersion.VERSION_1_7
        targetCompatibility = JavaVersion.VERSION_1_7
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // minSdk 3 是本项目的明确目标，关闭对应提示。
        disable += "MinSdkTooLow"
    }
}
