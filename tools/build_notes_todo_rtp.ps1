param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"

$source = Join-Path $ProjectRoot (
    "upstream\oneplus13_hyper_haptics_0.8.8\app\src\main\assets" +
    "\rtp\effect_206.bin"
)
$destination = Join-Path $ProjectRoot "app\src\main\assets\rtp\effect_206.bin"
$expectedSha256 = "97CDC11E2236AFBDB59F517288558B2356857C5071B5DC26182726F4EDC86718"

if (!(Test-Path -LiteralPath $source)) {
    throw "Missing source RTP: $source"
}
if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $expectedSha256) {
    throw "Unexpected effect 206 source; refusing to trim unknown waveform"
}

$inputBytes = [IO.File]::ReadAllBytes($source)

# The Xiaomi-tuned source contains two carrier lobes separated by 79 ms of
# digital silence. Xiaomi's slower mechanical decay blends them into one
# gesture; the OnePlus 13 actuator brakes fast enough to expose two distinct
# taps. Remove only that silent interval and join at the +/-1 zero crossing.
$prefixEnd = 260
$suffixStart = 2162
$outputLength = ($prefixEnd + 1) + ($inputBytes.Length - $suffixStart)
$outputBytes = New-Object byte[] $outputLength
[Buffer]::BlockCopy($inputBytes, 0, $outputBytes, 0, $prefixEnd + 1)
[Buffer]::BlockCopy(
    $inputBytes,
    $suffixStart,
    $outputBytes,
    $prefixEnd + 1,
    $inputBytes.Length - $suffixStart
)

[IO.Directory]::CreateDirectory((Split-Path -Parent $destination)) | Out-Null
[IO.File]::WriteAllBytes($destination, $outputBytes)

$durationMs = [Math]::Round($outputBytes.Length / 24.0, 2)
$sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
Write-Output "effect 206: $($inputBytes.Length) -> $($outputBytes.Length) bytes, ${durationMs}ms, SHA256=$sha256"
