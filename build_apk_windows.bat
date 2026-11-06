@echo off
setlocal
cd /d "%~dp0"
echo Compilando Mis Finanzas Privadas con Gradle Wrapper...
echo Configura JAVA_HOME con JDK 17 o 21 y el SDK 35 en Android Studio.
call gradlew.bat --console=plain :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
if errorlevel 1 (
  echo ERROR: Revisa el resultado de compilacion y la configuracion de Java y Android SDK.
  pause
  exit /b 1
)
echo APK lista: app\build\outputs\apk\debug\app-debug.apk
echo Para actualizar conserva la misma firma. No desinstales la app anterior.
pause
