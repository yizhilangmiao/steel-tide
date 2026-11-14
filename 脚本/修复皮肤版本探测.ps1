# 修复 CustomSkinLoader 在 PCL 实例上崩溃（version.json 缺失 → 版本被判为 0）
# 背景：CSL 通过 ClassLoader.getSystemClassLoader().getResourceAsStream("version.json") 判断 MC 版本。
# PCL 生成的版本 JSON 不把原版 client jar 放进 -cp，只放一个不存在的 "<版本名>.jar" 占位，
# 于是 CSL 读到版本 0，挑错补丁变体，抛 "did not modify any bytecode" 直接崩在 Minecraft.<init>。
# 修法：在 -cp 里那个占位 jar 的位置放一个只含 version.json 的 jar（无 class，等同官方 client-extra）。
#
# 用法：pwsh -File .\脚本\修复皮肤版本探测.ps1 -VersionDir "D:\mc\versions\钢铁潮汐-开发"
# 撤销：删掉生成的 <版本名>.jar 即可。

param(
    [Parameter(Mandatory = $true)]
    [string]$VersionDir
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not (Test-Path -LiteralPath $VersionDir)) {
    throw "版本目录不存在：$VersionDir"
}
$VersionDir = (Resolve-Path -LiteralPath $VersionDir).Path
$versionName = Split-Path -Leaf $VersionDir
$versionJsonPath = Join-Path $VersionDir "$versionName.json"

if (-not (Test-Path -LiteralPath $versionJsonPath)) {
    throw "找不到版本 JSON：$versionJsonPath"
}

$versionJson = Get-Content -LiteralPath $versionJsonPath -Raw | ConvertFrom-Json
$parentId = $versionJson.inheritsFrom
if (-not $parentId) { $parentId = $versionJson.id }
if (-not $parentId) { $parentId = '1.21.1' }

$versionsRoot = Split-Path -Parent $VersionDir
$parentDir = Join-Path $versionsRoot $parentId
$parentJar = Join-Path $parentDir "$parentId.jar"
if (-not (Test-Path -LiteralPath $parentJar)) {
    throw "找不到原版 client jar（该 jar 内含 version.json）：$parentJar；请先在 PCL 里安装/补全 $parentId"
}

# 读出原版 jar 根目录的 version.json
$srcZip = [System.IO.Compression.ZipFile]::OpenRead($parentJar)
try {
    $entry = $srcZip.Entries | Where-Object { $_.FullName -eq 'version.json' } | Select-Object -First 1
    if (-not $entry) { throw "$parentJar 里没有 version.json，无法作为版本元数据源" }
    $ms = New-Object System.IO.MemoryStream
    $entry.Open().CopyTo($ms)
    $payload = $ms.ToArray()
}
finally {
    $srcZip.Dispose()
}

$targetJar = Join-Path $VersionDir "$versionName.jar"
if (Test-Path -LiteralPath $targetJar) {
    $existing = [System.IO.Compression.ZipFile]::OpenRead($targetJar)
    try { $existingNames = $existing.Entries.FullName | Sort-Object } finally { $existing.Dispose() }
    if (($existingNames -join ',') -ne 'version.json') {
        throw "目标已存在且不是我们生成的占位 jar（内容：$($existingNames -join ', ')），已中止，未做任何改动：$targetJar"
    }
    Remove-Item -LiteralPath $targetJar -Force
}

$fs = [System.IO.File]::Create($targetJar)
try {
    $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $ze = $zip.CreateEntry('version.json', [System.IO.Compression.CompressionLevel]::Optimal)
        $out = $ze.Open()
        try { $out.Write($payload, 0, $payload.Length) } finally { $out.Dispose() }
    }
    finally { $zip.Dispose() }
}
finally { $fs.Dispose() }

# 回读校验
$check = [System.IO.Compression.ZipFile]::OpenRead($targetJar)
try {
    $names = $check.Entries.FullName
    $e2 = $check.Entries | Where-Object { $_.FullName -eq 'version.json' } | Select-Object -First 1
    $r = New-Object System.IO.StreamReader($e2.Open())
    $text = $r.ReadToEnd(); $r.Close()
    $meta = $text | ConvertFrom-Json
    Write-Host "已生成：$targetJar（$((Get-Item -LiteralPath $targetJar).Length) B，条目：$($names -join ', ')）" -ForegroundColor Green
    Write-Host "版本元数据：name=$($meta.name) protocol_version=$($meta.protocol_version) world_version=$($meta.world_version)" -ForegroundColor Green
    Write-Host "现在重启客户端，CustomSkinLoader 应该能正确识别版本并不再崩溃。" -ForegroundColor Green
    Write-Host "撤销：删除 $targetJar" -ForegroundColor Yellow
}
finally { $check.Dispose() }
