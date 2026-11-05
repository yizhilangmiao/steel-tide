# .agents\whoami.ps1  —  解析「我是哪个 agent 的哪个会话」
#
#   pwsh -File .agents\whoami.ps1            -> dsh:3f2a91
#   pwsh -File .agents\whoami.ps1 -Brief     -> 只输出 id（给别的脚本调用）
#   pwsh -File .agents\whoami.ps1 -Explain   -> 说明这个 id 从哪来
#   pwsh -File .agents\whoami.ps1 -Base bot  -> 强制前缀 bot:3f2a91
#
# 为什么需要后缀：两个 dsh 会话都自称 dsh，锁会互相"续租"（同名=自己人），
# 于是两个会话同时改一个文件却以为独占。加会话指纹后 dsh:3f2a91 ≠ dsh:9c11b7。
#
# 指纹稳定性：取自宿主注入的会话 id（DSH_SESSION_ID 等），同一会话内每次
# `pwsh -File` 都是新进程，但环境变量不变 → 指纹不变，锁的租约才能续上。
param(
  [string]$Base,
  [switch]$Brief,
  [switch]$Explain
)

$ErrorActionPreference = 'Stop'

# ---- 1. 找宿主注入的会话 id（顺序即优先级）----
$sources = [ordered]@{
  'DSH_SESSION_ID'    = $env:DSH_SESSION_ID
  'CLAUDE_SESSION_ID' = $env:CLAUDE_SESSION_ID
  'CODEX_SESSION_ID'  = $env:CODEX_SESSION_ID
  'TERM_SESSION_ID'   = $env:TERM_SESSION_ID
}
$sid = $null; $sidFrom = $null
foreach ($k in $sources.Keys) {
  if ($sources[$k]) { $sid = $sources[$k]; $sidFrom = "env:$k"; break }
}

# ---- 2. 默认前缀：从宿主类型推断，不硬编码 dsh ----
if (-not $Base) {
  if ($env:DSH_SESSION_ID -or $env:DSH_SHELL) { $Base = 'dsh' }
  elseif ($env:CLAUDE_SESSION_ID -or $env:CLAUDECODE) { $Base = 'claude' }
  elseif ($env:CODEX_SESSION_ID) { $Base = 'codex' }
  else { $Base = 'agent' }
}

# ---- 3. 没有会话 id 时退到「宿主进程指纹」----
# 这里刻意不按 PID：pwsh 每次都是新进程，PID 一定变。退到最近一个非 shell 祖先
# （DeepSeek Harness / claude / node 之类），它在整个 App 生命周期内不变。
if (-not $sid) {
  $skip = @('pwsh.exe','powershell.exe','cmd.exe','bash.exe','sh.exe','conhost.exe','winpty.exe','WindowsTerminal.exe')
  $p = $PID
  for ($i = 0; $i -lt 8; $i++) {
    $o = Get-CimInstance Win32_Process -Filter "ProcessId=$p" -ErrorAction SilentlyContinue
    if (-not $o) { break }
    if ($o.ProcessId -ne $PID -and $skip -notcontains $o.Name) {
      $sid = "$($o.Name)|$($o.ProcessId)|$($o.CreationDate.ToString('o'))"
      $sidFrom = "ancestor:$($o.Name) pid=$($o.ProcessId)"
      break
    }
    $p = $o.ParentProcessId
  }
}

# ---- 4. 取摘要 ----
if (-not $sid) {
  $id = $Base
  $sidFrom = '无（只有前缀，不带后缀）'
} else {
  $bytes = [Text.Encoding]::UTF8.GetBytes($sid)
  $hash  = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
  $suffix = -join ($hash[0..2] | ForEach-Object { $_.ToString('x2') })   # 6 位十六进制
  $id = "$Base`:$suffix"
}

if ($Explain) {
  Write-Host "agent id : $id"
  Write-Host "前缀      : $Base"
  Write-Host "来源      : $sidFrom"
  Write-Host "原始值    : $(if ($sid) { $sid } else { '(空)' })"
  Write-Host ""
  Write-Host "同一会话内多次 pwsh -File 结果相同 → 锁的租约能续上；"
  Write-Host "换会话/重启 App 后指纹变化 → 旧锁按 600s 过期自动接管。"
  exit 0
}

# -Brief 与默认都只输出 id 本身，方便别的脚本 `$a = & whoami.ps1 -Brief` 直接吃
Write-Output $id
