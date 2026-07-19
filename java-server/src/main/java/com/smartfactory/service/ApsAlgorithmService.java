package com.smartfactory.service;

import com.smartfactory.model.*;
import com.smartfactory.state.ApiState;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Центральный сервис APS-алгоритма.
 * Каждый публичный метод соответствует конкретному блоку блок-схемы.
 * Методы вызываются последовательно из SimulationService.globalSchedulerLoop().
 */
@Service
public class ApsAlgorithmService {

    private final ApiState apiState;
    private final ScheduleService scheduleService;
    private final Random random = new Random();

    // Параметры комплекса (инициализируются один раз)
    private ComplexParameters complexParams;

    // Файл суперпозиции (пересоздаётся при каждом цикле)
    private SuperpositionData superpositionData;

    // Текущая последовательность действий
    private ActionSequence currentSequence;

    public ApsAlgorithmService(ApiState apiState, ScheduleService scheduleService) {
        this.apiState = apiState;
        this.scheduleService = scheduleService;
        this.complexParams = ComplexParameters.createDefault();
    }

    public ComplexParameters getComplexParams() {
        return complexParams;
    }

    public SuperpositionData getSuperpositionData() {
        return superpositionData;
    }

    public ActionSequence getCurrentSequence() {
        return currentSequence;
    }

    // =========================================================================
    // БЛОК: НАЧАЛО → Проверка активности комплекса (вызов проверки таймера)
    // =========================================================================

    /**
     * Проверяет активность комплекса. Читает параметры комплекса.
     * Ромб «Таймер ведёт счёт?» → если simRunning == false, возвращает false.
     * @return true если комплекс активен (таймер ведёт счёт)
     */
    public boolean checkComplexActivity() {
        apiState.setApsPhase("CHECK_ACTIVITY");
        addApsLog("Проверка активности комплекса...");

        // Обновляем время смены (симуляция)
        complexParams.setCurrentShiftElapsedSec(
                complexParams.getCurrentShiftElapsedSec() + 1);

        // Симулируем случайные изменения загрузки оборудования (в основном уменьшение, так как детали обрабатываются)
        for (ComplexParameters.Equipment eq : complexParams.getEquipment()) {
            double drift = (random.nextDouble() - 0.7) * 15; // -10.5 to +4.5
            double newLoad = Math.max(0, Math.min(eq.getCapacity(),
                    eq.getCurrentLoad() + drift));
            eq.setCurrentLoad(Math.round(newLoad * 10.0) / 10.0);
        }

        boolean active = apiState.isSimRunning();
        if (!active) {
            addApsLog("Таймер НЕ ведёт счёт → ожидание запуска");
        } else {
            addApsLog("Таймер ведёт счёт → комплекс активен");
        }
        return active;
    }

    // =========================================================================
    // БЛОК: Определение наличия очереди на обработку в расписании
    // =========================================================================

    /**
     * Проверяет наличие очереди. Читает файл расписания.
     * Ромб «Очередь присутствует?»
     * @return true если очередь не пуста
     */
    public boolean checkQueuePresence() {
        apiState.setApsPhase("CHECK_QUEUE");
        addApsLog("Чтение файла расписания...");

        ScheduleService.ScheduleData schedule = scheduleService.readSchedule();
        boolean hasQueue = !schedule.getQueue().isEmpty();

        if (hasQueue) {
            addApsLog("Очередь присутствует: " + schedule.getQueue().size() + " партий");
        } else {
            addApsLog("Очередь пуста → переход F/G");
        }
        return hasQueue;
    }

    // =========================================================================
    // БЛОК: Определение приоритета обработки партии (включая линию и очерёдность)
    // =========================================================================

