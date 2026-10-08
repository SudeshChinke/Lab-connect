$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

mvn --batch-mode -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE" }
New-Item -ItemType Directory -Force -Path "dist/windows" | Out-Null
Get-ChildItem "dist/windows" -File -ErrorAction SilentlyContinue | Remove-Item -Force
$InputDir = Join-Path ([IO.Path]::GetTempPath()) ("LabConnect-jpackage-input-" + [guid]::NewGuid())
$WorkDir = Join-Path ([IO.Path]::GetTempPath()) ("LabConnect-jpackage-work-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $InputDir, $WorkDir | Out-Null

try {
  Copy-Item "target/labconnect-core-1.0.0-SNAPSHOT.jar" $InputDir
  jpackage `
    --type app-image `
    --name LabConnect `
    --app-version 1.0.0 `
    --vendor LabConnect `
    --description "Local network messaging and file sharing" `
    --input $InputDir `
    --main-jar labconnect-core-1.0.0-SNAPSHOT.jar `
    --main-class com.labconnect.desktop.Launcher `
    --dest $WorkDir `
    --java-options "-Dlabconnect.packaged=true"
  if ($LASTEXITCODE -ne 0) { throw "jpackage app-image creation failed with exit code $LASTEXITCODE" }

  $AppImage = Join-Path $WorkDir "LabConnect"
  # jpackage deliberately creates a reduced runtime; java.exe is not required.
  $RequiredRuntimeFiles = @(
    (Join-Path $AppImage "LabConnect.exe"),
    (Join-Path $AppImage "runtime/bin/java.dll"),
    (Join-Path $AppImage "runtime/bin/server/jvm.dll"),
    (Join-Path $AppImage "runtime/lib/modules")
  )
  $MissingRuntimeFiles = $RequiredRuntimeFiles | Where-Object { -not (Test-Path $_ -PathType Leaf) }
  if ($MissingRuntimeFiles) {
    throw "jpackage app-image is missing required launcher/runtime files: $($MissingRuntimeFiles -join ', ')"
  }

  jpackage `
    --type msi `
    --name LabConnect `
    --app-version 1.0.0 `
    --vendor LabConnect `
    --description "Local network messaging and file sharing" `
    --app-image $AppImage `
    --dest dist/windows `
    --win-menu `
    --win-shortcut `
    --win-per-user-install
  if ($LASTEXITCODE -ne 0) { throw "jpackage MSI creation failed with exit code $LASTEXITCODE" }

  $Installer = Join-Path $RootDir "dist/windows/LabConnect-1.0.0.msi"
  if (-not (Test-Path $Installer -PathType Leaf) -or (Get-Item $Installer).Length -eq 0) {
    throw "jpackage did not produce a non-empty LabConnect-1.0.0.msi"
  }
} finally {
  Remove-Item -Recurse -Force $InputDir
  Remove-Item -Recurse -Force $WorkDir
}

Write-Host "Built Windows installer in dist/windows/"
