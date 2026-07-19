/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect, useRef } from 'react';

// =============================================
// APS Phase definitions matching the flowchart
// =============================================
const APS_PHASES: Record<string, { label: string; color: string; step: number }> = {
  IDLE:                { label: 'Ожидание',                          color: 'text-slate-500',   step: 0 },
  CHECK_ACTIVITY:      { label: 'Проверка активности комплекса',     color: 'text-sky-400',     step: 1 },
  CHECK_QUEUE:         { label: 'Определение наличия очереди',       color: 'text-sky-400',     step: 2 },
  WAITING_ORDERS:      { label: 'Очередь пуста — ожидание',          color: 'text-amber-400',   step: 2 },
  CALC_PRIORITY:       { label: 'Определение приоритета партии',     color: 'text-violet-400',  step: 3 },
  CALC_UTILIZATION:    { label: 'Расчёт загруженности устройств',    color: 'text-orange-400',  step: 4 },
  CALC_PROCESSING_TIME:{ label: 'Расчёт времени обработки',          color: 'text-orange-400',  step: 5 },
  CALC_FREE_INTERVALS: { label: 'Расчёт свободных интервалов',       color: 'text-teal-400',    step: 6 },
  COMPARE_SELECTION:   { label: 'Сопоставление с реальной выборкой', color: 'text-yellow-400',  step: 7 },
  CALC_ACTION_SEQ:     { label: 'Расчёт последовательности действий',color: 'text-emerald-400', step: 8 },
  APPROVE_SEQUENCE:    { label: 'Утверждение расписания',            color: 'text-emerald-400', step: 9 },
  DETERMINE_CAUSE:     { label: 'Определение причины отказа',        color: 'text-red-400',     step: 10 },
  MANUAL_MODE:         { label: 'Ручной режим оператора',            color: 'text-red-400',     step: 11 },
  MANUAL_DECISION:     { label: 'Ручное решение оператора',          color: 'text-amber-400',   step: 12 },
  ANALYZE_REJECTION: { label: 'Причина отказа', step: 9, color: 'text-orange-400' },
  FILL_SCHEDULE: { label: 'Заполнение расписания', step: 10, color: 'text-emerald-400' },
  SAVE_EXPORT: { label: 'Сохранение / Конец', step: 11, color: 'text-emerald-400' },
  EXECUTING: { label: 'Исполнение', step: 12, color: 'text-emerald-500 animate-pulse font-bold' },
};

// Flowchart blocks for visual display
const FLOWCHART_BLOCKS = [
  { id: 'CHECK_ACTIVITY',       label: 'Проверка активности',       type: 'process' },
  { id: 'CHECK_QUEUE',          label: 'Наличие очереди?',          type: 'decision' },
  { id: 'CALC_PRIORITY',        label: 'Приоритет партии',          type: 'process' },
  { id: 'CALC_UTILIZATION',     label: 'Загруженность устройств',   type: 'process' },
  { id: 'CALC_PROCESSING_TIME', label: 'Время обработки',           type: 'process' },
  { id: 'CALC_FREE_INTERVALS',  label: 'Свободные интервалы',       type: 'process' },
  { id: 'COMPARE_SELECTION',    label: 'Возможно включить?',        type: 'decision' },
  { id: 'CALC_ACTION_SEQ',      label: 'Последовательность',        type: 'process' },
  { id: 'APPROVE_SEQUENCE',     label: 'Решение?',                  type: 'decision' },
  { id: 'ANALYZE_REJECTION', label: 'Причина отказа', type: 'step' },
  { id: 'FILL_SCHEDULE', label: 'Заполнение расписания', type: 'step' },
  { id: 'SAVE_EXPORT', label: 'Сохранение → Конец', type: 'terminal' },
  { id: 'EXECUTING', label: 'ИСПОЛНЕНИЕ...', type: 'terminal' },
];

interface BatchInfo {
  id: string;
  name: string;
  material: string;
  weight: number;
  priority: number;
  urgencyScore: number;
  materialScore: number;
  weightScore: number;
  status: string;
  totalProcessingTimeSec: number;
  orderInLine: number;
  assignedLineId: string;
}

interface EquipmentInfo {
  id: string;
  name: string;
  capacity: number;
  currentLoad: number;
  processingTimeSec: number;
  available: boolean;
}

function formatTimeSec(sec: number): string {
  if (!sec || sec <= 0) return '—';
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (h > 0) return `${h}ч ${m.toString().padStart(2, '0')}м`;
  if (m > 0) return `${m}м ${s.toString().padStart(2, '0')}с`;
  return `${s}с`;
}

