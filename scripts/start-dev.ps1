param(
  [string]$WslDistro = "Ubuntu"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Backend = Join-Path $Root "backend"
$Frontend = Join-Path $Root "frontend"
$Runtime = Join-Path $Root ".runtime"
$EnvFile = Join-Path $Root ".env"

if (!(Test-Path -LiteralPath $EnvFile)) {
  throw "Missing .env. Copy .env.example to .env first."
}
New-Item -ItemType Directory -Force -Path $Runtime | Out-Null

function Import-DotEnv {
  param([string]$Path)
  foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -match '^\s*#' -or $line -match '^\s*$') { continue }
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_.]*)\s*=\s*(.*)\s*$') {
      $name = $Matches[1]
      $value = $Matches[2]
      if ($value.StartsWith('"') -and $value.EndsWith('"') -and $value.Length -ge 2) {
        $value = $value.Substring(1, $value.Length - 2)
      } elseif ($value.StartsWith("'") -and $value.EndsWith("'") -and $value.Length -ge 2) {
        $value = $value.Substring(1, $value.Length - 2)
      }
      [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
  }
}

function Test-Http {
  param([string]$Url)
  try {
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
    return $response.StatusCode -ge 200 -and $response.StatusCode -lt 400
  } catch {
    return $false
  }
}

function Wait-Http {
  param(
    [string]$Url,
    [int]$TimeoutSeconds = 180,
    [string]$Name
  )
  $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
  while ([DateTime]::UtcNow -lt $deadline) {
    if (Test-Http -Url $Url) {
      Write-Host "[ok] $Name is ready: $Url"
      return
    }
    Start-Sleep -Seconds 2
  }
  throw "$Name did not become ready at $Url within $TimeoutSeconds seconds."
}

Import-DotEnv -Path $EnvFile

function Test-PortFree {
  param([int]$Port)
  return -not (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

if (!$env:POSTGRES_PORT) {
  $candidate = 5432
  while ($candidate -lt 5460 -and !(Test-PortFree -Port $candidate)) { $candidate++ }
  if ($candidate -ge 5460) { throw "No free PostgreSQL host port between 5432 and 5459." }
  $env:POSTGRES_PORT = [string]$candidate
}
if (!$env:SPRING_DATASOURCE_URL) {
  $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$($env:POSTGRES_PORT)/tutor"
}
Write-Host "[info] PostgreSQL host port: $($env:POSTGRES_PORT)"


$requiredCommands = @("mvn", "node", "npm", "wsl.exe")
foreach ($commandName in $requiredCommands) {
  if (!(Get-Command $commandName -ErrorAction SilentlyContinue)) {
    throw "Required command not found: $commandName"
  }
}

# Start PostgreSQL and Neo4j through WSL because Docker is only available there.
$drive = $Root.Substring(0, 1).ToLowerInvariant()
$relativeRoot = $Root.Substring(3).Replace('\', '/')
$wslRoot = "/mnt/$drive/$relativeRoot"
Write-Host "[1/4] Starting dependencies with Docker Compose..."
  & wsl.exe -d $WslDistro -- bash -lc "cd '$wslRoot' && POSTGRES_PORT=$env:POSTGRES_PORT docker compose --env-file .env up -d"
if ($LASTEXITCODE -ne 0) {
  throw "docker compose up failed. Ensure WSL distribution '$WslDistro' and Docker are running."
}

# Backend
$backendUrl = "http://127.0.0.1:8180/readyz"
$backendPortBusy = [bool](Get-NetTCPConnection -LocalPort 8180 -State Listen -ErrorAction SilentlyContinue)
$backendStarted = $false
if ($backendPortBusy -and (Test-Http -Url $backendUrl)) {
  Write-Host "[2/4] Backend already running: $backendUrl"
} elseif ($backendPortBusy) {
  throw "Port 8180 is occupied, but the backend health endpoint is unavailable."
} else {
  Write-Host "[2/4] Starting backend with Maven..."
  $maven = (Get-Command mvn).Source
  $backendOut = Join-Path $Runtime "backend-dev.log"
  $backendErr = Join-Path $Runtime "backend-dev.err.log"
  $backendProcess = Start-Process -FilePath $maven -ArgumentList @("-DskipTests", "spring-boot:run") -WorkingDirectory $Backend -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr -WindowStyle Hidden -PassThru
  $backendStarted = $true
  Wait-Http -Url $backendUrl -Name "Backend" -TimeoutSeconds 240
}

# Frontend
$frontendUrl = "http://127.0.0.1:5173/"
$frontendPortBusy = [bool](Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue)
if ($frontendPortBusy -and (Test-Http -Url $frontendUrl)) {
  Write-Host "[3/4] Frontend already running: $frontendUrl"
} elseif ($frontendPortBusy) {
  throw "Port 5173 is occupied, but the frontend health endpoint is unavailable."
} else {
  Write-Host "[3/4] Starting frontend with Vite..."
  if (!(Test-Path -LiteralPath (Join-Path $Frontend "node_modules"))) {
    Push-Location $Frontend
    try {
      & npm install
      if ($LASTEXITCODE -ne 0) { throw "npm install failed." }
    } finally {
      Pop-Location
    }
  }
  $npm = (Get-Command npm).Source
  $frontendOut = Join-Path $Runtime "frontend-dev.log"
  $frontendErr = Join-Path $Runtime "frontend-dev.err.log"
  $frontendProcess = Start-Process -FilePath $npm -ArgumentList @("run", "dev") -WorkingDirectory $Frontend -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr -WindowStyle Hidden -PassThru
  Wait-Http -Url $frontendUrl -Name "Frontend" -TimeoutSeconds 120
}

Write-Host "[4/4] Project started."
Write-Host "Frontend: http://127.0.0.1:5173"
Write-Host "Backend:  http://127.0.0.1:8180"
Write-Host "Logs:     $Runtime"
