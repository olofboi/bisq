/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.inventory;


import bisq.core.network.p2p.inventory.GetInventoryRequestManager;
import bisq.core.network.p2p.inventory.model.Average;
import bisq.core.network.p2p.inventory.model.InventoryItem;
import bisq.core.network.p2p.inventory.model.RequestInfo;
import bisq.core.network.p2p.seed.DefaultSeedNodeRepository;
import bisq.core.proto.network.CoreNetworkProtoResolver;

import bisq.network.p2p.NetworkNodeProvider;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.network.NetworkNode;
import bisq.network.p2p.network.SetupListener;

import bisq.common.UserThread;
import bisq.common.config.BaseCurrencyNetwork;
import bisq.common.file.JsonFileManager;
import bisq.common.util.Utilities;

import java.time.Clock;

import java.io.File;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

@Slf4j
public class InventoryMonitor implements SetupListener {
    private final Map<NodeAddress, JsonFileManager> jsonFileManagerByNodeAddress = new HashMap<>();
    private final Map<NodeAddress, List<RequestInfo>> requestInfoListByNode = new HashMap<>();
    private final File appDir;
    private final boolean useLocalhostForP2P;
    private final int intervalSec;
    private final NetworkNode networkNode;
    private final GetInventoryRequestManager getInventoryRequestManager;

    private ArrayList<NodeAddress> seedNodes;
    private InventoryWebServer inventoryWebServer;
    private int requestCounter = 0;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public InventoryMonitor(File appDir,
                            boolean useLocalhostForP2P,
                            BaseCurrencyNetwork network,
                            int intervalSec,
                            int port) {
        this.appDir = appDir;
        this.useLocalhostForP2P = useLocalhostForP2P;
        this.intervalSec = intervalSec;

        networkNode = getNetworkNode(appDir);
        getInventoryRequestManager = new GetInventoryRequestManager(networkNode);

        // We maintain our own list as we want to monitor also old v2 nodes which are not part of the normal seed
        // node list anymore.
        String networkName = network.name().toLowerCase();
        String fileName = network.isMainnet() ? "inv_" + networkName : networkName;
        DefaultSeedNodeRepository.readSeedNodePropertyFile(fileName)
                .ifPresent(bufferedReader -> {
                    seedNodes = new ArrayList<>(DefaultSeedNodeRepository.getSeedNodeAddressesFromPropertyFile(fileName));
                    addJsonFileManagers(seedNodes);
                    inventoryWebServer = new InventoryWebServer(port, seedNodes, bufferedReader);
                    networkNode.start(this);
                });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void shutDown(Runnable shutDownCompleteHandler) {
        networkNode.shutDown(shutDownCompleteHandler);
        jsonFileManagerByNodeAddress.values().forEach(JsonFileManager::shutDown);
        inventoryWebServer.shutDown();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // SetupListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onTorNodeReady() {
        UserThread.runPeriodically(this::requestFromAllSeeds, intervalSec);
        requestFromAllSeeds();
    }

    @Override
    public void onHiddenServicePublished() {
    }

    @Override
    public void onSetupFailed(Throwable throwable) {
    }

    @Override
    public void onRequestCustomBridges() {
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void requestFromAllSeeds() {
        requestCounter++;
        seedNodes.forEach(nodeAddress -> {
            RequestInfo requestInfo = new RequestInfo(System.currentTimeMillis());
            new Thread(() -> {
                Thread.currentThread().setName("request @ " + getShortAddress(nodeAddress, useLocalhostForP2P));
                getInventoryRequestManager.request(nodeAddress,
                        result -> processResponse(nodeAddress, requestInfo, result, null),
                        errorMessage -> processResponse(nodeAddress, requestInfo, null, errorMessage));
            }).start();
        });
    }

    private void processResponse(NodeAddress nodeAddress,
                                 RequestInfo requestInfo,
                                 @Nullable Map<InventoryItem, String> result,
                                 @Nullable String errorMessage) {
        if (errorMessage != null) {
            log.warn("Error at connection to peer {}: {}", nodeAddress, errorMessage);
            requestInfo.setErrorMessage(errorMessage);
        }

        if (result != null) {
            log.info("nodeAddress={}, result={}", nodeAddress, result.toString());
            requestInfo.setInventory(result);
            long responseTime = System.currentTimeMillis();
            requestInfo.setResponseTime(responseTime);
        }

        requestInfoListByNode.putIfAbsent(nodeAddress, new ArrayList<>());
        List<RequestInfo> requestInfoList = requestInfoListByNode.get(nodeAddress);
        requestInfoList.add(requestInfo);

        // We create average of all nodes latest results. It might be that the nodes last result is
        // from a previous request as the response has not arrived yet.
        Set<RequestInfo> requestInfoSet = requestInfoListByNode.values().stream()
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(list.size() - 1))
                .collect(Collectors.toSet());
        Map<InventoryItem, Double> averageValues = Average.of(requestInfoSet);

        inventoryWebServer.onNewRequestInfo(requestInfoListByNode, averageValues, requestCounter);

        String json = Utilities.objectToJson(requestInfo);
        jsonFileManagerByNodeAddress.get(nodeAddress).writeToDisc(json, String.valueOf(requestInfo.getRequestStartTime()));
    }

    private void addJsonFileManagers(List<NodeAddress> seedNodes) {
        File jsonDir = new File(appDir, "json");
        if (!jsonDir.exists() && !jsonDir.mkdir()) {
            log.warn("make jsonDir failed");
        }
        seedNodes.forEach(nodeAddress -> {
            JsonFileManager jsonFileManager = new JsonFileManager(new File(jsonDir, getShortAddress(nodeAddress, useLocalhostForP2P)));
            jsonFileManagerByNodeAddress.put(nodeAddress, jsonFileManager);
        });
    }

    private NetworkNode getNetworkNode(File appDir) {
        File torDir = new File(appDir, "tor");
        CoreNetworkProtoResolver networkProtoResolver = new CoreNetworkProtoResolver(Clock.systemDefaultZone());
        return new NetworkNodeProvider(networkProtoResolver,
                ArrayList::new,
                useLocalhostForP2P,
                9999,
                torDir,
                null,
                "",
                -1,
                "",
                null,
                false,
                false).get();
    }

    private String getShortAddress(NodeAddress nodeAddress, boolean useLocalhostForP2P) {
        return useLocalhostForP2P ?
                nodeAddress.getFullAddress().replace(":", "_") :
                nodeAddress.getFullAddress().substring(0, 10);
    }
}
