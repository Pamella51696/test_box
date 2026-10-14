plugins {
    kotlin("jvm") version "1.9.24"
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        kotlin {
            srcDirs(
                "../curboverlay/model",
                "../curboverlay/parser",
                "../curboverlay/mapper",
                "../curboverlay/renderer",
            )
            exclude("android/**")
        }
    }
    test {
        kotlin.srcDirs("../tests")
        resources.srcDir("../fixtures")
    }
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
