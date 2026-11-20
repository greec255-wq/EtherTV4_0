
plugins {
    java
}

group = "ru.ethertv"
version = "1.3.0-dev"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
    compileOnly(files("libs/CustomDiscs-2.7.0.jar"))
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.27")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}
