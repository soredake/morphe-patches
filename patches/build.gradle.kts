group = "app.soredake"

patches {
    about {
        name = "Qwen Custom Tabs"
        description = "Opens Qwen links in Android Custom Tabs"
        source = "https://github.com/soredake/morphe-patches"
        author = "soredake"
        contact = "https://github.com/soredake/morphe-patches/issues"
        website = "https://morphe.software/faq"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
