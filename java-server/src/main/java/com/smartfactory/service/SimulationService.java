package com.smartfactory.service;

import com.smartfactory.model.ActionSequence;
import com.smartfactory.model.BatchInfo;
import com.smartfactory.state.ApiState;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

/**
 * Handles the background asyncio loops from the Python implementation.
 * globalSchedulerLoop() now executes the full APS algorithm per the flowchart.
 * sensorUpdateLoop() is UNCHANGED per user requirements.
 */
@Service
public class SimulationService {
    private final ApiState apiState;
    private final ScheduleService scheduleService;
    private final ApsAlgorithmService apsService;
    private final Random random = new Random();

    private boolean previousDoorState = true;
    private boolean simDoorState = true;

    public SimulationService(ApiState apiState, ScheduleService scheduleService,
                             ApsAlgorithmService apsService) {
        this.apiState = apiState;
        this.scheduleService = scheduleService;
        this.apsService = apsService;
    }

    // =========================================================================
    // sensorUpdateLoop — НЕ ИЗМЕНЁН (по требованию пользователя)
    // =========================================================================
    @Scheduled(fixedRate = 1000)
    public void sensorUpdateLoop() {
        if (!apiState.isClientConnected()) {
            return;
        }
        if (!apiState.isSimRunning()) {
            return;
        }

        // Simulate sensor data (In production: read from jSerialComm)
        int tempVal = 55 + random.nextInt(11); // 55 to 65
        int humVal = 15 + random.nextInt(16);  // 15 to 30
        boolean currentDoorState = simDoorState;

        apiState.setTemperature(tempVal);
        apiState.setHumidity(humVal);
        apiState.setDoorClosed(currentDoorState);

        if (previousDoorState && !currentDoorState) {
            apiState.setManualMode(true);
            apiState.setStatus("MANUAL_INTERVENTION");
        } else if (!previousDoorState && currentDoorState) {
            apiState.setManualMode(false);
            apiState.setStatus("AUTO_NORMAL");
        }
        previousDoorState = currentDoorState;

        // In a real application, you would also update the Eclipse Milo OPC UA Nodes here.
    }

    // =========================================================================
    // globalSchedulerLoop — ПЕРЕПИСАН: полный алгоритм APS по блок-схеме
    // =========================================================================

