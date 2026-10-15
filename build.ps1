param([switch]$Verify)
$ErrorActionPreference = 'Stop'
$taskRuntime = Join-Path $env:USERPROFILE '.codex\android-runtime\focusmirror'
if (-not $env:JAVA_HOME) {
    $taskJdk = Get-ChildItem -LiteralPath (Join-Path $taskRuntime 'jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($taskJdk) { $env:JAVA_HOME = $taskJdk.FullName }
}
if ($env:JAVA_HOME) {
    if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw 'JAVA_HOME 无效，请设置为 JDK 17 或更新版本的目录。' }
} elseif (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw '请安装 JDK 17 或更新版本，并设置 JAVA_HOME 或加入 PATH。' }
if (-not $env:ANDROID_HOME) {
    if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
    elseif (Test-Path -LiteralPath (Join-Path $taskRuntime 'sdk')) { $env:ANDROID_HOME = Join-Path $taskRuntime 'sdk' }
    elseif (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'local.properties')) {
        $taskSdkLine = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'local.properties') | Where-Object { $_ -match '^sdk\.dir\s*=' } | Select-Object -First 1
        if ($taskSdkLine) { $env:ANDROID_HOME = ($taskSdkLine -replace '^sdk\.dir\s*=\s*','').Replace('\:',':').Replace('\\','\').Replace('\ ',' ') }
    }
}
if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) { throw '请安装 Android SDK，并设置 ANDROID_HOME，或用 Android Studio 生成 local.properties。' }
$taskGradle = Join-Path $taskRuntime 'gradle\gradle-8.14.3\bin\gradle.bat'
$taskArgs = @(':app:assembleDebug', '--console=plain')
if ($Verify) { $taskArgs += @(':app:testDebugUnitTest', ':app:lintDebug', ':app:streamingLowMemory') }
Push-Location $PSScriptRoot
try {
    if (Test-Path -LiteralPath $taskGradle) { & $taskGradle @taskArgs } else { & (Join-Path $PSScriptRoot 'gradlew.bat') @taskArgs }
    if ($LASTEXITCODE -ne 0) { throw 'Android 构建或验证失败，请查看上方日志。' }
    $taskOutput = Join-Path $PSScriptRoot 'deliverables'
    New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
    $taskGradleSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app\build.gradle.kts') -Raw
    $taskVersionMatch = [regex]::Match($taskGradleSource, 'versionName\s*=\s*"([0-9A-Za-z._-]+)"')
    if (-not $taskVersionMatch.Success) { throw '无法读取 versionName，请检查 app/build.gradle.kts。' }
    $taskApkName = '同页-' + $taskVersionMatch.Groups[1].Value + '.apk'
    $taskApkPath = Join-Path $taskOutput $taskApkName
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $taskApkPath -Force
    $taskHash = (Get-FileHash -LiteralPath $taskApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath ($taskApkPath + '.sha256') -Value ($taskHash + '  ' + $taskApkName) -Encoding UTF8
    Write-Output ('构建完成：deliverables\' + $taskApkName)
} finally { Pop-Location }

