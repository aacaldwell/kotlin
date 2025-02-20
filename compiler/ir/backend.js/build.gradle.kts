plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    api(project(":compiler:util"))
    api(project(":compiler:frontend"))
    api(project(":compiler:backend-common"))
    api(project(":compiler:ir.tree"))
    api(project(":compiler:ir.backend.common"))
    api(project(":compiler:ir.serialization.common"))
    api(project(":compiler:ir.serialization.js"))
    api(project(":compiler:ir.tree.persistent"))
    api(project(":js:js.ast"))
    api(project(":js:js.frontend"))
    api(project(":js:js.sourcemap"))

    compileOnly(intellijCore())
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}
