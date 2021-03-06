/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.network.ChannelState;
import org.apache.kafka.common.network.NetworkReceive;
import org.apache.kafka.common.network.Selectable;
import org.apache.kafka.common.network.Send;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.CommonFields;
import org.apache.kafka.common.protocol.types.Struct;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.AbstractResponse;
import org.apache.kafka.common.requests.ApiVersionsRequest;
import org.apache.kafka.common.requests.ApiVersionsResponse;
import org.apache.kafka.common.requests.MetadataRequest;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.ResponseHeader;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Kafka 内核网络层，执行真正的异步网络读写，
 * 非线程安全类
 *
 * A network client for asynchronous request/response network i/o. This is an internal class used to implement the
 * user-facing producer and consumer clients.
 * <p>
 * This class is not thread-safe!
 */
public class NetworkClient implements KafkaClient {
private int i=0;
    private final Logger log;

    /** 核心 用于执行网络I/O*/
    private final Selectable selector;
    /** Metadata更新器 */
    private final MetadataUpdater metadataUpdater;

    private final Random randOffset;

    /** 每一个node的连接状态 **/
    private final ClusterConnectionStates connectionStates;

    /**
     * the set of requests currently being sent or awaiting a response
     *请求队列 保存正在发送或者还没有收到响应的请求
     */
    private final InFlightRequests inFlightRequests;

    /* the socket send buffer size in bytes */
    private final int socketSendBuffer;

    /* the socket receive size buffer in bytes */
    private final int socketReceiveBuffer;

    /* the client id used to identify this client in requests to the server */
    private final String clientId;

    /* the current correlation id to use when sending requests to servers */
    private int correlation;

    /** default timeout for individual requests to await acknowledgement from servers
     * 等待ack 的默认时间*/
    private final int defaultRequestTimeoutMs;

    /**与kafka 服务器 重试创建连接的等待时间  --> reconnect.backoff.ms  */
    private final long reconnectBackoffMs;

    private final Time time;

    /**
     * True if we should send an ApiVersionRequest when first connecting to a broker.
     */
    private final boolean discoverBrokerVersions;

    private final ApiVersions apiVersions;

    private final Map<String, ApiVersionsRequest.Builder> nodesNeedingApiVersionsFetch = new HashMap<>();

    private final List<ClientResponse> abortedSends = new LinkedList<>();

    private final Sensor throttleTimeSensor;

    public NetworkClient(Selectable selector, Metadata metadata, String clientId, int maxInFlightRequestsPerConnection,
                         long reconnectBackoffMs, long reconnectBackoffMax, int socketSendBuffer, int socketReceiveBuffer,
                         int defaultRequestTimeoutMs, Time time, boolean discoverBrokerVersions, ApiVersions apiVersions,
                         LogContext logContext) {
        this(null, metadata, selector, clientId, maxInFlightRequestsPerConnection, reconnectBackoffMs,
                reconnectBackoffMax, socketSendBuffer, socketReceiveBuffer, defaultRequestTimeoutMs, time,
                discoverBrokerVersions, apiVersions, null, logContext);
    }

    public NetworkClient(Selectable selector, Metadata metadata, String clientId, int maxInFlightRequestsPerConnection,
                         long reconnectBackoffMs, long reconnectBackoffMax, int socketSendBuffer, int socketReceiveBuffer,
                         int defaultRequestTimeoutMs, Time time, boolean discoverBrokerVersions, ApiVersions apiVersions,
                         Sensor throttleTimeSensor, LogContext logContext) {
        this(null, metadata, selector, clientId, maxInFlightRequestsPerConnection, reconnectBackoffMs,
                reconnectBackoffMax, socketSendBuffer, socketReceiveBuffer, defaultRequestTimeoutMs, time,
                discoverBrokerVersions, apiVersions, throttleTimeSensor, logContext);
    }

    public NetworkClient(Selectable selector, MetadataUpdater metadataUpdater, String clientId,
                         int maxInFlightRequestsPerConnection, long reconnectBackoffMs, long reconnectBackoffMax, int socketSendBuffer,
                         int socketReceiveBuffer, int defaultRequestTimeoutMs, Time time, boolean discoverBrokerVersions,
                         ApiVersions apiVersions, LogContext logContext) {
        this(metadataUpdater, null, selector, clientId, maxInFlightRequestsPerConnection, reconnectBackoffMs,
                reconnectBackoffMax, socketSendBuffer, socketReceiveBuffer, defaultRequestTimeoutMs, time,
                discoverBrokerVersions, apiVersions, null, logContext);
    }