    /**
     * Основной цикл планировщика.
     * Реализует полный алгоритм APS по блок-схеме:
     *
     * НАЧАЛО
     *   → Проверка активности комплекса (таймер)
     *   → Определение наличия очереди
     *   → Определение приоритета обработки партии
     *   → Расчёт коэффициента загруженности
     *   → Расчёт времени обработки / Файл суперпозиции
     *   → Расчёт свободных интервалов
     *   → Сопоставление расчётов с реальной выборкой
     *   → «Возможно включить?» (ДА/НЕТ)
     *   → Расчёт последовательности действий
     *   → Утверждение последовательности
     *   → «Решение положительно?» (ДА/НЕТ)
     *   → Определить причину / Пересчитать? (цикл B/C)
     *   → Заполнение расписания / Сохранение
     * КОНЕЦ
     */
    @Scheduled(fixedRate = 8000)  // Каждые 8 секунд — один полный цикл APS
    public void globalSchedulerLoop() {
        try {
            if (!apiState.isClientConnected()) {
                apiState.setStep("Ожидание подключения PyGame клиента...");
                apiState.setStatus("WAITING_CLIENT");
                apiState.setApsPhase("IDLE");
                return;
            }

            // =============================================
            // НАЧАЛО → Проверка активности комплекса
            // =============================================
            if (!apsService.checkComplexActivity()) {
                // Таймер НЕ ведёт счёт → B ←→ F
                apiState.setStep("ОЖИДАНИЕ ЗАПУСКА");
                apiState.setApsPhase("IDLE");
                return;
            }

            // =============================================
            // Проверка ручного режима (вход из E)
            // =============================================
            if (apiState.isManualMode()) {
                apiState.setStep("ОЖИДАНИЕ_ОПЕРАТОРА (Авария / Ручной режим)");
                apiState.setApsPhase("MANUAL_MODE");
                return;
            }

            // =============================================
            // Начало цикла симуляции
            // =============================================
            apiState.setStep("1. Проверка очередей...");
            Thread.sleep(2000);

            boolean hasQueue = apsService.checkQueuePresence();
            if (!hasQueue) {
                // Очередь НЕ присутствует → F, G
                apiState.setStep("Очередь пуста. Генерируем тестовые партии...");
                apiState.setApsPhase("WAITING_ORDERS");
                apsService.generateTestBatches();
                Thread.sleep(1000);
                return;
            }

            // =============================================
            // Определение приоритета обработки партии
            // (включая линию и очерёдность)
            // =============================================
            apiState.setStep("2. Определение приоритета...");
            Thread.sleep(500);

            List<BatchInfo> prioritizedBatches = apsService.determineBatchPriority();
            if (prioritizedBatches.isEmpty()) return;

            BatchInfo topBatch = prioritizedBatches.get(0);

            // =============================================
            // Расчёт коэффициента загруженности
            // исполнительных устройств
            // =============================================
            apiState.setStep("3. Расчёт загруженности...");
            Thread.sleep(500);

            double utilization = apsService.calculateUtilization();

            // =============================================
            // Расчёт времени обработки изделия
            // → Файл суперпозиции
            // =============================================
            apiState.setStep("4. Расчёт времени обработки...");
            Thread.sleep(500);

            int processingTime = apsService.calculateProcessingTime(topBatch);
            if (processingTime < 0) return;

            // =============================================
            // Расчёт свободных интервалов для обработки
            // =============================================
            apiState.setStep("5. Расчёт свободных интервалов...");
            Thread.sleep(500);

            apsService.calculateFreeIntervals();

            // =============================================
            // Сопоставление расчётов с возможностью
            // реальной выборки → «Возможно включить?»
            // =============================================
            apiState.setStep("6. Сопоставление с реальной выборкой...");
            Thread.sleep(500);

            boolean canInclude = apsService.compareWithRealSelection(topBatch);

            if (!canInclude) {
                // =============================================
                // Возможно включить? → НЕТ → E (ручной режим)
                // =============================================
                apiState.setStep("Невозможно включить → переход E (ручной режим)");
                apsService.switchToManualMode();
                return;
            }

            // =============================================
            // Возможно включить? → ДА
            // Расчёт последовательности действий
            // =============================================
            apiState.setStep("7. Расчёт последовательности...");
            Thread.sleep(500);

            ActionSequence sequence = apsService.calculateActionSequence(topBatch);

            // =============================================
            // Утверждение последовательности действий (расписания)
            // Ромб «Решение положительно?»
            // =============================================
            apiState.setStep("8. Утверждение расписания...");
            Thread.sleep(500);

            boolean approved = apsService.approveSequence();

            if (!approved) {
                // =============================================
                // Решение НЕ положительно
                // → Определить причину → Пересчитать?
                // =============================================
                apiState.setStep("9. Анализ причины отказа...");
                Thread.sleep(500);

                boolean shouldRecalculate = apsService.determineRejectionAndRecalculate();

                if (shouldRecalculate) {
                    // Пересчитать? → ДА → B (возврат к началу цикла)
                    apiState.setStep("Пересчёт → возврат к началу цикла (B)...");
                    apiState.setRecalculationCount(apiState.getRecalculationCount() + 1);
                    // Цикл повторится на следующей итерации @Scheduled
                    return;
                } else {
                    // Пересчитать? → НЕТ → C → ручной режим
                    apiState.setStep("Пересчёт невозможен → переход C (ручной режим)");
                    apsService.switchToManualMode();
                    return;
                }
            }

            // =============================================
            // Решение положительно → ДА → A
            // Заполнение файла расписания доп. данными
            // Расчёт последовательности действий
            // исполнительными устройствами обработки
            // =============================================
            apiState.setStep("10. Заполнение расписания...");
            Thread.sleep(500);

            apsService.fillScheduleWithAdditionalData(topBatch);

            // =============================================
            // A + D → Сохранить, обновить, выгрузить
            // файл расписания → КОНЕЦ
            // =============================================
            apiState.setStep("11. Сохранение расписания → КОНЕЦ");
            Thread.sleep(300);

            apsService.saveAndExportSchedule();

            apiState.setStatus("В работе: " + topBatch.getName());
            apiState.setTotalCyclesCompleted(apiState.getTotalCyclesCompleted() + 1);
            apiState.setRecalculationCount(0);
            
            // =============================================
            // ВИЗУАЛЬНАЯ СИМУЛЯЦИЯ ИСПОЛНЕНИЯ (Для демо)
            // Имитируем фактическое исполнение шагов, чтобы 
            // состояние сервера не сбрасывалось мгновенно
            // =============================================
            apiState.setApsPhase("EXECUTING");
            if (sequence != null && sequence.getActions() != null) {
                for (ActionSequence.Action s : sequence.getActions()) {
                    if (!apiState.isSimRunning() || apiState.isManualMode()) break; // Прерывание
                    apiState.setStep("Выполнение: " + s.getOperation() + " [" + s.getEquipmentId() + "]");
                    Thread.sleep(3000); // 3 секунды на каждый шаг для наглядности
                }
            } else {
                Thread.sleep(5000);
            }
            
            apiState.setStep("Партия " + topBatch.getName() + " обработана.");
            apiState.setStatus("AUTO_NORMAL");
            Thread.sleep(2000);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
