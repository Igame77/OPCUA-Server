using System;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading;

class Program
{
    private static Process serverProcess = null;
    private static Process simulatorProcess = null;

    [DllImport("Kernel32")]
    private static extern bool SetConsoleCtrlHandler(EventHandler handler, bool add);

    private delegate bool EventHandler(CtrlType sig);
    private static EventHandler _handler;

    private enum CtrlType
    {
        CTRL_C_EVENT = 0,
        CTRL_BREAK_EVENT = 1,
        CTRL_CLOSE_EVENT = 2,
        CTRL_LOGOFF_EVENT = 5,
        CTRL_SHUTDOWN_EVENT = 6
    }

    private static bool Handler(CtrlType sig)
    {
        Cleanup();
        return true;
    }

    static void Cleanup()
    {
        Console.WriteLine("\n[Launcher] Остановка всех сервисов...");
        try
        {
            if (simulatorProcess != null && !simulatorProcess.HasExited)
            {
                simulatorProcess.Kill();
                Console.WriteLine("[Launcher] Симулятор остановлен.");
            }
        }
        catch { }

        try
        {
            if (serverProcess != null && !serverProcess.HasExited)
            {
                serverProcess.Kill();
                Console.WriteLine("[Launcher] Сервер остановлен.");
            }
        }
        catch { }
    }

    static bool CheckPort(int port)
    {
        try
        {
            using (TcpClient client = new TcpClient("127.0.0.1", port))
            {
                return true;
            }
        }
        catch
        {
            return false;
        }
    }

    static void Main(string[] args)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        _handler += new EventHandler(Handler);
        SetConsoleCtrlHandler(_handler, true);
        AppDomain.CurrentDomain.ProcessExit += (s, e) => Cleanup();

        Console.Title = "Smart Factory - Unified Launcher";
        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("================================================================");
        Console.WriteLine("          SMART FACTORY: OPC UA & MES SYSTEM LAUNCHER           ");
        Console.WriteLine("================================================================");
        Console.ResetColor();
        Console.WriteLine();

        string currentDir = AppDomain.CurrentDomain.BaseDirectory;
        string jarPath = Path.Combine(currentDir, "server.jar");
        string simPath = Path.Combine(currentDir, "simulator.exe");

        if (!File.Exists(jarPath))
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("[ОШИБКА] Файл server.jar не найден рядом с лаунчером!");
            Console.ResetColor();
            Console.ReadKey();
            return;
        }

        // 1. Проверка Java
        Console.Write("[1/3] Проверка наличия Java... ");
        try
        {
            Process javaCheck = Process.Start(new ProcessStartInfo
            {
                FileName = "java",
                Arguments = "-version",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true
            });
            javaCheck.WaitForExit();
            if (javaCheck.ExitCode == 0)
            {
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("OK");
                Console.ResetColor();
            }
            else
            {
                throw new Exception();
            }
        }
        catch
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("НЕ НАЙДЕНА");
            Console.WriteLine("\n[ВНИМАНИЕ] Для запуска сервера требуется установленная Java 17 или выше!");
            Console.WriteLine("Скачайте и установите Java: https://adoptium.net/");
            Console.ResetColor();
            Console.WriteLine("\nНажмите любую клавишу для выхода...");
            Console.ReadKey();
            return;
        }

        // 2. Запуск Spring Boot / OPC UA Сервера
        Console.WriteLine("[2/3] Запуск Java Backend & OPC UA Server (порт 8080 / 4840)...");
        ProcessStartInfo serverInfo = new ProcessStartInfo
        {
            FileName = "java",
            Arguments = "-jar \"" + jarPath + "\"",
            WorkingDirectory = currentDir,
            UseShellExecute = false
        };

        try
        {
            serverProcess = Process.Start(serverInfo);
        }
        catch (Exception ex)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("[ОШИБКА] Не удалось запустить server.jar: " + ex.Message);
            Console.ResetColor();
            Console.ReadKey();
            return;
        }

        Console.Write("      Ожидание инициализации сервера ");
        int attempts = 0;
        while (attempts < 25)
        {
            if (CheckPort(8080))
            {
                break;
            }
            Thread.Sleep(1000);
            Console.Write(".");
            attempts++;
        }
        Console.WriteLine(" Готово!");

        // Открытие веб-интерфейса в браузере
        try
        {
            Process.Start("http://localhost:8080");
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine("      Веб-интерфейс открыт: http://localhost:8080");
            Console.ResetColor();
        }
        catch { }

        // 3. Запуск 2D Симулятора
        if (File.Exists(simPath))
        {
            Console.WriteLine("[3/3] Запуск 2D Симулятора (PyGame)...");
            ProcessStartInfo simInfo = new ProcessStartInfo
            {
                FileName = simPath,
                WorkingDirectory = currentDir,
                UseShellExecute = true
            };
            try
            {
                simulatorProcess = Process.Start(simInfo);
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("      Симулятор успешно запущен!");
                Console.ResetColor();
            }
            catch (Exception ex)
            {
                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine("      [Предупреждение] Не удалось запустить симулятор: " + ex.Message);
                Console.ResetColor();
            }
        }
        else
        {
            Console.ForegroundColor = ConsoleColor.Yellow;
            Console.WriteLine("[3/3] Файл simulator.exe не найден, симулятор пропущен.");
            Console.ResetColor();
        }

        Console.WriteLine();
        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine("================================================================");
        Console.WriteLine("                  СИСТЕМА УСПЕШНО РАБОТАЕТ!                     ");
        Console.WriteLine("================================================================");
        Console.ResetColor();
        Console.WriteLine(" • Веб-интерфейс SCADA/MES: http://localhost:8080");
        Console.WriteLine(" • Адрес OPC UA Сервера:    opc.tcp://localhost:4840/milo");
        Console.WriteLine(" • 2D Симулятор линий:      Запущен в отдельном окне");
        Console.WriteLine();
        Console.ForegroundColor = ConsoleColor.Yellow;
        Console.WriteLine("Для остановки всех сервисов нажмите 'Q' или закройте это окно.");
        Console.ResetColor();

        while (true)
        {
            if (Console.KeyAvailable)
            {
                ConsoleKeyInfo key = Console.ReadKey(true);
                if (key.Key == ConsoleKey.Q)
                {
                    break;
                }
            }
            if (serverProcess != null && serverProcess.HasExited)
            {
                Console.WriteLine("\n[Сервер неожиданно завершил работу]");
                break;
            }
            Thread.Sleep(300);
        }

        Cleanup();
        Console.WriteLine("Все процессы корректно завершены. До свидания!");
        Thread.Sleep(1000);
    }
}
