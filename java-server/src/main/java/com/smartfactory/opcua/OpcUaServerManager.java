package com.smartfactory.opcua;

import com.smartfactory.state.ApiState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Initializes the Eclipse Milo OPC UA Server on port 4840.
 * Equivalent to Server() from asyncua.
 */
@Component
public class OpcUaServerManager {

    private OpcUaServer server;
    private final ApiState apiState;
    private FactoryNamespace factoryNamespace;

    public OpcUaServerManager(ApiState apiState) {
        this.apiState = apiState;
    }

    @PostConstruct
    public void init() {
        try {
            org.eclipse.milo.opcua.stack.server.EndpointConfiguration endpoint = org.eclipse.milo.opcua.stack.server.EndpointConfiguration.newBuilder()
                    .setBindAddress("0.0.0.0")
                    .setBindPort(4840)
                    .setSecurityPolicy(org.eclipse.milo.opcua.stack.core.security.SecurityPolicy.None)
                    .setSecurityMode(org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode.None)
                    .build();

            OpcUaServerConfig config = OpcUaServerConfig.builder()
                    .setApplicationName(LocalizedText.english("Smart Factory APS OPC UA Server"))
                    .setApplicationUri("urn:factory-aps:local")
                    .setEndpoints(java.util.Collections.singleton(endpoint))
                    .build();

            server = new OpcUaServer(config);
            server.startup().get();

            factoryNamespace = new FactoryNamespace(server, apiState);
            factoryNamespace.startup();
            factoryNamespace.registerNodes();

            System.out.println("[INFO] OPC UA Server started on port 4840");
            
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to start OPC UA Server: " + e.getMessage());
        }
    }

    @Scheduled(fixedRate = 200)
    public void syncWithOpcUa() {
        if (factoryNamespace != null) {
            boolean isActive = factoryNamespace.isClientActive();
            apiState.setClientConnected(isActive);

            if (isActive) {
                factoryNamespace.readClientValuesToApiState(apiState);
                factoryNamespace.writeServerValuesToOpc(apiState);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (server != null) {
            if (factoryNamespace != null) {
                factoryNamespace.shutdown();
            }
            server.shutdown();
        }
    }
}
