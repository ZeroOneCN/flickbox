<#
  build_apk.ps1 -- build FlickBox APK without Gradle / Android Studio.

  Toolchain layout (default root D:\CodeDevelop\Env, override with -EnvRoot):
    <EnvRoot>\jdk-17\bin\java.exe
    <EnvRoot>\kotlin\lib\kotlin-compiler.jar
    <EnvRoot>\android-sdk\platforms\android-34\android.jar
    <EnvRoot>\android-sdk\build-tools\34.0.0\{aapt2.exe,zipalign.exe,apksigner.bat,lib\d8.jar}

  Pipeline: aapt2 compile/link -> kotlinc -> jar -> R8 (shrink+minify kotlin-stdlib)
            -> inject classes.dex -> zipalign -> apksigner.
#>
param(
    [string]$EnvRoot = 'D:\CodeDevelop\Env',
    [string]$Platform = 'android-34',
    [string]$BuildTools = '34.0.0',
    [switch]$SkipShrink   # keep full kotlin-stdlib, faster build, bigger APK
)

$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $PSScriptRoot
$Out = Join-Path $Project 'build'
$PgConf = Join-Path $Project 'r8.pro'
$JdkHome = Join-Path $EnvRoot 'jdk-17'
$env:JAVA_HOME = $JdkHome
$env:Path = (Join-Path $JdkHome 'bin') + ';' + $env:Path

$Java = Join-Path $JdkHome 'bin\java.exe'
$Jar = Join-Path $JdkHome 'bin\jar.exe'
$Keytool = Join-Path $JdkHome 'bin\keytool.exe'
$Kotlinc = Join-Path $EnvRoot 'kotlin\lib\kotlin-compiler.jar'
$Kotlib = Join-Path $EnvRoot 'kotlin\lib'
$Stdlib = Join-Path $EnvRoot 'kotlin\lib\kotlin-stdlib.jar'
$Sdk = Join-Path $EnvRoot 'android-sdk'
$AndroidJar = Join-Path $Sdk "platforms\$Platform\android.jar"
$Bt = Join-Path $Sdk "build-tools\$BuildTools"
$Aapt2 = Join-Path $Bt 'aapt2.exe'
$Zipalign = Join-Path $Bt 'zipalign.exe'
$Apksigner = Join-Path $Bt 'apksigner.bat'
$D8 = Join-Path $Bt 'lib\d8.jar'

foreach ($p in @($Java, $Kotlinc, $Stdlib, $AndroidJar, $Aapt2, $Zipalign, $Apksigner, $D8)) {
    if (-not (Test-Path $p)) { throw "missing toolchain file: $p" }
}

function Step($msg) { Write-Host "==> $msg" }

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Path $Out | Out-Null
$Classes = Join-Path $Out 'classes'
$Compiled = Join-Path $Out 'res.zip'
$Unsigned = Join-Path $Out 'app.unsigned.apk'
$Aligned = Join-Path $Out 'app.aligned.apk'
$DexDir = Join-Path $Out 'dex'
$Dist = Join-Path $Project 'dist'
if (-not (Test-Path $Dist)) { New-Item -ItemType Directory -Path $Dist | Out-Null }
$Final = Join-Path $Dist 'FlickBox.apk'

Step 'aapt2 compile resources'
$resDir = Join-Path $Project 'res'
& $Aapt2 compile --dir $resDir -o $Compiled
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Step 'aapt2 link'
& $Aapt2 link -o $Unsigned -I $AndroidJar --manifest (Join-Path $Project 'AndroidManifest.xml') `
    -R $Compiled --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Step 'kotlinc'
$sources = Get-ChildItem (Join-Path $Project 'src') -Recurse -Filter '*.kt' | ForEach-Object { $_.FullName }
New-Item -ItemType Directory -Path $Classes | Out-Null
& $Java -cp "$Kotlib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-jdk -jvm-target 1.8 -nowarn `
    -classpath "$AndroidJar;$Stdlib" `
    -d $Classes @sources
if ($LASTEXITCODE -ne 0) { throw 'kotlin compile failed' }

Step 'jar classes'
$AppJar = Join-Path $Out 'app.jar'
& $Jar --create --file $AppJar -C $Classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

if ($SkipShrink) {
    Step 'd8 (no shrinking)'
    New-Item -ItemType Directory -Path $DexDir | Out-Null
    & $Java -cp $D8 com.android.tools.r8.D8 --release --min-api 21 --lib $AndroidJar `
        --output $DexDir $AppJar $Stdlib
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
} else {
    Step 'r8 shrink + minify'
    New-Item -ItemType Directory -Path $DexDir | Out-Null
    & $Java -cp $D8 com.android.tools.r8.R8 --release --min-api 21 --lib $AndroidJar `
        --pg-conf $PgConf --pg-map-output (Join-Path $Out 'mapping.txt') `
        --output $DexDir $AppJar $Stdlib
    if ($LASTEXITCODE -ne 0) { throw 'r8 failed' }
}

Step 'inject classes.dex'
$DexFile = Join-Path $DexDir 'classes.dex'
if (-not (Test-Path $DexFile)) { throw "classes.dex not produced in $DexDir" }
Push-Location $DexDir
& $Jar --update --file $Unsigned classes.dex
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { throw 'dex injection failed' }

Step 'zipalign'
& $Zipalign -f 4 $Unsigned $Aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Step 'sign'
$Keystore = Join-Path $EnvRoot 'pd-debug.keystore'
if (-not (Test-Path $Keystore)) {
    & $Keytool -genkeypair -v -keystore $Keystore -alias pd -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android -dname 'CN=PD Player, OU=dev, O=PD, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keystore generation failed' }
}
if (Test-Path $Final) { Remove-Item $Final -Force }
& $Apksigner sign --ks $Keystore --ks-pass pass:android --key-pass pass:android --out $Final $Aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& $Apksigner verify --verbose $Final
if ($LASTEXITCODE -ne 0) { throw 'signature verification failed' }

Step 'badging'
& $Aapt2 dump badging $Final | Select-String -Pattern "package:|sdkVersion|uses-permission|application-label|launchable-activity"

$size = (Get-Item $Final).Length
Write-Host ''
Write-Host ("APK: {0}" -f $Final)
Write-Host ("Size: {0} bytes ({1:N0} KB)" -f $size, ($size / 1KB))
