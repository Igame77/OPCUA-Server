package com.smartfactory.opcua;

import com.smartfactory.state.ApiState;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;

import java.util.List;

/**
 * OPC UA Namespace: "http://factory-aps.local"
 *
 * Структура нодов (совместима с PyGame-клиентом):
 *   Objects/
 *     └─ 8_Global_Scheduler/
 *         ├─ SimulationRunning   (Boolean, R/W — клиент переключает пробелом)
 *         ├─ ManualOverride      (Boolean, R/W — клиент переключает кнопкой G)
 *         ├─ SystemStatus        (String,  R/W — сервер пишет статус APS)
 *         └─ CurrentAlgorithmStep(String,  R/W — сервер пишет текущий шаг)
 */
public class FactoryNamespace extends ManagedNamespaceWithLifecycle {

    public static final String NAMESPACE_URI = "http://factory-aps.local";

    private final ApiState apiState;

    // ── 1_Alkaline_Degreasing ──
    private UaVariableNode stage1TempNode;
    private UaVariableNode stage1ActiveNode;
    private UaVariableNode stage1HeaterNode;

    // ── 2_Rinsing_1 ──
    private UaVariableNode stage2ActiveNode;
    private UaVariableNode stage2PumpNode;

    // ── 3_Pickling ──
    private UaVariableNode stage3TempNode;
    private UaVariableNode stage3ActiveNode;
    private UaVariableNode stage3AgitatorNode;

    // ── 4_Rinsing_2 ──
    private UaVariableNode stage4ActiveNode;
    private UaVariableNode stage4PumpNode;

    // ── 5_Fluxing ──
    private UaVariableNode stage5TempNode;
    private UaVariableNode stage5ActiveNode;
    private UaVariableNode stage5HeaterNode;

    // ── 6_Drying_Chamber ──
    private UaVariableNode stage6TempNode;
    private UaVariableNode stage6HumidityNode;
    private UaVariableNode stage6ActiveNode;
    private UaVariableNode stage6FanNode;
    private UaVariableNode stage6SensorConnectedNode;

    // ── 7_Zinc_Bath ──
    private UaVariableNode stage7TempNode;
    private UaVariableNode stage7ActiveNode;
    private UaVariableNode stage7HeaterNode;

    // OPC UA Variable nodes
    private UaVariableNode simRunningNode;
    private UaVariableNode manualOverrideNode;
    private UaVariableNode systemStatusNode;
    private UaVariableNode currentStepNode;
    
    // New nodes for R-PRO integration
    private UaVariableNode conveyorRunNode;
    private UaVariableNode robot1CommandNode;
    private UaVariableNode robot2CommandNode;
    private UaVariableNode robot3CommandNode;
    private UaVariableNode robot4CommandNode;

    // Track client activity (set when any node is read/written by a remote client)
    private volatile long lastClientAccessMs = 0;

    public FactoryNamespace(OpcUaServer server, ApiState apiState) {
        super(server, NAMESPACE_URI);
        this.apiState = apiState;
    }

    // ==========================================================================
    // Lifecycle
    // ==========================================================================

    @Override
    public void onDataItemsCreated(List<org.eclipse.milo.opcua.sdk.server.api.DataItem> dataItems) {}

    @Override
    public void onDataItemsModified(List<org.eclipse.milo.opcua.sdk.server.api.DataItem> dataItems) {}

    @Override
    public void onDataItemsDeleted(List<org.eclipse.milo.opcua.sdk.server.api.DataItem> dataItems) {}

    @Override
    public void onMonitoringModeChanged(List<org.eclipse.milo.opcua.sdk.server.api.MonitoredItem> monitoredItems) {}

