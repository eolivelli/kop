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

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.message.ListOffsetsRequestData;
import org.apache.kafka.common.message.ListOffsetsResponseData;
import org.apache.kafka.common.message.OffsetCommitRequestData;
import org.apache.kafka.common.requests.CreatePartitionsRequest;
import org.apache.kafka.common.requests.FetchRequest;
import org.apache.kafka.common.requests.OffsetCommitRequest;
import org.apache.kafka.common.requests.TxnOffsetCommitRequest;
import org.jetbrains.annotations.NotNull;

public class KafkaCommonTestUtils {

    public static List<ListOffsetsRequestData.ListOffsetsTopic> newListOffsetTargetTimes(
            TopicPartition topicPartition,
            long timestamp) {
        return Collections.singletonList(new ListOffsetsRequestData.ListOffsetsTopic()
                .setName(topicPartition.topic())
                .setPartitions(Collections.singletonList(new ListOffsetsRequestData.ListOffsetsPartition()
                        .setMaxNumOffsets(100)
                        .setPartitionIndex(topicPartition.partition())
                        .setTimestamp(timestamp))));
    }

    public static FetchRequest.PartitionData newFetchRequestPartitionData(long fetchOffset,
                                                                          long logStartOffset,
                                                                          int maxBytes) {
        return new FetchRequest.PartitionData(fetchOffset,
                logStartOffset,
                maxBytes,
                Optional.empty()
        );
    }

    public static TxnOffsetCommitRequest.CommittedOffset newTxnOffsetCommitRequestCommittedOffset(
            long offset,
            String metadata) {
        return new TxnOffsetCommitRequest.CommittedOffset(offset,
                metadata,
                Optional.empty()
        );
    }

    public static OffsetCommitRequestData.OffsetCommitRequestTopic newOffsetCommitRequestPartitionData(TopicPartition tp,
                                                                                                       long offset,
                                                                                                       String metadata) {
        return new OffsetCommitRequestData.OffsetCommitRequestTopic()
                .setName(tp.topic())
                .setPartitions(Collections.singletonList(new OffsetCommitRequestData.OffsetCommitRequestPartition()
                        .setCommittedOffset(offset)
                        .setPartitionIndex(tp.partition())
                        .setCommittedMetadata(metadata)));
    }


    public static Map<String, CreatePartitionsRequest.PartitionDetails> newPartitionsMap(
                                                                            List<String> topics, int totalCount) {
        return topics.stream().collect(Collectors.toMap(topic -> topic,
                __ -> new CreatePartitionsRequest.PartitionDetails(totalCount)));
    }

    public static Map<String, CreatePartitionsRequest.PartitionDetails> newPartitionsMap(String topic, int totalCount) {
        return newPartitionsMap(Collections.singletonList(topic), totalCount);
    }

    public static ListOffsetsResponseData.ListOffsetsPartitionResponse getListOffsetsPartitionResponse(TopicPartition tp, ListOffsetsResponseData listOffsetResponse) {
        ListOffsetsResponseData.ListOffsetsPartitionResponse listOffsetsPartitionResponse = listOffsetResponse
                .topics()
                .stream()
                .filter(t -> t.name().equals(tp.topic()))
                .findFirst()
                .get()
                .partitions()
                .stream()
                .filter(p -> p.partitionIndex() == tp.partition())
                .findFirst()
                .get();
        return listOffsetsPartitionResponse;
    }
}
