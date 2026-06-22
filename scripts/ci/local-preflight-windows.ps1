[CmdletBinding()]
param(
    [string]$Java17Home = $env:JAVA17_HOME,
    [string]$Java21Home = $env:JAVA21_HOME,
    [string]$Java25Home = $env:JAVA25_HOME,
    [string]$MavenCommand
)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path
$lockPath = Join-Path $repoRoot '.git\api-workbench-preflight.lock'
$lockToken = [guid]::NewGuid().ToString()
$lockAcquired = $false
$summary = [System.Collections.Generic.List[object]]::new()
$originalNativeCommandPreference = $null

if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $originalNativeCommandPreference = $PSNativeCommandUseErrorActionPreference
    $PSNativeCommandUseErrorActionPreference = $false
}

Push-Location $repoRoot

function Acquire-PreflightLock {
    param(
        [string]$Path,
        [string]$Token
    )

    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    } catch [System.IO.IOException] {
        throw "Another Windows/WSL preflight may be running: $Path already exists"
    }

    try {
        $writer = [System.IO.StreamWriter]::new($stream)
        $writer.WriteLine("token=$Token")
        $writer.WriteLine("os=Windows")
        $writer.WriteLine("pid=$PID")
        $writer.WriteLine("timestamp=$(Get-Date -Format o)")
        $writer.WriteLine("hostname=$env:COMPUTERNAME")
        $writer.Flush()
    } finally {
        if ($writer) {
            $writer.Dispose()
        }
        $stream.Dispose()
    }

    $script:lockAcquired = $true
}

function Release-PreflightLock {
    param(
        [string]$Path,
        [string]$Token
    )

    if (-not $script:lockAcquired) {
        return
    }

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    $storedToken = Get-Content -LiteralPath $Path -TotalCount 1 -ErrorAction SilentlyContinue
    if ($storedToken -eq "token=$Token") {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    }
}

function Resolve-MavenCommand {
    param([string]$RequestedCommand)

    if ($RequestedCommand) {
        return (Resolve-Path $RequestedCommand).Path
    }

    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvn) {
        return $mvn.Source
    }

    $repoMaven = Join-Path $repoRoot '.tools\maven\apache-maven-3.9.9\bin\mvn.cmd'
    if (Test-Path $repoMaven) {
        return (Resolve-Path $repoMaven).Path
    }

    throw "Maven command not found. Provide -MavenCommand or add mvn to PATH."
}

function Add-SummaryRow {
    param(
        [string]$Java,
        [string]$Result
    )

    $summary.Add([pscustomobject]@{
        OS      = 'Windows'
        Java    = $Java
        Command = 'mvn -B clean verify'
        Result  = $Result
    }) | Out-Null
}

function Show-Summary {
    if ($summary.Count -eq 0) {
        return
    }

    ''
    '| OS | Java | Command | Result |'
    '|---|---:|---|---:|'
    foreach ($row in $summary) {
        "| $($row.OS) | $($row.Java) | ``$($row.Command)`` | $($row.Result) |"
    }
    ''
}

function Get-JavaMajorVersion {
    param([string]$JavaExe)

    $properties = cmd /c ('"' + $JavaExe + '" -XshowSettings:properties -version 2>&1')
    $line = $properties | Select-String 'java.specification.version ='
    if (-not $line) {
        throw "Unable to detect java.specification.version for $JavaExe"
    }

    $rawVersion = ($line.Line -split '=')[-1].Trim()
    if ($rawVersion -notmatch '^\d+$') {
        throw "Unsupported java.specification.version '$rawVersion' for $JavaExe"
    }

    return [int]$rawVersion
}

function Invoke-VerifyRun {
    param(
        [int]$ExpectedMajor,
        [string]$JavaHome
    )

    if (-not $JavaHome) {
        Add-SummaryRow -Java $ExpectedMajor -Result 'FAIL'
        Show-Summary
        throw "JAVA${ExpectedMajor}_HOME is required."
    }

    $resolvedJavaHome = (Resolve-Path $JavaHome -ErrorAction SilentlyContinue)
    if (-not $resolvedJavaHome) {
        Add-SummaryRow -Java $ExpectedMajor -Result 'FAIL'
        Show-Summary
        throw "JAVA${ExpectedMajor}_HOME path does not exist: $JavaHome"
    }

    $javaExe = Join-Path $resolvedJavaHome.Path 'bin\java.exe'
    if (-not (Test-Path $javaExe)) {
        Add-SummaryRow -Java $ExpectedMajor -Result 'FAIL'
        Show-Summary
        throw "java.exe not found under $resolvedJavaHome"
    }

    cmd /c ('"' + $javaExe + '" -version')
    $actualMajor = Get-JavaMajorVersion -JavaExe $javaExe
    if ($actualMajor -ne $ExpectedMajor) {
        Add-SummaryRow -Java $ExpectedMajor -Result 'FAIL'
        Show-Summary
        throw "Expected Java $ExpectedMajor at $resolvedJavaHome but detected Java $actualMajor"
    }

    $env:JAVA_HOME = $resolvedJavaHome.Path
    $env:Path = "$($resolvedJavaHome.Path)\bin;$originalPath"

    & $script:mavenCommand -B clean verify
    if ($LASTEXITCODE -ne 0) {
        Add-SummaryRow -Java $ExpectedMajor -Result 'FAIL'
        Show-Summary
        throw "mvn -B clean verify failed for Java $ExpectedMajor"
    }

    Add-SummaryRow -Java $ExpectedMajor -Result 'PASS'
}

try {
    Acquire-PreflightLock -Path $lockPath -Token $lockToken
    $script:mavenCommand = Resolve-MavenCommand -RequestedCommand $MavenCommand

    Invoke-VerifyRun -ExpectedMajor 17 -JavaHome $Java17Home
    Invoke-VerifyRun -ExpectedMajor 21 -JavaHome $Java21Home
    Invoke-VerifyRun -ExpectedMajor 25 -JavaHome $Java25Home

    Show-Summary
} finally {
    Release-PreflightLock -Path $lockPath -Token $lockToken
    $env:JAVA_HOME = $originalJavaHome
    $env:Path = $originalPath
    if ($null -ne $originalNativeCommandPreference) {
        $PSNativeCommandUseErrorActionPreference = $originalNativeCommandPreference
    }
    Pop-Location
}