    /**
     * NetworkClient 的核心构造方法
     *
     * @param metadataUpdater
     * @param metadata
     * @param selector
     * @param clientId
     * @param maxInFlightRequestsPerConnection max.in.flight.requests.per.connection
     * @param reconnectBackoffMs               reconnect.backoff.ms
     * @param reconnectBackoffMax              reconnect.backoff.max.ms
     * @param socketSendBuffer                 send.buffer.bytes
     * @param socketReceiveBuffer              receive.buffer.bytes
     * @param defaultRequestTimeoutMs          request.timeout.ms
     * @param time
     * @param discoverBrokerVersions
     * @param apiVersions
     * @param throttleTimeSensor
     * @param logContext
     */
    private NetworkClient(MetadataUpdater metadataUpdater,
                          Metadata metadata,
                          Selectable selector,
                          String clientId,
                          int maxInFlightRequestsPerConnection,
                          long reconnectBackoffMs,
                          long reconnectBackoffMax,
                          int socketSendBuffer,
                          int socketReceiveBuffer,
                          int defaultRequestTimeoutMs,
                          Time time,
                          boolean discoverBrokerVersions,
                          ApiVersions apiVersions,
                          Sensor throttleTimeSensor,
                          LogContext logContext) {
        /* It would be better if we could pass `DefaultMetadataUpdater` from the public constructor, but it's not
         * possible because `DefaultMetadataUpdater` is an inner class and it can only be instantiated after the
         * super constructor is invoked.
         */
        if (metadataUpdater == null) {
            if (metadata == null)
                throw new IllegalArgumentException("`metadata` must not be null");
            this.metadataUpdater = new DefaultMetadataUpdater(metadata);
        } else {
            this.metadataUpdater = metadataUpdater;
        }
        this.selector = selector;
        this.clientId = clientId;
        this.inFlightRequests = new InFlightRequests(maxInFlightRequestsPerConnection);
        this.connectionStates = new ClusterConnectionStates(reconnectBackoffMs, reconnectBackoffMax);
        this.socketSendBuffer = socketSendBuffer;
        this.socketReceiveBuffer = socketReceiveBuffer;
        this.correlation = 0;
        this.randOffset = new Random();
        this.defaultRequestTimeoutMs = defaultRequestTimeoutMs;
        this.reconnectBackoffMs = reconnectBackoffMs;
        this.time = time;
        this.discoverBrokerVersions = discoverBrokerVersions;
        this.apiVersions = apiVersions;
        this.throttleTimeSensor = throttleTimeSensor;
        this.log = logContext.logger(NetworkClient.class);
    }

    /**
     * Begin connecting to the given node, return true if we are already connected and ready to send to that node.
     *
     * 检测指定node是否可以发送请求
     * 如果可以发送，那么就返回true,否则就初始化一个连接，
     * @param node The node to check
     * @param now  The current timestamp
     * @return True if we are ready to send to the given node
     */
    @Override
    public boolean ready(Node node, long now) {
        System.out.println("====ready====");
        if (node.isEmpty())
            throw new IllegalArgumentException("Cannot connect to empty node " + node);

        //是否已经准备好发送请求
        if (isReady(node, now))
            return true;

        // 检测该节点现在是否能够连接
        if (connectionStates.canConnect(node.idString(), now))
            // if we are interested in sending to a node and we don't have a connection to it, initiate one
          //初始化一个连接
            initiateConnect(node, now);

        return false;
    }

    // Visible for testing
    boolean canConnect(Node node, long now) {
        return connectionStates.canConnect(node.idString(), now);
    }

    /**
     * Disconnects the connection to a particular node, if there is one. Any pending ClientRequests for this connection
     * will receive disconnections.
     *
     * 断开连接
     * @param nodeId The id of the node
     */
    @Override
    public void disconnect(String nodeId) {
        //将该节点的状态标记为 DISCONNECTED
        if (connectionStates.isDisconnected(nodeId))
            return;

        selector.close(nodeId);
        List<ApiKeys> requestTypes = new ArrayList<>();
        long now = time.milliseconds();
        for (InFlightRequest request : inFlightRequests.clearAll(nodeId)) {
            if (request.isInternalRequest) {
                if (request.header.apiKey() == ApiKeys.METADATA) {
                    metadataUpdater.handleDisconnection(request.destination);
                }
            } else {
                requestTypes.add(request.header.apiKey());
                abortedSends.add(new ClientResponse(request.header, request.callback, request.destination,
                        request.createdTimeMs, now, true, null, null, null));
            }
        }
        //将该节点的状态标记为 DISCONNECTED
        connectionStates.disconnected(nodeId, now);
        if (log.isDebugEnabled()) {
            log.debug("Manually disconnected from {}. Removed requests: {}.", nodeId, Utils.join(requestTypes, ", "));
        }
    }

    /**
     * Closes the connection to a particular node (if there is one). All requests on the connection will be cleared.
     * ClientRequest callbacks will not be invoked for the cleared requests, nor will they be returned from poll().
     *
     * @param nodeId The id of the node
     */
    @Override
    public void close(String nodeId) {
        selector.close(nodeId);
        for (InFlightRequest request : inFlightRequests.clearAll(nodeId))
            if (request.isInternalRequest && request.header.apiKey() == ApiKeys.METADATA)
                metadataUpdater.handleDisconnection(request.destination);
        connectionStates.remove(nodeId);
    }

    /**
     * Returns the number of milliseconds to wait, based on the connection state, before attempting to send data. When
     * disconnected, this respects the reconnect backoff time. When connecting or connected, this handles slow/stalled
     * connections.
     *
     * @param node The node to check
     * @param now  The current timestamp
     * @return The number of milliseconds to wait.
     */
    @Override
    public long connectionDelay(Node node, long now) {
        return connectionStates.connectionDelay(node.idString(), now);
    }

    // Return the remaining throttling delay in milliseconds if throttling is in progress. Return 0, otherwise.
    // This is for testing.
    public long throttleDelayMs(Node node, long now) {
        return connectionStates.throttleDelayMs(node.idString(), now);
    }

    /**
     * Return the poll delay in milliseconds based on both connection and throttle delay.
     *
     * @param node the connection to check
     * @param now  the current time in ms
     */
    @Override
    public long pollDelayMs(Node node, long now) {
        return connectionStates.pollDelayMs(node.idString(), now);
    }

