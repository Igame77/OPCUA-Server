import asyncio
import pygame
import sys
import random
import urllib.request
import json
from asyncua import Client, ua

# ==========================================
# НАСТРОЙКИ PYGAME И СОВРЕМЕННАЯ ПАЛИТРА
# ==========================================
WIDTH, HEIGHT = 1200, 900
FPS = 60

# Темная индустриальная тема
BG_COLOR = (28, 33, 40)  # Темно-синий фон цеха
PANEL_COLOR = (45, 51, 63)  # Цвет панелей
RAIL_COLOR = (140, 145, 155)  # Металлические рельсы
CRANE_COLOR = (220, 225, 230)  # Светлый металл крана
TEXT_COLOR = (230, 235, 240)
BATH_BG = (55, 62, 75)  # Корпус ванны

# Индикаторы
GREEN = (46, 204, 113)
RED = (231, 76, 60)
BLUE_GLOW = (52, 152, 219)
PART_COLOR = (243, 156, 18)  # Раскаленный металл / Медь

# Жидкости в ваннах (яркие, неоновые)
C_ALK = (173, 212, 92)  # Обезжиривание (Лайм)
C_ACID = (0, 206, 209)  # Травление (Бирюза)
C_COPPER = (205, 127, 50)  # Медь (Бронзово-медный)
C_WATER = (65, 105, 225)  # Вода (Глубокий синий)

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
    "robot1_cmd": "IDLE"
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
# КЛАСС ПРОИЗВОДСТВЕННОЙ ЛИНИИ (СКВОЗНОЙ ПОТОК)
# ==========================================
class ProductionLine:
    def __init__(self, name, y_offset, bath1_color, bath2_color, bath1_name, bath2_name, is_first=False):
        self.name = name
        self.y_base = y_offset
        self.x_start, self.x_bath1, self.x_bath2, self.x_end = 80, 350, 700, 1000
        self.bath1_color = bath1_color
        self.bath2_color = bath2_color
        self.bath1_name = bath1_name
        self.bath2_name = bath2_name

        self.crane_x = self.x_start
        self.crane_y = self.y_base - 100
        self.hook_y = self.y_base - 80

        self.parts = []
        self.crane_state = "IDLE"
        self.crane_part = None
        self.crane_target = None
        self.bath1_part = None
        self.bath2_part = None
        
        self.pickup_x = 0
        self.pickup_y = 0
        self.drop_x = 0
        self.drop_y = 0
        self.task_type = ""

        # Логика непрерывного потока
        self.is_first = is_first
        self.next_line = None
        self.pending_parts_queue = 0  # Буфер деталей на входе

    def update(self, current_time):
        if opc_data["manual_mode"] or not opc_data["sim_running"]:
            return

        crane_speed_x, hook_speed_y, process_duration = 6, 4, 2500

        if self.crane_state == "IDLE":
            if self.bath2_part and self.bath2_part['state'] == 'WAITING_END':
                self.crane_state = "MOVE_TO_PICKUP"
                self.crane_target = self.bath2_part
                self.pickup_x, self.pickup_y = self.x_bath2, self.y_base + 10
                self.drop_x, self.drop_y = self.x_end, self.y_base
                self.task_type = "B2_TO_END"
            elif self.bath1_part and self.bath1_part['state'] == 'WAITING_B2' and not self.bath2_part:
                self.crane_state = "MOVE_TO_PICKUP"
                self.crane_target = self.bath1_part
                self.pickup_x, self.pickup_y = self.x_bath1, self.y_base + 10
                self.drop_x, self.drop_y = self.x_bath2, self.y_base + 10
                self.task_type = "B1_TO_B2"
            else:
                should_spawn = False
                if self.is_first and opc_data["pending_spawns"] > 0 and not self.bath1_part:
                    should_spawn = True
                    opc_data["pending_spawns"] -= 1
                elif not self.is_first and self.pending_parts_queue > 0 and not self.bath1_part:
                    should_spawn = True
                    self.pending_parts_queue -= 1
                    
                if should_spawn:
                    new_part = {'x': self.x_start, 'y': self.y_base, 'state': 'WAITING_START', 'timer': 0}
                    self.parts.append(new_part)
                    self.crane_state = "MOVE_TO_PICKUP"
                    self.crane_target = new_part
                    self.pickup_x, self.pickup_y = self.x_start, self.y_base
                    self.drop_x, self.drop_y = self.x_bath1, self.y_base + 10
                    self.task_type = "START_TO_B1"

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
                if self.task_type == "B1_TO_B2": self.bath1_part = None
                if self.task_type == "B2_TO_END": self.bath2_part = None
                self.crane_state = "LIFT_UP_PICKUP"
                
        elif self.crane_state == "LIFT_UP_PICKUP":
            if self.hook_y > self.y_base - 80:
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
                if self.task_type == "START_TO_B1":
                    self.bath1_part = self.crane_part
                    self.bath1_part['state'] = 'IN_BATH1'
                    self.bath1_part['timer'] = current_time
                elif self.task_type == "B1_TO_B2":
                    self.bath2_part = self.crane_part
                    self.bath2_part['state'] = 'IN_BATH2'
                    self.bath2_part['timer'] = current_time
                elif self.task_type == "B2_TO_END":
                    self.crane_part['state'] = 'LEAVING'
                
                self.crane_part = None
                self.crane_state = "LIFT_UP_RETURN"
                
        elif self.crane_state == "LIFT_UP_RETURN":
            if self.hook_y > self.y_base - 80:
                self.hook_y -= hook_speed_y
            else:
                self.crane_state = "IDLE"

        # Update attached part coords
        if self.crane_part:
            self.crane_part['x'] = self.crane_x
            self.crane_part['y'] = self.hook_y + 40
            
        # Update leaving parts
        for part in list(self.parts):
            if part['state'] == 'LEAVING':
                part['x'] += 6
                if part['x'] > WIDTH - 50:
                    self.parts.remove(part)
                    if self.next_line is not None:
                        self.next_line.pending_parts_queue += 1

        # Bath timers
        if self.bath1_part and self.bath1_part['state'] == 'IN_BATH1':
            if current_time - self.bath1_part['timer'] > process_duration:
                self.bath1_part['state'] = 'WAITING_B2'
                
        if self.bath2_part and self.bath2_part['state'] == 'IN_BATH2':
            if current_time - self.bath2_part['timer'] > process_duration:
                self.bath2_part['state'] = 'WAITING_END'

    def draw(self, surface, font, small_font):
        # Название линии
        surface.blit(font.render(self.name, True, TEXT_COLOR), (20, self.y_base - 140))

        # Конвейеры (с роликами)
        self.draw_conveyor(surface, 0, self.y_base + 40, 140)
        self.draw_conveyor(surface, self.x_end - 40, self.y_base + 40, WIDTH - self.x_end + 40)

        # Отрисовка деталей в буфере ожидания (показываем очередь)
        for i in range(min(self.pending_parts_queue, 3)):
            px = self.x_start - 10 - (i * 20)
            pygame.draw.rect(surface, PART_COLOR, (px, self.y_base + 10, 40, 30), border_radius=4)

        # Рисование ванн
        self.draw_bath(surface, small_font, self.x_bath1, self.bath1_color, self.bath1_name)
        self.draw_bath(surface, small_font, self.x_bath2, self.bath2_color, self.bath2_name)

        # Рельс манипулятора
        pygame.draw.rect(surface, RAIL_COLOR, (40, self.y_base - 100, WIDTH - 80, 8), border_radius=4)

        # Отрисовка деталей
        for part in self.parts:
            pygame.draw.rect(surface, PART_COLOR, (part['x'] - 20, part['y'], 40, 30), border_radius=4)
            pygame.draw.rect(surface, (255, 200, 100), (part['x'] - 20, part['y'], 40, 30), 2, border_radius=4)

        # Отрисовка манипулятора (Каретка + Трос + Захват)
        pygame.draw.rect(surface, CRANE_COLOR, (self.crane_x - 25, self.crane_y - 15, 50, 20), border_radius=5)
        pygame.draw.line(surface, CRANE_COLOR, (self.crane_x, self.crane_y), (self.crane_x, self.hook_y), 4)

        # Рисуем клешни
        pygame.draw.line(surface, CRANE_COLOR, (self.crane_x - 20, self.hook_y), (self.crane_x + 20, self.hook_y), 6)
        pygame.draw.line(surface, CRANE_COLOR, (self.crane_x - 20, self.hook_y), (self.crane_x - 20, self.hook_y + 20),
                         4)
        pygame.draw.line(surface, CRANE_COLOR, (self.crane_x + 20, self.hook_y), (self.crane_x + 20, self.hook_y + 20),
                         4)

    def draw_conveyor(self, surface, x, y, width):
        pygame.draw.rect(surface, PANEL_COLOR, (x, y, width, 24), border_radius=3)
        for i in range(x + 10, x + width - 10, 20):
            pygame.draw.circle(surface, RAIL_COLOR, (i, y + 12), 8)

    def draw_bath(self, surface, font, x, color, name):
        # Тень
        pygame.draw.rect(surface, (15, 20, 25), (x - 90, self.y_base - 5, 180, 95), border_radius=10)
        # Корпус
        pygame.draw.rect(surface, BATH_BG, (x - 90, self.y_base - 10, 180, 90), border_radius=10)
        # Жидкость
        pygame.draw.rect(surface, color, (x - 80, self.y_base, 160, 70), border_radius=5)
        # Блик жидкости (для объема)
        s = pygame.Surface((160, 20), pygame.SRCALPHA)
        s.fill((255, 255, 255, 40))
        surface.blit(s, (x - 80, self.y_base))
        # Текст
        text = font.render(name, True, TEXT_COLOR)
        surface.blit(text, (x - text.get_width() // 2, self.y_base + 90))


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

            # R-PRO Nodes
            try:
                conveyor_node = await scheduler_node.get_child([f"{idx}:Conveyor_Run"])
                robot1_node = await scheduler_node.get_child([f"{idx}:Robot1_Command"])
            except Exception:
                conveyor_node = None
                robot1_node = None

            while True:
                opc_data["manual_mode"] = await manual_node.get_value()
                opc_data["status"] = await status_node.get_value()
                opc_data["step"] = await step_node.get_value()
                opc_data["sim_running"] = await sim_node.get_value()
                if conveyor_node:
                    opc_data["conveyor_run"] = await conveyor_node.get_value()
                if robot1_node:
                    opc_data["robot1_cmd"] = await robot1_node.get_value()

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
    pygame.display.set_caption("Smart Factory: Galvanic Line Twin")

    font = pygame.font.SysFont("segoeui", 20, bold=True)
    small_font = pygame.font.SysFont("segoeui", 16)
    big_font = pygame.font.SysFont("segoeui", 28, bold=True)
    mega_font = pygame.font.SysFont("segoeui", 40, bold=True)

    # Инициализация сквозного потока
    l1 = ProductionLine("ВЕТКА 1 (Подготовка и травление)", 250, C_ALK, C_ACID, "Обезжиривание", "Травление", is_first=True)
    l2 = ProductionLine("ВЕТКА 2 (Меднение и сушка)", 550, C_COPPER, C_WATER, "Меднение", "Сушка")

    # Связываем линии в цепочку
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

        # Отрисовка визуальных связей между конвейерами (трубы/шахты)
        pygame.draw.rect(screen, PANEL_COLOR, (WIDTH - 120, 290, 40, 300))  # Между 1 и 2
        pygame.draw.rect(screen, RAIL_COLOR, (WIDTH - 110, 290, 20, 300))  # Внутренний лифт

        for line in lines:
            line.draw(screen, font, small_font)

        # СОВРЕМЕННЫЙ ДАШБОРД (HUD)
        pygame.draw.rect(screen, PANEL_COLOR, (0, 0, WIDTH, 60))
        pygame.draw.line(screen, BLUE_GLOW, (0, 60), (WIDTH, 60), 3)

        if not opc_data["connected"]:
            screen.blit(big_font.render("ОШИБКА: ПОИСК OPC UA СЕРВЕРА...", True, RED), (20, 12))
        else:
            if opc_data["manual_mode"]:
                if current_time % 1000 < 500:
                    pygame.draw.rect(screen, RED, (0, 0, WIDTH, HEIGHT), 8)
                screen.blit(big_font.render("ВНИМАНИЕ! РУЧНОЕ ВМЕШАТЕЛЬСТВО. КОМПЛЕКС ОСТАНОВЛЕН", True, RED), (20, 12))
            else:
                screen.blit(big_font.render(f"АВТОМАТИЧЕСКИЙ РЕЖИМ | {opc_data['status']}", True, GREEN), (20, 12))

            # Текст алгоритма выровнен вправо
            step_surf = small_font.render(f"АЛГОРИТМ APS: {opc_data['step'].upper()}", True, BLUE_GLOW)
            screen.blit(step_surf, (WIDTH - step_surf.get_width() - 20, 20))

            # R-PRO Mode Warning
            if opc_data.get("rpro_mode", False):
                rpro_surf = font.render(f"R-PRO СОВМЕСТИМОСТЬ (Конвейер: {opc_data.get('conveyor_run', False)}, Р1: {opc_data.get('robot1_cmd', 'IDLE')})", True, PART_COLOR)
                screen.blit(rpro_surf, (20, 80))

        # ЭКРАН ОЖИДАНИЯ С ЗАМТЕНЕНИЕМ
        if opc_data["connected"] and not opc_data["sim_running"]:
            overlay = pygame.Surface((WIDTH, HEIGHT))
            overlay.set_alpha(180)
            overlay.fill((10, 15, 20))
            screen.blit(overlay, (0, 0))

            if current_time % 1200 < 800:
                text = mega_font.render("НАЖМИТЕ [ПРОБЕЛ] ДЛЯ ЗАПУСКА ПРОИЗВОДСТВА", True, GREEN)
                screen.blit(text, (WIDTH // 2 - text.get_width() // 2, HEIGHT // 2))

            hint = small_font.render("Кнопка [G] - Симуляция аппаратной аварии", True, TEXT_COLOR)
            screen.blit(hint, (WIDTH // 2 - hint.get_width() // 2, HEIGHT // 2 + 60))

        pygame.display.flip()
        await asyncio.sleep(1 / FPS)


async def main():
    await asyncio.gather(opcua_client_task(), api_poll_task(), pygame_loop())


if __name__ == "__main__":
    asyncio.run(main())