    /**
     * Рассчитывает приоритет для каждой партии в очереди.
     * Учитывает: срочность (время ожидания), тип материала, вес.
     * Назначает линию и очерёдность.
     * @return отсортированный список партий по приоритету
     */
    public List<BatchInfo> determineBatchPriority() {
        apiState.setApsPhase("CALC_PRIORITY");
        addApsLog("Определение приоритета обработки партии...");

        ScheduleService.ScheduleData schedule = scheduleService.readSchedule();
        List<BatchInfo> batches = schedule.getQueue();

        for (BatchInfo batch : batches) {
            // Балл срочности: чем дольше ждёт, тем выше приоритет
            long waitTimeMs = System.currentTimeMillis() - batch.getCreatedAt();
            int urgency = (int) Math.min(50, waitTimeMs / 10000); // max 50 баллов
            batch.setUrgencyScore(urgency);

            // Балл по материалу (разный материал = разная сложность)
            int materialScore;
            switch (batch.getMaterial()) {
                case "Медь":     materialScore = 30; break; // Медь — приоритет выше
                case "Алюминий": materialScore = 20; break;
                case "Сталь":    materialScore = 10; break;
                default:         materialScore = 5;  break;
            }
            batch.setMaterialScore(materialScore);

            // Балл по весу (тяжелее → выше приоритет — эффективнее загрузка)
            batch.setWeightScore((int) Math.min(20, batch.getWeight() / 5));

            // Итоговый приоритет
            batch.setPriority(urgency + materialScore + batch.getWeightScore());

            // Назначаем линию (в нашей симуляции — единственная линия)
            batch.setAssignedLineId("LINE_GALVANIC_1");
            batch.setStatus("PRIORITIZED");
        }

        // Сортируем по приоритету (убывание)
        batches.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

        // Назначаем очерёдность
        for (int i = 0; i < batches.size(); i++) {
            batches.get(i).setOrderInLine(i + 1);
        }

        // Сохраняем обратно
        schedule.setQueue(batches);
        scheduleService.writeSchedule(schedule);

        BatchInfo top = batches.get(0);
        addApsLog("Высший приоритет: " + top.getName() +
                " (P=" + top.getPriority() + ", материал: " + top.getMaterial() + ")");

        apiState.setCurrentBatch(top.getName());
        return batches;
    }

    // =========================================================================
    // БЛОК: Расчёт коэффициента загруженности исполнительных устройств
    // =========================================================================

    /**
     * Рассчитывает загруженность каждого устройства.
     * Использует параметры комплекса и файл расписания.
     * @return средний коэффициент загруженности [0..1]
     */
    public double calculateUtilization() {
        apiState.setApsPhase("CALC_UTILIZATION");
        addApsLog("Расчёт коэффициента загруженности устройств...");

        double avgUtil = complexParams.getAverageUtilization();
        apiState.setUtilizationCoeff(Math.round(avgUtil * 1000.0) / 1000.0);

        StringBuilder sb = new StringBuilder("Загруженность: ");
        for (ComplexParameters.Equipment eq : complexParams.getEquipment()) {
            double util = complexParams.getEquipmentUtilization(eq.getId());
            sb.append(eq.getName()).append("=")
              .append(String.format("%.0f%%", util * 100)).append(", ");
        }
        addApsLog(sb.toString());
        addApsLog("Средний коэффициент загруженности: " +
                String.format("%.1f%%", avgUtil * 100));

        return avgUtil;
    }

    // =========================================================================
    // БЛОК: Расчёт времени обработки изделия
    // =========================================================================