export default function App() {
  const [simRunning, setSimRunning] = useState(true);
  const [manualMode, setManualMode] = useState(false);
  const [temperature, setTemperature] = useState(62.4);
  const [humidity, setHumidity] = useState(22.8);
  const [doorClosed, setDoorClosed] = useState(true);

  // APS state
  const [apsPhase, setApsPhase] = useState('IDLE');
  const [utilizationCoeff, setUtilizationCoeff] = useState(0);
  const [processingTimeEstimate, setProcessingTimeEstimate] = useState(0);
  const [canIncludeResult, setCanIncludeResult] = useState(false);
  const [currentBatch, setCurrentBatch] = useState('');
  const [rejectionCause, setRejectionCause] = useState('');
  const [actionSequenceSteps, setActionSequenceSteps] = useState(0);
  const [recalculationCount, setRecalculationCount] = useState(0);
  const [totalCyclesCompleted, setTotalCyclesCompleted] = useState(0);
  const [apsHistory, setApsHistory] = useState<string[]>([]);
  const [queue, setQueue] = useState<BatchInfo[]>([]);
  const [currentTasks, setCurrentTasks] = useState<BatchInfo[]>([]);

  // Equipment
  const [equipment, setEquipment] = useState<EquipmentInfo[]>([]);

  const [logs, setLogs] = useState([
    { id: 1, time: '14:28:30', src: 'OPC_SERVER', msg: "Переменная 'SimulationRunning' установлена в TRUE", color: 'text-slate-300' },
    { id: 2, time: '14:28:31', src: 'REST_API', msg: "GET /api/queue запрос от 192.168.1.15 (Android)", color: 'text-slate-300' },
    { id: 3, time: '14:28:34', src: 'SCHEDULER', msg: "Деталь 'Партия_1_Сталь' перемещена из Очереди в ТЕКУЩИЕ_ЗАДАЧИ", color: 'text-emerald-400 font-bold' },
    { id: 4, time: '14:28:38', src: 'HW_BRIDGE', msg: "Получено обновление температуры (62C)", color: 'text-slate-300' },
  ]);

  const [cranePos, setCranePos] = useState(38);
  const [clock, setClock] = useState("00:00:00");

  const apsLogRef = useRef<HTMLDivElement>(null);

  const addLog = (src: string, msg: string, color: string = 'text-slate-300') => {
    const now = new Date();
    const timeStr = now.toLocaleTimeString('ru-RU');
    setLogs(prev => {
      const newLogs = [...prev, { id: Date.now() + Math.random(), time: timeStr, src, msg, color }];
      return newLogs.slice(-6);
    });
  };

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'Space') {
        e.preventDefault();
        setSimRunning(prev => {
          const newState = !prev;
          addLog('OPC_SERVER', `Переменная 'SimulationRunning' установлена в ${newState ? 'TRUE' : 'FALSE'}`);
          return newState;
        });
      }
      if (e.code === 'KeyG' || e.key === 'g' || e.key === 'п') {
        setManualMode(prev => {
          const newState = !prev;
          addLog('HW_BRIDGE', `Аварийное вмешательство: ${newState ? 'ВКЛЮЧЕНО' : 'ОТКЛЮЧЕНО'}`, newState ? 'text-red-400 font-bold' : 'text-emerald-400 font-bold');
          return newState;
        });
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  useEffect(() => {
    const t = setInterval(() => {
      setClock(new Date().toLocaleTimeString('ru-RU') + ':' + Math.floor(Math.random() * 99).toString().padStart(2, '0'));
    }, 100);
    return () => clearInterval(t);
  }, []);

  // Only move the crane locally for visual effect, because the server doesn't provide crane coordinates
  useEffect(() => {
    if (!simRunning || manualMode) return;
    const interval = setInterval(() => {
      setCranePos(prev => {
        let next = prev + 5;
        if (next > 85) next = 15;
        return next;
      });
    }, 1000);
    return () => clearInterval(interval);
  }, [simRunning, manualMode]);

  // Polling backend API instead of simulating
  useEffect(() => {
    let cancelled = false;

    const fetchApsState = async () => {
      try {
        const apsRes = await fetch('http://localhost:8080/api/aps-state');
        if (!apsRes.ok) throw new Error('API error');
        const apsData = await apsRes.json();

        if (cancelled) return;

        const apiState = apsData.apiState || {};
        
        setSimRunning(apiState.simRunning ?? false);
        setManualMode(apiState.manualMode ?? false);
        setTemperature(apiState.temperature ?? 60);
        setHumidity(apiState.humidity ?? 20);
        setDoorClosed(apiState.doorClosed ?? true);

        setApsPhase(apiState.apsPhase || 'IDLE');
        setUtilizationCoeff(apiState.utilizationCoeff || 0);
        setProcessingTimeEstimate(apiState.processingTimeEstimate || 0);
        setCanIncludeResult(apiState.canIncludeResult || false);
        setCurrentBatch(apiState.currentBatch || '');
        setRejectionCause(apiState.rejectionCause || '');
        setActionSequenceSteps(apiState.actionSequenceSteps || 0);
        setRecalculationCount(apiState.recalculationCount || 0);
        setTotalCyclesCompleted(apiState.totalCyclesCompleted || 0);

        if (apsData.equipment) {
            setEquipment(apsData.equipment);
        }

        if (apiState.apsHistory && apiState.apsHistory.length > 0) {
            setApsHistory(apiState.apsHistory.slice(-15));
        }

        const qRes = await fetch('http://localhost:8080/api/queue');
        if (qRes.ok) {
            const qData = await qRes.json();
            if (!cancelled) {
                setQueue(qData.queue || []);
                setCurrentTasks(qData.current_tasks || []);
            }
        }
      } catch (err) {
        console.error('Failed to fetch from backend', err);
        setSimRunning(false);
        setApsPhase('СЕРВЕР ОТКЛЮЧЕН');
      }
    };

    const interval = setInterval(fetchApsState, 500);
    fetchApsState();

    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  // Auto-scroll APS log
  useEffect(() => {
    if (apsLogRef.current) {
      apsLogRef.current.scrollTop = apsLogRef.current.scrollHeight;
    }
  }, [apsHistory]);

  const addTask = () => {
    const names = ['Новый_Заказ_Сталь', 'Срочный_Медь', 'Партия_Алюм', 'Тест_Пуск'];
    const name = names[Math.floor(Math.random() * names.length)] + '_' + Math.floor(Math.random() * 1000);
    const material = name.includes('Медь') ? 'Медь' : name.includes('Алюм') ? 'Алюминий' : 'Сталь';
    const newBatch: BatchInfo = {
      id: 'B_' + Date.now(),
      name,
      material,
      weight: +(20 + Math.random() * 50).toFixed(1),
      priority: 0,
      urgencyScore: 0,
      materialScore: 0,
      weightScore: 0,
      status: 'WAITING',
      totalProcessingTimeSec: 0,
      orderInLine: 0,
      assignedLineId: '',
    };
    setQueue(prev => [...prev, newBatch]);
    addLog('REST_API', `POST /api/queue — '${name}' добавлена`, 'text-sky-300');
  };

  const currentPhaseInfo = APS_PHASES[apsPhase] || APS_PHASES.IDLE;

  return (
    <div className="w-full min-h-screen bg-[#0f172a] text-slate-200 flex flex-col font-sans overflow-hidden selection:bg-sky-500/30 selection:text-white">
      {/* Top Navigation Bar */}
      <header className="h-16 border-b border-sky-500/30 bg-slate-900/80 backdrop-blur-md flex items-center justify-between px-6 shrink-0">
        <div className="flex items-center space-x-4">
          <div className="w-10 h-10 bg-sky-500/20 border border-sky-400 flex items-center justify-center rounded shadow-[0_0_15px_rgba(56,189,248,0.4)]">
            <div className="w-6 h-6 border-2 border-sky-400 rotate-45 flex items-center justify-center">
              <div className="w-2 h-2 bg-sky-400"></div>
            </div>
          </div>
          <div className="min-w-0 pr-2">
            <h1 className="text-sm md:text-base lg:text-lg font-bold tracking-wider text-sky-100 flex items-center flex-wrap gap-x-2">
              ЦИФРОВОЙ ДВОЙНИК <span className="text-sky-500 text-[10px] lg:text-xs font-mono shrink-0">v3.0-APS</span>
            </h1>
            <p className="text-[9px] lg:text-[10px] text-slate-400 font-mono hidden sm:block truncate">OPC.TCP://0.0.0.0:4840/ | REST API: 8000 | APS Engine</p>
          </div>
        </div>

        <div className="flex items-center space-x-4 lg:space-x-8 shrink-0">
          <div className="flex flex-col items-end">
            <span className="text-[9px] lg:text-[10px] uppercase text-slate-500 font-bold tracking-widest">Статус системы</span>
            <div className="flex items-center">
              <div className={`w-2 h-2 rounded-full mr-2 shrink-0 ${manualMode ? 'bg-red-500 shadow-[0_0_10px_#ef4444]' : (simRunning ? 'bg-emerald-500 shadow-[0_0_10px_#10b981]' : 'bg-amber-500 shadow-[0_0_10px_#f59e0b]')}`}></div>
              <span className={`font-bold text-[10px] lg:text-xs uppercase tracking-widest text-right whitespace-nowrap ${manualMode ? 'text-red-400' : (simRunning ? 'text-emerald-400' : 'text-amber-400')}`}>
                {manualMode ? 'РУЧНОЕ УПРАВЛЕНИЕ' : (simRunning ? 'АВТОМАТИЧЕСКИЙ РЕЖИМ' : 'ОЖИДАНИЕ ЗАПУСКА')}
              </span>
            </div>
          </div>
          <div className="flex-col items-end hidden md:flex">
            <span className="text-[9px] lg:text-[10px] uppercase text-slate-500 font-bold tracking-widest">Фаза APS</span>
            <span className={`font-mono text-xs lg:text-sm text-right whitespace-nowrap ${currentPhaseInfo.color}`}>
              {currentPhaseInfo.label}
            </span>
          </div>
          <div className="flex-col items-end hidden lg:flex">
            <span className="text-[9px] text-slate-500 font-bold tracking-widest uppercase">Циклы APS</span>
            <span className="text-emerald-400 font-mono text-sm">{totalCyclesCompleted}</span>
          </div>
          <button
            onClick={() => {
              setManualMode(!manualMode);
              addLog('HW_BRIDGE', `Аварийное вмешательство: ${!manualMode ? 'ВКЛЮЧЕНО' : 'ОТКЛЮЧЕНО'}`, !manualMode ? 'text-red-400 font-bold' : 'text-emerald-400 font-bold');
            }}
            className={`px-2 lg:px-4 py-2 border text-[10px] lg:text-xs font-bold rounded transition-colors uppercase whitespace-nowrap ${manualMode ? 'bg-emerald-600/20 border-emerald-500/50 text-emerald-500 hover:bg-emerald-600/40' : 'bg-red-600/20 border-red-500/50 text-red-500 hover:bg-red-600/40'}`}
          >
            {manualMode ? 'Возобновить [G]' : 'Остановка [G]'}
          </button>
        </div>
      </header>

      {/* Main Content Area */}
      <main className="flex-1 flex gap-3 p-3 min-h-0">

        {/* Left Rail: Telemetry + Equipment */}
        <aside className="w-48 lg:w-56 flex flex-col gap-3 shrink-0">
          {/* Sensor Telemetry — UNCHANGED */}
          <div className="bg-slate-800/50 border border-slate-700 p-3 rounded-xl shadow-inner">
            <h2 className="text-[10px] font-bold uppercase text-slate-500 mb-4 flex items-center tracking-widest">
              <svg className="w-3.5 h-3.5 mr-1.5 text-sky-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path></svg>
              Сушильная камера
            </h2>

            <div className="space-y-4">
              <div>
                <label className="text-[9px] uppercase text-slate-400">Температура</label>
                <div className="text-2xl font-mono text-orange-400 font-light">{temperature.toFixed(1)} <span className="text-sm">°C</span></div>
                <div className="w-full bg-slate-900 h-1 mt-1 rounded-full overflow-hidden">
                  <div className="bg-orange-500 h-full shadow-[0_0_10px_#f97316] transition-all duration-1000" style={{ width: `${(temperature / 100) * 100}%` }}></div>
                </div>
              </div>

              <div>
                <label className="text-[9px] uppercase text-slate-400">Влажность</label>
                <div className="text-2xl font-mono text-sky-400 font-light">{humidity.toFixed(1)} <span className="text-sm">%</span></div>
                <div className="w-full bg-slate-900 h-1 mt-1 rounded-full overflow-hidden">
                  <div className="bg-sky-500 h-full shadow-[0_0_10px_#0ea5e9] transition-all duration-1000" style={{ width: `${(humidity / 100) * 100}%` }}></div>
                </div>
              </div>

              <div
                className="p-2 bg-slate-900/50 rounded border border-slate-700/50 cursor-pointer hover:bg-slate-800 transition-colors"
                onClick={() => {
                  setDoorClosed(!doorClosed);
                  addLog('HW_BRIDGE', `Датчик двери: ${!doorClosed ? 'Закрыто' : 'Открыто'}`);
                }}
              >
                <div className="flex items-center justify-between">
                  <span className="text-[10px] text-slate-400">Датчик двери</span>
                  <span className={`text-[10px] font-bold uppercase ${doorClosed ? 'text-emerald-400' : 'text-amber-400'}`}>
                    {doorClosed ? 'Закрыта' : 'Открыта'}
                  </span>
                </div>
              </div>
            </div>
          </div>

          {/* Equipment Utilization */}
          <div className="flex-1 bg-slate-800/50 border border-slate-700 p-3 rounded-xl shadow-inner overflow-y-auto">
            <h2 className="text-[10px] font-bold uppercase text-slate-500 mb-3 tracking-widest">
              Загруженность устройств
            </h2>
            <div className="space-y-2">
              {equipment.map(eq => {
                const util = eq.capacity > 0 ? eq.currentLoad / eq.capacity : 0;
                const pct = Math.round(util * 100);
                const barColor = pct > 85 ? 'bg-red-500' : pct > 60 ? 'bg-amber-500' : 'bg-emerald-500';
                return (
                  <div key={eq.id} className="group">
                    <div className="flex items-center justify-between mb-0.5">
                      <span className="text-[9px] text-slate-400 truncate">{eq.name}</span>
                      <span className={`text-[9px] font-mono font-bold ${pct > 85 ? 'text-red-400' : pct > 60 ? 'text-amber-400' : 'text-emerald-400'}`}>{pct}%</span>
                    </div>
                    <div className="w-full bg-slate-900 h-1 rounded-full overflow-hidden">
                      <div className={`${barColor} h-full transition-all duration-1000`} style={{ width: `${pct}%` }}></div>
                    </div>
                  </div>
                );
              })}
            </div>
            <div className="mt-3 pt-2 border-t border-slate-700/50">
              <div className="flex items-center justify-between">
                <span className="text-[9px] text-slate-500 uppercase font-bold">Средняя</span>
                <span className="text-xs font-mono text-sky-400 font-bold">{(utilizationCoeff * 100).toFixed(1)}%</span>
              </div>
            </div>
          </div>

          {/* Clock */}
          <div className="h-20 bg-slate-800/50 border border-slate-700 p-3 rounded-xl flex flex-col justify-center items-center">
            <span className="text-[9px] uppercase text-slate-500 mb-1 font-bold">Время симуляции</span>
            <div className="text-xl font-mono text-slate-300 tabular-nums">{clock}</div>
          </div>
        </aside>

        {/* Center: Factory Floor + APS Flowchart + Logs */}
        <section className="flex-1 flex flex-col gap-3 min-w-0">
          {/* Top: Factory Line */}
          <div className="h-40 bg-[#1c2128] border border-slate-700 rounded-xl relative p-4 flex flex-col justify-center shrink-0">
            <div className="absolute top-3 left-4 flex items-center space-x-2">
              <div className={`w-2.5 h-2.5 rounded-sm ${simRunning && !manualMode ? 'bg-sky-500 animate-pulse' : 'bg-slate-600'}`}></div>
              <span className="text-[10px] font-mono text-sky-500">ДИНАМИЧЕСКИЙ_ПРОСМОТР_ЛИНИИ</span>
            </div>

            <div className="h-full w-full flex flex-col justify-around py-4 mt-2">
              <div className="h-3 w-full bg-slate-800 rounded-full border border-slate-700 flex items-center px-4 overflow-hidden">
                <div className="h-0.5 w-full border-t border-dashed border-slate-600"></div>
              </div>

              <div className="flex justify-around items-end px-2 md:px-6 relative mt-8 w-full">
                {/* Crane */}
                <div
                  className="absolute -top-14 lg:-top-16 transition-all duration-1000 ease-linear transform -translate-x-1/2 z-10"
                  style={{ left: `${cranePos}%` }}
                >
                  <div className="w-10 h-0.5 bg-sky-400 shadow-[0_0_10px_#38bdf8] mx-auto"></div>
                  <div className="w-0.5 h-6 lg:h-8 bg-sky-400 mx-auto"></div>
                  <div className="w-8 h-6 bg-orange-500/80 border border-orange-400 rounded-sm mx-auto shadow-[0_0_15px_rgba(249,115,22,0.4)] flex items-center justify-center">
                    <span className="text-[7px] text-white font-bold">P_102</span>
                  </div>
                </div>

                {/* Bath 1 */}
                <div className="flex flex-col items-center w-1/4">
                  <div className={`w-full max-w-[6rem] h-10 lg:h-12 bg-slate-800 border-2 border-slate-700 relative rounded-b-lg overflow-hidden z-0 ${apsPhase === 'CALC_ACTION_SEQ' || apsPhase === 'FILL_SCHEDULE' ? 'border-sky-400/50' : ''}`}>
                    <div className={`absolute inset-0 bg-lime-500/30 ${simRunning && !manualMode ? 'animate-pulse' : ''}`}></div>
                    <div className="absolute bottom-0 inset-x-0 h-1.5 bg-lime-500/60"></div>
                  </div>
                  <span className="mt-2 text-[8px] md:text-[9px] text-center font-bold text-slate-500 uppercase">01 Обезжирив.</span>
                </div>

                {/* Bath 2 */}
                <div className="flex flex-col items-center w-1/4">
                  <div className={`w-full max-w-[6rem] h-10 lg:h-12 bg-slate-800 border-2 border-slate-700 relative rounded-b-lg overflow-hidden z-0 ${apsPhase === 'CALC_ACTION_SEQ' || apsPhase === 'FILL_SCHEDULE' ? 'border-sky-400/50' : ''}`}>
                    <div className="absolute inset-0 bg-cyan-500/30"></div>
                    <div className="absolute bottom-0 inset-x-0 h-1.5 bg-cyan-500/60"></div>
                  </div>
                  <span className="mt-2 text-[8px] md:text-[9px] text-center font-bold text-slate-500 uppercase">02 Травление</span>
                </div>

                {/* Bath 3 */}
                <div className="flex flex-col items-center w-1/4">
                  <div className={`w-full max-w-[6rem] h-10 lg:h-12 bg-slate-800 border-2 border-slate-700 relative rounded-b-lg overflow-hidden z-0 ${apsPhase === 'CALC_ACTION_SEQ' || apsPhase === 'FILL_SCHEDULE' ? 'border-sky-400/50' : ''}`}>
                    <div className="absolute inset-0 bg-orange-500/20"></div>
                    <div className="absolute bottom-0 inset-x-0 h-1.5 bg-orange-500/60 shadow-[0_-5px_15px_rgba(249,115,22,0.3)]"></div>
                  </div>
                  <span className="mt-2 text-[8px] md:text-[9px] text-center font-bold text-slate-500 uppercase">03 Ванна цинка</span>
                </div>
              </div>
            </div>
          </div>

          {/* Middle: APS Flowchart Visualization */}
          <div className="flex-1 bg-slate-800/50 border border-slate-700 rounded-xl p-3 flex flex-col min-h-0">
            <div className="flex items-center justify-between mb-2 shrink-0">
              <h2 className="text-[10px] font-bold uppercase text-slate-500 tracking-widest flex items-center">
                <svg className="w-3.5 h-3.5 mr-1.5 text-violet-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"></path></svg>
                Блок-схема APS (состояние алгоритма)
              </h2>
              <div className="flex items-center space-x-3">
                {recalculationCount > 0 && (
                  <span className="text-[9px] bg-amber-500/20 text-amber-400 px-2 py-0.5 rounded border border-amber-500/30">
                    Пересчёт: {recalculationCount}
                  </span>
                )}
                {currentBatch && (
                  <span className="text-[9px] bg-sky-500/20 text-sky-400 px-2 py-0.5 rounded border border-sky-500/30">
                    {currentBatch}
                  </span>
                )}
              </div>
            </div>

            {/* Flowchart blocks */}
            <div className="flex-1 flex flex-col justify-center">
              <div className="flex flex-wrap gap-1.5 justify-center items-center">
                {FLOWCHART_BLOCKS.map((block, i) => {
                  const isActive = apsPhase === block.id;
                  const phaseInfo = APS_PHASES[block.id];
                  const isPast = phaseInfo && currentPhaseInfo.step > phaseInfo.step && currentPhaseInfo.step > 0;
                  const isDecision = block.type === 'decision';
                  const isTerminal = block.type === 'terminal';

                  return (
                    <React.Fragment key={block.id}>
                      {i > 0 && (
                        <svg className="w-3 h-3 shrink-0 text-slate-600" viewBox="0 0 12 12" fill="none">
                          <path d="M2 6 L10 6 M7 3 L10 6 L7 9" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                        </svg>
                      )}
                      <div
                        className={`
                          relative px-2 py-1 text-[8px] lg:text-[9px] font-bold uppercase tracking-wide text-center
                          transition-all duration-300 shrink-0 min-w-[5rem] lg:min-w-[6rem]
                          ${isDecision ? 'rotate-0 border-2' : isTerminal ? 'rounded-full border-2' : 'rounded border'}
                          ${isActive
                            ? 'bg-sky-500/20 border-sky-400 text-sky-300 shadow-[0_0_12px_rgba(56,189,248,0.4)] scale-110'
                            : isPast
                              ? 'bg-emerald-500/10 border-emerald-500/40 text-emerald-400/80'
                              : 'bg-slate-900/50 border-slate-700 text-slate-500'
                          }
                          ${isDecision ? (isActive ? 'border-yellow-400 bg-yellow-500/10 text-yellow-300' : '') : ''}
                        `}
                        style={isDecision ? { clipPath: 'polygon(50% 0%, 100% 50%, 50% 100%, 0% 50%)' , padding: '0.6rem 1rem' } : undefined}
                      >
                        {block.label}
                        {isActive && (
                          <div className="absolute -top-0.5 -right-0.5 w-2 h-2 bg-sky-400 rounded-full animate-ping"></div>
                        )}
                      </div>
                    </React.Fragment>
                  );
                })}
              </div>

              {/* APS Metrics Row */}
              <div className="flex gap-3 mt-3 justify-center flex-wrap">
                <div className="bg-slate-900/60 border border-slate-700 rounded px-3 py-1.5 text-center">
                  <div className="text-[8px] uppercase text-slate-500 font-bold">Загруженность</div>
                  <div className={`text-sm font-mono font-bold ${utilizationCoeff > 0.85 ? 'text-red-400' : utilizationCoeff > 0.6 ? 'text-amber-400' : 'text-emerald-400'}`}>
                    {(utilizationCoeff * 100).toFixed(1)}%
                  </div>
                </div>
                <div className="bg-slate-900/60 border border-slate-700 rounded px-3 py-1.5 text-center">
                  <div className="text-[8px] uppercase text-slate-500 font-bold">Время обработки</div>
                  <div className="text-sm font-mono font-bold text-orange-400">{formatTimeSec(processingTimeEstimate)}</div>
                </div>
                <div className="bg-slate-900/60 border border-slate-700 rounded px-3 py-1.5 text-center">
                  <div className="text-[8px] uppercase text-slate-500 font-bold">Включение</div>
                  <div className={`text-sm font-bold ${canIncludeResult ? 'text-emerald-400' : 'text-red-400'}`}>
                    {canIncludeResult ? '✓ ДА' : '✗ НЕТ'}
                  </div>
                </div>
                <div className="bg-slate-900/60 border border-slate-700 rounded px-3 py-1.5 text-center">
                  <div className="text-[8px] uppercase text-slate-500 font-bold">Шагов</div>
                  <div className="text-sm font-mono font-bold text-violet-400">{actionSequenceSteps}</div>
                </div>
                {rejectionCause && (
                  <div className="bg-red-900/20 border border-red-500/30 rounded px-3 py-1.5 text-center max-w-[12rem]">
                    <div className="text-[8px] uppercase text-red-400 font-bold">Причина отказа</div>
                    <div className="text-[9px] text-red-300 truncate">{rejectionCause}</div>
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* Bottom: Two log panels */}
          <div className="flex gap-3 shrink-0" style={{ height: '9rem' }}>
            {/* APS Algorithm Log */}
            <div ref={apsLogRef} className="flex-1 bg-violet-950/20 border border-violet-500/20 rounded-xl p-3 font-mono text-[10px] overflow-y-auto leading-relaxed flex flex-col">
              <div className="text-[9px] uppercase text-violet-400/60 font-bold mb-1 tracking-widest shrink-0">Лог APS-алгоритма</div>
              <div className="flex-1 flex flex-col justify-end">
                {apsHistory.map((entry, i) => (
                  <div key={i} className={`mb-0.5 ${entry.includes('✓') ? 'text-emerald-400' : entry.includes('✗') ? 'text-red-400' : entry.includes('—') ? 'text-slate-500' : 'text-violet-300'}`}>
                    {entry}
                  </div>
                ))}
              </div>
            </div>

            {/* System Log */}
            <div className="flex-1 bg-black/40 border border-slate-700 rounded-xl p-3 font-mono text-[10px] overflow-y-auto leading-relaxed flex flex-col">
              <div className="text-[9px] uppercase text-slate-500 font-bold mb-1 tracking-widest shrink-0">Системный лог</div>
              <div className="flex-1 flex flex-col justify-end">
                {logs.map((log) => (
                  <div key={log.id} className="text-sky-400 break-all mb-0.5">
                    [{log.time}] <span className={log.color}>{log.src}: {log.msg}</span>
                  </div>
                ))}
                {simRunning && !manualMode && (
                  <div className="text-sky-400 mt-1">
                    [{clock.substring(0,8)}] <span className="text-slate-400 animate-pulse">_ ожидание данных с датчиков...</span>
                  </div>
                )}
              </div>
            </div>
          </div>
        </section>

        <div className="flex gap-3 pr-3 py-3 shrink-0">
          {/* Active Tasks Column */}
          <aside className="w-48 lg:w-56 bg-slate-900/80 border border-emerald-500/30 p-3 rounded-xl flex flex-col shrink-0 shadow-[0_0_15px_rgba(16,185,129,0.1)]">
            <div className="flex items-center justify-between mb-3 shrink-0">
              <h2 className="text-[10px] font-bold uppercase text-emerald-500 tracking-widest">В работе</h2>
              <span className="bg-emerald-500/20 text-emerald-400 text-[9px] px-1.5 py-0.5 rounded border border-emerald-400/30">
                {currentTasks.length} Акт.
              </span>
            </div>

            <div className="flex-1 overflow-y-auto space-y-1.5 pr-1">
              {currentTasks.map((item) => (
                <div key={item.id} className="p-2 rounded border transition-all bg-emerald-500/10 border-emerald-500/30 shadow-[0_0_10px_rgba(16,185,129,0.15)] relative overflow-hidden">
                  <div className="absolute top-0 left-0 w-1 h-full bg-emerald-500 animate-pulse"></div>
                  <div className="pl-2">
                    <div className="flex items-center justify-between mb-0.5">
                      <span className="text-[10px] truncate mr-1 font-bold text-emerald-400">
                        {item.name}
                      </span>
                    </div>
                    <div className="flex items-center justify-between text-[8px] text-emerald-400/60 mt-1">
                      <span>{item.material} • {item.weight.toFixed(1)}кг</span>
                    </div>
                    {item.totalProcessingTimeSec > 0 && (
                      <div className="text-[8px] text-emerald-300 mt-1 flex items-center">
                        <span className="animate-spin mr-1">⚙</span> Исполнение...
                      </div>
                    )}
                  </div>
                </div>
              ))}
              {currentTasks.length === 0 && (
                <div className="text-center text-slate-500 text-[10px] py-4 uppercase tracking-widest border border-dashed border-slate-700 rounded p-2">Нет активных задач</div>
              )}
            </div>
          </aside>

          {/* Right Rail: ERP/MES Queue */}
          <aside className="w-48 lg:w-56 bg-slate-800/50 border border-slate-700 p-3 rounded-xl flex flex-col shrink-0 shadow-xl">
            <div className="flex items-center justify-between mb-3 shrink-0">
              <h2 className="text-[10px] font-bold uppercase text-slate-500 tracking-widest">Очередь MES</h2>
              <span className="bg-sky-500/20 text-sky-400 text-[9px] px-1.5 py-0.5 rounded border border-sky-400/30">
                {queue.length} Ожидают
              </span>
            </div>

            <div className="flex-1 overflow-y-auto space-y-1.5 pr-1">
              {queue.map((item) => (
                <div key={item.id} className={`p-2 rounded border transition-all ${
                  item.status === 'SCHEDULED' ? 'bg-emerald-500/10 border-emerald-500/30' :
                  item.status === 'PRIORITIZED' ? 'bg-sky-500/10 border-sky-500/30' :
                  'bg-slate-900/60 border-slate-700'
                }`}>
                  <div className="flex items-center justify-between mb-0.5">
                    <span className={`text-[10px] truncate mr-1 ${item.status === 'SCHEDULED' ? 'font-bold text-emerald-200' : item.status === 'PRIORITIZED' ? 'font-bold text-sky-200' : 'text-slate-300'}`}>
                      {item.name}
                    </span>
                    <span className={`text-[8px] px-1 py-0.5 rounded shrink-0 ${
                      item.status === 'SCHEDULED' ? 'bg-emerald-500 text-white font-bold' :
                      item.status === 'PRIORITIZED' ? 'bg-sky-500/80 text-white font-bold' :
                      'text-slate-500'
                    }`}>
                      {item.status}
                    </span>
                  </div>
                  <div className="flex items-center justify-between text-[8px] text-slate-500">
                    <span>{item.material} • {item.weight.toFixed(1)}кг</span>
                    {item.priority > 0 && (
                      <span className="text-violet-400 font-bold">P={item.priority}</span>
                    )}
                  </div>
                  {item.totalProcessingTimeSec > 0 && (
                    <div className="text-[8px] text-orange-400 mt-0.5">
                      ⏱ {formatTimeSec(item.totalProcessingTimeSec)}
                    </div>
                  )}
                  {item.orderInLine > 0 && (
                    <div className="text-[8px] text-sky-400 mt-0.5">
                      Очерёдность: #{item.orderInLine}
                    </div>
                  )}
                </div>
              ))}
            </div>

            <button
              onClick={addTask}
              className="mt-3 w-full py-2.5 bg-slate-700 border border-slate-600 text-[10px] font-bold rounded-lg hover:bg-slate-600 transition-all uppercase tracking-widest text-slate-300 shadow-lg"
            >
              Добавить задачу
            </button>
          </aside>
        </div>
      </main>

      {/* Bottom Status Footer */}
      <footer className="h-7 bg-slate-950 border-t border-slate-800 flex items-center justify-between px-6 text-[9px] text-slate-500 shrink-0">
        <div className="flex items-center space-x-4">
          <span className="flex items-center">
            <div className="w-1.5 h-1.5 bg-emerald-500 rounded-full mr-1.5 shadow-[0_0_5px_#10b981]"></div>
            API СЕРВЕР: ОК
          </span>
          <span className="flex items-center">
            <div className="w-1.5 h-1.5 bg-emerald-500 rounded-full mr-1.5 shadow-[0_0_5px_#10b981]"></div>
            OPC UA: ПОДКЛЮЧЕН
          </span>
          <span className="flex items-center">
            <div className="w-1.5 h-1.5 bg-sky-500 rounded-full mr-1.5 shadow-[0_0_5px_#0ea5e9]"></div>
            APS ENGINE: {apsPhase !== 'IDLE' ? 'ACTIVE' : 'IDLE'}
          </span>
        </div>
        <div className="italic font-mono">ПРОБЕЛ: пауза | G: ручной режим | APS v3.0 Цифровой двойник</div>
      </footer>
    </div>
  );
}
