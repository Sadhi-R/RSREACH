# Starts rsreach-api against local PostgreSQL using .env.local.
# Prerequisites: Java 17, Maven, .env.local. Flyway runs on API startup.

param(
    [string]$EnvFile = ".env.local"
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "load-env.ps1") -EnvFile $EnvFile

$mvn = $env:RSREACH_MVN
if (-not $mvn) {
    $candidate = Join-Path $env:LOCALAPPDATA "Temp\apache-maven-3.9.9\bin\mvn.cmd"
    if (Test-Path $candidate) { $mvn = $candidate } else { $mvn = "mvn" }
}

Set-Location (Join-Path $PSScriptRoot "..\backend")
& $mvn -pl rsreach-api -am -DskipTests install
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $mvn -pl rsreach-api spring-boot:run "-Dspring-boot.run.profiles=local"
