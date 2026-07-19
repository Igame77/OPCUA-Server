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

    // OPC UA Variable nodes
    private UaVariableNode simRunningNode;
    private UaVariableNode manualOverrideNode;
    private UaVariableNode systemStatusNode;
    private UaVariableNode currentStepNode;

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

        // ── Folder: 8_Global_Scheduler (organizes under Objects) ──────────
        NodeId folderId = newNodeId("8_Global_Scheduler");
        UaFolderNode schedulerFolder = new UaFolderNode(
                getNodeContext(),
                folderId,
                newQualifiedName("8_Global_Scheduler"),
                LocalizedText.english("8_Global_Scheduler")
        );
        getNodeManager().addNode(schedulerFolder);

        // ObjectsFolder --Organizes--> schedulerFolder  (stored as inverse on folder)
        schedulerFolder.addReference(new Reference(
                schedulerFolder.getNodeId(),
                Identifiers.Organizes,
                Identifiers.ObjectsFolder.expanded(),
                false
        ));

        // ── Variable nodes (Read/Write = UByte(3): bit0=Read, bit1=Write) ─
        UByte rwAccess = UByte.valueOf(3);

        simRunningNode     = createVariable(folderId, "SimulationRunning",
                Identifiers.Boolean, new Variant(false), rwAccess);
        manualOverrideNode = createVariable(folderId, "ManualOverride",
                Identifiers.Boolean, new Variant(false), rwAccess);
        systemStatusNode   = createVariable(folderId, "SystemStatus",
                Identifiers.String,  new Variant("WAITING"), rwAccess);
        currentStepNode    = createVariable(folderId, "CurrentAlgorithmStep",
                Identifiers.String,  new Variant("Ожидание подключения клиента..."), rwAccess);

        System.out.println("[OPC-UA] Namespace '" + NAMESPACE_URI +
                "' зарегистрирован: 4 переменных в узле 8_Global_Scheduler");
    }

    // ==========================================================================
    // Node creation helper
    // ==========================================================================

    private UaVariableNode createVariable(
            NodeId parentId, String name, NodeId dataType,
            Variant initialValue, UByte accessLevel) {

        NodeId nodeId = newNodeId(name);

        UaVariableNode node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                .setNodeId(nodeId)
                .setBrowseName(newQualifiedName(name))
                .setDisplayName(LocalizedText.english(name))
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
            systemStatusNode.setValue(new DataValue(new Variant(state.getStatus())));
            currentStepNode.setValue(new DataValue(new Variant(state.getStep())));
            manualOverrideNode.setValue(new DataValue(new Variant(state.isManualMode())));
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
