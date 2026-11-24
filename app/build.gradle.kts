plugins {
    alias(libs.plugins.android.application)
}

layout.buildDirectory.set(
    File(System.getProperty("java.io.tmpdir"), "douyin-immersive-gradle/app")
)

android {
    namespace = "com.zz.douyin"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zz.douyin"
        minSdk = 28
        targetSdk = 35
        versionCode = 29
        versionName = "1.7.7"
    }

    /*
     * 固定签名。之前用 signingConfigs.getByName("debug")，CI 每个 runner 都会
     * 生成新的 ~/.android/debug.keystore —— 每次构建签名都不同，装新包必须先卸载。
     * 现在和 DouyinDownloader 用同一份 release-key.jks：
     *   ① 能覆盖安装
     *   ② 两个 App 签名一致，SharedProvider 的签名校验（① 层）真正生效
     * 口令从环境变量读（GitHub Secrets），本地没有就退回 debug 签名，不至于编不了。
     */
    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("release-key.jks")
            val storePass = System.getenv("DOUYIN_STORE_PASSWORD") ?: ""
            val keyPass = System.getenv("DOUYIN_KEY_PASSWORD") ?: ""
            if (keystoreFile.exists() && storePass.isNotEmpty() && keyPass.isNotEmpty()) {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = "douyin-release"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 注意判断条件：create("release") 之后 findByName 永远非 null，
            // 拿它当条件会在「口令没配」时选中一个空配置，打包直接报
            // "SigningConfig release is missing required property storeFile"。
            // 必须看 storeFile 到底有没有赋值。
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation("de.sciss:jump3r:1.0.5")
    testImplementation(libs.libxposed.api)
    testImplementation("junit:junit:4.13.2")
}
