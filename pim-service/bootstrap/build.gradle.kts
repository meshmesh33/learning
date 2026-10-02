plugins {
    id("pim.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":application"))
    implementation(project(":adapters:persistence-postgres"))
    implementation(project(":adapters:outbox-kafka"))
    implementation(project(":adapters:web"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.tx)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.archunit.junit5)
}

springBoot {
    mainClass = "com.example.pim.bootstrap.PimApplication"
}
