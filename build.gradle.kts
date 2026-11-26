// 최상위 build.gradle.kts
// - Android Gradle Plugin 8.5.2 / Kotlin 1.9.24 조합은 Android Studio Ladybug(2024.2) 이상에서 바로 열립니다.
// - 최신 Android Studio로 열면 "AGP 업그레이드 권장" 안내가 뜰 수 있는데, 그대로 두고 Sync만 하면 됩니다.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
