# MBB-Eval 构建脚本
# 用法：.\build.ps1 -Bot "D:\CodeX\Projects\MoBoxBot\out\MoBoxBot.jar"
# 契约见 docs/MBB-Eval.md §11.5：只编译本插件源码，不打包 MoBoxBot 与公共依赖。
param(
    [string]$Bot = "D:\CodeX\Projects\MoBoxBot\out\MoBoxBot.jar"
)

$ErrorActionPreference = "Stop"

# Windows PowerShell 5.1 在 Stop 策略下会把原生命令写到 stderr 的输出视为终止性错误；
# javac -version 会写 stderr，因此调用期间临时放宽，只按退出码判定成败。
$nativeErrorAction = $ErrorActionPreference

$pluginName = "MBB-Eval"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcJava = Join-Path $root "src\main\java"
$srcResources = Join-Path $root "src\main\resources"
$outDir = Join-Path $root "out"
$buildDir = Join-Path $outDir "build"
$classesDir = Join-Path $buildDir "classes"
$jarPath = Join-Path $outDir ($pluginName + ".jar")

Write-Host ("==== " + $pluginName + " 构建 ====")

if (-not (Test-Path -LiteralPath $Bot)) {
    Write-Host ("找不到 MoBoxBot.jar：" + $Bot)
    exit 1
}

# 构建目录必须落在插件目录内，避免误删插件目录之外的路径
$resolvedRoot = [System.IO.Path]::GetFullPath($root)
$resolvedBuild = [System.IO.Path]::GetFullPath($buildDir)
if (-not $resolvedBuild.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    Write-Host ("构建目录不在插件目录内，已中止：" + $resolvedBuild)
    exit 1
}

if (Test-Path -LiteralPath $buildDir) {
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 统一产出 Java 8 字节码：javac 9 及以上需要 --release 8
$ErrorActionPreference = "Continue"
$javacVersionText = (& javac -version 2>&1 | Out-String).Trim()
$ErrorActionPreference = $nativeErrorAction
$releaseArgs = @()
if ($javacVersionText -match "javac\s+(\d+)") {
    $javacMajor = [int]$Matches[1]
    if ($javacMajor -ge 9) {
        $releaseArgs = @("--release", "8", "-Xlint:-options")
    }
}
Write-Host ("编译工具：" + $javacVersionText)

$javaFiles = @()
if (Test-Path -LiteralPath $srcJava) {
    $javaFiles = @(Get-ChildItem -LiteralPath $srcJava -Recurse -File -Filter *.java | Select-Object -ExpandProperty FullName)
}
if ($javaFiles.Count -eq 0) {
    Write-Host ("没有找到 Java 源码：" + $srcJava)
    exit 1
}
Write-Host ("编译源码：" + $javaFiles.Count + " 个文件")

$ErrorActionPreference = "Continue"
& javac -encoding UTF-8 @releaseArgs -cp $Bot -d $classesDir $javaFiles
$compileExit = $LASTEXITCODE
$ErrorActionPreference = $nativeErrorAction
if ($compileExit -ne 0) {
    Write-Host ("javac 编译失败，退出码 " + $compileExit)
    exit 1
}

if (Test-Path -LiteralPath $srcResources) {
    Copy-Item -Path (Join-Path $srcResources "*") -Destination $classesDir -Recurse -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $classesDir "plugin.json"))) {
    Write-Host "打包目录缺少 plugin.json，请检查 src/main/resources"
    exit 1
}

if (Test-Path -LiteralPath $jarPath) {
    Remove-Item -LiteralPath $jarPath -Force
}
$ErrorActionPreference = "Continue"
& jar cf $jarPath -C $classesDir .
$jarExit = $LASTEXITCODE
$ErrorActionPreference = $nativeErrorAction
if ($jarExit -ne 0) {
    Write-Host ("jar 打包失败，退出码 " + $jarExit)
    exit 1
}
if (-not (Test-Path -LiteralPath $jarPath)) {
    Write-Host "未生成目标 JAR，构建失败"
    exit 1
}

$size = (Get-Item -LiteralPath $jarPath).Length
Write-Host ("构建成功：" + $jarPath + "（" + $size + " 字节）")
exit 0