    /**
     * Check if the connection of the node has failed, based on the connection state. Such connection failure are
     * usually transient and can be resumed in the next {@link #ready(Node, long)} } call, but there are cases where
     * transient failures needs to be caught and re-acted upon.
     *
     * @param node the node to check
     * @return true iff the connection has failed and the node is disconnected
     */
    @Override
    public boolean connectionFailed(Node node) {
        return connectionStates.isDisconnected(node.idString());
    }

    /**
     * Check if authentication to this node has failed, based on the connection state. Authentication failures are
     * propagated without any retries.
     *
     * @param node the node to check
     * @return an AuthenticationException iff authentication has failed, null otherwise
     */
    @Override
    public AuthenticationException authenticationException(Node node) {
        return connectionStates.authenticationException(node.idString());
    }

    /**
     * Check if the node with the given id is ready to send more requests.
     *
     * @param node The node
     * @param now  The current time in ms
     * @return true if the node is ready
     */
    @Override
    public boolean isReady(Node node, long now) {
        // if we need to update our metadata now declare all requests unready to make metadata requests first
        // priority
        /**是否我们现在需要更新元数据，如果需要更新数据，该方法返回false,
         *
         * !metadataUpdater.isUpdateDue(now)  现在不需要更新
         */
        // 以及该node是否已经可以发送请求
        return !metadataUpdater.isUpdateDue(now) && canSendRequest(node.idString(), now);
    }

    /**
     * Are we connected and ready and able to send more requests to the given connection?
     * 是否与指定的kafka节点已经建立好连接
     * @param node The node
     * @param now  the current timestamp
     */
    private boolean canSendRequest(String node, long now) {
    //node的状态被标记为就绪， channel is ready 并且，请求队列可以发送请求
        return connectionStates.isReady(node, now) && selector.isChannelReady(node)
                && inFlightRequests.canSendMore(node);
    }

    /**
     * Queue up the given request for sending. Requests can only be sent out to ready nodes.
     *发送request
     *
     *Kafka所有发送的东西，最终都会包装成ClientRequest
     * @param request The request
     * @param now     The current timestamp
     */
    @Override
    public void send(ClientRequest request, long now) {
        //发送request
        doSend(request, false, now);
    }

    private void sendInternalMetadataRequest(MetadataRequest.Builder builder, String nodeConnectionId, long now) {
        ClientRequest clientRequest = newClientRequest(nodeConnectionId, builder, now, true);
        doSend(clientRequest, true, now);
    }

    private void doSend(ClientRequest clientRequest, boolean isInternalRequest, long now) {
       // 获取发往的node Id
        String nodeId = clientRequest.destination();
        if (!isInternalRequest) {
            // If this request came from outside the NetworkClient, validate
            // that we can send data. If the request is internal, we trust
            // that internal code has done this validation. Validation
            // will be slightly different for some internal requests (for
            // example, ApiVersionsRequests can be sent prior to being in
            // READY state.)
            if (!canSendRequest(nodeId, now))
                throw new IllegalStateException("Attempt to send a request to node " + nodeId + " which is not ready.");
        }
        AbstractRequest.Builder<?> builder = clientRequest.requestBuilder();
        try {
            NodeApiVersions versionInfo = apiVersions.get(nodeId);
            short version;
            // Note: if versionInfo is null, we have no server version information. This would be
            // the case when sending the initial ApiVersionRequest which fetches the version
            // information itself. It is also the case when discoverBrokerVersions is set to false.
            if (versionInfo == null) {
                version = builder.latestAllowedVersion();
                if (discoverBrokerVersions && log.isTraceEnabled())
                    log.trace(
                            "No version information found when sending {} with correlation id {} to node {}. "
                                    + "Assuming version {}.",
                            clientRequest.apiKey(), clientRequest.correlationId(), nodeId, version);
            } else {
                version = versionInfo.latestUsableVersion(clientRequest.apiKey(), builder.oldestAllowedVersion(),
                        builder.latestAllowedVersion());
            }
            // The call to build may also throw UnsupportedVersionException, if there are essential
            // fields that cannot be represented in the chosen version.
            doSend(clientRequest, isInternalRequest, now, builder.build(version));
        } catch (UnsupportedVersionException unsupportedVersionException) {
            // If the version is not supported, skip sending the request over the wire.
            // Instead, simply add it to the local queue of aborted requests.
            log.debug("Version mismatch when attempting to send {} with correlation id {} to {}", builder,
                    clientRequest.correlationId(), clientRequest.destination(), unsupportedVersionException);
            ClientResponse clientResponse =
                    new ClientResponse(clientRequest.makeHeader(builder.latestAllowedVersion()), clientRequest.callback(),
                            clientRequest.destination(), now, now, false, unsupportedVersionException, null, null);
            abortedSends.add(clientResponse);
        }
    }

    private void doSend(ClientRequest clientRequest, boolean isInternalRequest, long now, AbstractRequest request) {
        //获取要发往的node的id
        String destination = clientRequest.destination();
        /**
         * 组装请求头：
         * api_key
         * api_version
         * client_id
         * correlation_id
         * 这个请求头不是消息头，类似API公参
         */
        RequestHeader header = clientRequest.makeHeader(request.version());
        if (log.isDebugEnabled()) {
            int latestClientVersion = clientRequest.apiKey().latestVersion();
            if (header.apiVersion() == latestClientVersion) {
                log.trace("Sending {} {} with correlation id {} to node {}", clientRequest.apiKey(), request,
                        clientRequest.correlationId(), destination);
            } else {
                log.debug("Using older server API v{} to send {} {} with correlation id {} to node {}",
                        header.apiVersion(), clientRequest.apiKey(), request, clientRequest.correlationId(), destination);
            }
        }

        //生成待发送的send对象
        Send send = request.toSend(destination, header);
        /**
         * 构建一个InFlightRequest请求对象，并添加到请求队列
         */
        InFlightRequest inFlightRequest =
                new InFlightRequest(clientRequest, header, isInternalRequest, request, send, now);
        //添加请求到inFlightRequests队列
        log.info(++i+"添加请求到请求队列...............");
        this.inFlightRequests.add(inFlightRequest);
        //发送请求
        selector.send(send);
    }

