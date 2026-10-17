$ErrorActionPreference = "Stop"

$Repo = "GokhanDurmaz/jmon"
$InstallRoot = Join-Path $HOME ".local\opt\jmon"
$BinDir = Join-Path $HOME ".local\bin"
$TempDir = Join-Path $env:TEMP ("jmon-" + [guid]::NewGuid())
$ZipPath = Join-Path $TempDir "jmon.zip"

try {
    # Check Java
    $Java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $Java) {
        throw "Java 17 or later is required. Install a JDK first."
    }

    $JavaVersion = (& java -version 2>&1 | Out-String)
    if ($JavaVersion -notmatch 'version "(\d+)' -and
        $JavaVersion -notmatch 'openjdk (\d+)') {
        throw "Could not detect Java version."
    }

    $Major = [int]$Matches[1]
    if ($Major -lt 17) {
        throw "JMon requires Java 17 or later."
    }

    # Download the latest release
    New-Item -ItemType Directory -Path $TempDir -Force | Out-Null
    $Url = "https://github.com/$Repo/releases/latest/download/jmon-windows.zip"

    Write-Host "Downloading JMon..."
    Invoke-WebRequest -Uri $Url -OutFile $ZipPath

    # Extract archive
    $ExtractDir = Join-Path $TempDir "extracted"
    Expand-Archive -Path $ZipPath -DestinationPath $ExtractDir -Force

    $PackageDir = Join-Path $ExtractDir "jmon"
    $Launcher = Join-Path $PackageDir "bin\jmon.bat"

    if (-not (Test-Path $Launcher)) {
        throw "The release archive does not contain jmon.bat."
    }

    # Install
    New-Item -ItemType Directory -Path (Split-Path $InstallRoot) -Force | Out-Null

    if (Test-Path $InstallRoot) {
        Remove-Item $InstallRoot -Recurse -Force
    }

    Move-Item -Path $PackageDir -Destination $InstallRoot

    # Create command wrapper
    New-Item -ItemType Directory -Path $BinDir -Force | Out-Null
    $Wrapper = Join-Path $BinDir "jmon.cmd"
    $InstalledLauncher = Join-Path $InstallRoot "bin\jmon.bat"

    "@echo off`r`ncall `"$InstalledLauncher`" %*" |
        Set-Content -Path $Wrapper -Encoding ASCII

    # Add to user PATH
    $UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $Entries = @($UserPath -split ";" | Where-Object { $_ })

    if ($Entries -notcontains $BinDir) {
        $NewPath = (@($Entries) + $BinDir) -join ";"
        [Environment]::SetEnvironmentVariable("Path", $NewPath, "User")
    }

    $env:Path = "$BinDir;$env:Path"

    Write-Host ""
    Write-Host "JMon installed successfully!" -ForegroundColor Green
    Write-Host "Run: jmon --help"
}
finally {
    if (Test-Path $TempDir) {
        Remove-Item $TempDir -Recurse -Force
    }
}
