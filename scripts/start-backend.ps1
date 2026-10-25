$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../backend')
$env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/ms-17.0.20.1'
$maven = 'D:/Develop/App/apache-maven-3.6.3/bin/mvn.cmd'
if (!(Test-Path "$env:JAVA_HOME/bin/java.exe")) { throw 'Java 17 not found. Update JAVA_HOME in scripts/start-backend.ps1.' }
if (!(Test-Path $maven)) { throw 'Maven not found. Update its path in scripts/start-backend.ps1.' }
$env:SPRING_PROFILES_ACTIVE = 'demo'
$env:SERVER_PORT = '8080'
Write-Host 'Starting backend: Java 17, profile=demo, http://127.0.0.1:8080'
& $maven '--settings=.mvn/central-settings.xml' '-B' '-ntp' '-Dspring-boot.run.profiles=demo' 'spring-boot:run'
exit $LASTEXITCODE