    /**
     * Do actual reads and writes to sockets.
     *
     * 进行网络读写
     * @param timeout The maximum amount of time to wait (in ms) for responses if there are none immediately, must be
     *                non-negative. The actual timeout will be the minimum of timeout, request timeout and metadata timeout
     * @param now     The current time in milliseconds
     * @return The list of responses received
     */
    @Override
    public List<ClientResponse> poll(long timeout, long now) {
        if (!abortedSends.isEmpty()) {
            // If there are aborted sends because of unsupported version exceptions or disconnects,
            // handle them immediately without waiting for Selector#poll.
            List<ClientResponse> responses = new ArrayList<>();
            handleAbortedSends(responses);
            completeResponses(responses);
            return responses;
        }
        /**
         * 在client 初始化的实例化的，用的new DefaultMetadataUpdater(metadata);
         *
         *metadataTimeout的超时时间，也就是metadata的下次更新时间，该方法内部会检查
         * metada是否需要更新，如果需要并且进行更新，如果连接断开，会返回重试的连接的时间
         * reconnect.backoff.ms。
         *
         *
         */
        // 更新元数据请求
        long metadataTimeout = metadataUpdater.maybeUpdate(now);
        try {
            // 执行I/O操作
            this.selector.poll(Utils.min(timeout, metadataTimeout, defaultRequestTimeoutMs));
        } catch (IOException e) {
            log.error("Unexpected error during I/O", e);
        }

        // process completed actions
        //处理完成的行为
        long updatedNow = this.time.milliseconds();
        List<ClientResponse> responses = new ArrayList<>();
        //处理完成发送
        handleCompletedSends(responses, updatedNow);
       // 循环selector中的completedReceives列表，逐个处理完成的Receive对象。
        handleCompletedReceives(responses, updatedNow);
        // 循环selector中的disconnected的Map，
        handleDisconnections(responses, updatedNow);
        /**
         *处理connect列表
         */
        handleConnections();

        handleInitiateApiVersionRequests(updatedNow);
        /**
         * 处理超时请求
         */
        handleTimedOutRequests(responses, updatedNow);

        completeResponses(responses);

        return responses;
    }

    private void completeResponses(List<ClientResponse> responses) {
        for (ClientResponse response : responses) {
            try {
                response.onComplete();
            } catch (Exception e) {
                log.error("Uncaught error in request completion:", e);
            }
        }
    }

    /**
     * Get the number of in-flight requests
     */
    @Override
    public int inFlightRequestCount() {
        return this.inFlightRequests.count();
    }

    @Override
    public boolean hasInFlightRequests() {
        return !this.inFlightRequests.isEmpty();
    }

    /**
     * Get the number of in-flight requests for a given node
     */
    @Override
    public int inFlightRequestCount(String node) {
        return this.inFlightRequests.count(node);
    }

    @Override
    public boolean hasInFlightRequests(String node) {
        return !this.inFlightRequests.isEmpty(node);
    }

    @Override
    public boolean hasReadyNodes(long now) {
        return connectionStates.hasReadyNodes(now);
    }

    /**
     * Interrupt the client if it is blocked waiting on I/O.
     */
    @Override
    public void wakeup() {
        this.selector.wakeup();
    }

    /**
     * Close the network client
     */
    @Override
    public void close() {
        this.selector.close();
        this.metadataUpdater.close();
    }

    /**
     * Choose the node with the fewest outstanding requests which is at least eligible for connection. This method will
     * prefer a node with an existing connection, but will potentially choose a node for which we don't yet have a
     * connection if all existing connections are in use. This method will never choose a node for which there is no
     * existing connection and from which we have disconnected within the reconnect backoff period.
     *
     * @return The node with the fewest in-flight requests.
     */
    @Override
    public Node leastLoadedNode(long now) {
        /**
         * 获取所有的节点
         */
        List<Node> nodes = this.metadataUpdater.fetchNodes();
        int inflight = Integer.MAX_VALUE;
        Node found = null;

        int offset = this.randOffset.nextInt(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            int idx = (offset + i) % nodes.size();
            Node node = nodes.get(idx);
            int currInflight = this.inFlightRequests.count(node.idString());
            if (currInflight == 0 && isReady(node, now)) {
                // if we find an established connection with no in-flight requests we can stop right away
                log.trace("Found least loaded node {} connected with no in-flight requests", node);
                return node;
            } else if (!this.connectionStates.isBlackedOut(node.idString(), now) && currInflight < inflight) {
                // otherwise if this is the best we have found so far, record that
                inflight = currInflight;
                found = node;
            } else if (log.isTraceEnabled()) {
                log.trace(
                        "Removing node {} from least loaded node selection: is-blacked-out: {}, in-flight-requests: {}",
                        node, this.connectionStates.isBlackedOut(node.idString(), now), currInflight);
            }
        }

        if (found != null)
            log.trace("Found least loaded node {}", found);
        else
            log.trace("Least loaded node selection failed to find an available node");

        return found;
    }

