plugins { `java-gradle-plugin` }

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

gradlePlugin {
    plugins {
        create("robomoriDevelopmentMacIcon") {
            id = "com.robomori.development-mac-icon"
            implementationClass = "com.robomori.development.MacDevelopmentIconPlugin"
        }
    }
}
