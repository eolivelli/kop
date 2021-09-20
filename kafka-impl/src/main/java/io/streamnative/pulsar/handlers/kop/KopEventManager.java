/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.streamnative.pulsar.handlers.kop.coordinator.group.GroupCoordinator;
import io.streamnative.pulsar.handlers.kop.coordinator.group.GroupMetadata;
import io.streamnative.pulsar.handlers.kop.utils.KopTopic;
import io.streamnative.pulsar.handlers.kop.utils.ShutdownableThread;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.common.util.Murmur3_32Hash;
import org.apache.pulsar.metadata.api.MetadataStore;
import org.apache.pulsar.metadata.api.Notification;

@Slf4j
public class KopEventManager {
    private static final String REGEX = "^(.*)://\\[?([0-9a-zA-Z\\-%._:]*)\\]?:(-?[0-9]+)";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private static final String kopEventThreadName = "kop-event-thread";
    private final ReentrantLock putLock = new ReentrantLock();
    private static final LinkedBlockingQueue<KopEvent> queue =
            new LinkedBlockingQueue<>();
    private final KopEventThread thread =
            new KopEventThread(kopEventThreadName);
    private final GroupCoordinator coordinator;
    private final AdminManager adminManager;
    private final DeletionTopicsHandler deletionTopicsHandler;
    private final BrokersChangeHandler brokersChangeHandler;
    private final MetadataStore metadataStore;

    public KopEventManager(GroupCoordinator coordinator,
                           AdminManager adminManager,
                           MetadataStore metadataStore) {
        this.coordinator = coordinator;
        this.adminManager = adminManager;
        this.deletionTopicsHandler = new DeletionTopicsHandler(this);
        this.brokersChangeHandler = new BrokersChangeHandler(this);
        this.metadataStore = metadataStore;
    }

    public void start() {
        registerChildChangeHandler();
        thread.start();
    }

    public void close() {
        try {
            thread.initiateShutdown();
            clearAndPut(getShutdownEventThread());
            thread.awaitShutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted at shutting down {}", kopEventThreadName);
        }

    }


