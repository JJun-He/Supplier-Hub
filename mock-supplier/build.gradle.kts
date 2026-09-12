plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.supplierhub"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}