<#
老旧安卓电视投屏工具 —— 手工构建脚本

不依赖 Gradle / Android Studio。流程：
  javac -> d8 -> aapt2 compile -> aapt2 link -> 塞 classes.dex -> zipalign + apksigner

用法：
  .\build.ps1 -App 投屏手机端
  .\build.ps1 -App 投屏电视端

需要环境变量（或手动传参）：
  ANDROID_HOME / ANDROID_SDK_ROOT   指向 Android SDK（要有 build-tools 与 platforms\android-36）
  JAVA_HOME                         指向 JDK 17

关于中文路径：
  aapt2 / apksigner 在中文路径上会报 "failed to open directory"，所以本脚本会把源码
  复制到一个纯 ASCII 的临时目录里编译，编完再把 APK 复制回本脚本所在目录。
  临时目录下的 release.keystore 会被保留复用（删掉它会导致签名变化，覆盖安装会失败）。

改代码后请务必提 AndroidManifest.xml 里的 versionCode，否则覆盖安装会被当成同一个包。
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('投屏手机端', '投屏电视端')]
    [string]$App,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $SdkRoot) { throw '找不到 Android SDK：请设置 ANDROID_HOME，或用 -SdkRoot 传入。' }
if (-not $JdkHome) { throw '找不到 JDK：请设置 JAVA_HOME，或用 -JdkHome 传入。' }

# 每个 App 的最低 API（决定 d8 --min-api 与 apksigner --min-sdk-version），Key 同时用作 ASCII 工作目录名
$meta = @{
    '投屏手机端' = @{ Key = 'sender';  MinApi = 26; Plat = 'android-36' }
    '投屏电视端' = @{ Key = 'mirror';  MinApi = 16; Plat = 'android-36' }
}
$m = $meta[$App]

$srcRoot = Join-Path $PSScriptRoot $App
if (-not (Test-Path -LiteralPath (Join-Path $srcRoot 'AndroidManifest.xml'))) {
    throw "找不到源码目录：$srcRoot"
}

# ---- 找一个纯 ASCII 的编译工作目录 ----
function Test-AsciiPath([string]$p) { return [regex]::IsMatch($p, '^[\x20-\x7E]+$') }

$workRoot = $null
foreach ($cand in @((Join-Path $env:TEMP 'tvrevive-build'), 'C:\tvrevive-build')) {
    if (-not (Test-AsciiPath $cand)) { continue }
    try {
        New-Item -ItemType Directory -Force -Path $cand | Out-Null
        $workRoot = $cand
        break
    } catch { }
}
if (-not $workRoot) { throw '找不到可用的 ASCII 路径作为编译目录（请检查用户名是否含中文）。' }

$work = Join-Path $workRoot $m.Key
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $work | Out-Null

Copy-Item -LiteralPath (Join-Path $srcRoot 'src') -Destination $work -Recurse -Force
Copy-Item -LiteralPath (Join-Path $srcRoot 'AndroidManifest.xml') -Destination $work -Force
$resSrc = Join-Path $srcRoot 'res'
if (Test-Path -LiteralPath $resSrc) {
    Copy-Item -LiteralPath $resSrc -Destination $work -Recurse -Force
}

$g = $work
$out = Join-Path $g 'out'

# build-tools 取版本号最大的那个
$btRoot = Join-Path $SdkRoot 'build-tools'
if (-not (Test-Path -LiteralPath $btRoot)) { throw "SDK 里没有 build-tools：$btRoot" }
$bt = Get-ChildItem -LiteralPath $btRoot -Directory |
      Sort-Object { [version](($_.Name -replace '[^0-9\.]', '').Trim('.')) } |
      Select-Object -Last 1 -ExpandProperty FullName

$aj = Join-Path $SdkRoot ("platforms\{0}\android.jar" -f $m.Plat)
if (-not (Test-Path -LiteralPath $aj)) { throw "找不到 android.jar：$aj（需要 platforms\$($m.Plat)）" }

$ks = Join-Path $workRoot 'release.keystore'
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$bt;$env:PATH"

if (-not (Test-Path -LiteralPath $ks)) {
    Write-Host '[0/6] 生成自签名 keystore（口令 123456）'
    & "$JdkHome\bin\keytool.exe" -genkeypair -keystore $ks -alias tvbox `
        -storepass 123456 -keypass 123456 -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Old TV Revive,O=Community,C=CN' 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool 生成 keystore 失败' }
}

New-Item -ItemType Directory -Force -Path (Join-Path $out 'classes'), (Join-Path $out 'dex') | Out-Null

Write-Host '[1/6] javac'
$srcs = Get-ChildItem -LiteralPath (Join-Path $g 'src') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $srcs) { throw "源码目录里没有 .java：$srcRoot\src" }
& "$JdkHome\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $aj -d (Join-Path $out 'classes') $srcs 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '[2/6] d8'
$cls = Get-ChildItem -LiteralPath (Join-Path $out 'classes') -Recurse -Filter *.class | Select-Object -ExpandProperty FullName
& "$bt\d8.bat" --min-api $m.MinApi --lib $aj --output (Join-Path $out 'dex') $cls 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '[3/6] aapt2 compile'
$res = Join-Path $g 'res'
if (Test-Path -LiteralPath $res) {
    & "$bt\aapt2.exe" compile --dir $res -o (Join-Path $out 'res.zip') 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }
}

Write-Host '[4/6] aapt2 link'
$linkArgs = @('-o', (Join-Path $out 'base.apk'), '-I', $aj, '--manifest', (Join-Path $g 'AndroidManifest.xml'))
if (Test-Path -LiteralPath (Join-Path $out 'res.zip')) {
    $linkArgs += @('-R', (Join-Path $out 'res.zip'), '--auto-add-overlay')
}
& "$bt\aapt2.exe" link @linkArgs 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '[5/6] add dex'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item -LiteralPath (Join-Path $out 'base.apk') -Destination (Join-Path $out 'g.apk') -Force
$zip = [System.IO.Compression.ZipFile]::Open((Join-Path $out 'g.apk'), 'Update')
$e = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::NoCompression)
$s = $e.Open()
$b = [IO.File]::ReadAllBytes((Join-Path $out 'dex\classes.dex'))
$s.Write($b, 0, $b.Length)
$s.Close()
$zip.Dispose()

Write-Host '[6/6] zipalign + sign'
$built = Join-Path $work ("{0}.apk" -f $m.Key)
& "$bt\zipalign.exe" -f 4 (Join-Path $out 'g.apk') (Join-Path $out 'g_aligned.apk') 2>&1 | Out-Null
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:123456 --key-pass pass:123456 `
    --ks-key-alias tvbox --min-sdk-version $m.MinApi --out $built (Join-Path $out 'g_aligned.apk') 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'apksigner 签名失败' }

$final = Join-Path $PSScriptRoot ("{0}.apk" -f $App)
Copy-Item -LiteralPath $built -Destination $final -Force
$f = Get-Item -LiteralPath $final
Write-Host ("OK -> {0}  {1} bytes" -f $f.Name, $f.Length)
