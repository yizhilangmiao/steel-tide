# .agents\doctor.ps1  —  开工自检。任何 agent 动手前第一条命令。
# 用法: pwsh -File .agents\doctor.ps1        (加 -Json 输出机器可读)
param([switch]$Json, [switch]$Quiet)

$ErrorActionPreference = 'SilentlyContinue'
$root = $PSScriptRoot
$envf = Join-Path $root 'env.json'
if (-not (Test-Path $envf)) { Write-Host "FATAL: env.json 不存在"; exit 2 }

$cfg = Get-Content $envf -Raw -Encoding UTF8 | ConvertFrom-Json
# 相对路径一律按工作区解析（.agents 的上一级）。doctor 的 CWD 是谁无所谓 —— 从别的目录跑时
# Test-Path '工程' 会拿 CWD 去拼，全报缺失。
$ws = Split-Path $root -Parent
function Resolve-P([string]$v) {
  if ([string]::IsNullOrWhiteSpace($v)) { return $v }
  if ([IO.Path]::IsPathRooted($v)) { return $v }
  return (Join-Path $ws $v)
}
$problems = @()
$rows = @()

function New-Row($kind, $name, $ok, $detail) {
  [pscustomobject]@{ kind = $kind; name = $name; ok = [bool]$ok; detail = $detail }
}

