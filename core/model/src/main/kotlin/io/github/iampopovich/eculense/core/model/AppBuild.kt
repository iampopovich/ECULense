package io.github.iampopovich.eculense.core.model

/** Version and build flavor of the running app (cloud/local), provided by :app for «About» and problem reports. */
data class AppBuild(val versionName: String, val flavor: String)
