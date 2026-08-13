param(
  [string]$OutputDir = "backups"
)

$ErrorActionPreference = "Stop"
$resolved = [System.IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force -Path $resolved | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$file = Join-Path $resolved "tutor-$stamp.sql"

docker compose --env-file .env -f docker-compose.prod.yml exec -T postgres `
  pg_dump -U tutor -d tutor --no-owner --no-privileges > $file

if (-not (Test-Path -LiteralPath $file) -or (Get-Item -LiteralPath $file).Length -lt 100) {
  throw "Backup was not created or is unexpectedly small: $file"
}
Write-Output "Created PostgreSQL backup: $file"
