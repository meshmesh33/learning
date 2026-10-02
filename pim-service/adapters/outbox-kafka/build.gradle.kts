plugins {
    id("pim.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.json)
    implementation(libs.spring.kafka)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}
