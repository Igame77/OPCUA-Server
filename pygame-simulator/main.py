import asyncio
import pygame
import sys
import random
import urllib.request
import json
from asyncua import Client, ua

# ==========================================
# НАСТРОЙКИ PYGAME И ПАЛИТРА ТЕМЫ
# ==========================================
WIDTH, HEIGHT = 1360, 960
FPS = 60

# Индустриальная тема
BG_COLOR = (24, 28, 36)
PANEL_COLOR = (38, 44, 56)
PANEL_BORDER = (55, 65, 82)
RAIL_COLOR = (130, 138, 150)
CRANE_COLOR = (225, 230, 238)
TEXT_COLOR = (235, 240, 245)
MUTED_TEXT = (150, 160, 175)
BATH_BG = (48, 55, 68)

# Индикаторы
GREEN = (46, 204, 113)
RED = (231, 76, 60)
AMBER = (241, 196, 15)
BLUE_GLOW = (52, 152, 219)
PART_COLOR = (243, 156, 18)

# Жидкости и технологические среды узлов 1-7
C_ALK = (168, 215, 80)     # 01 Обезжиривание (лайм)
C_WATER = (52, 140, 235)   # 02, 04 Промывка (чистая вода)
C_ACID = (0, 210, 210)     # 03 Травление (бирюзовая кислота)
C_FLUX = (245, 176, 65)    # 05 Флюсование (янтарно-желтый)
C_DRY = (155, 89, 182)     # 06 Сушильная камера (конвекционная сушка)
C_ZINC = (235, 110, 30)    # 07 Ванна цинкования (расплав 450°C)

opc_data = {
    "connected": False,
    "manual_mode": False,
    "status": "WAITING",
    "step": "Ожидание...",
    "sim_running": False,
    "toggle_request": False,
    "manual_toggle_request": False,
    "pending_spawns": 0,
    "rpro_mode": True,
    "conveyor_run": False,
    "robot1_cmd": "IDLE",
    # 7 технологических узлов
    "stage1": {"temp": 50, "is_active": False, "heater": False},
    "stage2": {"is_active": False, "pump": False},
    "stage3": {"temp": 25, "is_active": False, "agitator": False},
    "stage4": {"is_active": False, "pump": False},
    "stage5": {"temp": 60, "is_active": False, "heater": False},
    "stage6": {"temp": 55, "hum": 20, "is_active": False, "fan": False, "sensor_connected": True},
    "stage7": {"temp": 450, "is_active": True, "heater": False}
}


async def api_poll_task():
    last_tasks = set()
    while True:
        try:
            req = urllib.request.urlopen("http://localhost:8080/api/queue", timeout=1)
            data = json.loads(req.read().decode('utf-8'))
            current_ids = {t["id"] for t in data.get("current_tasks", [])}
            new_tasks = current_ids - last_tasks
            if new_tasks:
                opc_data["pending_spawns"] += len(new_tasks)
            last_tasks = current_ids
        except Exception:
            pass
        await asyncio.sleep(1)


# ==========================================
# КЛАСС ТЕХНОЛОГИЧЕСКОЙ СТАНЦИИ
# ==========================================
class Station:
    def __init__(self, name, x, color, stage_key, is_dryer=False):
        self.name = name
        self.x = x
        self.color = color
        self.stage_key = stage_key
        self.is_dryer = is_dryer
        self.current_part = None
        self.process_start_time = 0


