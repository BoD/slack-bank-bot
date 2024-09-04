rootProject.name = "slack-bank-bot"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

plugins {
    // See https://splitties.github.io/refreshVersions/
    id("de.fayard.refreshVersions") version "0.60.5"
}

