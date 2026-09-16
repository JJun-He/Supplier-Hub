import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
	java
	id("org.springframework.boot") version "4.0.8"
	id("io.spring.dependency-management") version "1.1.7"
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
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webclient")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")

	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webclient-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")

	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

val e2eSourceSet = sourceSets.create("e2eTest")
configurations[e2eSourceSet.implementationConfigurationName]
	.extendsFrom(configurations.testImplementation.get())
configurations[e2eSourceSet.runtimeOnlyConfigurationName]
	.extendsFrom(configurations.testRuntimeOnly.get())

val mockApplicationJar = configurations.create("mockApplicationJar") {
	isCanBeConsumed = false
	isCanBeResolved = true
	isTransitive = false
}
dependencies {
	add(mockApplicationJar.name, project(
		path = ":mock-supplier",
		configuration = "executableJar"
	))
}

val applicationJar = tasks.named<BootJar>("bootJar").flatMap { it.archiveFile }
val e2eLogDirectory = layout.buildDirectory.dir("reports/e2e")
val e2eTest = tasks.register<Test>("e2eTest") {
	description = "실제 DB와 메인·Mock 프로세스를 연결해 고객 API를 검증한다."
	group = "verification"
	testClassesDirs = e2eSourceSet.output.classesDirs
	classpath = e2eSourceSet.runtimeClasspath
	inputs.file(applicationJar).withPropertyName("applicationJar")
	inputs.files(mockApplicationJar).withPropertyName("mockApplicationJar")
	dependsOn(tasks.named("bootJar"), mockApplicationJar)
	shouldRunAfter(tasks.named("test"), ":mock-supplier:test")
	maxParallelForks = 1
	doFirst {
		systemProperty("e2e.app.jar", applicationJar.get().asFile.absolutePath)
		systemProperty("e2e.mock.jar", mockApplicationJar.singleFile.absolutePath)
		systemProperty("e2e.java.executable", javaLauncher.get().executablePath.asFile.absolutePath)
		systemProperty("e2e.logs", e2eLogDirectory.get().asFile.absolutePath)
	}
}

tasks.named("check") {
	dependsOn(e2eTest)
}