    public void registerNodes() {
        UByte rwAccess = UByte.valueOf(3);

        // ── Folder: 1_Alkaline_Degreasing ─────────────────────────────────
        UaFolderNode folder1 = createFolder("1_Alkaline_Degreasing");
        stage1TempNode   = createVariable(folder1.getNodeId(), "1_Alkaline_Degreasing/Temperature", "Temperature", Identifiers.Int32, new Variant(50), rwAccess);
        stage1ActiveNode = createVariable(folder1.getNodeId(), "1_Alkaline_Degreasing/IsActive", "IsActive", Identifiers.Boolean, new Variant(false), rwAccess);
        stage1HeaterNode = createVariable(folder1.getNodeId(), "1_Alkaline_Degreasing/HeaterOn", "HeaterOn", Identifiers.Boolean, new Variant(false), rwAccess);

        // ── Folder: 2_Rinsing_1 ───────────────────────────────────────────
        UaFolderNode folder2 = createFolder("2_Rinsing_1");
        stage2ActiveNode = createVariable(folder2.getNodeId(), "2_Rinsing_1/IsActive", "IsActive", Identifiers.Boolean, new Variant(false), rwAccess);
        stage2PumpNode   = createVariable(folder2.getNodeId(), "2_Rinsing_1/PumpRunning", "PumpRunning", Identifiers.Boolean, new Variant(false), rwAccess);

        // ── Folder: 3_Pickling ────────────────────────────────────────────
        UaFolderNode folder3 = createFolder("3_Pickling");
        stage3TempNode     = createVariable(folder3.getNodeId(), "3_Pickling/Temperature", "Temperature", Identifiers.Int32, new Variant(25), rwAccess);
        stage3ActiveNode   = createVariable(folder3.getNodeId(), "3_Pickling/IsActive", "IsActive", Identifiers.Boolean, new Variant(false), rwAccess);
        stage3AgitatorNode = createVariable(folder3.getNodeId(), "3_Pickling/AgitatorOn", "AgitatorOn", Identifiers.Boolean, new Variant(false), rwAccess);

        // ── Folder: 4_Rinsing_2 ───────────────────────────────────────────
        UaFolderNode folder4 = createFolder("4_Rinsing_2");
        stage4ActiveNode = createVariable(folder4.getNodeId(), "4_Rinsing_2/IsActive", "IsActive", Identifiers.Boolean, new Variant(false), rwAccess);
        stage4PumpNode   = createVariable(folder4.getNodeId(), "4_Rinsing_2/PumpRunning", "PumpRunning", Identifiers.Boolean, new Variant(false), rwAccess);

        // ── Folder: 5_Fluxing ─────────────────────────────────────────────
        UaFolderNode folder5 = createFolder("5_Fluxing");
        stage5TempNode   = createVariable(folder5.getNodeId(), "5_Fluxing/Temperature", "Temperature", Identifiers.Int32, new Variant(60), rwAccess);
        stage5ActiveNode = createVariable(folder5.getNodeId(), "5_Fluxing/IsActive", "IsActive", Identifiers.Boolean, new Variant(false), rwAccess);
        stage5HeaterNode = createVariable(folder5.getNodeId(), "5_Fluxing/HeaterOn", "HeaterOn", Identifiers.Boolean, new Variant(false), rwAccess);

        // ── Folder: 6_Drying_Chamber ──────────────────────────────────────
        UaFolderNode folder6 = createFolder("6_Drying_Chamber");
        stage6TempNode            = createVariable(folder6.getNodeId(), "6_Drying_Chamber/TemperatureCelsius", "TemperatureCelsius", Identifiers.Int32, new Variant(55), rwAccess);
        stage6HumidityNode        = createVariable(folder6.getNodeId(), "6_Drying_Chamber/HumidityPercent", "HumidityPercent", Identifiers.Int32, new Variant(20), rwAccess);
        stage6ActiveNode          = createVariable(folder6.getNodeId(), "6_Drying_Chamber/IsActive", "IsActive", Identifiers.Boolean, new Variant(false), rwAccess);
        stage6FanNode             = createVariable(folder6.getNodeId(), "6_Drying_Chamber/FanOn", "FanOn", Identifiers.Boolean, new Variant(false), rwAccess);
        stage6SensorConnectedNode = createVariable(folder6.getNodeId(), "6_Drying_Chamber/SensorConnected", "SensorConnected", Identifiers.Boolean, new Variant(true), rwAccess);

        // ── Folder: 7_Zinc_Bath ───────────────────────────────────────────
        UaFolderNode folder7 = createFolder("7_Zinc_Bath");
        stage7TempNode   = createVariable(folder7.getNodeId(), "7_Zinc_Bath/Temperature", "Temperature", Identifiers.Int32, new Variant(450), rwAccess);
        stage7ActiveNode = createVariable(folder7.getNodeId(), "7_Zinc_Bath/IsActive", "IsActive", Identifiers.Boolean, new Variant(true), rwAccess);
        stage7HeaterNode = createVariable(folder7.getNodeId(), "7_Zinc_Bath/HeaterOn", "HeaterOn", Identifiers.Boolean, new Variant(false), rwAccess);

        // ── Folder: 8_Global_Scheduler ────────────────────────────────────
        UaFolderNode schedulerFolder = createFolder("8_Global_Scheduler");
        NodeId folderId = schedulerFolder.getNodeId();

        simRunningNode     = createVariable(folderId, "SimulationRunning", "SimulationRunning",
                Identifiers.Boolean, new Variant(false), rwAccess);
        manualOverrideNode = createVariable(folderId, "ManualOverride", "ManualOverride",
                Identifiers.Boolean, new Variant(false), rwAccess);
        systemStatusNode   = createVariable(folderId, "SystemStatus", "SystemStatus",
                Identifiers.String,  new Variant("WAITING"), rwAccess);
        currentStepNode    = createVariable(folderId, "CurrentAlgorithmStep", "CurrentAlgorithmStep",
                Identifiers.String,  new Variant("Ожидание подключения клиента..."), rwAccess);

        // R-PRO specific nodes
        conveyorRunNode    = createVariable(folderId, "Conveyor_Run", "Conveyor_Run",
                Identifiers.Boolean, new Variant(false), rwAccess);
        robot1CommandNode  = createVariable(folderId, "Robot1_Command", "Robot1_Command",
                Identifiers.String,  new Variant("IDLE"), rwAccess);
        robot2CommandNode  = createVariable(folderId, "Robot2_Command", "Robot2_Command",
                Identifiers.String,  new Variant("IDLE"), rwAccess);
        robot3CommandNode  = createVariable(folderId, "Robot3_Command", "Robot3_Command",
                Identifiers.String,  new Variant("IDLE"), rwAccess);
        robot4CommandNode  = createVariable(folderId, "Robot4_Command", "Robot4_Command",
                Identifiers.String,  new Variant("IDLE"), rwAccess);

        System.out.println("[OPC-UA] Namespace '" + NAMESPACE_URI +
                "' успешно зарегистрирован: созданы все 8 узлов технологической линии (1_Alkaline_Degreasing .. 8_Global_Scheduler)");
    }

