.\gradlew.bat build
Copy-Item -Path "build\libs\Authme.jar" -Destination "D:\Games\mindustry\minsv\config\mods\Authme.jar" -Force
Push-Location "D:\Games\mindustry\minsv"
java -jar sv.jar
Pop-Location