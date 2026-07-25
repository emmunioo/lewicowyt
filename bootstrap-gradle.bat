@echo off
setlocal
set "ROOT=%~dp0"
set "DIR=%ROOT%gradle\wrapper"
set "JAR=%DIR%\gradle-wrapper.jar"
set "URL=https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
set "EXPECTED_SHA256=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

if not exist "%DIR%" mkdir "%DIR%"
if not exist "%JAR%" (
  echo Pobieranie oficjalnego Gradle Wrapper 8.13...
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%JAR%'"
  if errorlevel 1 (
    echo Nie udalo sie pobrac Gradle Wrapper.
    exit /b 1
  )
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$actual=(Get-FileHash -Algorithm SHA256 '%JAR%').Hash.ToLower(); if($actual -ne '%EXPECTED_SHA256%'){throw 'Suma kontrolna Gradle Wrapper nie pasuje.'}"
if errorlevel 1 (
  echo Nie udalo sie zweryfikowac Gradle Wrapper.
  exit /b 1
)

echo Gotowe. Mozesz teraz otworzyc projekt w Android Studio.
endlocal
