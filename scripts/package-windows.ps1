$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

mvn --batch-mode -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE" }
New-Item -ItemType Directory -Force -Path "dist/windows" | Out-Null
Get-ChildItem "dist/windows" -File -ErrorAction SilentlyContinue | Remove-Item -Force
$InputDir = Join-Path ([IO.Path]::GetTempPath()) ("LabConnect-jpackage-input-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $InputDir | Out-Null

try {
  Copy-Item "target/labconnect-core-1.0.0-SNAPSHOT.jar" $InputDir
  jpackage `
    --type msi `
    --name LabConnect `
    --app-version 1.0.0 `
    --vendor LabConnect `
    --description "Local network messaging and file sharing" `
    --input $InputDir `
    --main-jar labconnect-core-1.0.0-SNAPSHOT.jar `
    --main-class com.labconnect.desktop.Launcher `
    --dest dist/windows `
    --win-menu `
    --win-shortcut `
    --win-per-user-install `
    --java-options "-Dlabconnect.packaged=true"
  if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }
} finally {
  Remove-Item -Recurse -Force $InputDir
}

Write-Host "Built Windows installer in dist/windows/"