    // ==========================================================================
    // Node creation helpers
    // ==========================================================================

    private UaFolderNode createFolder(String folderName) {
        NodeId folderId = newNodeId(folderName);
        UaFolderNode folder = new UaFolderNode(
                getNodeContext(),
                folderId,
                newQualifiedName(folderName),
                LocalizedText.english(folderName)
        );
        getNodeManager().addNode(folder);

        folder.addReference(new Reference(
                folder.getNodeId(),
                Identifiers.Organizes,
                Identifiers.ObjectsFolder.expanded(),
                false
        ));
        return folder;
    }

    private UaVariableNode createVariable(
            NodeId parentId, String nodeIdStr, String browseName, NodeId dataType,
            Variant initialValue, UByte accessLevel) {

        NodeId nodeId = newNodeId(nodeIdStr);

        UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                .setNodeId(nodeId)
                .setBrowseName(newQualifiedName(browseName))
                .setDisplayName(LocalizedText.english(browseName))
                .setDataType(dataType)
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setAccessLevel(accessLevel)
                .setUserAccessLevel(accessLevel)
                .build();

        node.setValue(new DataValue(initialValue));
        getNodeManager().addNode(node);

        // parentFolder --Organizes--> variableNode  (stored as inverse on variable)
        node.addReference(new Reference(
                node.getNodeId(),
                Identifiers.Organizes,
                parentId.expanded(),
                false
        ));

        return node;
    }

    // ==========================================================================
    // Client-activity tracking (overrides called on every remote OPC read/write)
    // ==========================================================================

    @Override
    public void read(org.eclipse.milo.opcua.sdk.server.api.services.AttributeServices.ReadContext context,
                     Double maxAge,
                     org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn timestamps,
                     List<org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId> readValueIds) {
        lastClientAccessMs = System.currentTimeMillis();
        super.read(context, maxAge, timestamps, readValueIds);
    }

    @Override
    public void write(org.eclipse.milo.opcua.sdk.server.api.services.AttributeServices.WriteContext context,
                      List<org.eclipse.milo.opcua.stack.core.types.structured.WriteValue> writeValues) {
        lastClientAccessMs = System.currentTimeMillis();
        super.write(context, writeValues);
    }

    /**
     * Returns true if a remote client accessed any node in the last N milliseconds.
     * The PyGame client polls at ~10 Hz, so a 5-second timeout safely detects disconnection.
     */
    public boolean isClientActive() {
        return lastClientAccessMs > 0
                && (System.currentTimeMillis() - lastClientAccessMs) < 5000;
    }

    // ==========================================================================
    // Sync:  OPC nodes ←→ ApiState
    // ==========================================================================

    /**
     * Reads client-controlled OPC node values into ApiState.
     * Called periodically by OpcUaServerManager.
     */
    public void readClientValuesToApiState(ApiState state) {
        try {
            Object sim = simRunningNode.getValue().getValue().getValue();
            if (sim instanceof Boolean) state.setSimRunning((Boolean) sim);

            Object manual = manualOverrideNode.getValue().getValue().getValue();
            if (manual instanceof Boolean) state.setManualMode((Boolean) manual);
        } catch (Exception ignored) { }
    }

