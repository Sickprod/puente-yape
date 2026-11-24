@echo off
setlocal
title VISION 20/20 - Compilar la app Puente Yape (Android)
cd /d "%~dp0"
echo ==============================================================
echo   VISION 20/20 - Puente Yape para Android
echo ==============================================================
echo.
rem Usa el Java y el SDK que instala Android Studio
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined JAVA_HOME (
  echo [ERROR] No se encontro Java. Instale Android Studio: https://developer.android.com/studio
  echo y abralo una vez para que descargue el SDK.
  goto :error
)
if not defined ANDROID_HOME (
  echo [ERROR] No se encontro el SDK de Android. Abra Android Studio una vez para instalarlo.
  goto :error
)
echo sdk.dir=%ANDROID_HOME:\=\\%> local.properties
echo Compilando... (la primera vez descarga componentes y puede tardar varios minutos)
call gradlew.bat --no-daemon assembleRelease
if errorlevel 1 goto :error
for %%f in ("app\build\outputs\apk\release\*.apk") do copy /y "%%f" "VisionPuenteYape.apk" >nul
echo.
echo ==============================================================
echo   LISTO: %CD%\VisionPuenteYape.apk
echo   Paselo al celular (WhatsApp, cable USB o Drive) e instalelo.
echo ==============================================================
start "" explorer /select,"%CD%\VisionPuenteYape.apk"
pause
exit /b 0
:error
echo.
echo No se pudo compilar.
pause
exit /b 1
