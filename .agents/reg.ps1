# .agents\reg.ps1  —  登记一次环境变更（只 append，不改不删）
# 用法:
#   pwsh -File .agents\reg.ps1 -Act install -Target ffmpeg -Cmd "winget install Gyan.FFmpeg" -Result ok
#   （省略 -Agent 会自动解析成「前缀:会话指纹」，如 dsh:801af8；显式传 -Agent 则照用）
param(
  [string]$Agent,
  [Parameter(Mandatory)][ValidateSet('install', 'remove', 'upgrade', 'start', 'stop', 'config', 'edit', 'decision', 'other')][string]$Act,
  [Parameter(Mandatory)][string]$Target,
  [string]$Cmd = '',
  [string]$Result = 'ok',
  [string]$Note = ''
)

$ErrorActionPreference = 'Stop'
if (-not $Agent) { $Agent = (& (Join-Path $PSScriptRoot 'whoami.ps1') -Brief) }
$jf = Join-Path $PSScriptRoot 'journal.jsonl'

$line = [ordered]@{
  ts     = (Get-Date).ToString('o')
  agent  = $Agent
  act    = $Act
  target = $Target
  cmd    = $Cmd
  result = $Result
}
if ($Note) { $line.note = $Note }

$json = ($line | ConvertTo-Json -Compress -Depth 4)
Add-Content -Path $jf -Value $json -Encoding UTF8

Write-Host "已登记 -> journal.jsonl"
Write-Host "  $json"
Write-Host ""
Write-Host "别忘了同步 env.json（updated / by / 对应条目），否则下次 doctor 会说它是 MISSING：" -ForegroundColor Yellow
Write-Host "  $((Resolve-Path (Join-Path $PSScriptRoot 'env.json')).Path)" -ForegroundColor Yellow