    /**
     * Рассчитывает время обработки для партии с наивысшим приоритетом.
     * Учитывает: параметры комплекса, материал, вес.
     * Создаёт файл суперпозиции.
     * @param batch партия для расчёта
     * @return расчётное время обработки в секундах
     */
    public int calculateProcessingTime(BatchInfo batch) {
        apiState.setApsPhase("CALC_PROCESSING_TIME");
        addApsLog("Расчёт времени обработки изделия '" + batch.getName() + "'...");

        // Определяем шаги обработки на основе линии
        ComplexParameters.ProductionLine line = complexParams.getLines().stream()
                .filter(l -> l.getId().equals(batch.getAssignedLineId()))
                .findFirst().orElse(null);

        if (line == null) {
            addApsLog("ОШИБКА: Линия " + batch.getAssignedLineId() + " не найдена!");
            return -1;
        }

        List<BatchInfo.ProcessingStep> steps = new ArrayList<>();
        int totalTime = 0;

        for (String eqId : line.getEquipmentIds()) {
            ComplexParameters.Equipment eq = complexParams.getEquipment().stream()
                    .filter(e -> e.getId().equals(eqId))
                    .findFirst().orElse(null);
            if (eq == null) continue;

            // Коэффициент материала (разные материалы обрабатываются с разной скоростью)
            double materialCoeff;
            switch (batch.getMaterial()) {
                case "Медь":     materialCoeff = 1.3; break;
                case "Алюминий": materialCoeff = 0.8; break;
                case "Сталь":    materialCoeff = 1.0; break;
                default:         materialCoeff = 1.0; break;
            }

            // Коэффициент веса (тяжелее → дольше)
            double weightCoeff = 0.8 + (batch.getWeight() / 100.0) * 0.4;

            int stepTime = (int)(eq.getProcessingTimeSec() * materialCoeff * weightCoeff);

            steps.add(new BatchInfo.ProcessingStep(
                    eqId, eq.getName(), stepTime, false));
            totalTime += stepTime;
        }

        // Добавляем время крана (перемещения между ваннами)
        int craneMoves = line.getEquipmentIds().size() - 1;
        ComplexParameters.Equipment crane = complexParams.getEquipment().stream()
                .filter(e -> e.getId().equals("CRANE")).findFirst().orElse(null);
        if (crane != null) {
            totalTime += craneMoves * crane.getProcessingTimeSec();
        }

        batch.setSteps(steps);
        batch.setTotalProcessingTimeSec(totalTime);
        apiState.setProcessingTimeEstimate(totalTime);

        addApsLog("Расчётное время: " + formatTime(totalTime) +
                " (" + steps.size() + " этапов + " + craneMoves + " перемещений)");

        return totalTime;
    }

    // =========================================================================
    // БЛОК: Расчёт свободных интервалов для обработки → Файл суперпозиции
    // =========================================================================

    /**
     * Рассчитывает свободные временные интервалы на оборудовании.
     * Создаёт/обновляет файл суперпозиции.
     * @return SuperpositionData с рассчитанными интервалами
     */
    public SuperpositionData calculateFreeIntervals() {
        apiState.setApsPhase("CALC_FREE_INTERVALS");
        addApsLog("Расчёт свободных интервалов для обработки...");

        superpositionData = new SuperpositionData();
        superpositionData.setCalculatedAt(System.currentTimeMillis());
        superpositionData.setShiftDurationSec(complexParams.getShiftDurationSec());

        int elapsed = complexParams.getCurrentShiftElapsedSec();
        int remaining = complexParams.getShiftDurationSec() - elapsed;

        // Для каждого устройства строим временную карту
        for (ComplexParameters.Equipment eq : complexParams.getEquipment()) {
            if (eq.getId().equals("CRANE")) continue; // кран не планируется отдельно

            double util = complexParams.getEquipmentUtilization(eq.getId());

            // Генерируем занятые интервалы на основе текущей загрузки
            int occupiedTime = (int)(remaining * util);
            int freeTime = remaining - occupiedTime;

            // Занятый слот
            if (occupiedTime > 0) {
                superpositionData.getTimeSlots().add(new SuperpositionData.TimeSlot(
                        eq.getId(), elapsed, elapsed + occupiedTime, true, "CURRENT_WORK"));
            }
            // Свободный слот
            if (freeTime > 0) {
                SuperpositionData.TimeSlot freeSlot = new SuperpositionData.TimeSlot(
                        eq.getId(), elapsed + occupiedTime,
                        elapsed + occupiedTime + freeTime, false, null);
                superpositionData.getTimeSlots().add(freeSlot);
                superpositionData.getFreeIntervals().add(freeSlot);
            }
        }

        superpositionData.setOverallUtilization(complexParams.getAverageUtilization());

        addApsLog("Файл суперпозиции обновлён. Свободных интервалов: " +
                superpositionData.getFreeIntervals().size() +
                ", оставшееся время смены: " + formatTime(remaining));

        return superpositionData;
    }

