# .agents\lock.ps1  —  单写者锁（防两个 agent 同时改同一个文件）
#
# 上锁:   pwsh -File .agents\lock.ps1 -Target "src\main.cjs"
# 续租:   同一个 -Agent 再跑一次即可（长任务每 5 分钟续一次）
# 解锁:   pwsh -File .agents\lock.ps1 -Target "..." -Release -Agent dsh
# 看锁:   pwsh -File .agents\lock.ps1 -List
# 抢锁:   持有者确认死了 → 加 -Force
#
# 语义: 按 agent 身份发放「时限租约」(默认 600 秒)，不依赖 CLI 进程存活
#       （每次 pwsh -File 都是新进程，PID 一返回就死了，靠 PID 判活会立刻误失效）。
#       锁文件: .agents\claim\<安全名>.lock，内容 JSON(target/agent/pid/ts/expire)。
param(
  [string]$Target,
  [string]$Agent,
  [switch]$Release,
  [switch]$List,
  [switch]$Force,
  [int]$Stale = 600
)

$ErrorActionPreference = 'Stop'

# agent 身份不写死：优先 -Agent 显式传入，否则自动解析成「前缀:会话指纹」，
# 这样两个都叫 dsh 的会话不会互相续租（详见 whoami.ps1）。
if (-not $Agent) { $Agent = (& (Join-Path $PSScriptRoot 'whoami.ps1') -Brief) }
$dir = Join-Path $PSScriptRoot 'claim'
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

if ($List) {
  $fs = Get-ChildItem $dir -Filter '*.lock' -ErrorAction SilentlyContinue
  if (-not $fs) { Write-Host '当前没有锁'; exit 0 }
  foreach ($f in $fs) {
    $info = Get-Content $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    $left = [int]([datetime]$info.expire - (Get-Date)).TotalSeconds
    $state = if ($left -gt 0) { "有效 剩余${left}s" } else { "已过期$([math]::Abs($left))s" }
    Write-Host ("  {0,-46} agent={1,-10} {2}" -f $info.target, $info.agent, $state)
  }
  exit 0
}

if (-not $Target) { Write-Host '需要 -Target <相对路径>，或 -List'; exit 2 }

$safe = ($Target -replace '[\\/:*?"<>|]', '_')
$lock = Join-Path $dir "$safe.lock"

# ---------- 解锁 ----------
if ($Release) {
  if (-not (Test-Path $lock)) { Write-Host "没有这把锁: $Target"; exit 0 }
  $info = Get-Content $lock -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($info.agent -ne $Agent -and -not $Force) {
    Write-Host "拒绝解锁: 锁属于 agent=$($info.agent)，你是 $Agent。确认它放弃了再加 -Force。" -ForegroundColor Yellow
    exit 1
  }
  Remove-Item $lock -Force
  Write-Host "已解锁: $Target"
  exit 0
}

# ---------- 上锁 / 续租 ----------
if (Test-Path $lock) {
  $info = Get-Content $lock -Raw -Encoding UTF8 | ConvertFrom-Json
  $left = [int]([datetime]$info.expire - (Get-Date)).TotalSeconds
  $same = ($info.agent -eq $Agent)

  if ($left -gt 0 -and -not $same -and -not $Force) {
    Write-Host "拒绝: $Target 被别人占着" -ForegroundColor Red
    Write-Host "  agent=$($info.agent)  剩余 ${left}s  锁=$lock"
    Write-Host "  等它 -Release，或确认它已放弃再加 -Force。别硬改文件。"
    exit 1
  }
  if ($same -and $left -gt 0) {
    Write-Host "续租: $Target  (agent=$Agent，剩余 ${left}s → 重置 ${Stale}s)"
  } elseif ($Force) {
    Write-Host "强制抢占: $Target  (原 agent=$($info.agent))" -ForegroundColor Yellow
  } else {
    Write-Host "接管过期锁: $Target  (原 agent=$($info.agent)，过期 $([math]::Abs($left))s)"
  }
}

$payload = [ordered]@{
  target = $Target
  agent  = $Agent
  pid    = $PID
  ts     = (Get-Date).ToString('o')
  expire = (Get-Date).AddSeconds($Stale).ToString('o')
}
($payload | ConvertTo-Json -Compress) | Set-Content -Path $lock -Encoding UTF8

Write-Host "已上锁: $Target  (agent=$Agent 租约 ${Stale}s)"
Write-Host "  长任务续租: pwsh -File .agents\lock.ps1 -Target `"$Target`" -Agent $Agent"
Write-Host "  用完解锁:   pwsh -File .agents\lock.ps1 -Target `"$Target`" -Release -Agent $Agent"