    public static AbstractResponse parseResponse(ByteBuffer responseBuffer, RequestHeader requestHeader) {
        Struct responseStruct = parseStructMaybeUpdateThrottleTimeMetrics(responseBuffer, requestHeader, null, 0);
        return AbstractResponse.parseResponse(requestHeader.apiKey(), responseStruct);
    }

    private static Struct parseStructMaybeUpdateThrottleTimeMetrics(ByteBuffer responseBuffer,
                                                                    RequestHeader requestHeader, Sensor throttleTimeSensor, long now) {
        ResponseHeader responseHeader = ResponseHeader.parse(responseBuffer);
        // Always expect the response version id to be the same as the request version id
        Struct responseBody = requestHeader.apiKey().parseResponse(requestHeader.apiVersion(), responseBuffer);
        correlate(requestHeader, responseHeader);
        if (throttleTimeSensor != null && responseBody.hasField(CommonFields.THROTTLE_TIME_MS))
            throttleTimeSensor.record(responseBody.get(CommonFields.THROTTLE_TIME_MS), now);
        return responseBody;
    }

    /**
     * Post process disconnection of a node
     *
     * @param responses The list of responses to update
     * @param nodeId    Id of the node to be disconnected
     * @param now       The current time
     */
    private void processDisconnection(List<ClientResponse> responses, String nodeId, long now,
                                      ChannelState disconnectState) {
        connectionStates.disconnected(nodeId, now);
        apiVersions.remove(nodeId);
        nodesNeedingApiVersionsFetch.remove(nodeId);
        switch (disconnectState.state()) {
            case AUTHENTICATION_FAILED:
                AuthenticationException exception = disconnectState.exception();
                connectionStates.authenticationFailed(nodeId, now, exception);
                metadataUpdater.handleAuthenticationFailure(exception);
                log.error("Connection to node {} failed authentication due to: {}", nodeId, exception.getMessage());
                break;
            case AUTHENTICATE:
                // This warning applies to older brokers which don't provide feedback on authentication failures
                log.warn("Connection to node {} terminated during authentication. This may indicate "
                        + "that authentication failed due to invalid credentials.", nodeId);
                break;
            case NOT_CONNECTED:
                log.warn("Connection to node {} could not be established. Broker may not be available.", nodeId);
                break;
            default:
                break; // Disconnections in other states are logged at debug level in Selector
        }
        for (InFlightRequest request : this.inFlightRequests.clearAll(nodeId)) {
            log.trace("Cancelled request {} {} with correlation id {} due to node {} being disconnected",
                    request.header.apiKey(), request.request, request.header.correlationId(), nodeId);
            if (!request.isInternalRequest)
                responses.add(request.disconnected(now, disconnectState.exception()));
            else if (request.header.apiKey() == ApiKeys.METADATA)
                metadataUpdater.handleDisconnection(request.destination);
        }
    }

    /**
     * Iterate over all the inflight requests and expire any requests that have exceeded the configured requestTimeout.
     * The connection to the node associated with the request will be terminated and will be treated as a disconnection.
     *
     * @param responses The list of responses to update
     * @param now       The current time
     */
    private void handleTimedOutRequests(List<ClientResponse> responses, long now) {
        List<String> nodeIds = this.inFlightRequests.nodesWithTimedOutRequests(now);
        for (String nodeId : nodeIds) {
            // close connection to the node
            this.selector.close(nodeId);
            log.debug("Disconnecting from node {} due to request timeout.", nodeId);
            processDisconnection(responses, nodeId, now, ChannelState.LOCAL_CLOSE);
        }

        // we disconnected, so we should probably refresh our metadata
        if (!nodeIds.isEmpty())
            metadataUpdater.requestUpdate();
    }

    private void handleAbortedSends(List<ClientResponse> responses) {
        responses.addAll(abortedSends);
        abortedSends.clear();
    }

    /**
     * Handle any completed request send. In particular if no response is expected consider the request complete.
     *
     * @param responses The list of responses to update
     * @param now       The current time
     */
    private void handleCompletedSends(List<ClientResponse> responses, long now) {
        // if no response is expected then when the send is completed, return it
       //获取上一次调用poll完成的发送列表，进行遍历每一个send数据
        for (Send send : this.selector.completedSends()) {
            //从请求队列中获取最后一个请求
            InFlightRequest request = this.inFlightRequests.lastSent(send.destination());
           //不需要响应
            if (!request.expectResponse) {
                this.inFlightRequests.completeLastSent(send.destination());
                responses.add(request.completed(null, now));
            }
        }
    }

    /**
     * If a response from a node includes a non-zero throttle delay and client-side throttling has been enabled for the
     * connection to the node, throttle the connection for the specified delay.
     *
     * @param response   the response
     * @param apiVersion the API version of the response
     * @param nodeId     the id of the node
     * @param now        The current time
     */
    private void maybeThrottle(AbstractResponse response, short apiVersion, String nodeId, long now) {
        int throttleTimeMs = response.throttleTimeMs();
        if (throttleTimeMs > 0 && response.shouldClientThrottle(apiVersion)) {
            connectionStates.throttle(nodeId, now + throttleTimeMs);
            log.trace("Connection to node {} is throttled for {} ms until timestamp {}", nodeId, throttleTimeMs,
                    now + throttleTimeMs);
        }
    }

