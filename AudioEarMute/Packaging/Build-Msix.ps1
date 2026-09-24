<#
.SYNOPSIS
    Builds an MSIX package of AudioEarMute ready for upload to Partner Center.

.DESCRIPTION
    Publishes the app self-contained for win-x64, stages it together with the manifest
    and the generated assets, then packs it with makeappx.exe from the Windows SDK.

    The package is left unsigned by default. That is deliberate: the Microsoft Store
    re-signs uploaded packages with its own certificate, so a developer signature is not
    needed for submission. Pass -SignForLocalTesting to get a self-signed package you can
    actually install on this machine to try before submitting.

.PARAMETER IdentityName
    Package identity name from Partner Center, Product identity. Looks like
    12345YourName.AudioEarMute

.PARAMETER IdentityPublisher
    Package identity publisher from the same page. Looks like
    CN=A1B2C3D4-1234-5678-9ABC-DEF012345678

.PARAMETER PublisherDisplayName
    Publisher display name from the same page, shown to users in the Store.

.EXAMPLE
    .\Build-Msix.ps1 -IdentityName 12345Greg.AudioEarMute `
                     -IdentityPublisher "CN=A1B2C3D4-1234-5678-9ABC-DEF012345678" `
                     -PublisherDisplayName "Greg"

.EXAMPLE
    .\Build-Msix.ps1 -IdentityName ... -IdentityPublisher ... -PublisherDisplayName ... `
                     -SignForLocalTesting
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $IdentityName,
    [Parameter(Mandatory = $true)] [string] $IdentityPublisher,
    [Parameter(Mandatory = $true)] [string] $PublisherDisplayName,
    [string] $Version = '1.0.0.0',
    [switch] $SignForLocalTesting,
    [string] $OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptRoot = $PSScriptRoot
if (-not $scriptRoot) { $scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }

$projectDirectory = Split-Path -Parent $scriptRoot
$projectFile = Join-Path $projectDirectory 'AudioEarMute.csproj'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $scriptRoot 'out' }

$stageDirectory = Join-Path $OutputDirectory 'stage'
$packagePath = Join-Path $OutputDirectory 'AudioEarMute.msix'

# The Store rejects a four-part version whose revision is not zero. Catch it here rather
# than after a five-minute upload.
if ($Version -notmatch '^\d+\.\d+\.\d+\.0$') {
    throw "Version must be a.b.c.0 - the Store reserves the fourth part. Got '$Version'."
}

function Find-SdkTool {
    param([Parameter(Mandatory = $true)][string] $ToolName)

    $roots = @(
        "${env:ProgramFiles(x86)}\Windows Kits\10\bin",
        "$env:ProgramFiles\Windows Kits\10\bin"
    ) | Where-Object { $_ -and (Test-Path $_) }

    $candidates = foreach ($root in $roots) {
        Get-ChildItem -Path $root -Filter $ToolName -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match '\\x64\\' }
    }

    # Highest SDK version wins; the tools are backwards compatible.
    $best = $candidates | Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $best) {
        throw @"
$ToolName was not found. Install the Windows 10/11 SDK, which provides the MSIX tools:

    winget install Microsoft.WindowsSDK.10.0.26100

Only the 'Windows SDK Signing Tools' and 'MSIX Packaging Tools' components are required.
"@
    }

    return $best.FullName
}

Write-Output '=== 1. Checking the .NET SDK ==='
$dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
if (-not $dotnet) {
    throw @'
dotnet was not found on PATH. Install the .NET 8 SDK:

    winget install Microsoft.DotNet.SDK.8
'@
}
Write-Output "dotnet: $($dotnet.Source)"

Write-Output ''
Write-Output '=== 2. Running the unit tests ==='
$testProject = Join-Path (Split-Path -Parent $projectDirectory) 'AudioEarMute.Tests\AudioEarMute.Tests.csproj'
if (Test-Path $testProject) {
    & dotnet test $testProject -c Release --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Unit tests failed. Not packaging a broken build.' }
} else {
    Write-Warning "Test project not found at $testProject - skipping."
}

Write-Output ''
Write-Output '=== 3. Generating assets ==='
& (Join-Path $scriptRoot 'Generate-Assets.ps1') | Select-Object -Last 1

Write-Output ''
Write-Output '=== 4. Publishing the app ==='
if (Test-Path $stageDirectory) { Remove-Item -Recurse -Force -Path $stageDirectory }
New-Item -ItemType Directory -Path $stageDirectory -Force | Out-Null

# The assembly version is the three-part form; the package keeps the four-part one.
# Built into a variable first: an inline "-p:Version=(expression)" is split by PowerShell
# into two arguments, and MSBuild then reports a second project file.
$assemblyVersion = $Version -replace '\.0$', ''

# Self-contained so the package carries its own runtime - a Store user must not be asked
# to install .NET first. Not single-file: inside MSIX that only adds extraction cost.
& dotnet publish $projectFile `
    -c Release `
    -r win-x64 `
    --self-contained true `
    -p:PublishSingleFile=false `
    "-p:Version=$assemblyVersion" `
    -o $stageDirectory `
    --nologo
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed.' }

