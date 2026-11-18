plugins {
    id("ecu.jvm.library")
    `java-test-fixtures`
}

dependencies {
    // SQLite на JVM — только для тестов и синтетических бэкапов; на Android читает android.database.sqlite.
    testFixturesImplementation(libs.sqlite.jdbc)
    testImplementation(libs.sqlite.jdbc)
}
