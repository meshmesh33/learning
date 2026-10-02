plugins {
    id("pim.java-conventions")
}

dependencies {
    implementation(project(":application"))
    implementation(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.test)
}
