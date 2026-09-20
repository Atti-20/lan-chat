param(
    [Parameter(Mandatory = $true)]
    [string]$BundleRoot,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedVersion,

    [string]$ReportDirectory = "release-verification/windows"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path -LiteralPath $BundleRoot -PathType Container)) {
    throw "Bundle root does not exist: $BundleRoot"
}
if ([string]::IsNullOrWhiteSpace($env:TAURI_WINDOWS_CERTIFICATE_THUMBPRINT)) {
    throw "TAURI_WINDOWS_CERTIFICATE_THUMBPRINT is required"
}

New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null

function Get-SingleFile {
    param([string]$Filter)

    $files = @(
        Get-ChildItem -LiteralPath $BundleRoot -Recurse -File -Filter $Filter
    )
    if ($files.Count -ne 1) {
        throw "Expected exactly one $Filter below $BundleRoot; found $($files.Count)"
    }
    return $files[0]
}

function Assert-AuthenticodeSignature {
    param([System.IO.FileInfo]$File)

    $signature = Get-AuthenticodeSignature -FilePath $File.FullName
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
        throw "$($File.Name) Authenticode status is $($signature.Status): $($signature.StatusMessage)"
    }
    if ($null -eq $signature.SignerCertificate) {
        throw "$($File.Name) has no signer certificate"
    }
    if ($null -eq $signature.TimeStamperCertificate) {
        throw "$($File.Name) has no Authenticode timestamp certificate"
    }

    $expectedThumbprint = $env:TAURI_WINDOWS_CERTIFICATE_THUMBPRINT.Replace(" ", "").ToUpperInvariant()
    $actualThumbprint = $signature.SignerCertificate.Thumbprint.Replace(" ", "").ToUpperInvariant()
    if ($actualThumbprint -ne $expectedThumbprint) {
        throw "$($File.Name) signer thumbprint $actualThumbprint does not match $expectedThumbprint"
    }

    [PSCustomObject]@{
        File = $File.Name
        Status = $signature.Status.ToString()
        Subject = $signature.SignerCertificate.Subject
        Thumbprint = $actualThumbprint
        NotAfter = $signature.SignerCertificate.NotAfter.ToUniversalTime().ToString("o")
        TimestampSubject = $signature.TimeStamperCertificate.Subject
    }
}

$nsis = Get-SingleFile "*-setup.exe"
$msi = Get-SingleFile "*.msi"
$nsisSignature = Get-SingleFile "*-setup.exe.sig"
$msiSignature = Get-SingleFile "*.msi.sig"

foreach ($file in @($nsis, $msi, $nsisSignature, $msiSignature)) {
    if ($file.Length -le 0) {
        throw "Release artifact is empty: $($file.FullName)"
    }
}
if (-not $nsis.Name.Contains("_${ExpectedVersion}_")) {
    throw "NSIS filename does not contain version ${ExpectedVersion}: $($nsis.Name)"
}
if (-not $msi.Name.Contains("_${ExpectedVersion}_")) {
    throw "MSI filename does not contain version ${ExpectedVersion}: $($msi.Name)"
}

$signatureReport = @(
    Assert-AuthenticodeSignature -File $nsis
    Assert-AuthenticodeSignature -File $msi
)
$signatureReport |
    ConvertTo-Json -Depth 3 |
    Set-Content -LiteralPath (Join-Path $ReportDirectory "authenticode.json") -Encoding utf8

Get-FileHash -Algorithm SHA256 -LiteralPath @(
    $nsis.FullName,
    $msi.FullName,
    $nsisSignature.FullName,
    $msiSignature.FullName
) |
    Select-Object Path, Hash |
    ConvertTo-Json -Depth 3 |
    Set-Content -LiteralPath (Join-Path $ReportDirectory "sha256.json") -Encoding utf8

Write-Output "Verified Windows Authenticode signatures and updater artifact."
