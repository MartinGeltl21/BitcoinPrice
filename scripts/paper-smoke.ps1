param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$MavenCommand,
    [Parameter(Mandatory)][string]$PaperJar,
    [Parameter(Mandatory)][string]$Workspace,
    [string]$RuntimeCache,
    [switch]$EulaAccepted
)
$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskWorkspace = [IO.Path]::GetFullPath($Workspace)
if ($taskWorkspace.StartsWith($taskRepo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Use a separate temporary directory outside the repository for the Paper test world.'
}
New-Item -ItemType Directory -Force -Path $taskWorkspace | Out-Null
if (Test-Path -LiteralPath (Join-Path $taskWorkspace 'plugins/BitcoinPriceSmoke')) {
    throw 'Use a fresh workspace for each complete integration run; existing fixture state must be preserved.'
}
$taskEulaFile = Join-Path $taskWorkspace 'eula.txt'
if (!(Test-Path -LiteralPath $taskEulaFile) -or !(Select-String -LiteralPath $taskEulaFile -Pattern '^eula=true$' -Quiet)) {
    if (!$EulaAccepted) { throw 'Provide an already accepted eula.txt or explicitly pass -EulaAccepted after reading the Minecraft EULA.' }
    [IO.File]::WriteAllText($taskEulaFile, "eula=true`n", [Text.UTF8Encoding]::new($false))
}
if ($RuntimeCache) {
    foreach ($taskDirectory in @('libraries', 'versions', 'cache')) {
        $taskCacheDirectory = Join-Path $RuntimeCache $taskDirectory
        if ((Test-Path -LiteralPath $taskCacheDirectory) -and !(Test-Path -LiteralPath (Join-Path $taskWorkspace $taskDirectory))) {
            Copy-Item -LiteralPath $taskCacheDirectory -Destination $taskWorkspace -Recurse
        }
    }
}
Copy-Item -LiteralPath $PaperJar -Destination (Join-Path $taskWorkspace 'paper.jar') -Force
New-Item -ItemType Directory -Force -Path (Join-Path $taskWorkspace 'plugins/BitcoinPrice') | Out-Null
$taskUtf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $taskWorkspace 'server.properties'), @'
server-ip=127.0.0.1
server-port=25586
online-mode=false
enable-rcon=false
view-distance=2
simulation-distance=2
spawn-protection=0
level-type=minecraft:flat
sync-chunk-writes=false
motd=Local BitcoinPrice integration test
'@, $taskUtf8)
$taskConfigPath = Join-Path $taskWorkspace 'plugins/BitcoinPrice/config.yml'
if (!(Test-Path -LiteralPath $taskConfigPath)) {
    [IO.File]::WriteAllText($taskConfigPath, @'
price-interval: 10
price-currency: EUR
api:
  url: 'http://127.0.0.1:28761/price'
  timeout: 2000
  cache-seconds: 60
'@, $taskUtf8)
}
$env:JAVA_HOME = [IO.Path]::GetFullPath($JavaHome)
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
Push-Location $taskRepo
try {
    & $MavenCommand --batch-mode --no-transfer-progress clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Plugin build failed.' }
    $taskClassPathFile = Join-Path $taskWorkspace 'classpath.txt'
    & $MavenCommand --batch-mode --no-transfer-progress dependency:build-classpath "-Dmdep.outputFile=$taskClassPathFile" '-Dmdep.includeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'Dependency classpath resolution failed.' }
    $taskClassPath = "$(Join-Path $taskRepo 'target/classes');$([IO.File]::ReadAllText($taskClassPathFile).Trim())"
    $taskClasses = Join-Path $taskWorkspace 'smoke-classes'
    New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
    & "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp $taskClassPath -d $taskClasses (Join-Path $taskRepo 'src/test/paper/BitcoinPriceSmoke.java')
    if ($LASTEXITCODE -ne 0) { throw 'Paper test plugin compilation failed.' }
    Copy-Item -LiteralPath (Join-Path $taskRepo 'src/test/paper/plugin.yml') -Destination (Join-Path $taskClasses 'plugin.yml') -Force
    & "$env:JAVA_HOME/bin/jar.exe" --create --file (Join-Path $taskWorkspace 'plugins/BitcoinPriceSmoke.jar') -C $taskClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Paper test plugin packaging failed.' }
    Copy-Item -LiteralPath (Join-Path $taskRepo 'target/BitcoinPrice-2.1.0.jar') -Destination (Join-Path $taskWorkspace 'plugins/BitcoinPrice.jar') -Force
} finally { Pop-Location }
Push-Location $taskWorkspace
try {
    foreach ($taskPhase in 1..2) {
        $taskLog = Join-Path $taskWorkspace "phase-$taskPhase.log"
        & "$env:JAVA_HOME/bin/java.exe" '-Dterminal.jline=false' '-Dterminal.ansi=false' -Xms512M -Xmx1G -jar paper.jar --nogui 2>&1 | Tee-Object -FilePath $taskLog
        $taskExitCode = $LASTEXITCODE
        $taskCompleted = Select-String -LiteralPath $taskLog -Pattern 'SMOKE COMPLETE' -Quiet
        $taskFailed = Select-String -LiteralPath $taskLog -Pattern 'SMOKE FAILED' -Quiet
        if ($taskExitCode -ne 0 -or !$taskCompleted -or $taskFailed) {
            throw "Paper integration phase $taskPhase failed. See $taskLog"
        }
    }
} finally { Pop-Location }
Write-Output 'Paper integration and restart checks passed.'
