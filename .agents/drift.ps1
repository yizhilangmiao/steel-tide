# .agents\drift.ps1  —  磁盘漂移检测：磁盘上有的，env.json 里有没有登记。
# 为什么不用 hook：DSH 的 asar 里没有 PostToolUse/PreToolUse 钩子（2026-10-28 实测），
# 与其挂不上钩子，不如每次开工扫一遍磁盘 —— 不管哪个 AI 装的，都跑不掉。
# 用法: pwsh -File .agents\drift.ps1            (人看)
#       pwsh -File .agents\drift.ps1 -Json     (doctor 调用)
param([switch]$Json)

$ErrorActionPreference = 'SilentlyContinue'
$root = $PSScriptRoot                       # ...\.agents
$proj = Split-Path $root -Parent            # 项目根 = .agents 的上一级
$envf = Join-Path $root 'env.json'
if (-not (Test-Path $envf)) { Write-Host 'FATAL: env.json 不存在'; exit 2 }
$cfg = Get-Content $envf -Raw -Encoding UTF8 | ConvertFrom-Json

# ---- 收集"已登记"的路径与名字 ----
$regPaths = @()
foreach ($t in $cfg.tools) {
  foreach ($v in $t.file, $t.via.exe, $t.cmd, $t.install) { if ($v) { $regPaths += [string]$v } }
}
foreach ($p in $cfg.paths.PSObject.Properties) { $regPaths += [string]$p.Value }
$regPaths = $regPaths | Where-Object { $_ }

# 允许忽略（env.json 里 "drift": { "ignore": ["脚本"] }）
$ignore = @()
if ($cfg.drift -and $cfg.drift.ignore) { $ignore = @($cfg.drift.ignore) }
$ignore += @('节点缓存', 'node_modules', '__pycache__', '.cache')

$drift = @()
function Add-Drift($kind, $detail, $hint) {
  $script:drift += [pscustomobject]@{ kind = $kind; detail = $detail; hint = $hint }
}

# ---- A) 工具\ 下每个顶层项，是否被某个登记项覆盖 ----
$toolName = if ($cfg.drift -and $cfg.drift.toolRoot) { [string]$cfg.drift.toolRoot } else { '工具' }
$toolRoot = Join-Path $proj $toolName
if (Test-Path $toolRoot) {
  foreach ($i in Get-ChildItem $toolRoot -Force) {
    if ($ignore -contains $i.Name) { continue }
    $hit = $false
    foreach ($rp in $regPaths) {
      if ($rp -like "*$($i.Name)*") { $hit = $true; break }
    }
    if (-not $hit) {
      Add-Drift '工具未登记' "工具\$($i.Name)" "pwsh -File .agents\reg.ps1 -Act install -Target '$($i.Name)' -Cmd '<怎么装的>' -Result ok  然后把路径写进 env.json 的 tools 或 paths"
    }
  }
}

# ---- B) 监听端口：属于本项目的服务，是否登记为应开 ----
$svcPorts = @{}
foreach ($s in $cfg.services) { $svcPorts[[int]$s.port] = $s }
$listeners = @()
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object { $_.LocalAddress -in @('127.0.0.1', '0.0.0.0', '::', '::1') } |
  ForEach-Object {
    $pr = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
    $listeners += [pscustomobject]@{ port = [int]$_.LocalPort; pid = $_.OwningProcess; name = $pr.ProcessName; path = $pr.Path }
  }
foreach ($l in ($listeners | Sort-Object port -Unique)) {
  $mine = $false
  if ($l.path) {
    $lp = [string]$l.path
    if ($lp.StartsWith($proj, 'IgnoreCase') -or $lp -like "$proj\*") { $mine = $true }
  }
  if (-not $mine) { continue }   # 别人的服务（DSH、显卡驱动等）不管

  $s = $svcPorts[$l.port]
  if (-not $s) {
    Add-Drift '端口未登记' (":$($l.port) 由 $($l.name) (PID $($l.pid)) 监听") "在 env.json services 里加一条，或关掉它"
  } elseif ($s.disabled) {
    Add-Drift '登记为停用却在跑' (":$($l.port) $($s.name) 标记 disabled=true，实际有 $($l.name) 在监听") "pwsh -File .agents\libtv.ps1 -Action off  或改回 disabled=false"
  }
}

# ---- C) 登记为应开却没开 ----
foreach ($s in $cfg.services) {
  if ($s.disabled -or $s.optional) { continue }
  $on = $listeners | Where-Object { $_.port -eq [int]$s.port }
  if (-not $on) {
    Add-Drift '登记应开却未开' (":$($s.port) $($s.name)") "启动：$($s.start)"
  }
}

# ---- D) 开机启动项：Startup 里有、环境里没交代 ----
$startup = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup'
if (Test-Path $startup) {
  foreach ($lnk in Get-ChildItem $startup -Filter *.lnk -Force) {
    $known = $false
    foreach ($s in $cfg.services) {
      if ($s.lnk -and $lnk.Name -like "*$($s.lnk)*") { $known = $true; break }
      if ($s.name -and $lnk.Name -like "*$($s.name)*") { $known = $true; break }
    }
    if (-not $known) {
      Add-Drift '启动项未登记' $lnk.Name "在 env.json services 里记下它（含 autostart 开关方式），或移走该 .lnk"
    }
  }
}

# ---- 输出 ----
if ($Json) {
  [pscustomobject]@{ ts = (Get-Date).ToString('o'); by = 'drift'; drift = @($drift) } | ConvertTo-Json -Depth 5
  exit ([int]($drift.Count -gt 0))
}

if ($drift.Count -eq 0) {
  Write-Host '漂移检查: 磁盘与 env.json 一致。' -ForegroundColor Green
  exit 0
}
Write-Host ("漂移检查: {0} 项磁盘与登记不一致" -f $drift.Count) -ForegroundColor Yellow
foreach ($d in $drift) {
  Write-Host ""
  Write-Host ("  [{0}] {1}" -f $d.kind, $d.detail) -ForegroundColor Yellow
  Write-Host ("        怎么办: {0}" -f $d.hint)
}
Write-Host ""
exit 1
