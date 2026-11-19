plugins {
    id("ecu.jvm.library")
    `java-test-fixtures`
}

dependencies {
    // SQLite on the JVM — only for tests and synthetic backups; on Android android.database.sqlite reads it.
    testFixturesImplementation(libs.sqlite.jdbc)
    testImplementation(libs.sqlite.jdbc)
}
