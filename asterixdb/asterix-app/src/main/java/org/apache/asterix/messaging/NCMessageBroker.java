/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.messaging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.asterix.common.api.IAsterixAppRuntimeContext;
import org.apache.asterix.common.messaging.AbstractApplicationMessage;
import org.apache.asterix.common.messaging.CompleteFailbackRequestMessage;
import org.apache.asterix.common.messaging.CompleteFailbackResponseMessage;
import org.apache.asterix.common.messaging.PreparePartitionsFailbackRequestMessage;
import org.apache.asterix.common.messaging.PreparePartitionsFailbackResponseMessage;
import org.apache.asterix.common.messaging.ReplicaEventMessage;
import org.apache.asterix.common.messaging.ReportMaxResourceIdMessage;
import org.apache.asterix.common.messaging.TakeoverMetadataNodeResponseMessage;
import org.apache.asterix.common.messaging.TakeoverPartitionsRequestMessage;
import org.apache.asterix.common.messaging.TakeoverPartitionsResponseMessage;
import org.apache.asterix.common.messaging.api.IApplicationMessage;
import org.apache.asterix.common.messaging.api.IApplicationMessageCallback;
import org.apache.asterix.common.messaging.api.INCMessageBroker;
import org.apache.asterix.common.replication.IRemoteRecoveryManager;
import org.apache.asterix.common.replication.Replica;
import org.apache.asterix.common.replication.ReplicaEvent;
import org.apache.asterix.event.schema.cluster.Node;
import org.apache.asterix.metadata.bootstrap.MetadataIndexImmutableProperties;
import org.apache.asterix.transaction.management.resource.PersistentLocalResourceRepository;
import org.apache.hyracks.api.messages.IMessage;
import org.apache.hyracks.api.util.JavaSerializationUtils;
import org.apache.hyracks.control.nc.NodeControllerService;

public class NCMessageBroker implements INCMessageBroker {
    private final static Logger LOGGER = Logger.getLogger(NCMessageBroker.class.getName());

    private final NodeControllerService ncs;
    private final AtomicLong messageId = new AtomicLong(0);
    private final Map<Long, IApplicationMessageCallback> callbacks;
    private final IAsterixAppRuntimeContext appContext;

    public NCMessageBroker(NodeControllerService ncs) {
        this.ncs = ncs;
        appContext = (IAsterixAppRuntimeContext) ncs.getApplicationContext().getApplicationObject();
        callbacks = new ConcurrentHashMap<Long, IApplicationMessageCallback>();
    }

    @Override
    public void sendMessage(IApplicationMessage message, IApplicationMessageCallback callback) throws Exception {
        if (callback != null) {
            long uniqueMessageId = messageId.incrementAndGet();
            message.setId(uniqueMessageId);
            callbacks.put(uniqueMessageId, callback);
        }
        try {
            ncs.sendApplicationMessageToCC(JavaSerializationUtils.serialize(message), null);
        } catch (Exception e) {
            if (callback != null) {
                //remove the callback in case of failure
                callbacks.remove(message.getId());
            }
            throw e;
        }
    }

