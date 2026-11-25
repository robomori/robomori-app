plugins {
    // On the classpath through buildSrc, which sets its version.
    id("org.jetbrains.kotlin.jvm")
    application
}

version = "0.1.0"
description = "Updates a JVM desktop app's own code without a new installer."

kotlin { jvmToolchain { languageVersion = JavaLanguageVersion.of(25); vendor = JvmVendorSpec.JETBRAINS } }

// `./gradlew :codepush:run --args="publish …"` runs the publisher.
application { mainClass = "com.composables.codepush.CodePushPublisher" }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Test filters for the app, such as --tests com.robomori.HostMediaTest, match nothing here; that is fine.
    filter.isFailOnNoMatchingTests = false
}
