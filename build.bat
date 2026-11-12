@echo off
rem Compila e crea l'app portatile in "out\Comprimi Immagini Rapido".
rem Serve un JDK 21 (con jpackage) nel PATH: https://adoptium.net
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
if exist out rmdir /s /q out
mkdir build dist

java tools\CreaIcona.java assets\icon.png build\icon.ico || goto :errore
javac --release 21 --enable-preview -Xlint:-preview -encoding UTF-8 -d build src\*.java || goto :errore
copy /y assets\icon.png build\icon.png >nul
rem MozJPEG: se esiste vendor\cjpeg.exe finisce in tools\ accanto al programma (altrimenti si usa il JPEG di Java)
if exist vendor\cjpeg.exe (
  mkdir dist\tools
  copy /y vendor\cjpeg.exe dist\tools\cjpeg.exe >nul
) else (
  echo Nota: vendor\cjpeg.exe non trovato, l'app usera' il JPEG normale.
)
jar --create --file dist\ComprimiImmaginiRapido.jar --main-class ComprimiImmaginiRapido -C build . || goto :errore

jpackage --type app-image --name "Comprimi Immagini Rapido" --app-version 1.0 ^
  --vendor "Domenico Castaldo" --description "Comprimi, ridimensiona e ritaglia le immagini" ^
  --icon build\icon.ico --input dist --main-jar ComprimiImmaginiRapido.jar --main-class ComprimiImmaginiRapido ^
  --add-modules java.base,java.desktop ^
  --java-options "-Xmx1g" --java-options "--enable-preview" --java-options "--enable-native-access=ALL-UNNAMED" ^
  --dest out || goto :errore

rmdir /s /q build
rmdir /s /q dist
echo.
echo Pronto: "out\Comprimi Immagini Rapido\Comprimi Immagini Rapido.exe"
exit /b 0

:errore
echo.
echo ERRORE: la compilazione non e' riuscita.
exit /b 1