    // =========================================================================
    // БЛОК: Сопоставление расчётов с возможностью реальной выборки
    // =========================================================================

    /**
     * Сопоставляет требования партии со свободными интервалами.
     * Ромб «Возможно включить?» → ДА / НЕТ
     * @param batch партия для проверки
     * @return true если партию можно включить в расписание
     */
    public boolean compareWithRealSelection(BatchInfo batch) {
        apiState.setApsPhase("COMPARE_SELECTION");
        addApsLog("Сопоставление расчётов с возможностью реальной выборки...");

        if (superpositionData == null) {
            addApsLog("ОШИБКА: Файл суперпозиции не рассчитан!");
            return false;
        }

        boolean canFit = true;
        String reason = null;

        // 1. Проверяем, хватает ли свободного времени на каждом устройстве
        for (BatchInfo.ProcessingStep step : batch.getSteps()) {
            long freeTimeOnEquipment = superpositionData.getFreeIntervals().stream()
                    .filter(slot -> slot.getEquipmentId().equals(step.getEquipmentId()))
                    .mapToInt(slot -> slot.getEndTimeSec() - slot.getStartTimeSec())
                    .sum();

            if (freeTimeOnEquipment < step.getDurationSec()) {
                canFit = false;
                reason = "Недостаточно времени на устройстве " + step.getOperation() +
                        " (нужно " + formatTime(step.getDurationSec()) +
                        ", доступно " + formatTime((int)freeTimeOnEquipment) + ")";
                break;
            }
        }

        // 2. Проверяем критический порог загруженности
        if (canFit && complexParams.getAverageUtilization() >
                complexParams.getCriticalUtilizationThreshold()) {
            canFit = false;
            reason = "Загруженность комплекса выше критического порога (" +
                    String.format("%.0f%%", complexParams.getAverageUtilization() * 100) +
                    " > " + String.format("%.0f%%",
                    complexParams.getCriticalUtilizationThreshold() * 100) + ")";
        }

        // 3. Проверяем вес партии vs доступная ёмкость
        if (canFit) {
            for (BatchInfo.ProcessingStep step : batch.getSteps()) {
                ComplexParameters.Equipment eq = complexParams.getEquipment().stream()
                        .filter(e -> e.getId().equals(step.getEquipmentId()))
                        .findFirst().orElse(null);
                if (eq != null) {
                    double available = eq.getCapacity() - eq.getCurrentLoad();
                    if (batch.getWeight() > available) {
                        canFit = false;
                        reason = "Вес партии (" + String.format("%.1f", batch.getWeight()) +
                                "кг) превышает доступную ёмкость " + eq.getName() +
                                " (" + String.format("%.1f", available) + "кг)";
                        break;
                    }
                }
            }
        }

        // Записываем результат в суперпозицию
        SuperpositionData.FitResult result = new SuperpositionData.FitResult();
        result.setBatchId(batch.getId());
        result.setCanFit(canFit);
        result.setReason(reason);
        superpositionData.getFitResults().add(result);

        apiState.setCanIncludeResult(canFit);

        if (canFit) {
            addApsLog("✓ Возможно включить → ДА");
        } else {
            addApsLog("✗ Возможно включить → НЕТ: " + reason);
            apiState.setRejectionCause(reason);
        }

        return canFit;
    }

    // =========================================================================
    // БЛОК: Расчёт последовательности действий
    // =========================================================================

