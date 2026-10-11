# Task 2: Green baseline — RELEASE build type + signing placeholder

## Task Description

Add a `release` build type and an env-gated `signingConfigs` placeholder to the
Android app so a release artifact path exists (unsigned when no keystore env is
present; signed when `OLLAMADEV_KEYSTORE_*` env vars are set).

## Files

- Modify: `/home/dev/OllamaDev/app/build.gradle.kts`

## Acceptance

- `android { signingConfigs { ... } }` gated on `System.getenv("OLLAMADEV_KEYSTORE_PATH") != null`
- `buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("release") } }`
- `./gradlew :app:assembleRelease --console=plain` produces an (unsigned when env unset) release APK
- `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain` still green
- Existing buildTypes (debug) untouched

## Steps

1. Read `app/build.gradle.kts` (current buildTypes/signing setup — note the run-ollamadev skill says README's signing-config step is stale; align with the real file).
2. Add the env-gated signingConfigs + release buildType exactly as in the plan:

```kotlin
android {
  signingConfigs {
    if (System.getenv("OLLAMADEV_KEYSTORE_PATH") != null) {
      create("release") {
        storeFile = file(System.getenv("OLLAMADEV_KEYSTORE_PATH"))
        storePassword = System.getenv("OLLAMADEV_KEYSTORE_PASS")
        keyAlias = System.getenv("OLLAMADEV_KEYSTORE_ALIAS")
        keyPassword = System.getenv("OLLAMADEV_KEYSTORE_PASS")
      }
    }
  }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      signingConfig = signingConfigs.findByName("release") // null -> unsigned
    }
  }
}
```

3. Verify (background + poll): `nohup ./gradlew :app:assembleRelease --console=plain >/tmp/t2.log 2>&1 &` until BUILD SUCCESSFUL; confirm `app/build/outputs/apk/release/app-release-unsigned.apk` (or similar) exists.
4. Verify debug + unit tests still green (background).
5. Commit ONLY `app/build.gradle.kts`: `build: add gated release signing placeholder + release build type` (use plan's message: `build: add gated release signing placeholder + release build type`).

## Context

- Repo: /home/dev/OllamaDev, branch main. JAVA_HOME unset = OpenJDK 17. CentOS limited RAM (5.3GB) — gradle tuning deliberate, never change gradle.properties.
- WSL IPv4 loopback workaround in run-ollamadev skill if maven deps missing.
- Do not push.
