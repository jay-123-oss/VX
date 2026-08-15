# Fix Gradle build error: 'litert' task not found and duplicate classes

The project is experiencing two related issues:
1. **Missing Task Error**: You are likely trying to run a command like `./gradlew litert` or a misconfigured `dependencyInsight` task. `litert` is a library name, not a default Gradle task.
2. **Duplicate Class Errors**: There is a conflict between the legacy `org.tensorflow:tensorflow-lite` and the new `com.google.ai.edge.litert` libraries. Modern versions of ARCore and other Google libraries are migrating to LiteRT, causing overlaps if TFLite is also explicitly included.

This plan migrates the project to LiteRT exclusively to resolve the classpath conflicts and fixes the dependency definitions.

## User Review Required

> [!IMPORTANT]
> The `litert` task you were trying to run does not exist. If you were trying to inspect dependencies, use:
> `./gradlew :app:dependencyInsight --dependency litert --configuration debugRuntimeClasspath`

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/jayde/AndroidStudioProjects/VX2/gradle/libs.versions.toml)
Replace TensorFlow Lite versions and libraries with LiteRT equivalents.

#### [MODIFY] [build.gradle.kts](file:///C:/Users/jayde/AndroidStudioProjects/VX2/app/build.gradle.kts)
Update the dependencies block to use the new LiteRT entries from the version catalog.

---

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to verify that duplicate class errors are resolved and the build completes successfully.

### Manual Verification
- Verify that `MLModelManager.kt` still compiles (LiteRT provides a compatibility layer for `org.tensorflow.lite` packages).
