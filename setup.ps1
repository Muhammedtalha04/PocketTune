param(
    [switch]$CheckOnly,
    [switch]$SkipRuntimeTools,
    [switch]$SkipVerification
)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'scripts/setup-dev.ps1') @PSBoundParameters
