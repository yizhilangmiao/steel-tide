# patch_datapack_modloader.ps1
# 用途：把纯数据/资源包类 mod jar 的 META-INF/neoforge.mods.toml 里 modLoader 改成 lowcodefml。
# 原因：ModMind 同步器只接受「有 .class」或「modLoader = lowcodefml」的 jar；
#       纯 datapack jar（无 class）若声明 javafml 会被拒（Invalid Mod JAR: does not contain compiled class files）。
#       游戏侧无影响：lowcodefml 就是 Modrinth 给纯 datapack jar 用的加载器类型。
# 用法：pwsh -File 脚本/patch_datapack_modloader.ps1 -Jar mods/xxx.jar

param(
    [Parameter(Mandatory = $true)][string]$Jar
)

Add-Type -AssemblyName System.IO.Compression.FileSystem

$path = (Resolve-Path -LiteralPath $Jar).Path
$tmp = Join-Path $env:TEMP ('patch-' + [guid]::NewGuid().ToString('N') + '.jar')

$zin = [IO.Compression.ZipFile]::OpenRead($path)
$target = $zin.Entries | Where-Object { $_.FullName -eq 'META-INF/neoforge.mods.toml' }
if (-not $target) { $zin.Dispose(); throw "$Jar 没有 META-INF/neoforge.mods.toml，不动" }

$sr = New-Object IO.StreamReader($target.Open())
$toml = $sr.ReadToEnd()
$sr.Close()
if ($toml.Contains("modLoader = 'lowcodefml'") -or $toml.Contains('modLoader="lowcodefml"')) {
    $zin.Dispose()
    Write-Output "已是 lowcodefml，无需处理：$Jar"
    return
}

$patched = $toml -replace "modLoader\s*=\s*'javafml'", "modLoader = 'lowcodefml'" -replace 'modLoader\s*=\s*"javafml"', "modLoader = 'lowcodefml'"
if ($patched -eq $toml) { $zin.Dispose(); throw "未找到 javafml 声明，不动：$Jar" }

$zout = [IO.Compression.ZipFile]::Open($tmp, [IO.Compression.ZipArchiveMode]::Create)
foreach ($e in $zin.Entries) {
    $ne = $zout.CreateEntry($e.FullName, [IO.Compression.CompressionLevel]::Optimal)
    $ne.LastWriteTime = $e.LastWriteTime
    $os = $ne.Open()
    if ($e.FullName -eq 'META-INF/neoforge.mods.toml') {
        $bytes = (New-Object Text.UTF8Encoding($false)).GetBytes($patched)
        $os.Write($bytes, 0, $bytes.Length)
    }
    else {
        $is = $e.Open()
        $is.CopyTo($os)
        $is.Close()
    }
    $os.Close()
}
$zout.Dispose()
$zin.Dispose()

Copy-Item -LiteralPath $tmp -Destination $path
$newHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()
$newSize = (Get-Item -LiteralPath $path).Length
Write-Output "已补丁：$Jar  size=$newSize sha256=$newHash"
Write-Output "记得同步 modmind.pack.json 的 sha256/size 与测试实例副本。"