# ---------- tools ----------
foreach ($t in $cfg.tools) {
  $ok = $false; $detail = ''
  try {
    if ($t.file) {
      $tf = Resolve-P $t.file
      # file 分支：路径存在即算可用。detail 三档取，绝不把二进制或脚本内容刷进日志。
      if (Test-Path $tf) {
        $ok = $true
        $leaf = Split-Path $t.file -Leaf
        $ext = ([IO.Path]::GetExtension($tf)).ToLower()
        $exeExt = @('.exe', '.com', '.cmd', '.bat', '.ps1')
        if ($t.via -eq 'file') {
          # 显式声明只查存在（会弹窗/会崩的工具用这个）
          $detail = "[present] $leaf"
        } elseif (($exeExt -contains $ext) -and $t.version_arg) {
          # 可执行且有 version_arg：跑一次取首行当版本（python -V / ffmpeg -version / git --version）
          $out = (& $tf @($t.version_arg) 2>&1 | Select-Object -First 1)
          if ("$out") { $detail = ("{0}  [{1}]" -f ("$out" -replace '\s+', ' ').Trim(), $leaf) }
          else { $detail = "[present] $leaf" }
        } elseif ($exeExt -contains $ext) {
          # 可执行但没给 version_arg：不跑，只报在
          $detail = "[present] $leaf"
        } else {
          # 非可执行（如 工具\electron-runtime\version）：小文本才读内容，二进制一律只报 present
          $bytes = [IO.File]::ReadAllBytes($tf)
          if ($bytes.Length -gt 0 -and $bytes.Length -le 4096 -and -not ($bytes -contains 0)) {
            $txt = [Text.Encoding]::UTF8.GetString($bytes).Trim()
            if ($txt) { $detail = ("{0}  [file {1}]" -f $txt, $leaf) }
            else { $detail = "[present] $leaf" }
          } else { $detail = "[present] $leaf" }
        }
      } else { $detail = "文件缺失: $($t.file)" }
    } elseif ($t.via) {
      $exe = Resolve-P $t.via.exe
      if (-not (Test-Path $exe)) {
        $detail = "via 可执行缺失: $exe"
      } else {
        # 记录原值(含"原本不存在")，结束后精确还原，避免 ELECTRON_RUN_AS_NODE 泄漏污染后续探测
        $saved = @{}
        foreach ($p in $t.via.env.PSObject.Properties) {
          $saved[$p.Name] = [Environment]::GetEnvironmentVariable($p.Name)
          [Environment]::SetEnvironmentVariable($p.Name, [string]$p.Value)
        }
        $args2 = @()
        if ($t.probe) { $args2 = @($t.probe) }
        $out = (& $exe @args2 2>&1 | Select-Object -First 1)
        foreach ($k in $saved.Keys) {
          if ($null -eq $saved[$k]) { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
          else { [Environment]::SetEnvironmentVariable($k, [string]$saved[$k]) }
        }
        if ("$out" -match '\d') {
          $ok = $true
          $detail = ("{0}  [via {1}]" -f ("$out" -replace '\s+', ' '), (Split-Path $exe -Leaf))
        } else { $detail = "via 运行无输出" }
      }
    } else {
      $c = Get-Command $t.cmd -ErrorAction SilentlyContinue
      if (-not $c) {
        $detail = '未安装或不在 PATH'
      } else {
        $a = @()
        if ($t.probe) { $a = @($t.probe) }
        $out = if ($a.Count -gt 0) { (& $c.Source @a 2>&1 | Select-Object -First 1) } else { $c.Source }
        $ok = $true
        $detail = ("{0}" -f ("$out" -replace '\s+', ' ')).Trim()
        if (-not $detail) { $detail = $c.Source }
      }
    }
  } catch { $detail = "探测异常: $($_.Exception.Message)" }

  if (-not $ok) {
    $opt = if ($t.optional) { 'OPT ' } else { 'ERR ' }
    $detail = "$opt$detail"
    if ($t.install) { $detail += "  -> $($t.install)" }
    if (-not $t.optional) { $problems += "tool:$($t.name)" }
  }
  $rows += New-Row 'tool' $t.name $ok $detail
}

# ---------- services ----------
foreach ($s in $cfg.services) {
  if ($s.disabled) {
    # 登记为「已按需停用」：不做探测，也不算问题
    $rows += New-Row 'svc' $s.name $true ("OFF  已停用(按登记) :$($s.port)")
    continue
  }
  $ok = $false
  try {
    $cli = New-Object Net.Sockets.TcpClient
    $ok = $cli.ConnectAsync('127.0.0.1', [int]$s.port).Wait(400) -and $cli.Connected
    $cli.Close()
  } catch { $ok = $false }
  $detail = if ($ok) { "LISTEN :$($s.port)" } else { "DOWN :$($s.port)" }
  if (-not $ok) {
    if ($s.optional) { $detail += '  (optional)' }
    else { $detail += "  -> $($s.start)"; $problems += "svc:$($s.name)" }
  }
  $rows += New-Row 'svc' $s.name $ok $detail
}

# ---------- paths ----------
foreach ($p in $cfg.paths.PSObject.Properties) {
  $ok = Test-Path (Resolve-P $p.Value)
  $detail = if ($ok) { $p.Value } else { "缺失: $($p.Value)" }
  if (-not $ok) { $problems += "path:$($p.Name)" }
  $rows += New-Row 'path' $p.Name $ok $detail
}

# ---------- skills：项目该带的那几把刀在不在、有没有漂移、有没有连带拷进运行时 ----------
# 清单真源 = .agents\skills.selected.json（由 sync-skills.ps1 写，不要手改）
# copy 模式比 SKILL.md 的 sha1；ref 模式只查源路径还在；单目录超 50 MB 视为把 .venv/tools 拷进来了
$selfFile = Join-Path $root 'skills.selected.json'
$selfDir  = Join-Path $root 'skills'
if (-not (Test-Path $selfFile)) {
  $rows += New-Row 'skill' '(none)' $true '未选技能（这条产线不带刀）'
} else {
  try {
    $sel = Get-Content $selfFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $selItems = if ($sel.items) { @($sel.items) } elseif ($sel -is [array]) { @($sel) } else { @($sel) }
  } catch {
    $selItems = @()
    $rows += New-Row 'skill' '(broken)' $false "skills.selected.json 解析失败: $($_.Exception.Message)"
    $problems += 'skill:manifest'
  }
  foreach ($s in $selItems) {
    $ok = $true; $detail = ''
    if ($s.mode -eq 'ref') {
      $src = [string]$s.source_path
      $srcAbs = if ($src -match '^~[\\/]') { Join-Path $env:USERPROFILE ($src.Substring(2)) } else { $src }
      if (Test-Path -LiteralPath $srcAbs) { $detail = "ref -> $src" }
      else { $ok = $false; $detail = "ref 源不存在: $src" }
    } else {
      $d = Join-Path $selfDir $s.name
      $md = Join-Path $d 'SKILL.md'
      if (-not (Test-Path -LiteralPath $d)) { $ok = $false; $detail = 'copy 目录缺失' }
      elseif (-not (Test-Path -LiteralPath $md)) { $ok = $false; $detail = 'copy 缺 SKILL.md' }
      else {
        $h = (Get-FileHash -LiteralPath $md -Algorithm SHA1).Hash
        $h12 = $h.Substring(0, 12).ToLower()
        if ($s.source_sha1 -and $h12 -ne $s.source_sha1) {
          $ok = $false; $detail = "copy 漂移 sha1=$h12 期望=$($s.source_sha1)"
        } else { $detail = "copy sha1=$h12" }
      }
      # 体积异常：技能目录里混进了运行时（.venv / tools / runs）
      if ($ok -and (Test-Path -LiteralPath $d)) {
        $mb = (Get-ChildItem -LiteralPath $d -Recurse -File -ErrorAction SilentlyContinue |
               Measure-Object Length -Sum).Sum / 1MB
        if ($mb -gt 50) { $ok = $false; $detail += ("  体积 {0:N0} MB，疑似带运行时，改 ref" -f $mb) }
      }
    }
    if (-not $ok) { $problems += "skill:$($s.name)" }
    $rows += New-Row 'skill' $s.name $ok $detail
  }
}

# ---------- patches：出包前自证。真跑一次，不信「我记得打过了」 ----------
if ($cfg.patches) {
  $p = $cfg.patches
  $pcwd = if ($p.cwd) { Resolve-P $p.cwd } else { $ws }
  $saved_enc = $env:PYTHONIOENCODING
  $env:PYTHONIOENCODING = 'utf-8'
  $pout = ''
  $pcode = 1
  try {
    $pout = (& pwsh -NoProfile -Command "Set-Location '$pcwd'; $($p.cmd)" 2>&1 | Out-String)
    $pcode = $LASTEXITCODE
  } catch {
    $pout = $_.Exception.Message
    $pcode = 1
  }
  $env:PYTHONIOENCODING = $saved_enc
  $okp = ($pcode -eq 0)
  if ($okp -and $p.expect) { $okp = ($pout -match [regex]::Escape($p.expect)) }
  $lastline = ($pout -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -Last 1)
  if (-not $okp) {
    $problems += "patch:$($p.name)"
    $rows += New-Row 'patch' $p.name $false ("EXIT=$pcode  $lastline")
  } else {
    $rows += New-Row 'patch' $p.name $true ("EXIT=0  " + ($lastline -replace '\s+', ' '))
  }
}

# ---------- links (junction / symlink)：一个源码树只许有一个路径 ----------
$proj = Split-Path $root -Parent
$links = Get-ChildItem $proj -Directory -Force -Recurse -Depth 4 -ErrorAction SilentlyContinue |
  Where-Object { $_.LinkType -and $_.FullName -notmatch '\\node_modules\\' }
if ($links) {
  foreach ($l in $links) {
    $rel = $l.FullName.Substring($proj.Length).TrimStart('\')
    $problems += "link:$rel"
    $rows += New-Row 'link' 'junction' $false ("$rel  ->  " + ($l.Target -join ','))
  }
} else {
  $rows += New-Row 'link' '(none)' $true '无 junction / symlink'
}

# ---------- drift：磁盘上有、env.json 里没登记 ----------
$drift = @()
try {
  $out = & (Join-Path $root 'drift.ps1') -Json 2>$null
  if ($out) { $drift = @(($out | ConvertFrom-Json).drift) }
} catch { }
if ($drift.Count -gt 0) {
  foreach ($d in $drift) {
    $rows += New-Row 'drift' $d.kind $false ($d.detail + "  -> " + $d.hint)
    $problems += "drift:$($d.kind)"
  }
} else {
  $rows += New-Row 'drift' '(none)' $true '磁盘与登记一致'
}

# ---------- temp：临时区体积闸门（>10 GiB 自动清到 8 GiB，24h 内活跃项不动） ----------
# 2026-11-11 参数化：原来硬编码 E:\ailian\...\node.exe，铺到别的项目一律误红。
# 现在按「项目自带 node → PATH 里的 node」两级找；项目根本没有 trim-temp.mjs 时整段跳过（不报红）。
$trimJs = Join-Path $proj '工具\脚本\临时区管理\trim-temp.mjs'
if (Test-Path $trimJs) {
  $trimNode = $null
  $bundled = Get-ChildItem (Join-Path $ws '工具\node') -Recurse -Filter 'node.exe' -Depth 2 -ErrorAction SilentlyContinue |
             Select-Object -First 1
  if ($bundled) { $trimNode = $bundled.FullName }
  if (-not $trimNode) {
    $g = Get-Command node -ErrorAction SilentlyContinue
    if ($g) { $trimNode = $g.Source }
  }
  if (-not $trimNode) {
    $rows += New-Row 'temp' '(missing)' $false '有 trim-temp.mjs 但找不到 node.exe（工具\node\ 或 PATH 皆无）'
    $problems += 'temp:nonode'
  } else {
    try {
      $tl = (& $trimNode $trimJs --auto 2>&1 | Select-Object -Last 1)
      $rows += New-Row 'temp' '临时区' $true ([string]$tl).Trim()
    } catch {
      $rows += New-Row 'temp' '临时区' $false ('闸门执行失败: ' + $_.Exception.Message)
      $problems += 'temp:fail'
    }
  }
}

# ---------- journal tail ----------
$jf = Join-Path $root 'journal.jsonl'
$last = @()
if (Test-Path $jf) { $last = Get-Content $jf -Encoding UTF8 -Tail 3 }

# ---------- progress：当前进度卡。新会话拿不到它会从零开始，所以在 doctor 里强制露出 ----------
$pf = Join-Path $root 'progress.md'
$progressMax = 50   # 正文上限（标题/说明/空行另给 4 行余量）
$progressLines = @()
if (Test-Path $pf) {
  $progressLines = @(Get-Content $pf -Encoding UTF8 | Where-Object { $_.Trim() -ne '' })
  if ($progressLines.Count -gt ($progressMax + 4)) {
    $rows += New-Row 'progress' '超长' $false ("progress.md 有 {0} 行（上限 {1}），旧内容先归档到 memory\handoff.md" -f $progressLines.Count, $progressMax)
    $problems += 'progress:oversize'
  }
} else {
  $rows += New-Row 'progress' '(missing)' $false '缺 .agents\progress.md，新会话拿不到进度'
  $problems += 'progress:missing'
}

if ($Json) {
  [pscustomobject]@{
    ts       = (Get-Date).ToString('o')
    by       = 'doctor'
    envStamp = $cfg.updated
    envBy    = $cfg.by
    rows     = $rows
    problems = $problems
    progress = ($progressLines -join "`n")
  } | ConvertTo-Json -Depth 6
  exit ([int]($problems.Count -gt 0))
}

if (-not $Quiet) {
  $bar = '=' * 72
  Write-Host $bar
  Write-Host ("  $proj 环境自检   {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm'))
  Write-Host ("  env.json 由 {0} 于 {1} 登记" -f $cfg.by, $cfg.updated)
  try {
    $me = & (Join-Path $PSScriptRoot 'whoami.ps1') -Brief
    Write-Host ("  当前会话 agent = {0}" -f $me)
  } catch { }
  Write-Host $bar

  foreach ($g in 'tool', 'svc', 'path', 'patch', 'skill', 'link', 'drift', 'temp') {
    $set = $rows | Where-Object { $_.kind -eq $g }
    if (-not $set) { continue }
    Write-Host ""
    Write-Host ("-- {0} --" -f $g.ToUpper())
    foreach ($r in $set) {
      $mark = if ($r.ok) { ' OK ' } else { ' !! ' }
      Write-Host ("  [{0}] {1,-12} {2}" -f $mark, $r.name, $r.detail)
    }
  }

  Write-Host ""
  if ($last) {
    Write-Host "-- 最近 3 条登记 (journal.jsonl) --"
    foreach ($l in $last) { Write-Host "  $l" }
    Write-Host ""
  }

  if ($problems.Count -eq 0) {
    Write-Host "结论: 环境完整，可以开工。" -ForegroundColor Green
  } else {
    Write-Host ("结论: {0} 项待处理 -> {1}" -f $problems.Count, ($problems -join ', ')) -ForegroundColor Yellow
    Write-Host "先补/确认，再动代码。补完务必登记：" -ForegroundColor Yellow
    Write-Host '  pwsh -File .agents\reg.ps1 -Agent <名字> -Act install -Target <东西> -Cmd "<命令>" -Result ok'
  }
  Write-Host ""

  # 新会话开场必读：这是"上一轮干到哪了"的唯一入口，放在最后一块，读完就开工
  if ($progressLines.Count -gt 0) {
    Write-Host $bar
    Write-Host ("  当前进度  {0}" -f (Get-Item $pf).LastWriteTime.ToString('yyyy-MM-dd HH:mm'))
    Write-Host $bar
    foreach ($l in $progressLines) {
      if ($l -match '^#\s') { Write-Host $l -ForegroundColor Cyan }
      elseif ($l -match '^##\s') { Write-Host $l -ForegroundColor Cyan }
      else { Write-Host "  $l" }
    }
    Write-Host ""
    Write-Host "收工前覆盖写 .agents\progress.md（≤$progressMax 行），否则下一轮还是从零开始。" -ForegroundColor DarkGray
    Write-Host ""
  } else {
    Write-Host "!! 缺 .agents\progress.md —— 新会话拿不到进度，先建一个。" -ForegroundColor Yellow
    Write-Host ""
  }

  # 反向通道：老大在 QQ 里跟小鲸鱼说的话，靠 pull-qq.py 拉回来。
  # 这里只报"上次拉到什么时候"，不去连服务器（会拖慢每次 doctor）。
  $qs = Join-Path $root 'qq-inbox-state.json'
  $qm = Join-Path $root 'inbox\老大留言.md'
  $qPulled = $null
  if (Test-Path $qs) {
    try { $qPulled = (Get-Content $qs -Raw -Encoding UTF8 | ConvertFrom-Json).pulled_at } catch { }
  }
  Write-Host "-- 老大的 QQ 留言 --"
  if ($qPulled) {
    $qTip = if (Test-Path $qm) { '本地有存档' } else { '本地无存档' }
    Write-Host ("  上次拉取 {0}（{1}）" -f $qPulled, $qTip)
  } else {
    Write-Host "  还没拉过"
  }
  Write-Host "  拉新的：python 工具\脚本\pull-qq.py --quiet" -ForegroundColor DarkGray
  Write-Host ""
}

exit ([int]($problems.Count -gt 0))
