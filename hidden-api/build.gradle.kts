// Compile-time stubs of hidden framework classes. The app depends on this module with
// compileOnly, so nothing here is ever packaged: at runtime the real framework classes are used.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