    @Override
    public void receivedMessage(IMessage message, String nodeId) throws Exception {
        try {
            AbstractApplicationMessage absMessage = (AbstractApplicationMessage) message;
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("Received message: " + absMessage.getMessageType().name());
            }
            //if the received message is a response to a sent message, deliver it to the sender
            IApplicationMessageCallback callback = callbacks.remove(absMessage.getId());
            if (callback != null) {
                callback.deliverMessageResponse(absMessage);
            }

            //handle requests from CC
            switch (absMessage.getMessageType()) {
                case REPORT_MAX_RESOURCE_ID_REQUEST:
                    reportMaxResourceId();
                    break;
                case TAKEOVER_PARTITIONS_REQUEST:
                    handleTakeoverPartitons(message);
                    break;
                case TAKEOVER_METADATA_NODE_REQUEST:
                    handleTakeoverMetadataNode(message);
                    break;
                case PREPARE_PARTITIONS_FAILBACK_REQUEST:
                    handlePreparePartitionsFailback(message);
                    break;
                case COMPLETE_FAILBACK_REQUEST:
                    handleCompleteFailbackRequest(message);
                    break;
                case REPLICA_EVENT:
                    handleReplicaEvent(message);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private void handleTakeoverPartitons(IMessage message) throws Exception {
        TakeoverPartitionsRequestMessage msg = (TakeoverPartitionsRequestMessage) message;
        //if the NC is shutting down, it should ignore takeover partitions request
        if (!appContext.isShuttingdown()) {
            try {
                IRemoteRecoveryManager remoteRecoeryManager = appContext.getRemoteRecoveryManager();
                remoteRecoeryManager.takeoverPartitons(msg.getPartitions());
            } finally {
                //send response after takeover is completed
                TakeoverPartitionsResponseMessage reponse = new TakeoverPartitionsResponseMessage(msg.getRequestId(),
                        appContext.getTransactionSubsystem().getId(), msg.getPartitions());
                sendMessage(reponse, null);
            }
        }
    }

    private void handleTakeoverMetadataNode(IMessage message) throws Exception {
        try {
            appContext.initializeMetadata(false);
            appContext.exportMetadataNodeStub();
        } finally {
            TakeoverMetadataNodeResponseMessage reponse = new TakeoverMetadataNodeResponseMessage(
                    appContext.getTransactionSubsystem().getId());
            sendMessage(reponse, null);
        }
    }

    @Override
    public void reportMaxResourceId() throws Exception {
        ReportMaxResourceIdMessage maxResourceIdMsg = new ReportMaxResourceIdMessage();
        //resource ids < FIRST_AVAILABLE_USER_DATASET_ID are reserved for metadata indexes.
        long maxResourceId = Math.max(appContext.getLocalResourceRepository().getMaxResourceID(),
                MetadataIndexImmutableProperties.FIRST_AVAILABLE_USER_DATASET_ID);
        maxResourceIdMsg.setMaxResourceId(maxResourceId);
        sendMessage(maxResourceIdMsg, null);
    }

    private void handleReplicaEvent(IMessage message) {
        ReplicaEventMessage msg = (ReplicaEventMessage) message;
        Node node = new Node();
        node.setId(msg.getNodeId());
        node.setClusterIp(msg.getNodeIPAddress());
        Replica replica = new Replica(node);
        ReplicaEvent event = new ReplicaEvent(replica, msg.getEvent());
        appContext.getReplicationManager().reportReplicaEvent(event);
    }

    private void handlePreparePartitionsFailback(IMessage message) throws Exception {
        PreparePartitionsFailbackRequestMessage msg = (PreparePartitionsFailbackRequestMessage) message;
        /**
         * if the metadata partition will be failed back
         * we need to flush and close all datasets including metadata datasets
         * otherwise we need to close all non-metadata datasets and flush metadata datasets
         * so that their memory components will be copied to the failing back node
         */
        if (msg.isReleaseMetadataNode()) {
            appContext.getDatasetLifecycleManager().closeAllDatasets();
            //remove the metadata node stub from RMI registry
            appContext.unexportMetadataNodeStub();
        } else {
            //close all non-metadata datasets
            appContext.getDatasetLifecycleManager().closeUserDatasets();
            //flush the remaining metadata datasets that were not closed
            appContext.getDatasetLifecycleManager().flushAllDatasets();
        }

        //mark the partitions to be closed as inactive
        PersistentLocalResourceRepository localResourceRepo = (PersistentLocalResourceRepository) appContext
                .getLocalResourceRepository();
        for (Integer partitionId : msg.getPartitions()) {
            localResourceRepo.addInactivePartition(partitionId);
        }

        //send response after partitions prepared for failback
        PreparePartitionsFailbackResponseMessage reponse = new PreparePartitionsFailbackResponseMessage(msg.getPlanId(),
                msg.getRequestId(), msg.getPartitions());
        sendMessage(reponse, null);
    }

    private void handleCompleteFailbackRequest(IMessage message) throws Exception {
        CompleteFailbackRequestMessage msg = (CompleteFailbackRequestMessage) message;
        try {
            IRemoteRecoveryManager remoteRecoeryManager = appContext.getRemoteRecoveryManager();
            remoteRecoeryManager.completeFailbackProcess();
        } finally {
            CompleteFailbackResponseMessage reponse = new CompleteFailbackResponseMessage(msg.getPlanId(),
                    msg.getRequestId(), msg.getPartitions());
            sendMessage(reponse, null);
        }
    }
}
