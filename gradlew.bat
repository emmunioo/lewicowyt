@echo off
setlocal
set "APP_HOME=%~dp0"
set "WRAPPER_DIR=%APP_HOME%gradle\wrapper"
set "WRAPPER_JAR=%WRAPPER_DIR%\gradle-wrapper.jar"
set "WRAPPER_URL=https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
set "EXPECTED_WRAPPER_SHA256=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
if not exist "%WRAPPER_JAR%" (
  echo Pobieranie oficjalnego Gradle Wrapper 8.13...
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%'"
  if errorlevel 1 exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$actual=(Get-FileHash -Algorithm SHA256 '%WRAPPER_JAR%').Hash.ToLower(); if($actual -ne '%EXPECTED_WRAPPER_SHA256%'){throw 'Suma kontrolna Gradle Wrapper nie pasuje.'}"
if errorlevel 1 exit /b 1

if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
set EXIT_CODE=%ERRORLEVEL%
endlocal & exit /b %EXIT_CODE%
