param(
  [string]$EnvFile = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($EnvFile)) {
  $EnvFile = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) ".env"
}

if (-not (Test-Path $EnvFile)) {
  exit 0
}

Get-Content $EnvFile | ForEach-Object {
  if ($_ -match "^\s*#" -or $_ -notmatch "=") { return }
  $parts = $_.Split("=", 2)
  $key = $parts[0].Trim()
  if ([string]::IsNullOrWhiteSpace($key)) { return }
  $value = $parts[1]
  Write-Output "$key=$value"
}