    /**
     * Writes server-generated values from ApiState to OPC nodes.
     * Called periodically by OpcUaServerManager.
     */
    public void writeServerValuesToOpc(ApiState state) {
        try {
            // 8_Global_Scheduler
            if (systemStatusNode != null) systemStatusNode.setValue(new DataValue(new Variant(state.getStatus())));
            if (currentStepNode != null) currentStepNode.setValue(new DataValue(new Variant(state.getStep())));
            if (manualOverrideNode != null) manualOverrideNode.setValue(new DataValue(new Variant(state.isManualMode())));

            // Stage 1: 1_Alkaline_Degreasing
            if (stage1TempNode != null) stage1TempNode.setValue(new DataValue(new Variant(state.getStage1().getTemperature())));
            if (stage1ActiveNode != null) stage1ActiveNode.setValue(new DataValue(new Variant(state.getStage1().isActive())));
            if (stage1HeaterNode != null) stage1HeaterNode.setValue(new DataValue(new Variant(state.getStage1().isHeaterOn())));

            // Stage 2: 2_Rinsing_1
            if (stage2ActiveNode != null) stage2ActiveNode.setValue(new DataValue(new Variant(state.getStage2().isActive())));
            if (stage2PumpNode != null) stage2PumpNode.setValue(new DataValue(new Variant(state.getStage2().isPumpRunning())));

            // Stage 3: 3_Pickling
            if (stage3TempNode != null) stage3TempNode.setValue(new DataValue(new Variant(state.getStage3().getTemperature())));
            if (stage3ActiveNode != null) stage3ActiveNode.setValue(new DataValue(new Variant(state.getStage3().isActive())));
            if (stage3AgitatorNode != null) stage3AgitatorNode.setValue(new DataValue(new Variant(state.getStage3().isAgitatorOn())));

            // Stage 4: 4_Rinsing_2
            if (stage4ActiveNode != null) stage4ActiveNode.setValue(new DataValue(new Variant(state.getStage4().isActive())));
            if (stage4PumpNode != null) stage4PumpNode.setValue(new DataValue(new Variant(state.getStage4().isPumpRunning())));

            // Stage 5: 5_Fluxing
            if (stage5TempNode != null) stage5TempNode.setValue(new DataValue(new Variant(state.getStage5().getTemperature())));
            if (stage5ActiveNode != null) stage5ActiveNode.setValue(new DataValue(new Variant(state.getStage5().isActive())));
            if (stage5HeaterNode != null) stage5HeaterNode.setValue(new DataValue(new Variant(state.getStage5().isHeaterOn())));

            // Stage 6: 6_Drying_Chamber
            if (stage6TempNode != null) stage6TempNode.setValue(new DataValue(new Variant(state.getStage6().getTemperatureCelsius())));
            if (stage6HumidityNode != null) stage6HumidityNode.setValue(new DataValue(new Variant(state.getStage6().getHumidityPercent())));
            if (stage6ActiveNode != null) stage6ActiveNode.setValue(new DataValue(new Variant(state.getStage6().isActive())));
            if (stage6FanNode != null) stage6FanNode.setValue(new DataValue(new Variant(state.getStage6().isFanOn())));
            if (stage6SensorConnectedNode != null) stage6SensorConnectedNode.setValue(new DataValue(new Variant(state.getStage6().isSensorConnected())));

            // Stage 7: 7_Zinc_Bath
            if (stage7TempNode != null) stage7TempNode.setValue(new DataValue(new Variant(state.getStage7().getTemperature())));
            if (stage7ActiveNode != null) stage7ActiveNode.setValue(new DataValue(new Variant(state.getStage7().isActive())));
            if (stage7HeaterNode != null) stage7HeaterNode.setValue(new DataValue(new Variant(state.getStage7().isHeaterOn())));
        } catch (Exception ignored) { }
    }

    // ==========================================================================
    // Direct node writes (used by server-side code, e.g. sensor loop / APS)
    // ==========================================================================

    /**
     * Directly sets ManualOverride on the OPC node.
     * Use instead of apiState.setManualMode() to keep OPC as source of truth.
     */
    public void writeManualOverride(boolean value) {
        try {
            manualOverrideNode.setValue(new DataValue(new Variant(value)));
        } catch (Exception ignored) { }
    }

    /**
     * Directly sets SimulationRunning on the OPC node.
     */
    public void writeSimulationRunning(boolean value) {
        try {
            simRunningNode.setValue(new DataValue(new Variant(value)));
        } catch (Exception ignored) { }
    }
}
