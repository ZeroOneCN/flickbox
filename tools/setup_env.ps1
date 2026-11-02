<#
  setup_env.ps1 -- install the offline toolchain used by build_apk.ps1.
  No Gradle, no Android Studio. Uses China-friendly mirrors (Tsinghua Adoptium,
  Aliyun Maven, dl.google.com). Safe to re-run: existing pieces are skipped.

  Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup_env.ps1
  Total download ~450 MB, installed under D:\CodeDevelop\Env by default.
#>
param(
    [string]$EnvRoot = 'D:\CodeDevelop\Env',
    [string]$Platform = 'android-34',
    [string]$BuildTools = '34.0.0',
    [string]$KotlinVersion = '2.1.0'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$JdkZip = 'OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$JdkUrl = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/$JdkZip"
$CmdToolsUrl = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
$MavenBase = 'https://maven.aliyun.com/repository/public'

$Jdk = Join-Path $EnvRoot 'jdk-17'
$Kot = Join-Path $EnvRoot 'kotlin'
$Sdk = Join-Path $EnvRoot 'android-sdk'
$Dl = Join-Path $EnvRoot 'dl'
New-Item -ItemType Directory -Force -Path $Dl | Out-Null

function Fetch($url, $dest) {
    if (Test-Path $dest) { Write-Host "cached  $(Split-Path -Leaf $dest)"; return }
    Write-Host "GET     $url"
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
}

# 1. JDK 17
if (-not (Test-Path (Join-Path $Jdk 'bin\java.exe'))) {
    $zip = Join-Path $Dl 'jdk17.zip'
    Fetch $JdkUrl $zip
    $tmp = Join-Path $Dl 'jdk-extract'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Write-Host 'expanding JDK...'
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    Move-Item $inner.FullName $Jdk -Force
    Remove-Item $tmp -Recurse -Force
}
$JavaExe = Join-Path $Jdk 'bin\java.exe'
& $JavaExe -version

# 2. Kotlin compiler (plain jars from Maven mirror, no kotlinc distribution needed)
$KotJars = @(
    @{ n = 'kotlin-compiler';            g = 'org/jetbrains/kotlin/kotlin-compiler';            v = $KotlinVersion; a = 'kotlin-compiler' },
    @{ n = 'kotlin-stdlib';              g = 'org/jetbrains/kotlin/kotlin-stdlib';              v = $KotlinVersion; a = 'kotlin-stdlib' },
    @{ n = 'kotlin-stdlib-jdk8';         g = 'org/jetbrains/kotlin/kotlin-stdlib-jdk8';         v = $KotlinVersion; a = 'kotlin-stdlib-jdk8' },
    @{ n = 'kotlin-stdlib-jdk7';         g = 'org/jetbrains/kotlin/kotlin-stdlib-jdk7';         v = $KotlinVersion; a = 'kotlin-stdlib-jdk7' },
    @{ n = 'kotlin-script-runtime';      g = 'org/jetbrains/kotlin/kotlin-script-runtime';      v = $KotlinVersion; a = 'kotlin-script-runtime' },
    @{ n = 'kotlin-reflect';             g = 'org/jetbrains/kotlin/kotlin-reflect';             v = $KotlinVersion; a = 'kotlin-reflect' },
    @{ n = 'trove4j';                    g = 'org/jetbrains/intellij/deps/trove4j';             v = '1.0.20200330'; a = 'trove4j' },
    @{ n = 'kotlinx-coroutines-core-jvm'; g = 'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm'; v = '1.6.4'; a = 'kotlinx-coroutines-core-jvm' },
    @{ n = 'annotations-13.0';           g = 'org/jetbrains/annotations';                       v = '13.0'; a = 'annotations' }
)
$libDir = Join-Path $Kot 'lib'
New-Item -ItemType Directory -Force -Path $libDir | Out-Null
foreach ($j in $KotJars) {
    $target = Join-Path $libDir ($j.n + '.jar')
    if (Test-Path $target) { continue }
    $url = "$MavenBase/$($j.g)/$($j.v)/$($j.a)-$($j.v).jar"
    Fetch $url $target
}

# 3. Android SDK command line tools
$SdkMgr = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $SdkMgr)) {
    $zip = Join-Path $Dl 'cmdline-tools.zip'
    Fetch $CmdToolsUrl $zip
    $tmp = Join-Path $Dl 'ct-extract'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Write-Host 'expanding cmdline-tools...'
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $dest = Join-Path $Sdk 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    Move-Item (Join-Path $tmp 'cmdline-tools') $dest -Force
    Remove-Item $tmp -Recurse -Force
}

# 4. platform + build tools
$AndroidJar = Join-Path $Sdk "platforms\$Platform\android.jar"
$Aapt2 = Join-Path $Sdk "build-tools\$BuildTools\aapt2.exe"
if (-not (Test-Path $AndroidJar) -or -not (Test-Path $Aapt2)) {
    $env:JAVA_HOME = $Jdk
    Write-Host 'accepting licenses...'
    $yesFile = Join-Path $Dl 'yes.txt'
    1..8 | ForEach-Object { 'y' } | Out-File -Encoding ascii $yesFile
    $yes = Get-Content $yesFile
    $yes | & cmd /c "`"$SdkMgr`" --sdk_root=$Sdk --licenses"
    Write-Host "installing platforms;$Platform and build-tools;$BuildTools ..."
    $yes | & cmd /c "`"$SdkMgr`" --sdk_root=$Sdk `"platforms;$Platform`" `"build-tools;$BuildTools`""
}

# 5. verify
foreach ($p in @($JavaExe, (Join-Path $Kot 'lib\kotlin-compiler.jar'), $AndroidJar, $Aapt2,
                 (Join-Path $Sdk "build-tools\$BuildTools\zipalign.exe"),
                 (Join-Path $Sdk "build-tools\$BuildTools\apksigner.bat"),
                 (Join-Path $Sdk "build-tools\$BuildTools\lib\d8.jar"))) {
    if (-not (Test-Path $p)) { throw "setup incomplete, missing: $p" }
}
Write-Host ''
Write-Host 'toolchain ready:'
Write-Host "  JDK          $Jdk"
Write-Host "  Kotlin libs  $libDir"
Write-Host "  Android SDK  $Sdk"
