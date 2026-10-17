@echo off
setlocal
setlocalenabledelayedexpansion

if exist "gradle.bat" (
    call gradle.bat %*
) else if exist "gradlew.bat" (
    call gradlew.bat %*
) else if exist "gradle" (
    call gradle %*
) else (
    echo Error: Gradle not found. Please install Gradle or run 'gradle wrapper' to generate the wrapper files.
    exit /b 1
)

if exist "gradlew.jar" (
    java -Djarmode=jar -jar gradlew.jar %*
) else (
    echo Error: gradlew.jar not found.
    exit /b 1
)
