
@echo off
setlocal EnableExtensions EnableDelayedExpansion

title JMon Windows Installer

set "REPO=GokhanDurmaz/jmon"
set "INSTALL_ROOT=%USERPROFILE%\.local\opt\jmon"
set "INSTALL_BIN=%USERPROFILE%\.local\opt\jmon\bin"
set "TEMP_DIR=%TEMP%\jmon-install-%RANDOM%-%RANDOM%"
set "ZIP_FILE=%TEMP_DIR%\jmon-windows.zip"
set "EXTRACT_DIR=%TEMP_DIR%\extracted"
set "BACKUP_ROOT=%USERPROFILE%\.local\opt\jmon.backup"
set "VERSION_FILE=%TEMP%\jmon-java-version.txt"
set "JDK21="

echo.
echo ==========================================
echo        JMon Windows Installer
echo ==========================================
echo.

echo [1/6] Searching for Java 21...
call :SCAN_JDK

if not defined JDK21 (
    echo Java 21 was not found. Installing Temurin JDK 21...
    echo.

    where.exe winget >nul 2>&1
    if errorlevel 1 (
        echo ERROR: winget is not available.
        echo Install Microsoft App Installer first:
        echo https://aka.ms/getwinget
        goto :FAIL
    )

    winget install --id EclipseAdoptium.Temurin.21.JDK --exact --accept-source-agreements --accept-package-agreements

    if errorlevel 1 (
        echo.
        echo ERROR: Java 21 installation failed.
        echo Check winget output and retry this installer.
        goto :FAIL
    )

    echo.
    echo Searching for the newly installed JDK...
    call :SCAN_JDK
)

if not defined JDK21 (
    echo ERROR: Java 21 was not found after installation.
    echo Check your JDK installation and try again.
    goto :FAIL
)

echo Java 21 found:
echo %JDK21%
echo.

echo [2/6] Setting user JAVA_HOME and PATH...

set "JMON_JAVA_HOME=%JDK21%"
set "JMON_INSTALL_BIN=%INSTALL_BIN%"

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
 "$ErrorActionPreference = 'Stop'; try {" ^
 "$jdk = $env:JMON_JAVA_HOME;" ^
 "$jdkBin = Join-Path $jdk 'bin';" ^
 "$jmonBin = $env:JMON_INSTALL_BIN;" ^
 "[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User');" ^
 "$old = [Environment]::GetEnvironmentVariable('Path', 'User');" ^
 "$items = @($old -split ';' | Where-Object {" ^
 "$_ -and $_.TrimEnd('\') -ine $jdkBin.TrimEnd('\') -and $_.TrimEnd('\') -ine $jmonBin.TrimEnd('\')" ^
 "});" ^
 "$new = (@($jdkBin, $jmonBin) + $items) -join ';';" ^
 "[Environment]::SetEnvironmentVariable('Path', $new, 'User');" ^
 "} catch { Write-Error $_; exit 1 }"

if errorlevel 1 (
    echo ERROR: Could not update user environment variables.
    goto :FAIL
)

echo JAVA_HOME and user PATH updated.
echo.

echo [3/6] Preparing download...

mkdir "%TEMP_DIR%" >nul 2>&1
if not exist "%TEMP_DIR%" (
    echo ERROR: Cannot create temporary directory.
    goto :FAIL
)

where.exe curl.exe >nul 2>&1
if errorlevel 1 (
    echo ERROR: curl.exe is not available.
    goto :FAIL
)

echo Downloading the latest JMon release...

curl.exe -fL --retry 2 --connect-timeout 20 ^
 "https://github.com/%REPO%/releases/latest/download/jmon-windows.zip" ^
 -o "%ZIP_FILE%"

if errorlevel 1 (
    echo.
    echo ERROR: Download failed.
    echo Make sure a published GitHub Release contains:
    echo jmon-windows.zip
    goto :FAIL
)

if not exist "%ZIP_FILE%" (
    echo ERROR: Downloaded archive was not found.
    goto :FAIL
)

for %%F in ("%ZIP_FILE%") do if %%~zF LEQ 0 (
    echo ERROR: Downloaded archive is empty.
    goto :FAIL
)

echo.
echo [4/6] Extracting and validating the package...

