$ErrorActionPreference = "Stop"

$rootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $rootDir

$appName = "BAKAPI"
$appVersion = if ($args.Count -gt 0) { $args[0] } else { "1.0.0" }
$mainJar = "target\bakapi-1.0-SNAPSHOT-all.jar"
$distDir = "dist"

mvn -q -DskipTests clean package

if (-not (Test-Path $mainJar)) {
    throw "Nenalezen shaded JAR: $mainJar"
}

New-Item -ItemType Directory -Force -Path $distDir | Out-Null

jpackage `
  --name $appName `
  --app-version $appVersion `
  --input target `
  --main-jar "bakapi-1.0-SNAPSHOT-all.jar" `
  --main-class cz.nero.bakapi.Main `
  --type exe `
  --dest $distDir `
  --win-menu `
  --win-shortcut `
  --win-dir-chooser

Write-Host "Hotovo: balíček je v $distDir/"
