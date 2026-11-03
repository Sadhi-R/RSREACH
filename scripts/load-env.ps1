# Loads KEY=VALUE pairs from a dotenv file into the current PowerShell process.
# Usage (from repo root):
#   . .\scripts\load-env.ps1
#   . .\scripts\load-env.ps1 -EnvFile .env.local

param(
    [string]$EnvFile = ".env.local"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$path = Join-Path $root $EnvFile

if (-not (Test-Path $path)) {
    throw "Env file not found: $path. Copy .env.local.example to .env.local first."
}

Get-Content -Path $path | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) {
        return
    }
    $idx = $line.IndexOf("=")
    if ($idx -lt 1) {
        return
    }
    $key = $line.Substring(0, $idx).Trim()
    $value = $line.Substring($idx + 1).Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    Set-Item -Path "Env:$key" -Value $value
}

Write-Host "Loaded environment from $path"
