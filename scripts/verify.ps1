param(
    [switch]$SkipFrontend,
    [switch]$SkipBackend
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Invoke-CheckedCommand {
    param(
        [string]$WorkingDirectory,
        [string]$Executable,
        [string[]]$Arguments
    )

    Push-Location $WorkingDirectory
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed: $Executable $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

if (-not $SkipBackend) {
    Invoke-CheckedCommand -WorkingDirectory (Join-Path $repoRoot 'backend') -Executable 'mvn' -Arguments @('-B', 'verify')
}

if (-not $SkipFrontend) {
    Invoke-CheckedCommand -WorkingDirectory (Join-Path $repoRoot 'frontend') -Executable 'npm' -Arguments @('run', 'build')
}

Write-Host 'Verification completed successfully.'
