@echo off
echo ====================================================
echo Starting Antigravity MES System (OPC UA + React + PyGame)
echo ====================================================
echo.

echo [1/3] Starting Java Spring Boot OPC UA Server...
start "Java OPC UA Server" cmd /k "cd java-server && .\mvnw.cmd spring-boot:run"

echo [2/3] Starting React Frontend...
start "React Web Interface" cmd /k "npm run dev"

echo [3/3] Starting PyGame 2D Simulator...
:: Даем серверу пару секунд на запуск перед стартом симулятора
timeout /t 5 /nobreak >nul
start "PyGame Simulator" cmd /k "cd pygame-simulator && python main.py"

echo.
echo All services are starting in separate windows!
echo - Web interface will be available at http://localhost:5173
echo - OPC UA Server will listen on opc.tcp://0.0.0.0:4840
echo - PyGame window will open automatically.
echo.
pause