    /**
     * Рассчитывает конкретную последовательность действий для партии.
     * @param batch партия
     * @return ActionSequence с расчётанными действиями
     */
    public ActionSequence calculateActionSequence(BatchInfo batch) {
        apiState.setApsPhase("CALC_ACTION_SEQ");
        addApsLog("Расчёт последовательности действий...");

        currentSequence = new ActionSequence();
        currentSequence.setBatchId(batch.getId());
        currentSequence.setBatchName(batch.getName());

        int currentTimeSec = complexParams.getCurrentShiftElapsedSec();
        int stepNum = 1;

        ComplexParameters.ProductionLine line = complexParams.getLines().stream()
                .filter(l -> l.getId().equals(batch.getAssignedLineId()))
                .findFirst().orElse(null);

        if (line == null) return currentSequence;

        for (int i = 0; i < line.getEquipmentIds().size(); i++) {
            String eqId = line.getEquipmentIds().get(i);

            // Перемещение краном (кроме первого шага)
            if (i > 0) {
                ComplexParameters.Equipment crane = complexParams.getEquipment().stream()
                        .filter(e -> e.getId().equals("CRANE")).findFirst().orElse(null);
                if (crane != null) {
                    currentSequence.getActions().add(new ActionSequence.Action(
                            stepNum++, "CRANE", "Мостовой кран",
                            "Перемещение к " + eqId,
                            currentTimeSec, crane.getProcessingTimeSec(), "PLANNED"));
                    currentTimeSec += crane.getProcessingTimeSec();
                }
            }

            // Обработка на устройстве
            BatchInfo.ProcessingStep step = batch.getSteps().stream()
                    .filter(s -> s.getEquipmentId().equals(eqId))
                    .findFirst().orElse(null);

            if (step != null) {
                ComplexParameters.Equipment eq = complexParams.getEquipment().stream()
                        .filter(e -> e.getId().equals(eqId)).findFirst().orElse(null);
                String eqName = eq != null ? eq.getName() : eqId;

                currentSequence.getActions().add(new ActionSequence.Action(
                        stepNum++, eqId, eqName,
                        "Обработка: " + batch.getMaterial(),
                        currentTimeSec, step.getDurationSec(), "PLANNED"));
                currentTimeSec += step.getDurationSec();
            }
        }

        currentSequence.recalculateTotals();
        apiState.setActionSequenceSteps(currentSequence.getActions().size());

        addApsLog("Последовательность: " + currentSequence.getActions().size() +
                " действий, общее время: " + formatTime(currentSequence.getTotalDurationSec()));

        return currentSequence;
    }

    // =========================================================================
    // БЛОК: Утверждение последовательности действий (расписания)
    // =========================================================================

    /**
     * Симулирует утверждение последовательности действий.
     * Ромб «Решение положительно?» → ДА / НЕТ
     * @return true если последовательность утверждена
     */
    public boolean approveSequence() {
        apiState.setApsPhase("APPROVE_SEQUENCE");
        addApsLog("Утверждение последовательности действий (расписания)...");

        if (currentSequence == null) return false;

        // Проверки для утверждения:
        boolean approved = true;
        String cause = null;

        // 1. Общее время не превышает остаток смены
        int remainingShift = complexParams.getShiftDurationSec() -
                complexParams.getCurrentShiftElapsedSec();
        if (currentSequence.getTotalDurationSec() > remainingShift) {
            approved = false;
            cause = "Время обработки (" + formatTime(currentSequence.getTotalDurationSec()) +
                    ") превышает остаток смены (" + formatTime(remainingShift) + ")";
        }

        // 2. Все устройства доступны
        if (approved) {
            for (ActionSequence.Action action : currentSequence.getActions()) {
                ComplexParameters.Equipment eq = complexParams.getEquipment().stream()
                        .filter(e -> e.getId().equals(action.getEquipmentId()))
                        .findFirst().orElse(null);
                if (eq != null && !eq.isAvailable()) {
                    approved = false;
                    cause = "Устройство '" + eq.getName() + "' недоступно";
                    break;
                }
            }
        }

        // 3. Случайный фактор (5% шанс отказа для демонстрации цикла пересчёта)
        if (approved && random.nextDouble() < 0.05) {
            approved = false;
            cause = "Конфликт приоритетов — обнаружена более срочная партия";
        }

        currentSequence.setApproved(approved);
        currentSequence.setApprovalStatus(approved ? "APPROVED" : "REJECTED");
        if (!approved) {
            currentSequence.setRejectionCause(cause);
        }

        if (approved) {
            addApsLog("✓ Решение положительно → ДА. Расписание утверждено.");
        } else {
            addApsLog("✗ Решение положительно → НЕТ: " + cause);
            apiState.setRejectionCause(cause);
        }

        return approved;
    }

