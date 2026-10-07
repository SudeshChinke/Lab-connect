$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $PSScriptRoot
Set-Location $RootDir

mvn --batch-mode -DskipTests clean package
New-Item -ItemType Directory -Force -Path "dist/windows" | Out-Null
Get-ChildItem "dist/windows" -File -ErrorAction SilentlyContinue | Remove-Item -Force

jpackage `
  --type msi `
  --name LabConnect `
  --app-version 1.0.0 `
  --vendor LabConnect `
  --description "Local network messaging and file sharing" `
  --input target `
  --main-jar labconnect-core-1.0.0-SNAPSHOT.jar `
  --main-class com.labconnect.desktop.Launcher `
  --dest dist/windows `
  --win-menu `
  --win-shortcut `
  --win-per-user-install `
  --java-options "-Dlabconnect.packaged=true"

Write-Host "Built Windows installer in dist/windows/"
