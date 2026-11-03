param([string]$EnvFile = ".env.local")
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "load-env.ps1") -EnvFile $EnvFile
$mvn = $env:RSREACH_MVN
if (-not $mvn) {
    $candidate = Join-Path $env:LOCALAPPDATA "Temp\apache-maven-3.9.9\bin\mvn.cmd"
    if (Test-Path $candidate) { $mvn = $candidate } else { $mvn = "mvn" }
}
Set-Location (Join-Path $PSScriptRoot "..\backend")
& $mvn -pl rsreach-scheduler -am -DskipTests install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $mvn -pl rsreach-scheduler spring-boot:run "-Dspring-boot.run.profiles=local"