    // =========================================================================
    // БЛОК: Определить причину → Пересчитать? → ДА(B) / НЕТ(C)
    // =========================================================================

    /**
     * Определяет причину отказа и решает, нужен ли пересчёт.
     * @return true если нужен пересчёт (переход B), false если нет (переход C → ручной режим)
     */
    public boolean determineRejectionAndRecalculate() {
        apiState.setApsPhase("DETERMINE_CAUSE");

        String cause = currentSequence != null ? currentSequence.getRejectionCause() : "Неизвестно";
        addApsLog("Определение причины отказа: " + cause);

        int recalcCount = currentSequence != null ? currentSequence.getRecalculationCount() : 0;

        // Решение о пересчёте зависит от причины и количества попыток
        boolean shouldRecalculate;

        if (recalcCount >= 3) {
            // Слишком много пересчётов → переход к ручному режиму
            shouldRecalculate = false;
            addApsLog("Пересчитать? → НЕТ (превышен лимит попыток: " + recalcCount + ")");
        } else if (cause != null && cause.contains("приоритет")) {
            shouldRecalculate = true;
            addApsLog("Пересчитать? → ДА (конфликт приоритетов, попытка " + (recalcCount + 1) + ")");
        } else if (cause != null && cause.contains("смены")) {
            shouldRecalculate = false;
            addApsLog("Пересчитать? → НЕТ (нет свободного времени в смене)");
        } else {
            shouldRecalculate = random.nextDouble() < 0.6; // 60% шанс пересчёта
            addApsLog("Пересчитать? → " + (shouldRecalculate ? "ДА" : "НЕТ"));
        }

        if (currentSequence != null && shouldRecalculate) {
            currentSequence.setNeedsRecalculation(true);
            currentSequence.setRecalculationCount(recalcCount + 1);
        }

        return shouldRecalculate;
    }

    // =========================================================================
    // БЛОК E: Переход на ручной режим редактирования расписания
    // =========================================================================

    /**
     * Переводит систему в ручной режим (E → C/G).
     * Ожидает решения оператора о вклинивании в очередь.
     */
    public void switchToManualMode() {
        apiState.setApsPhase("MANUAL_MODE");
        addApsLog("Переход на ручной режим редактирования расписания (E)");
        apiState.setManualMode(true);
        apiState.setStatus("MANUAL_INTERVENTION");
        apiState.setStep("ОЖИДАНИЕ_ОПЕРАТОРА (Ручной режим)");
    }

    // =========================================================================
    // БЛОК: Ручное решение о вклинивании или в очередь
    // =========================================================================

    /**
     * Обрабатывает ручное решение оператора.
     * @param batchName имя партии для вклинивания
     * @param insertAtFront true = вклинить в начало, false = добавить в конец
     */
    public void manualQueueDecision(String batchName, boolean insertAtFront) {
        apiState.setApsPhase("MANUAL_DECISION");
        addApsLog("Ручное решение оператора: " + (insertAtFront ? "ВКЛИНИТЬ" : "В ОЧЕРЕДЬ") +
                " '" + batchName + "'");

        ScheduleService.ScheduleData schedule = scheduleService.readSchedule();
        BatchInfo batch = BatchInfo.fromLegacyName(batchName);
        batch.setPriority(insertAtFront ? 999 : 0);

        if (insertAtFront) {
            schedule.getQueue().add(0, batch);
        } else {
            schedule.getQueue().add(batch);
        }

        scheduleService.writeSchedule(schedule);
        addApsLog("Партия добавлена в очередь (позиция: " +
                (insertAtFront ? "1 (приоритетная)" : schedule.getQueue().size()) + ")");
    }

