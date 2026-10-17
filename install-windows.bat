@echo off
setlocal

title JMon Windows Installer

set "REPO=GokhanDurmaz/jmon"
set "INSTALL_ROOT=%USERPROFILE%\.local\opt\jmon"
set "BIN_DIR=%USERPROFILE%\.local\bin"
set "TEMP_DIR=%TEMP%\jmon-install-%RANDOM%-%RANDOM%"
set "ZIP_FILE=%TEMP_DIR%\jmon-windows.zip"
set "EXTRACT_DIR=%TEMP_DIR%\extracted"

echo.
echo ========================================
echo          JMon Windows Installer
echo ========================================
echo.

where java >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java is not available in PATH.
    echo Install Java 21 and reopen CMD.
    goto :fail
)

echo Checking Java version...
java -version 2> "%TEMP%\jmon-java-version.txt"
type "%TEMP%\jmon-java-version.txt"
findstr /C:"1.8." "%TEMP%\jmon-java-version.txt" >nul
if not errorlevel 1 (
    echo.
    echo ERROR: Java 8 is active in CMD.
    echo Fix JAVA_HOME and PATH to use Java 21.
    del "%TEMP%\jmon-java-version.txt" >nul 2>&1
    goto :fail
)
del "%TEMP%\jmon-java-version.txt" >nul 2>&1

mkdir "%TEMP_DIR%" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Cannot create temporary directory.
    goto :fail
)

echo.
echo Downloading the latest JMon release...
curl.exe -fL --retry 2 ^
    "https://github.com/%REPO%/releases/latest/download/jmon-windows.zip" ^
    -o "%ZIP_FILE%"

if errorlevel 1 (
    echo.
    echo ERROR: Download failed.
    echo Check that a GitHub Release contains jmon-windows.zip.
    goto :fail
)

echo Extracting files...
powershell.exe -NoProfile -Command ^
    "Expand-Archive -LiteralPath '%ZIP_FILE%' -DestinationPath '%EXTRACT_DIR%' -Force"

if errorlevel 1 (
    echo ERROR: Could not extract the archive.
    goto :fail
)

if not exist "%EXTRACT_DIR%\jmon\bin\jmon.bat" (
    echo ERROR: jmon.bat was not found in the archive.
    goto :fail
)

echo Installing JMon...
if exist "%INSTALL_ROOT%" (
    rmdir /S /Q "%INSTALL_ROOT%"
)

mkdir "%USERPROFILE%\.local\opt" >nul 2>&1
move "%EXTRACT_DIR%\jmon" "%INSTALL_ROOT%" >nul

if errorlevel 1 (
    echo ERROR: Could not install JMon.
    goto :fail
)

echo Updating your user PATH...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
    "$p = [Environment]::GetEnvironmentVariable('Path','User');" ^
    "$d = '%INSTALL_ROOT%\bin';" ^
    "$items = @($p -split ';' | Where-Object { $_ });" ^
    "if (-not ($items | Where-Object { $_.TrimEnd('\') -ieq $d.TrimEnd('\') })) {" ^
    "$new = (@($items) + $d) -join ';';" ^
    "[Environment]::SetEnvironmentVariable('Path',$new,'User') }"

if errorlevel 1 (
    echo WARNING: PATH could not be updated automatically.
    echo Add this directory to your user PATH manually:
    echo %INSTALL_ROOT%\bin
)

rmdir /S /Q "%TEMP_DIR%" >nul 2>&1

echo.
echo ========================================
echo JMon installation finished!
echo.
echo Close this CMD window and open a new one.
echo Then run:
echo     jmon --version
echo     jmon --help
echo ========================================
echo.
pause
exit /b 0

:fail
echo.
echo Installation failed. Read the error above.
if exist "%TEMP_DIR%" rmdir /S /Q "%TEMP_DIR%" >nul 2>&1
pause
exit /b 1