    public void put(KopEvent event) {
        putLock.lock();
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Error put event {} to coordinator event queue", event, e);
        } finally {
            putLock.unlock();
        }
    }

    public void clearAndPut(KopEvent event) {
        putLock.lock();
        try {
            queue.clear();
            put(event);
        } finally {
            putLock.unlock();
        }
    }

    static class KopEventThread extends ShutdownableThread {

        public KopEventThread(String name) {
            super(name);
        }

        @Override
        protected void doWork() {
            KopEvent event = null;
            try {
                event = queue.take();
                if (event instanceof ShutdownEventThread) {
                    log.info("Shutting down KopEventThread.");
                } else {
                    event.process();
                }
            } catch (InterruptedException e) {
                log.error("Error processing event {}", event, e);
            }
        }

    }

    private void registerChildChangeHandler() {
        metadataStore.registerListener(this::handleChildChangePathNotification);

        // Really register ChildChange notification.
        metadataStore.getChildren(getDeleteTopicsPath());
        // init local kop brokers cache
        getBrokers(metadataStore.getChildren(getBrokersChangePath()).join());
    }

    private void handleChildChangePathNotification(Notification notification) {
        if (notification.getPath().equals(LoadManager.LOADBALANCE_BROKERS_ROOT)) {
            this.brokersChangeHandler.handleChildChange();
        } else if (notification.getPath().equals(getDeleteTopicsPath())) {
            this.deletionTopicsHandler.handleChildChange();
        }
    }

    private void getBrokers(List<String> pulsarBrokers) {
        final Set<Node> kopBrokers = Sets.newConcurrentHashSet();
        final AtomicInteger pendingBrokers = new AtomicInteger(pulsarBrokers.size());

        pulsarBrokers.forEach(broker -> {
            metadataStore.get(getBrokersChangePath() + "/" + broker).whenComplete(
                    (brokerData, e) -> {
                        if (e != null) {
                            log.error("Get broker {} path data failed which have an error", broker, e);
                            return;
                        }

                        if (brokerData.isPresent()) {
                            JsonObject jsonObject = parseJsonObject(
                                    new String(brokerData.get().getValue(), StandardCharsets.UTF_8));
                            JsonObject protocols = jsonObject.getAsJsonObject("protocols");
                            JsonElement element = protocols.get("kafka");

                            if (element != null) {
                                String kopBrokerStr = element.getAsString();
                                Node kopNode = getNode(kopBrokerStr);
                                kopBrokers.add(kopNode);
                            } else {
                                if (log.isDebugEnabled()) {
                                    log.debug("Get broker {} path currently not a kop broker, skip it.", broker);
                                }
                            }
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug("Get broker {} path data empty.", broker);
                            }
                        }

                        if (pendingBrokers.decrementAndGet() == 0) {
                            Collection<? extends Node> oldKopBrokers = adminManager.getBrokers();
                            adminManager.setBrokers(kopBrokers);
                            log.info("Refresh kop brokers new cache {}, old brokers cache {}",
                                    adminManager.getBrokers(), oldKopBrokers);
                        }
                    }
            );
        });

    }

    private JsonObject parseJsonObject(String info) {
        JsonParser parser = new JsonParser();
        return parser.parse(info).getAsJsonObject();
    }

    @VisibleForTesting
    public static Node getNode(String kopBrokerStr) {
        final String errorMessage = "kopBrokerStr " + kopBrokerStr + " is invalid";
        final Matcher matcher = PATTERN.matcher(kopBrokerStr);
        checkState(matcher.find(), errorMessage);
        checkState(matcher.groupCount() == 3, errorMessage);
        String host = matcher.group(2);
        String port = matcher.group(3);

        return new Node(
                Murmur3_32Hash.getInstance().makeHash((host + port).getBytes(StandardCharsets.UTF_8)),
                host,
                Integer.parseInt(port));
    }


    interface KopEvent {
        void process();
    }

    class DeleteTopicsEvent implements KopEvent {

        @Override
        public void process() {
            if (!coordinator.isActive()) {
                return;
            }

            try {
                List<String> topicsDeletions = metadataStore.getChildren(getDeleteTopicsPath()).get();

                HashSet<String> topicsFullNameDeletionsSets = Sets.newHashSet();
                HashSet<KopTopic> kopTopicsSet = Sets.newHashSet();
                topicsDeletions.forEach(topic -> {
                    KopTopic kopTopic = new KopTopic(topic);
                    kopTopicsSet.add(kopTopic);
                    topicsFullNameDeletionsSets.add(kopTopic.getFullName());
                });

                log.debug("Delete topics listener fired for topics {} to be deleted", topicsDeletions);
                Iterable<GroupMetadata> groupMetadataIterable = coordinator.getGroupManager().currentGroups();
                HashSet<TopicPartition> topicPartitionsToBeDeletions = Sets.newHashSet();

                groupMetadataIterable.forEach(groupMetadata -> {
                    topicPartitionsToBeDeletions.addAll(
                            groupMetadata.collectPartitionsWithTopics(topicsFullNameDeletionsSets));
                });

                Set<String> deletedTopics = Sets.newHashSet();
                if (!topicPartitionsToBeDeletions.isEmpty()) {
                    coordinator.handleDeletedPartitions(topicPartitionsToBeDeletions);
                    Set<String> collectDeleteTopics = topicPartitionsToBeDeletions
                            .stream()
                            .map(TopicPartition::topic)
                            .collect(Collectors.toSet());

                    deletedTopics = kopTopicsSet.stream().filter(
                            kopTopic -> collectDeleteTopics.contains(kopTopic.getFullName())
                    ).map(KopTopic::getOriginalName).collect(Collectors.toSet());

                    deletedTopics.forEach(deletedTopic -> {
                        metadataStore.delete(
                                getDeleteTopicsPath() + "/" + deletedTopic, Optional.of((long) -1));
                    });
                }

                log.info("GroupMetadata delete topics {}, no matching topics {}",
                        deletedTopics, Sets.difference(topicsFullNameDeletionsSets, deletedTopics));

            } catch (ExecutionException | InterruptedException e) {
                log.error("DeleteTopicsEvent process have an error", e);
            }
        }
    }

    class BrokersChangeEvent implements KopEvent {
        @Override
        public void process() {
            metadataStore.getChildren(getBrokersChangePath()).whenComplete(
                    (brokers, e) -> {
                        if (e != null) {
                            log.error("BrokersChangeEvent process have an error", e);
                            return;
                        }
                        getBrokers(brokers);
                    });
        }
    }

    static class ShutdownEventThread implements KopEvent {

        @Override
        public void process() {
            // Here is only record shutdown KopEventThread event.
        }
    }

    public DeleteTopicsEvent getDeleteTopicEvent() {
        return new DeleteTopicsEvent();
    }

    public BrokersChangeEvent getBrokersChangeEvent() {
        return new BrokersChangeEvent();
    }

    public ShutdownEventThread getShutdownEventThread() {
        return new ShutdownEventThread();
    }

    public static String getKopPath() {
        return "/kop";
    }

    public static String getDeleteTopicsPath() {
        return getKopPath() + "/delete_topics";
    }

    public static String getBrokersChangePath() {
        return LoadManager.LOADBALANCE_BROKERS_ROOT;
    }

}