    public void generateTestBatches() {
        addApsLog("Генерируем случайную партию для непрерывной симуляции...");
        ScheduleService.ScheduleData schedule = scheduleService.readSchedule();
        String[] materials = {"Сталь", "Медь", "Алюминий"};
        String mat = materials[random.nextInt(materials.length)];
        String name = "Авто_" + mat + "_" + random.nextInt(1000);
        BatchInfo batch = BatchInfo.fromLegacyName(name);
        batch.setWeight(20.0 + random.nextDouble() * 50.0);
        schedule.getQueue().add(batch);
        scheduleService.writeSchedule(schedule);
    }

    // =========================================================================
    // БЛОК: Заполнение файла расписания дополнительными данными
    // =========================================================================

    /**
     * Заполняет расписание дополнительными данными после утверждения.
     * Рассчитывает последовательность действий исполнительными устройствами.
     * @param batch утверждённая партия
     */
    public void fillScheduleWithAdditionalData(BatchInfo batch) {
        apiState.setApsPhase("FILL_SCHEDULE");
        addApsLog("Заполнение файла расписания дополнительными данными...");

        ScheduleService.ScheduleData schedule = scheduleService.readSchedule();

        // Устанавливаем временные рамки
        batch.setScheduledStartTime(System.currentTimeMillis());
        batch.setScheduledEndTime(System.currentTimeMillis() +
                (long) batch.getTotalProcessingTimeSec() * 1000);
        batch.setStatus("SCHEDULED");

        // Обновляем партию в очереди
        schedule.getQueue().removeIf(b -> b.getId().equals(batch.getId()));
        schedule.getCurrent_tasks().add(batch);

        scheduleService.writeSchedule(schedule);
        addApsLog("Расчёт последовательности действий исполнительными устройствами обработки...");

        // Обновляем загрузку оборудования
        for (BatchInfo.ProcessingStep step : batch.getSteps()) {
            complexParams.getEquipment().stream()
                    .filter(e -> e.getId().equals(step.getEquipmentId()))
                    .findFirst()
                    .ifPresent(eq -> eq.setCurrentLoad(
                            Math.min(eq.getCapacity(), eq.getCurrentLoad() + batch.getWeight() * 0.3)));
        }

        addApsLog("Расписание дополнено. Партия '" + batch.getName() + "' → ТЕКУЩИЕ_ЗАДАЧИ");
    }

    // =========================================================================
    // БЛОК: Сохранить, обновить, выгрузить файл расписания → КОНЕЦ
    // =========================================================================

    /**
     * Финальный блок: сохраняет и выгружает файл расписания.
     * Соответствует блокам A + D → КОНЕЦ.
     */
    public void saveAndExportSchedule() {
        apiState.setApsPhase("SAVE_EXPORT");
        addApsLog("Сохранение и выгрузка файла расписания → КОНЕЦ цикла");

        ScheduleService.ScheduleData schedule = scheduleService.readSchedule();
        scheduleService.writeSchedule(schedule);

        apiState.setStep("Цикл APS завершён. Ожидание следующей итерации...");
    }

    // =========================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // =========================================================================

    private void addApsLog(String message) {
        apiState.addApsHistoryEntry("[" + apiState.getApsPhase() + "] " + message);
    }

    private String formatTime(int totalSec) {
        int hours = totalSec / 3600;
        int minutes = (totalSec % 3600) / 60;
        int seconds = totalSec % 60;
        if (hours > 0) return String.format("%dч %02dм %02dс", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dм %02dс", minutes, seconds);
        return seconds + "с";
    }
}