set "JMON_ZIP=%ZIP_FILE%"
set "JMON_EXTRACT=%EXTRACT_DIR%"

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
 "$ErrorActionPreference = 'Stop'; try {" ^
 "Expand-Archive -LiteralPath $env:JMON_ZIP -DestinationPath $env:JMON_EXTRACT -Force;" ^
 "if (-not (Test-Path (Join-Path $env:JMON_EXTRACT 'jmon\bin\jmon.bat'))) {" ^
 "throw 'The archive does not contain jmon\bin\jmon.bat'" ^
 "}" ^
 "} catch { Write-Error $_; exit 1 }"

if errorlevel 1 (
    echo ERROR: Archive extraction or validation failed.
    goto :FAIL
)

echo Package validated.
echo.

echo [5/6] Installing JMon...

if not exist "%USERPROFILE%\.local\opt" (
    mkdir "%USERPROFILE%\.local\opt"
)

if exist "%BACKUP_ROOT%" (
    rmdir /S /Q "%BACKUP_ROOT%"
)

if exist "%INSTALL_ROOT%" (
    move "%INSTALL_ROOT%" "%BACKUP_ROOT%" >nul
    if errorlevel 1 (
        echo ERROR: Could not back up the existing installation.
        goto :FAIL
    )
)

move "%EXTRACT_DIR%\jmon" "%INSTALL_ROOT%" >nul

if errorlevel 1 (
    echo ERROR: Could not move JMon into the installation directory.

    if exist "%BACKUP_ROOT%" (
        move "%BACKUP_ROOT%" "%INSTALL_ROOT%" >nul
    )

    goto :FAIL
)

if exist "%BACKUP_ROOT%" (
    rmdir /S /Q "%BACKUP_ROOT%"
)

echo JMon installed successfully.
echo.

echo [6/6] Verifying installation...

rem Use Java 21 immediately in this installer process.
set "PATH=%JDK21%\bin;%INSTALL_BIN%;%PATH%"
set "JAVA_HOME=%JDK21%"

echo.
echo Java version:
"%JDK21%\bin\java.exe" -version

if errorlevel 1 (
    echo ERROR: Java 21 could not be started.
    goto :FAIL
)

echo.
echo JMon version:
call "%INSTALL_BIN%\jmon.bat" --version

if errorlevel 1 (
    echo WARNING: JMon version check failed.
    echo Try running jmon --help after installation.
)

echo.
echo Java executable resolved by this CMD:
where.exe java

echo.
echo JMon executable:
where.exe jmon

echo.
echo ==========================================
echo Installation finished.
echo.
echo Close this window and open a NEW CMD.
echo Then run:
echo.
echo   java -version
echo   where java
echo   jmon --version
echo   jmon --help
echo ==========================================
echo.

if exist "%TEMP_DIR%" rmdir /S /Q "%TEMP_DIR%" >nul 2>&1
if exist "%VERSION_FILE%" del "%VERSION_FILE%" >nul 2>&1

pause
exit /b 0


:SCAN_JDK
set "JDK21="

rem Check the configured JAVA_HOME first.
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        call :CHECK_JAVA "%JAVA_HOME%\bin\java.exe"
    )
)

rem Check every java.exe visible through the current PATH.
for /f "delims=" %%J in ('where.exe java 2^>nul') do (
    call :CHECK_JAVA "%%J"
)

rem Check common JDK installation locations.
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do (
    call :CHECK_JAVA "%%~D\bin\java.exe"
)

for /d %%D in ("%ProgramFiles%\Java\jdk-21*") do (
    call :CHECK_JAVA "%%~D\bin\java.exe"
)

for /d %%D in ("%ProgramFiles%\Microsoft\jdk-21*") do (
    call :CHECK_JAVA "%%~D\bin\java.exe"
)

exit /b 0


:CHECK_JAVA
if defined JDK21 exit /b 0
if not exist "%~1" exit /b 0

rem java -version writes its version information to stderr.
"%~1" -version >nul 2>"%VERSION_FILE%"

if errorlevel 1 exit /b 0

findstr /C:"21." "%VERSION_FILE%" >nul
if errorlevel 1 exit /b 0

rem java.exe is inside the JDK's bin directory.
for %%I in ("%~dp1..") do set "JDK21=%%~fI"

exit /b 0


:FAIL
echo.
echo ==========================================
echo Installation failed.
echo Review the error messages above.
echo ==========================================
echo.

if exist "%TEMP_DIR%" rmdir /S /Q "%TEMP_DIR%" >nul 2>&1
if exist "%VERSION_FILE%" del "%VERSION_FILE%" >nul 2>&1

pause
exit /b 1
