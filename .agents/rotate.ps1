# .agents\rotate.ps1  —  历史版本轮转：只留最近 N 个成品，更老的进回收站（不硬删）。
#
# 目录与匹配规则来自 env.json 的 history 段，不在脚本里写死项目目录：
#   "history": [
#     { "kind": "pc",     "dir": "发布版\历史版本", "patterns": ["*.zip", "*.7z"] },
#     { "kind": "mobile", "dir": "手机版\历史版本", "patterns": ["*.apk"] }
#   ]
#
# 用法:
#   pwsh -File .agents\rotate.ps1 -DryRun          # 所有分区，先看会删谁
#   pwsh -File .agents\rotate.ps1                  # 执行（默认留 2 个）
#   pwsh -File .agents\rotate.ps1 -Kind mobile -Keep 3
param(
  [string]$Kind = '',
  [int]$Keep = 2,
  [switch]$DryRun
)

$ErrorActionPreference = 'SilentlyContinue'
$root = $PSScriptRoot
$proj = Split-Path $root -Parent
$envf = Join-Path $root 'env.json'

if (-not (Test-Path $envf)) { Write-Host 'FATAL: env.json 不存在'; exit 2 }
$cfg = Get-Content $envf -Raw -Encoding UTF8 | ConvertFrom-Json

$sections = @()
if ($cfg.history) { $sections = @($cfg.history) }
if ($Kind) { $sections = @($sections | Where-Object { $_.kind -eq $Kind }) }
if ($sections.Count -eq 0) {
  Write-Host '没有可轮转的历史版本分区。'
  Write-Host '在 env.json 里加 history 段（kind / dir / patterns），或用 -Kind 指定已有分区。'
  exit 0
}

$totalFreed = 0
$totalDropped = 0
$rc = 0

foreach ($sec in $sections) {
  $target = Join-Path $proj ([string]$sec.dir)
  if (-not (Test-Path $target)) { Write-Host "跳过 [$($sec.kind)]: 目录不存在 $target"; continue }

  $patterns = @($sec.patterns)
  if (-not $patterns -or $patterns.Count -eq 0) { $patterns = @('*.zip', '*.7z', '*.apk') }

  $items = @()
  foreach ($p in $patterns) { $items += Get-ChildItem $target -Filter $p -File -Force }
  $items = $items | Sort-Object LastWriteTime -Descending

  Write-Host ("历史版本轮转  [{0}]  {1}" -f $sec.kind, $target)
  Write-Host ("  规则: 留最近 {0} 个，其余进回收站{1}" -f $Keep, $(if ($DryRun) { '（演练，不删）' } else { '' }))
  Write-Host ("  现有 {0} 个成品:" -f $items.Count)
  foreach ($i in $items) {
    Write-Host ("    {0,-52} {1,7:N1}MB  {2:yyyy-MM-dd HH:mm}" -f $i.Name, ($i.Length / 1MB), $i.LastWriteTime)
  }

  if ($items.Count -le $Keep) { Write-Host "  结论: 不需要轮转。" -ForegroundColor Green; continue }

  $drop = $items | Select-Object -Skip $Keep
  $freed = 0
  Write-Host ''
  Write-Host ("  将移入回收站 {0} 个:" -f $drop.Count) -ForegroundColor Yellow
  foreach ($d in $drop) {
    $freed += $d.Length
    Write-Host ("    - {0}  ({1:N1}MB, {2:yyyy-MM-dd HH:mm})" -f $d.Name, ($d.Length / 1MB), $d.LastWriteTime) -ForegroundColor Yellow
  }
  Write-Host ("  可回收 {0:N1}MB" -f ($freed / 1MB))

  if ($DryRun) { Write-Host '  演练结束，未删除。'; $totalFreed += $freed; $totalDropped += $drop.Count; continue }

  Add-Type -AssemblyName Microsoft.VisualBasic
  $okCount = 0
  foreach ($d in $drop) {
    try {
      [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($d.FullName, 'OnlyErrorDialogs', 'SendToRecycleBin')
      $okCount++
      & (Join-Path $root 'reg.ps1') -Act remove -Target "$($sec.dir)\$($d.Name)" `
        -Cmd "rotate.ps1 -Kind $($sec.kind) -Keep $Keep" -Result ok `
        -Note ("轮转删除（回收站）: {0:N1}MB, {1:yyyy-MM-dd HH:mm}" -f ($d.Length / 1MB), $d.LastWriteTime) | Out-Null
    } catch {
      Write-Host ("  !! 删除失败 {0}: {1}" -f $d.Name, $_.Exception.Message) -ForegroundColor Red
      $rc = 1
    }
  }
  Write-Host ("  完成: {0}/{1} 个已进回收站。" -f $okCount, $drop.Count) -ForegroundColor Green
  $totalFreed += $freed
  $totalDropped += $drop.Count
  Write-Host ''
}

if ($sections.Count -gt 1) { Write-Host ("合计: {0} 个成品, {1:N1}MB" -f $totalDropped, ($totalFreed / 1MB)) }
exit $rc
