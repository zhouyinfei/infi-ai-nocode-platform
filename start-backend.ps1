param([string]$JavaHome = $env:JAVA_HOME, [string]$Maven = 'mvn')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $JavaHome -and (Test-Path 'D:/Program Files (x86)/Java/jdk-21.0.12.1')) { $JavaHome = 'D:/Program Files (x86)/Java/jdk-21.0.12.1' }
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if ($Maven -eq 'mvn' -and -not (Get-Command mvn -ErrorAction SilentlyContinue) -and (Test-Path 'D:/Program Files/apache-maven-3.9.16/bin/mvn.cmd')) { $Maven = 'D:/Program Files/apache-maven-3.9.16/bin/mvn.cmd' }
if (-not (Test-Path 'src/main/resources/application-local.yml')) { throw '请先根据 application.yml 创建 application-local.yml 并填写真实配置。' }
& $Maven '-Dmaven.repo.local=.tools/m2' spring-boot:run '-Dspring-boot.run.arguments=--spring.profiles.active=local --spring.config.additional-location=optional:file:./src/main/resources/application-local.yml'
exit $LASTEXITCODE
