import asyncio
import random
import urllib.request
import json
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

try:
    import serial
except ImportError:
    serial = None

# --- НАСТРОЙКИ ПОРТА ---
COM_PORT = 'COM20'
BAUD_RATE = 115200

# Глобальное состояние всей производственной линии
factory_state = {
    "stage_1_alkaline_degreasing": {"temperature": 50, "is_active": False, "heater_on": False},
    "stage_2_rinsing_1": {"is_active": False, "pump_running": False},
    "stage_3_pickling": {"temperature": 25, "is_active": False, "agitator_on": False},
    "stage_4_rinsing_2": {"is_active": False, "pump_running": False},
    "stage_5_fluxing": {"temperature": 60, "is_active": False, "heater_on": False},
    "stage_6_drying_chamber": {
        "temperature_celsius": 0,
        "humidity_percent": 0,
        "is_active": False,
        "fan_on": False,
        "sensor_connected": False
    },
    "stage_7_zinc_bath": {"temperature": 450, "is_active": True, "heater_on": False}
}

ser = None
uart_task = None
sim_task = None
forward_task = None


async def read_serial_data():
    """Фоновая задача 1: Непрерывное чтение COM-порта для узла 6"""
    global factory_state, ser

    while True:
        try:
            if ser is None or not ser.is_open:
                await asyncio.sleep(1)
                continue

            if ser.in_waiting > 0:
                response_byte = ser.readline()
                response_text = response_byte.decode('utf-8', errors='ignore').strip()
                text_lower = response_text.lower()

                if 'temp:' in text_lower and 'hum:' in text_lower:
                    try:
                        parts = text_lower.split(';')
                        temp_part = parts[0].replace('temp:', '').strip()
                        hum_part = parts[1].replace('hum:', '').strip()

                        temp = int(temp_part)
                        hum = int(hum_part)

                        node6 = factory_state["stage_6_drying_chamber"]
                        node6["temperature_celsius"] = temp
                        node6["humidity_percent"] = hum
                        node6["is_active"] = hum > 30
                        node6["fan_on"] = hum > 40
                        node6["sensor_connected"] = True
                        print(f"[UART SUCCESS] Узел 6 обновлен: T={temp}, H={hum}")

                    except ValueError as ve:
                        print(f"[UART PARSE ERROR] Не смог извлечь числа из строки '{response_text}'. Ошибка: {ve}")
                else:
                    if response_text:
                        print(f"[UART WARNING] Неизвестный формат данных: '{response_text}'")

        except Exception as se:
            factory_state["stage_6_drying_chamber"]["sensor_connected"] = False
            print(f"[UART DISCONNECT] Потеряна связь с портом: {se}")
            if ser and hasattr(ser, 'is_open') and ser.is_open:
                ser.close()

        await asyncio.sleep(0.1)


async def simulate_other_nodes():
    """Фоновая задача 2: Симуляция работы узлов 1-5 и 7"""
    global factory_state

    while True:
        try:
            # Узел 1: Обезжиривание
            t1 = random.randint(45, 55)
            factory_state["stage_1_alkaline_degreasing"]["temperature"] = t1
            factory_state["stage_1_alkaline_degreasing"]["heater_on"] = t1 < 50
            factory_state["stage_1_alkaline_degreasing"]["is_active"] = random.choice([True, False])

            # Узел 2: Промывка 1
            pump2 = random.choice([True, False])
            factory_state["stage_2_rinsing_1"]["pump_running"] = pump2
            factory_state["stage_2_rinsing_1"]["is_active"] = pump2

            # Узел 3: Травление
            t3 = random.randint(20, 30)
            factory_state["stage_3_pickling"]["temperature"] = t3
            factory_state["stage_3_pickling"]["agitator_on"] = random.choice([True, False])
            factory_state["stage_3_pickling"]["is_active"] = random.choice([True, False])

            # Узел 4: Промывка 2
            pump4 = random.choice([True, False])
            factory_state["stage_4_rinsing_2"]["pump_running"] = pump4
            factory_state["stage_4_rinsing_2"]["is_active"] = pump4

            # Узел 5: Флюс
            t5 = random.randint(55, 65)
            factory_state["stage_5_fluxing"]["temperature"] = t5
            factory_state["stage_5_fluxing"]["heater_on"] = t5 < 60
            factory_state["stage_5_fluxing"]["is_active"] = random.choice([True, False])

            # Узел 7: Цинк
            t7 = random.randint(440, 460)
            factory_state["stage_7_zinc_bath"]["temperature"] = t7
            factory_state["stage_7_zinc_bath"]["heater_on"] = t7 < 448
            factory_state["stage_7_zinc_bath"]["is_active"] = True

        except Exception as e:
            print(f"[SIM ERROR] Ошибка симуляции: {e}")

        await asyncio.sleep(2)


async def forward_to_java_server():
    """Фоновая задача 3: Передача телеметрии в Java-сервер (если запущен на 8080)"""
    while True:
        try:
            url = "http://localhost:8080/api/factory-status"
            payload = json.dumps({"status": "ok", "data": factory_state}).encode('utf-8')
            req = urllib.request.Request(url, data=payload, headers={'Content-Type': 'application/json'}, method='POST')
            urllib.request.urlopen(req, timeout=1)
        except Exception:
            pass
        await asyncio.sleep(1)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Управление ресурсами при старте и остановке API"""
    global ser, uart_task, sim_task, forward_task, factory_state

    print("[API] Запуск фоновых задач и подключение оборудования...")
    sim_task = asyncio.create_task(simulate_other_nodes())
    forward_task = asyncio.create_task(forward_to_java_server())

    if serial is not None:
        try:
            ser = serial.Serial(port=COM_PORT, baudrate=BAUD_RATE, timeout=1)
            factory_state["stage_6_drying_chamber"]["sensor_connected"] = True
            print(f"[API] Порт {COM_PORT} успешно открыт. Чтение начато.")
            uart_task = asyncio.create_task(read_serial_data())
        except Exception as e:
            factory_state["stage_6_drying_chamber"]["sensor_connected"] = False
            print(f"[API WARNING] Не удалось открыть {COM_PORT}: {e}")
    else:
        print("[API WARNING] Пакет pyserial не установлен. Чтение UART отключено.")

    yield

    print("[API] Остановка сервиса, закрытие портов...")
    if uart_task:
        uart_task.cancel()
    if sim_task:
        sim_task.cancel()
    if forward_task:
        forward_task.cancel()
    if ser and hasattr(ser, 'is_open') and ser.is_open:
        ser.close()


# Инициализация приложения
app = FastAPI(title="Factory Full API (Standalone)", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/factory-status", tags=["Production Line"])
async def get_factory_status():
    """Возвращает актуальные данные со всех узлов (комбинация UART и симуляции)"""
    return {"status": "ok", "data": factory_state}


@app.get("/health", tags=["System"])
async def health_check():
    """Проверка статуса системы"""
    return {
        "status": "ok",
        "uart_connected": factory_state["stage_6_drying_chamber"]["sensor_connected"]
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