    /**
     * Handle any completed receives and update the response list with the responses received.
     *
     * @param responses The list of responses to update
     * @param now       The current time
     */
    private void handleCompletedReceives(List<ClientResponse> responses, long now) {
        //遍历上一次poll完成的接收列表
        for (NetworkReceive receive : this.selector.completedReceives()) {
            String source = receive.source();
            //从请求队列中移除请求
            InFlightRequest req = inFlightRequests.completeNext(source);
            Struct responseStruct =
                    parseStructMaybeUpdateThrottleTimeMetrics(receive.payload(), req.header, throttleTimeSensor, now);
            if (log.isTraceEnabled()) {
                log.trace("Completed receive from node {} for {} with correlation id {}, received {}", req.destination,
                        req.header.apiKey(), req.header.correlationId(), responseStruct);
            }
            // If the received response includes a throttle delay, throttle the connection.
            AbstractResponse body = AbstractResponse.parseResponse(req.header.apiKey(), responseStruct);
            maybeThrottle(body, req.header.apiVersion(), req.destination, now);
            if (req.isInternalRequest && body instanceof MetadataResponse)
                metadataUpdater.handleCompletedMetadataResponse(req.header, now, (MetadataResponse) body);
            else if (req.isInternalRequest && body instanceof ApiVersionsResponse)
                handleApiVersionsResponse(responses, req, now, (ApiVersionsResponse) body);
            else
                responses.add(req.completed(body, now));
        }
    }

    private void handleApiVersionsResponse(List<ClientResponse> responses, InFlightRequest req, long now,
                                           ApiVersionsResponse apiVersionsResponse) {
        final String node = req.destination;
        if (apiVersionsResponse.error() != Errors.NONE) {
            if (req.request.version() == 0 || apiVersionsResponse.error() != Errors.UNSUPPORTED_VERSION) {
                log.warn(
                        "Received error {} from node {} when making an ApiVersionsRequest with correlation id {}. Disconnecting.",
                        apiVersionsResponse.error(), node, req.header.correlationId());
                this.selector.close(node);
                processDisconnection(responses, node, now, ChannelState.LOCAL_CLOSE);
            } else {
                nodesNeedingApiVersionsFetch.put(node, new ApiVersionsRequest.Builder((short) 0));
            }
            return;
        }
        NodeApiVersions nodeVersionInfo = new NodeApiVersions(apiVersionsResponse.apiVersions());
        apiVersions.update(node, nodeVersionInfo);
        this.connectionStates.ready(node);
        log.debug("Recorded API versions for node {}: {}", node, nodeVersionInfo);
    }

    /**
     * Handle any disconnected connections
     *
     * @param responses The list of responses that completed with the disconnection
     * @param now       The current time
     */
    private void handleDisconnections(List<ClientResponse> responses, long now) {
        for (Map.Entry<String, ChannelState> entry : this.selector.disconnected().entrySet()) {
            String node = entry.getKey();
            log.debug("Node {} disconnected.", node);
            processDisconnection(responses, node, now, entry.getValue());
        }
        // we got a disconnect so we should probably refresh our metadata and see if that broker is dead
        if (this.selector.disconnected().size() > 0)
            metadataUpdater.requestUpdate();
    }

    /**
     * Record any newly completed connections
     */
    private void handleConnections() {
        for (String node : this.selector.connected()) {
            // We are now connected. Node that we might not still be able to send requests. For instance,
            // if SSL is enabled, the SSL handshake happens after the connection is established.
            // Therefore, it is still necessary to check isChannelReady before attempting to send on this
            // connection.
            if (discoverBrokerVersions) {
                this.connectionStates.checkingApiVersions(node);
                nodesNeedingApiVersionsFetch.put(node, new ApiVersionsRequest.Builder());
                log.debug("Completed connection to node {}. Fetching API versions.", node);
            } else {
                this.connectionStates.ready(node);
                log.debug("Completed connection to node {}. Ready.", node);
            }
        }
    }

