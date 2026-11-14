<#
.SYNOPSIS
  FTB Quests 章节自检：括号配平 / id 唯一 / 依赖存在 / 物品命名空间有对应模组。

.DESCRIPTION
  托管工具 modmind_modpack_write_ftb_quest 目前在收到 snbt 章节内容时会抛
  "Cannot read properties of undefined (reading 'trim')"，写不进任何文件。
  在它修好之前，改完章节后跑这个脚本做同等校验。

.EXAMPLE
  pwsh -File 脚本\check-ftb-quests.ps1
  pwsh -File 脚本\check-ftb-quests.ps1 -ChaptersDir overrides\config\ftbquests\quests\chapters
#>
param(
  [string]$ChaptersDir = (Join-Path $PSScriptRoot '..\overrides\config\ftbquests\quests\chapters'),
  [string]$LockFile    = (Join-Path $PSScriptRoot '..\mods.lock.json'),
  [string]$ModsDir     = (Join-Path $PSScriptRoot '..\.modmind\minecraft\mods')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$errors   = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]
$allIds   = @{}
$chapters = @{}

if (-not (Test-Path $ChaptersDir)) { Write-Error "找不到章节目录: $ChaptersDir"; exit 2 }

# ---- 已知 modId（先读 lock，lock 过期时再按需扫 jar） ----------------------
$knownModIds = @{ 'minecraft' = 'vanilla' }
if (Test-Path $LockFile) {
  try {
    $lock = Get-Content -LiteralPath $LockFile -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($m in $lock.mods) { if ($m.modId) { $knownModIds[$m.modId] = $m.file } }
  } catch { $warnings.Add("mods.lock.json 读不了，改用 jar 元数据判定命名空间") }
}

function Get-JarModIds {
  param([string]$Dir)
  $map = @{}
  if (-not (Test-Path $Dir)) { return $map }
  foreach ($jar in Get-ChildItem $Dir -Filter *.jar) {
    try {
      $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
      foreach ($name in @('META-INF/neoforge.mods.toml', 'META-INF/mods.toml')) {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq $name }
        if ($entry) {
          $sr = New-Object System.IO.StreamReader($entry.Open())
          $text = $sr.ReadToEnd(); $sr.Close()
          foreach ($hit in [regex]::Matches($text, '(?m)^\s*modId\s*=\s*"([^"]+)"')) {
            $map[$hit.Groups[1].Value] = $jar.Name
          }
        }
      }
      $zip.Dispose()
    } catch { $warnings.Add("读不了 jar 元数据: $($jar.Name)") }
  }
  return $map
}

# ---- 逐章检查 --------------------------------------------------------------
$files = Get-ChildItem $ChaptersDir -Filter *.snbt | Sort-Object Name
if ($files.Count -eq 0) { Write-Error "章节目录是空的: $ChaptersDir"; exit 2 }

foreach ($file in $files) {
  $text = Get-Content -LiteralPath $file.FullName -Raw
  $name = $file.Name
  $chapters[$name] = @()

  $ob = ([regex]::Matches($text, '\{')).Count; $cb = ([regex]::Matches($text, '\}')).Count
  $os = ([regex]::Matches($text, '\[')).Count; $cs = ([regex]::Matches($text, '\]')).Count
  if ($ob -ne $cb) { $errors.Add("$name : 花括号不配平 $ob/$cb") }
  if ($os -ne $cs) { $errors.Add("$name : 方括号不配平 $os/$cs") }

  # 数字 id = 章节 / 任务 / 奖励 id，必须全书唯一
  foreach ($hit in [regex]::Matches($text, '(?m)^\s*id:\s*"(\d+)"')) {
    $id = $hit.Groups[1].Value
    $chapters[$name] += $id
    if ($allIds.ContainsKey($id)) {
      $errors.Add("$name : id $id 与 $($allIds[$id]) 重复")
    } else { $allIds[$id] = $name }
  }

  # 依赖必须指向本章存在的任务
  foreach ($dep in [regex]::Matches($text, 'dependencies:\s*\[([^\]]*)\]')) {
    foreach ($ref in [regex]::Matches($dep.Groups[1].Value, '(\d+)')) {
      if ($chapters[$name] -notcontains $ref.Groups[1].Value) {
        $errors.Add("$name : 依赖 $($ref.Groups[1].Value) 在本章不存在")
      }
    }
  }

  # 物品命名空间必须能对上已安装的模组
  foreach ($hit in [regex]::Matches($text, 'id:\s*"([a-z0-9_.\-]+:[a-z0-9_./\-]+)"')) {
    $ns = $hit.Groups[1].Value.Split(':')[0]
    if (-not $knownModIds.ContainsKey($ns)) {
      if (-not $script:jarIds) { $script:jarIds = Get-JarModIds $ModsDir }
      if ($script:jarIds.ContainsKey($ns)) {
        $script:lockStale = $true
        $knownModIds[$ns] = $script:jarIds[$ns]
      } else {
        $errors.Add("$name : 物品命名空间 $ns 没有对应模组（$($hit.Groups[1].Value)）")
      }
    }
  }

  Write-Host ("{0,-28} 任务/奖励 id {1,3}  括号 {2}/{3}" -f $name, $chapters[$name].Count, $ob, $os)
}

Write-Host ''
if ($script:lockStale) { $warnings.Add('mods.lock.json 落后于 mods 目录（有命名空间只在 jar 里找到），建议重建 lock') }
if ($warnings.Count) { Write-Host '警告:'; $warnings | ForEach-Object { Write-Host "  - $_" } }
if ($errors.Count)   { Write-Host '错误:'; $errors   | ForEach-Object { Write-Host "  - $_" }; exit 1 }

Write-Host ("通过：{0} 个章节，{1} 个 id 无重复、无悬空依赖、命名空间齐全。" -f $files.Count, $allIds.Count)
exit 0