# ==========================================
# КЛАСС МНОГОПОЗИЦИОННОЙ ПРОИЗВОДСТВЕННОЙ ЛИНИИ
# ==========================================
class MultiStationLine:
    def __init__(self, name, y_base, x_start, x_end, stations, is_first=False):
        self.name = name
        self.y_base = y_base
        self.x_start = x_start
        self.x_end = x_end
        self.stations = stations
        self.is_first = is_first
        self.next_line = None
        self.pending_parts_queue = 0

        self.crane_x = self.x_start
        self.crane_y = self.y_base - 105
        self.hook_y = self.y_base - 85

        self.parts = []
        self.crane_state = "IDLE"
        self.crane_part = None
        self.crane_target = None
        self.pickup_x = 0
        self.pickup_y = 0
        self.drop_x = 0
        self.drop_y = 0
        self.target_station_idx = -1
        self.source_station_idx = -1

    def update(self, current_time):
        if opc_data["manual_mode"] or not opc_data["sim_running"]:
            return

        crane_speed_x = 7
        hook_speed_y = 5
        process_duration = 2400

        # Обновление таймеров обработки деталей на станциях
        for idx, station in enumerate(self.stations):
            if station.current_part:
                if current_time - station.process_start_time > process_duration:
                    station.current_part['ready_for_next'] = True

        # Логика диспетчера манипулятора
        if self.crane_state == "IDLE":
            task_found = False

            # 1. Приоритет: отправка готовой детали с последней станции на выходной конвейер
            last_station = self.stations[-1]
            if last_station.current_part and last_station.current_part.get('ready_for_next'):
                self.crane_state = "MOVE_TO_PICKUP"
                self.crane_target = last_station.current_part
                self.source_station_idx = len(self.stations) - 1
                self.target_station_idx = -2  # Выход
                self.pickup_x = last_station.x
                self.pickup_y = self.y_base + 10
                self.drop_x = self.x_end
                self.drop_y = self.y_base
                task_found = True

            # 2. Перемещение между станциями (от конца к началу, чтобы освобождать места)
            if not task_found:
                for i in range(len(self.stations) - 2, -1, -1):
                    src_st = self.stations[i]
                    dst_st = self.stations[i + 1]
                    if src_st.current_part and src_st.current_part.get('ready_for_next') and not dst_st.current_part:
                        self.crane_state = "MOVE_TO_PICKUP"
                        self.crane_target = src_st.current_part
                        self.source_station_idx = i
                        self.target_station_idx = i + 1
                        self.pickup_x = src_st.x
                        self.pickup_y = self.y_base + 10
                        self.drop_x = dst_st.x
                        self.drop_y = self.y_base + 10
                        task_found = True
                        break

            # 3. Загрузка новой детали со входного накопителя на первую станцию
            if not task_found:
                first_st = self.stations[0]
                should_spawn = False
                if self.is_first and opc_data["pending_spawns"] > 0 and not first_st.current_part:
                    should_spawn = True
                    opc_data["pending_spawns"] -= 1
                elif not self.is_first and self.pending_parts_queue > 0 and not first_st.current_part:
                    should_spawn = True
                    self.pending_parts_queue -= 1

                if should_spawn:
                    new_part = {'x': self.x_start, 'y': self.y_base, 'ready_for_next': False, 'state': 'WAITING_START'}
                    self.parts.append(new_part)
                    self.crane_state = "MOVE_TO_PICKUP"
                    self.crane_target = new_part
                    self.source_station_idx = -1  # Вход
                    self.target_station_idx = 0
                    self.pickup_x = self.x_start
                    self.pickup_y = self.y_base
                    self.drop_x = first_st.x
                    self.drop_y = self.y_base + 10

        elif self.crane_state == "MOVE_TO_PICKUP":
            if abs(self.crane_x - self.pickup_x) > crane_speed_x:
                self.crane_x += crane_speed_x if self.pickup_x > self.crane_x else -crane_speed_x
            else:
                self.crane_x = self.pickup_x
                self.crane_state = "LIFT_DOWN_PICKUP"

        elif self.crane_state == "LIFT_DOWN_PICKUP":
            if self.hook_y < self.pickup_y:
                self.hook_y += hook_speed_y
            else:
                self.hook_y = self.pickup_y
                self.crane_part = self.crane_target
                if self.source_station_idx >= 0:
                    self.stations[self.source_station_idx].current_part = None
                self.crane_state = "LIFT_UP_PICKUP"

        elif self.crane_state == "LIFT_UP_PICKUP":
            if self.hook_y > self.y_base - 85:
                self.hook_y -= hook_speed_y
            else:
                self.crane_state = "MOVE_TO_DROP"

        elif self.crane_state == "MOVE_TO_DROP":
            if abs(self.crane_x - self.drop_x) > crane_speed_x:
                self.crane_x += crane_speed_x if self.drop_x > self.crane_x else -crane_speed_x
            else:
                self.crane_x = self.drop_x
                self.crane_state = "LIFT_DOWN_DROP"

        elif self.crane_state == "LIFT_DOWN_DROP":
            if self.hook_y < self.drop_y:
                self.hook_y += hook_speed_y
            else:
                self.hook_y = self.drop_y
                if self.target_station_idx >= 0:
                    dst_st = self.stations[self.target_station_idx]
                    dst_st.current_part = self.crane_part
                    dst_st.process_start_time = current_time
                    self.crane_part['ready_for_next'] = False
                    self.crane_part['state'] = f'IN_{dst_st.stage_key}'
                elif self.target_station_idx == -2:
                    self.crane_part['state'] = 'LEAVING'

                self.crane_part = None
                self.crane_state = "LIFT_UP_RETURN"

        elif self.crane_state == "LIFT_UP_RETURN":
            if self.hook_y > self.y_base - 85:
                self.hook_y -= hook_speed_y
            else:
                self.crane_state = "IDLE"

        # Привязка детали к захвату крана
        if self.crane_part:
            self.crane_part['x'] = self.crane_x
            self.crane_part['y'] = self.hook_y + 35

        # Детали, покидающие линию по конвейеру
        for part in list(self.parts):
            if part.get('state') == 'LEAVING':
                part['x'] += 6
                if part['x'] > WIDTH - 60:
                    self.parts.remove(part)
                    if self.next_line is not None:
                        self.next_line.pending_parts_queue += 1

    def draw(self, surface, font, small_font, tiny_font):
        # Название линии
        surface.blit(font.render(self.name, True, TEXT_COLOR), (self.x_start, self.y_base - 145))

        # Конвейеры (вход и выход)
        self.draw_conveyor(surface, self.x_start - 60, self.y_base + 38, 120)
        self.draw_conveyor(surface, self.x_end - 40, self.y_base + 38, WIDTH - self.x_end + 40)

        # Буфер деталей на входе
        for i in range(min(self.pending_parts_queue, 4)):
            px = self.x_start - 20 - (i * 24)
            pygame.draw.rect(surface, PART_COLOR, (px, self.y_base + 12, 36, 26), border_radius=4)
            pygame.draw.rect(surface, (255, 210, 130), (px, self.y_base + 12, 36, 26), 2, border_radius=4)

        # Рельс манипулятора
        pygame.draw.rect(surface, RAIL_COLOR, (self.x_start - 40, self.y_base - 105, self.x_end - self.x_start + 80, 8), border_radius=4)

        # Отрисовка станций
        for station in self.stations:
            self.draw_station(surface, small_font, tiny_font, station)

        # Отрисовка деталей на станциях и в движении
        for part in self.parts:
            pygame.draw.rect(surface, PART_COLOR, (part['x'] - 18, part['y'], 36, 26), border_radius=4)
            pygame.draw.rect(surface, (255, 210, 130), (part['x'] - 18, part['y'], 36, 26), 2, border_radius=4)

        # Манипулятор (каретка, трос, захват)
        pygame.draw.rect(surface, CRANE_COLOR, (self.crane_x - 24, self.crane_y - 12, 48, 18), border_radius=4)
        pygame.draw.line(surface, CRANE_COLOR, (self.crane_x, self.crane_y), (self.crane_x, self.hook_y), 3)

        # Клешни захвата
        pygame.draw.line(surface, CRANE_COLOR, (self.crane_x - 18, self.hook_y), (self.crane_x + 18, self.hook_y), 5)
        pygame.draw.line(surface, CRANE_COLOR, (self.crane_x - 18, self.hook_y), (self.crane_x - 18, self.hook_y + 18), 4)
        pygame.draw.line(surface, CRANE_COLOR, (self.crane_x + 18, self.hook_y), (self.crane_x + 18, self.hook_y + 18), 4)

    def draw_conveyor(self, surface, x, y, width):
        pygame.draw.rect(surface, PANEL_COLOR, (x, y, width, 22), border_radius=3)
        for i in range(x + 12, x + width - 10, 22):
            pygame.draw.circle(surface, RAIL_COLOR, (i, y + 11), 7)

    def draw_station(self, surface, small_font, tiny_font, station):
        x = station.x
        w, h = 170, 80

        # Корпус
        pygame.draw.rect(surface, (16, 20, 26), (x - w // 2, self.y_base - 6, w, h + 8), border_radius=8)
        pygame.draw.rect(surface, BATH_BG, (x - w // 2, self.y_base - 10, w, h + 4), border_radius=8)

        # Среда ванны / сушильной камеры
        inner_w, inner_h = w - 16, h - 14
        pygame.draw.rect(surface, station.color, (x - inner_w // 2, self.y_base, inner_w, inner_h), border_radius=5)

        # Блик поверхности
        s = pygame.Surface((inner_w, 14), pygame.SRCALPHA)
        s.fill((255, 255, 255, 45))
        surface.blit(s, (x - inner_w // 2, self.y_base))

        # Название узла
        title_surf = small_font.render(station.name, True, TEXT_COLOR)
        surface.blit(title_surf, (x - title_surf.get_width() // 2, self.y_base + h + 2))

        # Карточка телеметрии под ванной
        card_y = self.y_base + h + 26
        pygame.draw.rect(surface, PANEL_COLOR, (x - w // 2, card_y, w, 52), border_radius=6)
        pygame.draw.rect(surface, PANEL_BORDER, (x - w // 2, card_y, w, 52), 1, border_radius=6)

        # Данные из OPC UA
        s_data = opc_data.get(station.stage_key, {})

        if station.stage_key == "stage1":
            t = s_data.get("temp", 50)
            heat = s_data.get("heater", False)
            act = s_data.get("is_active", False)
            line1 = f"T: {t}°C | ТЭН: {'ВКЛ' if heat else 'ВЫКЛ'}"
            line2 = f"Статус: {'АКТИВЕН' if act else 'ОЖИДАНИЕ'}"
            c1 = GREEN if heat else MUTED_TEXT
            c2 = GREEN if act else MUTED_TEXT

        elif station.stage_key in ("stage2", "stage4"):
            pump = s_data.get("pump", False)
            act = s_data.get("is_active", False)
            line1 = f"Помпа: {'РАБОТА' if pump else 'СТОП'}"
            line2 = f"Циркуляция: {'АКТИВНА' if act else 'ОЖИДАНИЕ'}"
            c1 = GREEN if pump else MUTED_TEXT
            c2 = GREEN if act else MUTED_TEXT

        elif station.stage_key == "stage3":
            t = s_data.get("temp", 25)
            agit = s_data.get("agitator", False)
            act = s_data.get("is_active", False)
            line1 = f"T: {t}°C | Мешалка: {'ВКЛ' if agit else 'ВЫКЛ'}"
            line2 = f"Травление: {'АКТИВНО' if act else 'ОЖИДАНИЕ'}"
            c1 = GREEN if agit else MUTED_TEXT
            c2 = GREEN if act else MUTED_TEXT

        elif station.stage_key == "stage5":
            t = s_data.get("temp", 60)
            heat = s_data.get("heater", False)
            act = s_data.get("is_active", False)
            line1 = f"T: {t}°C | Подогрев: {'ВКЛ' if heat else 'ВЫКЛ'}"
            line2 = f"Флюс: {'АКТИВЕН' if act else 'ОЖИДАНИЕ'}"
            c1 = GREEN if heat else MUTED_TEXT
            c2 = GREEN if act else MUTED_TEXT

        elif station.stage_key == "stage6":
            t = s_data.get("temp", 55)
            hum = s_data.get("hum", 20)
            fan = s_data.get("fan", False)
            sens = s_data.get("sensor_connected", False)
            line1 = f"T: {t}°C | Влажность: {hum}%"
            line2 = f"Вент: {'ВКЛ' if fan else 'ВЫКЛ'} | UART: {'OK' if sens else 'НЕТ'}"
            c1 = AMBER if t > 50 else MUTED_TEXT
            c2 = GREEN if sens else RED

        elif station.stage_key == "stage7":
            t = s_data.get("temp", 450)
            heat = s_data.get("heater", False)
            line1 = f"T: {t}°C (Расплав Zn)"
            line2 = f"ТЭН: {'ВКЛ' if heat else 'ВЫКЛ'} | АКТИВНО"
            c1 = AMBER
            c2 = GREEN
        else:
            line1, line2 = "OK", "АКТИВНО"
            c1, c2 = MUTED_TEXT, MUTED_TEXT

        l1_surf = tiny_font.render(line1, True, c1)
        l2_surf = tiny_font.render(line2, True, c2)
        surface.blit(l1_surf, (x - l1_surf.get_width() // 2, card_y + 8))
        surface.blit(l2_surf, (x - l2_surf.get_width() // 2, card_y + 28))


# ==========================================
# OPC UA КЛИЕНТ
# ==========================================
async def opcua_client_task():
    url = "opc.tcp://localhost:4840/"
    while True:
        client = Client(url=url)
        try:
            await client.connect()
            opc_data["connected"] = True

            idx = await client.get_namespace_index('http://factory-aps.local')
            scheduler_node = await client.nodes.objects.get_child([f"{idx}:8_Global_Scheduler"])

            manual_node = await scheduler_node.get_child([f"{idx}:ManualOverride"])
            status_node = await scheduler_node.get_child([f"{idx}:SystemStatus"])
            step_node = await scheduler_node.get_child([f"{idx}:CurrentAlgorithmStep"])
            sim_node = await scheduler_node.get_child([f"{idx}:SimulationRunning"])

            try:
                conveyor_node = await scheduler_node.get_child([f"{idx}:Conveyor_Run"])
                robot1_node = await scheduler_node.get_child([f"{idx}:Robot1_Command"])
            except Exception:
                conveyor_node = None
                robot1_node = None

            # Подключение ко всем 7 узлам технологической линии
            try:
                folder1 = await client.nodes.objects.get_child([f"{idx}:1_Alkaline_Degreasing"])
                s1_t = await folder1.get_child([f"{idx}:Temperature"])
                s1_a = await folder1.get_child([f"{idx}:IsActive"])
                s1_h = await folder1.get_child([f"{idx}:HeaterOn"])

                folder2 = await client.nodes.objects.get_child([f"{idx}:2_Rinsing_1"])
                s2_a = await folder2.get_child([f"{idx}:IsActive"])
                s2_p = await folder2.get_child([f"{idx}:PumpRunning"])

                folder3 = await client.nodes.objects.get_child([f"{idx}:3_Pickling"])
                s3_t = await folder3.get_child([f"{idx}:Temperature"])
                s3_a = await folder3.get_child([f"{idx}:IsActive"])
                s3_g = await folder3.get_child([f"{idx}:AgitatorOn"])

                folder4 = await client.nodes.objects.get_child([f"{idx}:4_Rinsing_2"])
                s4_a = await folder4.get_child([f"{idx}:IsActive"])
                s4_p = await folder4.get_child([f"{idx}:PumpRunning"])

                folder5 = await client.nodes.objects.get_child([f"{idx}:5_Fluxing"])
                s5_t = await folder5.get_child([f"{idx}:Temperature"])
                s5_a = await folder5.get_child([f"{idx}:IsActive"])
                s5_h = await folder5.get_child([f"{idx}:HeaterOn"])

                folder6 = await client.nodes.objects.get_child([f"{idx}:6_Drying_Chamber"])
                s6_t = await folder6.get_child([f"{idx}:TemperatureCelsius"])
                s6_w = await folder6.get_child([f"{idx}:HumidityPercent"])
                s6_a = await folder6.get_child([f"{idx}:IsActive"])
                s6_f = await folder6.get_child([f"{idx}:FanOn"])
                s6_s = await folder6.get_child([f"{idx}:SensorConnected"])

                folder7 = await client.nodes.objects.get_child([f"{idx}:7_Zinc_Bath"])
                s7_t = await folder7.get_child([f"{idx}:Temperature"])
                s7_a = await folder7.get_child([f"{idx}:IsActive"])
                s7_h = await folder7.get_child([f"{idx}:HeaterOn"])
                has_stages = True
            except Exception:
                has_stages = False

            while True:
                opc_data["manual_mode"] = await manual_node.get_value()
                opc_data["status"] = await status_node.get_value()
                opc_data["step"] = await step_node.get_value()
                opc_data["sim_running"] = await sim_node.get_value()

                if conveyor_node:
                    opc_data["conveyor_run"] = await conveyor_node.get_value()
                if robot1_node:
                    opc_data["robot1_cmd"] = await robot1_node.get_value()

                if has_stages:
                    try:
                        opc_data["stage1"] = {
                            "temp": await s1_t.get_value(),
                            "is_active": await s1_a.get_value(),
                            "heater": await s1_h.get_value()
                        }
                        opc_data["stage2"] = {
                            "is_active": await s2_a.get_value(),
                            "pump": await s2_p.get_value()
                        }
                        opc_data["stage3"] = {
                            "temp": await s3_t.get_value(),
                            "is_active": await s3_a.get_value(),
                            "agitator": await s3_g.get_value()
                        }
                        opc_data["stage4"] = {
                            "is_active": await s4_a.get_value(),
                            "pump": await s4_p.get_value()
                        }
                        opc_data["stage5"] = {
                            "temp": await s5_t.get_value(),
                            "is_active": await s5_a.get_value(),
                            "heater": await s5_h.get_value()
                        }
                        opc_data["stage6"] = {
                            "temp": await s6_t.get_value(),
                            "hum": await s6_w.get_value(),
                            "is_active": await s6_a.get_value(),
                            "fan": await s6_f.get_value(),
                            "sensor_connected": await s6_s.get_value()
                        }
                        opc_data["stage7"] = {
                            "temp": await s7_t.get_value(),
                            "is_active": await s7_a.get_value(),
                            "heater": await s7_h.get_value()
                        }
                    except Exception:
                        pass

                if opc_data["toggle_request"]:
                    new_state = not opc_data["sim_running"]
                    await sim_node.set_value(ua.Variant(new_state, ua.VariantType.Boolean))
                    opc_data["toggle_request"] = False

                if opc_data["manual_toggle_request"]:
                    new_manual_state = not opc_data["manual_mode"]
                    await manual_node.set_value(ua.Variant(new_manual_state, ua.VariantType.Boolean))
                    if new_manual_state:
                        await status_node.set_value(ua.Variant("MANUAL_INTERVENTION (Кнопка G)", ua.VariantType.String))
                    else:
                        await status_node.set_value(ua.Variant("AUTO_NORMAL", ua.VariantType.String))
                    opc_data["manual_toggle_request"] = False

                await asyncio.sleep(0.1)

        except Exception:
            opc_data["connected"] = False
            await asyncio.sleep(2)
        finally:
            try:
                await client.disconnect()
            except:
                pass


# ==========================================
# ГЛАВНЫЙ ЦИКЛ PYGAME
# ==========================================
async def pygame_loop():
    pygame.init()
    screen = pygame.display.set_mode((WIDTH, HEIGHT))
    pygame.display.set_caption("Smart Factory: Hot-Dip Galvanizing Digital Twin (7 Stages)")

    font = pygame.font.SysFont("segoeui", 20, bold=True)
    small_font = pygame.font.SysFont("segoeui", 15, bold=True)
    tiny_font = pygame.font.SysFont("segoeui", 12)
    big_font = pygame.font.SysFont("segoeui", 26, bold=True)
    mega_font = pygame.font.SysFont("segoeui", 38, bold=True)

    # Инициализация станций Ветки 1: Подготовка поверхности (Узлы 1-4)
    baths_line1 = [
        Station("01 Обезжиривание", 260, C_ALK, "stage1"),
        Station("02 Промывка 1", 520, C_WATER, "stage2"),
        Station("03 Травление", 780, C_ACID, "stage3"),
        Station("04 Промывка 2", 1040, C_WATER, "stage4")
    ]
    l1 = MultiStationLine("ВЕТКА 1: Подготовка поверхности (Обезжиривание → Промывка → Травление → Промывка)",
                          240, 80, 1240, baths_line1, is_first=True)

    # Инициализация станций Ветки 2: Покрытие и термообработка (Узлы 5-7)
    baths_line2 = [
        Station("05 Флюсование", 340, C_FLUX, "stage5"),
        Station("06 Сушильная камера", 690, C_DRY, "stage6", is_dryer=True),
        Station("07 Ванна цинкования (450°C)", 1040, C_ZINC, "stage7")
    ]
    l2 = MultiStationLine("ВЕТКА 2: Цинкование и сушка (Флюсование → Сушильная камера [UART COM20] → Ванна Zn 450°C)",
                          640, 100, 1260, baths_line2, is_first=False)

    l1.next_line = l2
    lines = [l1, l2]

    while True:
        current_time = pygame.time.get_ticks()

        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                pygame.quit()
                sys.exit()
            if event.type == pygame.KEYDOWN:
                if event.key == pygame.K_SPACE:
                    opc_data["toggle_request"] = True
                if event.key == pygame.K_g:
                    opc_data["manual_toggle_request"] = True

        for line in lines:
            line.update(current_time)

        screen.fill(BG_COLOR)

        # Отрисовка вертикального лифта между Веткой 1 и Веткой 2
        pygame.draw.rect(screen, PANEL_COLOR, (WIDTH - 110, 275, 40, 400), border_radius=5)
        pygame.draw.rect(screen, RAIL_COLOR, (WIDTH - 100, 275, 20, 400), border_radius=3)

        for line in lines:
            line.draw(screen, font, small_font, tiny_font)

        # ВЕРХНИЙ ДАШБОРД (HUD)
        pygame.draw.rect(screen, PANEL_COLOR, (0, 0, WIDTH, 68))
        pygame.draw.line(screen, BLUE_GLOW, (0, 68), (WIDTH, 68), 3)

        if not opc_data["connected"]:
            screen.blit(big_font.render("ОШИБКА: ПОИСК OPC UA СЕРВЕРА (opc.tcp://localhost:4840/)...", True, RED), (20, 16))
        else:
            if opc_data["manual_mode"]:
                if current_time % 1000 < 500:
                    pygame.draw.rect(screen, RED, (0, 0, WIDTH, HEIGHT), 8)
                screen.blit(big_font.render("ВНИМАНИЕ! РУЧНОЕ ВМЕШАТЕЛЬСТВО ОПЕРАТОРА (G). КОМПЛЕКС ОСТАНОВЛЕН", True, RED), (20, 16))
            else:
                screen.blit(big_font.render(f"АВТОМАТИЧЕСКИЙ РЕЖИМ | {opc_data['status']}", True, GREEN), (20, 16))

            step_surf = small_font.render(f"АЛГОРИТМ APS: {opc_data['step'].upper()}", True, BLUE_GLOW)
            screen.blit(step_surf, (WIDTH - step_surf.get_width() - 25, 24))

        # ПОЛОСА МИНИ-ИНДИКАТОРОВ ВСЕХ 7 СТАДИЙ
        badge_w = (WIDTH - 40) // 7
        for idx in range(1, 8):
            st_key = f"stage{idx}"
            s_val = opc_data.get(st_key, {})
            bx = 20 + (idx - 1) * badge_w
            pygame.draw.rect(screen, PANEL_COLOR, (bx, 76, badge_w - 6, 26), border_radius=4)
            pygame.draw.rect(screen, PANEL_BORDER, (bx, 76, badge_w - 6, 26), 1, border_radius=4)

            if idx == 1:
                txt = f"1. Обезжир: {s_val.get('temp', 50)}°C"
            elif idx == 2:
                txt = f"2. Промывка-1: {'РАБ' if s_val.get('pump') else 'СТОП'}"
            elif idx == 3:
                txt = f"3. Травление: {s_val.get('temp', 25)}°C"
            elif idx == 4:
                txt = f"4. Промывка-2: {'РАБ' if s_val.get('pump') else 'СТОП'}"
            elif idx == 5:
                txt = f"5. Флюс: {s_val.get('temp', 60)}°C"
            elif idx == 6:
                txt = f"6. Сушка: {s_val.get('temp', 55)}°C {s_val.get('hum', 20)}%"
            else:
                txt = f"7. Цинк: {s_val.get('temp', 450)}°C"

            ts = tiny_font.render(txt, True, TEXT_COLOR)
            screen.blit(ts, (bx + (badge_w - 6 - ts.get_width()) // 2, 81))

        # ЭКРАН ОЖИДАНИЯ ПРИ ОСТАНОВЛЕННОЙ СИМУЛЯЦИИ
        if opc_data["connected"] and not opc_data["sim_running"]:
            overlay = pygame.Surface((WIDTH, HEIGHT))
            overlay.set_alpha(185)
            overlay.fill((12, 16, 22))
            screen.blit(overlay, (0, 0))

            if current_time % 1200 < 800:
                text = mega_font.render("НАЖМИТЕ [ПРОБЕЛ] ДЛЯ ЗАПУСКА ПРОИЗВОДСТВЕННОЙ ЛИНИИ", True, GREEN)
                screen.blit(text, (WIDTH // 2 - text.get_width() // 2, HEIGHT // 2 - 20))

            hint = font.render("Клавиша [G] — Имитация аварийной остановки комплекса", True, TEXT_COLOR)
            screen.blit(hint, (WIDTH // 2 - hint.get_width() // 2, HEIGHT // 2 + 40))

        pygame.display.flip()
        await asyncio.sleep(1 / FPS)


async def main():
    await asyncio.gather(opcua_client_task(), api_poll_task(), pygame_loop())


if __name__ == "__main__":
    asyncio.run(main())