# The publish output carries debug symbols that have no business in a shipped package.
Get-ChildItem -Path $stageDirectory -Filter *.pdb -Recurse | Remove-Item -Force

Write-Output ''
Write-Output '=== 5. Staging the manifest and assets ==='
$manifest = Get-Content (Join-Path $scriptRoot 'AppxManifest.xml') -Raw
$manifest = $manifest.Replace('__IDENTITY_NAME__', $IdentityName)
$manifest = $manifest.Replace('__IDENTITY_PUBLISHER__', $IdentityPublisher)
$manifest = $manifest.Replace('__PUBLISHER_DISPLAY_NAME__', $PublisherDisplayName)

if ($manifest -match '__[A-Z_]+__') {
    throw "A placeholder was left unreplaced in the manifest: $($Matches[0])"
}

# Set the version on the Identity node specifically. A regex over the whole document
# would also rewrite MinVersion and MaxVersionTested on TargetDeviceFamily, because
# 'MinVersion="10.0.17763.0"' ends with a substring that looks exactly like a match.
# That silently shipped a package claiming to support Windows 1.0.
$manifestXml = New-Object System.Xml.XmlDocument
$manifestXml.PreserveWhitespace = $true
$manifestXml.LoadXml($manifest)

$namespaces = New-Object System.Xml.XmlNamespaceManager($manifestXml.NameTable)
$namespaces.AddNamespace('d', 'http://schemas.microsoft.com/appx/manifest/foundation/windows10')

$identityNode = $manifestXml.SelectSingleNode('/d:Package/d:Identity', $namespaces)
if (-not $identityNode) { throw 'No Identity element found in AppxManifest.xml.' }
$identityNode.SetAttribute('Version', $Version)

$manifest = $manifestXml.OuterXml

# Guard the thing that just went wrong: the target device family must still declare a
# real Windows 10 floor.
$targetFamily = $manifestXml.SelectSingleNode('/d:Package/d:Dependencies/d:TargetDeviceFamily', $namespaces)
if (-not $targetFamily -or -not $targetFamily.GetAttribute('MinVersion').StartsWith('10.')) {
    throw "TargetDeviceFamily MinVersion is '$($targetFamily.GetAttribute('MinVersion'))', expected a 10.x build."
}

# UTF-8 without a BOM: makeappx rejects a manifest that starts with one.
[System.IO.File]::WriteAllText(
    (Join-Path $stageDirectory 'AppxManifest.xml'),
    $manifest,
    (New-Object System.Text.UTF8Encoding($false)))

Copy-Item -Path (Join-Path $scriptRoot 'Assets') -Destination (Join-Path $stageDirectory 'Assets') -Recurse

Write-Output ''
Write-Output '=== 6. Packing the MSIX ==='
$makeappx = Find-SdkTool -ToolName 'makeappx.exe'
Write-Output "makeappx: $makeappx"

if (Test-Path $packagePath) { Remove-Item -Force -Path $packagePath }
& $makeappx pack /d $stageDirectory /p $packagePath /o
if ($LASTEXITCODE -ne 0) { throw 'makeappx failed.' }

if ($SignForLocalTesting) {
    Write-Output ''
    Write-Output '=== 7. Signing for local testing ==='
    Write-Output 'This signature is for trying the package on this machine only.'
    Write-Output 'The Store re-signs whatever you upload, so do not submit a self-signed package.'

    # The certificate subject must match Identity/Publisher exactly or signing succeeds
    # and installation then fails with a confusing "publisher mismatch".
    $cert = New-SelfSignedCertificate `
        -Type Custom `
        -Subject $IdentityPublisher `
        -KeyUsage DigitalSignature `
        -FriendlyName 'AudioEarMute local test' `
        -CertStoreLocation 'Cert:\CurrentUser\My' `
        -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}')

    $signtool = Find-SdkTool -ToolName 'signtool.exe'
    & $signtool sign /fd SHA256 /sha1 $cert.Thumbprint $packagePath
    if ($LASTEXITCODE -ne 0) { throw 'signtool failed.' }

    $cerPath = Join-Path $OutputDirectory 'AudioEarMute-test.cer'
    Export-Certificate -Cert $cert -FilePath $cerPath | Out-Null

    Write-Output ''
    Write-Output 'To install locally, first trust the test certificate from an elevated prompt:'
    Write-Output "  Import-Certificate -FilePath `"$cerPath`" -CertStoreLocation Cert:\LocalMachine\TrustedPeople"
    Write-Output 'then double-click the .msix.'
}

Write-Output ''
Write-Output '=== Done ==='
Write-Output "Package: $packagePath"
Write-Output "Size:    $([math]::Round((Get-Item $packagePath).Length / 1MB, 1)) MB"
Write-Output ''
Write-Output 'Upload this file on the Packages tab of the submission in Partner Center.'