    private void handleInitiateApiVersionRequests(long now) {
        Iterator<Map.Entry<String, ApiVersionsRequest.Builder>> iter =
                nodesNeedingApiVersionsFetch.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, ApiVersionsRequest.Builder> entry = iter.next();
            String node = entry.getKey();
            if (selector.isChannelReady(node) && inFlightRequests.canSendMore(node)) {
                log.debug("Initiating API versions fetch from node {}.", node);
                ApiVersionsRequest.Builder apiVersionRequestBuilder = entry.getValue();
                ClientRequest clientRequest = newClientRequest(node, apiVersionRequestBuilder, now, true);
                doSend(clientRequest, true, now);
                iter.remove();
            }
        }
    }

    /**
     * Validate that the response corresponds to the request we expect or else explode
     */
    private static void correlate(RequestHeader requestHeader, ResponseHeader responseHeader) {
        if (requestHeader.correlationId() != responseHeader.correlationId())
            throw new IllegalStateException("Correlation id for response (" + responseHeader.correlationId()
                    + ") does not match request (" + requestHeader.correlationId() + "), request header: " + requestHeader);
    }

    /**
     * Initiate a connection to the given node
     * 对指定的node 初始化连接
     */
    private void initiateConnect(Node node, long now) {
        System.out.println("initiateConnect===");
        //获取nodeId
        String nodeConnectionId = node.idString();
        try {
            log.debug("Initiating connection to node {}", node);
            //设置连接状态为正在建立连接
            this.connectionStates.connecting(nodeConnectionId, now);
            //建立连接
            selector.connect(nodeConnectionId, new InetSocketAddress(node.host(), node.port()), this.socketSendBuffer,
                    this.socketReceiveBuffer);
        } catch (IOException e) {
            /* attempt failed, we'll try again after the backoff */
            //设置连接状态为断开连接
            connectionStates.disconnected(nodeConnectionId, now);
            /* maybe the problem is our metadata, update it */
            metadataUpdater.requestUpdate();
            log.warn("Error connecting to node {}", node, e);
        }
    }

    class DefaultMetadataUpdater implements MetadataUpdater {

        /* the current cluster metadata */
        private final Metadata metadata;

        /**
         * true iff there is a metadata request that has been sent and for which we have not yet received a response
         *
         * 更新metadata正在进行中，元数据更新是否结束的一个标志
         *
         * 当发送更新metadata的请求已经发出去，处于等待kafka的集群响应则为true.
         */
        private boolean metadataFetchInProgress;

        DefaultMetadataUpdater(Metadata metadata) {
            this.metadata = metadata;
            this.metadataFetchInProgress = false;
        }

        @Override
        public List<Node> fetchNodes() {
            return metadata.fetch().nodes();
        }

        @Override
        public boolean isUpdateDue(long now) {
            //没有处于正在更新的过程中，metadata应该更新
            return !this.metadataFetchInProgress && this.metadata.timeToNextUpdate(now) == 0;
        }

        @Override
        /**
         * 更新倒计时，见接口方法注释
         *
         * 现依据更新策略进行检查是否需要更新 如果有正在更新的请求，
         * 那么倒计时就是 request.timeout.ms ,否则进行更新操作
         * 
         */
        public long maybeUpdate(long now) {
            // should we update our metadata?
            // 是否更新
            long timeToNextMetadataUpdate = metadata.timeToNextUpdate(now);
            // 是否有正在更新的操作
            long waitForMetadataFetch = this.metadataFetchInProgress ? defaultRequestTimeoutMs : 0;

            long metadataTimeout = Math.max(timeToNextMetadataUpdate, waitForMetadataFetch);

            if (metadataTimeout > 0) {
                return metadataTimeout;
            }
            //
            // Beware that the behavior of this method and the computation of timeouts for poll() are
            // highly dependent on the behavior of leastLoadedNode.
            /**
             * 选择一个可用节点，如果没有可用节点，则返回reconnectBackoffMs，重连的时间
             */
            Node node = leastLoadedNode(now);
            if (node == null) {
                log.debug("Give up sending metadata request since no node is available");
                return reconnectBackoffMs;
            }
            // 返回更新操作
            return maybeUpdate(now, node);
        }

        @Override
        public void handleDisconnection(String destination) {
            Cluster cluster = metadata.fetch();
            // 'processDisconnection' generates warnings for misconfigured bootstrap server configuration
            // resulting in 'Connection Refused' and misconfigured security resulting in authentication failures.
            // The warning below handles the case where connection to a broker was established, but was disconnected
            // before metadata could be obtained.
            if (cluster.isBootstrapConfigured()) {
                int nodeId = Integer.parseInt(destination);
                Node node = cluster.nodeById(nodeId);
                if (node != null)
                    log.warn("Bootstrap broker {} disconnected", node);
            }

            metadataFetchInProgress = false;
        }

        @Override
        public void handleAuthenticationFailure(AuthenticationException exception) {
            metadataFetchInProgress = false;
            if (metadata.updateRequested())
                metadata.failedUpdate(time.milliseconds(), exception);
        }

        @Override
        public void handleCompletedMetadataResponse(RequestHeader requestHeader, long now, MetadataResponse response) {
            this.metadataFetchInProgress = false;
            Cluster cluster = response.cluster();

            // If any partition has leader with missing listeners, log a few for diagnosing broker configuration
            // issues. This could be a transient issue if listeners were added dynamically to brokers.
            List<TopicPartition> missingListenerPartitions = response.topicMetadata().stream()
                    .flatMap(topicMetadata -> topicMetadata.partitionMetadata().stream()
                            .filter(partitionMetadata -> partitionMetadata.error() == Errors.LISTENER_NOT_FOUND)
                            .map(partitionMetadata -> new TopicPartition(topicMetadata.topic(), partitionMetadata.partition())))
                    .collect(Collectors.toList());
            if (!missingListenerPartitions.isEmpty()) {
                int count = missingListenerPartitions.size();
                log.warn("{} partitions have leader brokers without a matching listener, including {}", count,
                        missingListenerPartitions.subList(0, Math.min(10, count)));
            }

            // check if any topics metadata failed to get updated
            Map<String, Errors> errors = response.errors();
            if (!errors.isEmpty())
                log.warn("Error while fetching metadata with correlation id {} : {}", requestHeader.correlationId(),
                        errors);

            // don't update the cluster if there are no valid nodes...the topic we want may still be in the process of
            // being
            // created which means we will get errors and no nodes until it exists
            if (cluster.nodes().size() > 0) {
                this.metadata.update(cluster, response.unavailableTopics(), now);
            } else {
                log.trace("Ignoring empty metadata response with correlation id {}.", requestHeader.correlationId());
                this.metadata.failedUpdate(now, null);
            }
        }

        @Override
        public void requestUpdate() {
            this.metadata.requestUpdate();
        }

        @Override
        public void close() {
            this.metadata.close();
        }

        /**
         * Return true if there's at least one connection establishment is currently underway
         */
        private boolean isAnyNodeConnecting() {
            for (Node node : fetchNodes()) {
                if (connectionStates.isConnecting(node.idString())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Add a metadata request to the list of sends if we can make one
         */
        private long maybeUpdate(long now, Node node) {
            String nodeConnectionId = node.idString();
            // 可发送数据
            if (canSendRequest(nodeConnectionId, now)) {
                // 更新正在进行
                this.metadataFetchInProgress = true;
                MetadataRequest.Builder metadataRequest;
                // 是否需要更新所有的topic
                if (metadata.needMetadataForAllTopics())
                    metadataRequest = MetadataRequest.Builder.allTopics();
                else
                    metadataRequest = new MetadataRequest.Builder(new ArrayList<>(metadata.topics()),
                        metadata.allowAutoTopicCreation());

                log.debug("Sending metadata request {} to node {}", metadataRequest, node);
                // 发送更新请求
                sendInternalMetadataRequest(metadataRequest, nodeConnectionId, now);
                return defaultRequestTimeoutMs;
            }

            // If there's any connection establishment underway, wait until it completes. This prevents
            // the client from unnecessarily connecting to additional nodes while a previous connection
            // attempt has not been completed.
            /**
             * 是否存在node正在建立连接
             * 遍历所有的metada中cluster的node的状态
             * 是connectting
             */
            if (isAnyNodeConnecting()) {
                // Strictly the timeout we should return here is "connect timeout", but as we don't
                // have such application level configuration, using reconnect backoff instead.
                /***
                 *返回重试时间。严格的来讲 这里应该返回超时时间，但是kafka中并未提供连接超时的配置
                 * 所以使用重试时间替代
                 */
                return reconnectBackoffMs;
            }
            // 可连接
            if (connectionStates.canConnect(nodeConnectionId, now)) {
                // we don't have a connection to this node right now, make one
                log.debug("Initialize connection to node {} for sending metadata request", node);
                // 初始化连接
                initiateConnect(node, now);
                /**
                 * 返回的是重试连接，因为这里初始化连接可能不会成功，
                 */
                return reconnectBackoffMs;
            }

            // connected, but can't send more OR connecting
            // In either case, we just need to wait for a network event to let us know the selected
            // connection might be usable again.
            return Long.MAX_VALUE;
        }

    }

    @Override
    public ClientRequest newClientRequest(String nodeId, AbstractRequest.Builder<?> requestBuilder, long createdTimeMs,
                                          boolean expectResponse) {
        return newClientRequest(nodeId, requestBuilder, createdTimeMs, expectResponse, defaultRequestTimeoutMs, null);
    }

    @Override
    public ClientRequest newClientRequest(String nodeId, AbstractRequest.Builder<?> requestBuilder, long createdTimeMs,
                                          boolean expectResponse, int requestTimeoutMs, RequestCompletionHandler callback) {
        return new ClientRequest(nodeId, requestBuilder, correlation++, clientId, createdTimeMs, expectResponse,
                defaultRequestTimeoutMs, callback);
    }

    public boolean discoverBrokerVersions() {
        return discoverBrokerVersions;
    }

    /**
     * 在发送途中的请求
     * 包括：
     * 1. 正在发送的请求
     * 2. 已经发送的但还没有接收到response的请求;
     */
    static class InFlightRequest {
        final RequestHeader header;
        final String destination;
        final RequestCompletionHandler callback;
        final boolean expectResponse;
        final AbstractRequest request;
        final boolean isInternalRequest; // used to flag requests which are initiated internally by NetworkClient
        final Send send;
        final long sendTimeMs;
        final long createdTimeMs;
        final long requestTimeoutMs;

        public InFlightRequest(ClientRequest clientRequest, RequestHeader header, boolean isInternalRequest,
                               AbstractRequest request, Send send, long sendTimeMs) {
            this(header, clientRequest.requestTimeoutMs(), clientRequest.createdTimeMs(), clientRequest.destination(),
                    clientRequest.callback(), clientRequest.expectResponse(), isInternalRequest, request, send, sendTimeMs);
        }

        public InFlightRequest(RequestHeader header, int requestTimeoutMs, long createdTimeMs, String destination,
                               RequestCompletionHandler callback, boolean expectResponse, boolean isInternalRequest,
                               AbstractRequest request, Send send, long sendTimeMs) {
            this.header = header;
            this.requestTimeoutMs = requestTimeoutMs;
            this.createdTimeMs = createdTimeMs;
            this.destination = destination;
            this.callback = callback;
            this.expectResponse = expectResponse;
            this.isInternalRequest = isInternalRequest;
            this.request = request;
            this.send = send;
            this.sendTimeMs = sendTimeMs;
        }

        public ClientResponse completed(AbstractResponse response, long timeMs) {
            return new ClientResponse(header, callback, destination, createdTimeMs, timeMs, false, null, null,
                    response);
        }

        public ClientResponse disconnected(long timeMs, AuthenticationException authenticationException) {
            return new ClientResponse(header, callback, destination, createdTimeMs, timeMs, true, null,
                    authenticationException, null);
        }

        @Override
        public String toString() {
            return "InFlightRequest(header=" + header + ", destination=" + destination + ", expectResponse="
                    + expectResponse + ", createdTimeMs=" + createdTimeMs + ", sendTimeMs=" + sendTimeMs
                    + ", isInternalRequest=" + isInternalRequest + ", request=" + request + ", callback=" + callback
                    + ", send=" + send + ")";
        }
    }

}
