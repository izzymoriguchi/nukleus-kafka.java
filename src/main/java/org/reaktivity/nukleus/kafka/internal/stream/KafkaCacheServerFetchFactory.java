/**
 * Copyright 2016-2019 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.kafka.internal.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.ToIntFunction;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessageFunction;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.kafka.internal.KafkaConfiguration;
import org.reaktivity.nukleus.kafka.internal.KafkaNukleus;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheBrokerWriter;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheClusterWriter;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCachePartitionWriter;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheTopicWriter;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheWriter;
import org.reaktivity.nukleus.kafka.internal.types.ArrayFW;
import org.reaktivity.nukleus.kafka.internal.types.KafkaHeaderFW;
import org.reaktivity.nukleus.kafka.internal.types.KafkaKeyFW;
import org.reaktivity.nukleus.kafka.internal.types.KafkaOffsetFW;
import org.reaktivity.nukleus.kafka.internal.types.OctetsFW;
import org.reaktivity.nukleus.kafka.internal.types.String16FW;
import org.reaktivity.nukleus.kafka.internal.types.cache.KafkaCacheBeginExFW;
import org.reaktivity.nukleus.kafka.internal.types.cache.KafkaCacheFlushExFW;
import org.reaktivity.nukleus.kafka.internal.types.control.KafkaRouteExFW;
import org.reaktivity.nukleus.kafka.internal.types.control.RouteFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.DataFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.EndFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.ExtensionFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.FlushFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaBeginExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaDataExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaFetchBeginExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaFetchDataExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;

public final class KafkaCacheServerFetchFactory implements StreamFactory
{
    private static final Consumer<OctetsFW.Builder> EMPTY_EXTENSION = ex -> {};

    private static final int FLAGS_INIT = 0x02;
    private static final int FLAGS_FIN = 0x01;

    private final RouteFW routeRO = new RouteFW();
    private final KafkaRouteExFW routeExRO = new KafkaRouteExFW();

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final AbortFW abortRO = new AbortFW();
    private final ResetFW resetRO = new ResetFW();
    private final WindowFW windowRO = new WindowFW();
    private final ExtensionFW extensionRO = new ExtensionFW();
    private final KafkaBeginExFW kafkaBeginExRO = new KafkaBeginExFW();
    private final KafkaDataExFW kafkaDataExRO = new KafkaDataExFW();
    private final KafkaCacheBeginExFW kafkaCacheBeginExRO = new KafkaCacheBeginExFW();

    private final BeginFW.Builder beginRW = new BeginFW.Builder();
    private final FlushFW.Builder flushRW = new FlushFW.Builder();
    private final EndFW.Builder endRW = new EndFW.Builder();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();
    private final WindowFW.Builder windowRW = new WindowFW.Builder();
    private final KafkaBeginExFW.Builder kafkaBeginExRW = new KafkaBeginExFW.Builder();
    private final KafkaCacheBeginExFW.Builder kafkaCacheBeginExRW = new KafkaCacheBeginExFW.Builder();
    private final KafkaCacheFlushExFW.Builder kafkaCacheFlushExRW = new KafkaCacheFlushExFW.Builder();

    private final MessageFunction<RouteFW> wrapRoute = (t, b, i, l) -> routeRO.wrap(b, i, i + l);

    private final int kafkaTypeId;
    private final int kafkaCacheTypeId;
    private final KafkaCacheWriter cache;
    private final RouteManager router;
    private final MutableDirectBuffer writeBuffer;
    private final BufferPool bufferPool;
    private final LongUnaryOperator supplyInitialId;
    private final LongUnaryOperator supplyReplyId;
    private final LongFunction<KafkaCacheRoute> supplyCacheRoute;
    private final Long2ObjectHashMap<MessageConsumer> correlations;

    public KafkaCacheServerFetchFactory(
        KafkaConfiguration config,
        KafkaCacheWriter cache,
        RouteManager router,
        MutableDirectBuffer writeBuffer,
        BufferPool bufferPool,
        LongUnaryOperator supplyInitialId,
        LongUnaryOperator supplyReplyId,
        LongSupplier supplyTraceId,
        ToIntFunction<String> supplyTypeId,
        LongFunction<KafkaCacheRoute> supplyCacheRoute,
        Long2ObjectHashMap<MessageConsumer> correlations)
    {
        this.kafkaTypeId = supplyTypeId.applyAsInt(KafkaNukleus.NAME);
        this.kafkaCacheTypeId = supplyTypeId.applyAsInt(KafkaCacheWriter.TYPE_NAME);
        this.cache = cache;
        this.router = router;
        this.writeBuffer = writeBuffer;
        this.bufferPool = bufferPool;
        this.supplyInitialId = supplyInitialId;
        this.supplyReplyId = supplyReplyId;
        this.supplyCacheRoute = supplyCacheRoute;
        this.correlations = correlations;
    }

    @Override
    public MessageConsumer newStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer sender)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long routeId = begin.routeId();
        final long initialId = begin.streamId();
        final long affinity = begin.affinity();
        final long authorization = begin.authorization();

        assert (initialId & 0x0000_0000_0000_0001L) != 0L;

        final OctetsFW extension = begin.extension();
        final ExtensionFW beginEx = extension.get(extensionRO::wrap);
        assert beginEx != null && beginEx.typeId() == kafkaCacheTypeId;
        final KafkaCacheBeginExFW kafkaCacheBeginEx = extension.get(kafkaCacheBeginExRO::wrap);

        final String16FW beginTopic = kafkaCacheBeginEx.topic();
        final int partitionId = kafkaCacheBeginEx.partitionId();
        final long progressOffset = kafkaCacheBeginEx.progressOffset();

        MessageConsumer newStream = null;

        final MessagePredicate filter = (t, b, i, l) ->
        {
            final RouteFW route = wrapRoute.apply(t, b, i, l);
            final KafkaRouteExFW routeEx = route.extension().get(routeExRO::tryWrap);
            final String16FW routeTopic = routeEx != null ? routeEx.topic() : null;
            return routeTopic != null && Objects.equals(routeTopic, beginTopic);
        };

        final RouteFW route = router.resolve(routeId, authorization, filter, wrapRoute);
        if (route != null)
        {
            final String topicName = beginTopic.asString();
            final long resolvedId = route.correlationId();
            final KafkaCacheRoute cacheRoute = supplyCacheRoute.apply(resolvedId);
            final long partitionKey = cacheRoute.topicPartitionKey(topicName, partitionId);
            KafkaCacheServerFetchFanout fanout = cacheRoute.serverFetchFanoutsByTopicPartition.get(partitionKey);
            if (fanout == null)
            {
                final String clusterName = route.localAddress().asString();
                final KafkaCacheClusterWriter cluster = cache.supplyCluster(clusterName);
                final KafkaCacheBrokerWriter broker = cluster.supplyBroker(affinity);
                final KafkaCacheTopicWriter topic = broker.supplyTopic(topicName);
                final KafkaCachePartitionWriter partition = topic.supplyPartition(partitionId);
                final KafkaCacheServerFetchFanout newFanout =
                        new KafkaCacheServerFetchFanout(resolvedId, authorization, topic, partition);

                cacheRoute.serverFetchFanoutsByTopicPartition.put(partitionKey, newFanout);
                fanout = newFanout;
            }

            if (fanout != null)
            {
                newStream = new KafkaCacheServerFetchStream(
                        fanout,
                        sender,
                        routeId,
                        initialId,
                        affinity,
                        authorization,
                        progressOffset)::onServerMessage;
            }
        }

        return newStream;
    }

    private void doBegin(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long affinity,
        Consumer<OctetsFW.Builder> extension)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .affinity(affinity)
                .extension(extension)
                .build();

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    private void doFlush(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        int reserved,
        Consumer<OctetsFW.Builder> extension)
    {
        final FlushFW flush = flushRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .reserved(reserved)
                .extension(extension)
                .build();

        receiver.accept(flush.typeId(), flush.buffer(), flush.offset(), flush.sizeof());
    }

    private void doEnd(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        Consumer<OctetsFW.Builder> extension)
    {
        final EndFW end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                               .routeId(routeId)
                               .streamId(streamId)
                               .traceId(traceId)
                               .authorization(authorization)
                               .extension(extension)
                               .build();

        receiver.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof());
    }

    private void doAbort(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        Consumer<OctetsFW.Builder> extension)
    {
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .extension(extension)
                .build();

        receiver.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doWindow(
        MessageConsumer sender,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long budgetId,
        int credit,
        int padding)
    {
        final WindowFW window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .credit(credit)
                .padding(padding)
                .build();

        sender.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof());
    }

    private void doReset(
        MessageConsumer sender,
        long routeId,
        long streamId,
        long traceId,
        long authorization)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
               .routeId(routeId)
               .streamId(streamId)
               .traceId(traceId)
               .authorization(authorization)
               .build();

        sender.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }

    final class KafkaCacheServerFetchFanout
    {
        private final long routeId;
        private final long authorization;
        private final KafkaCacheTopicWriter topic;
        private final KafkaCachePartitionWriter partition;
        private final List<KafkaCacheServerFetchStream> members;

        private long initialId;
        private long replyId;
        private MessageConsumer receiver;

        private int state;

        private long progressOffset;

        private KafkaCacheServerFetchFanout(
            long routeId,
            long authorization,
            KafkaCacheTopicWriter topic,
            KafkaCachePartitionWriter partition)
        {
            this.routeId = routeId;
            this.authorization = authorization;
            this.topic = topic;
            this.partition = partition;
            this.progressOffset = partition.progressOffset();
            this.members = new ArrayList<>();
        }

        private void onServerFanoutMemberOpening(
            long traceId,
            KafkaCacheServerFetchStream member)
        {
            members.add(member);

            assert !members.isEmpty();

            doServerFanoutInitialBeginIfNecessary(traceId);

            if (KafkaState.initialOpened(state))
            {
                member.doServerInitialWindowIfNecessary(traceId, 0L, 0, 0);
            }

            if (KafkaState.replyOpened(state))
            {
                member.doServerReplyBeginIfNecessary(traceId);
            }
        }

        private void onServerFanoutMemberClosed(
            long traceId,
            KafkaCacheServerFetchStream member)
        {
            members.remove(member);

            if (members.isEmpty())
            {
                correlations.remove(replyId);
                doServerFanoutInitialEndIfNecessary(traceId);
            }
        }

        private void doServerFanoutInitialBeginIfNecessary(
            long traceId)
        {
            if (KafkaState.initialClosed(state) &&
                KafkaState.replyClosed(state))
            {
                state = 0;
            }

            if (!KafkaState.initialOpening(state))
            {
                doServerFanoutInitialBegin(traceId);
            }
        }

        private void doServerFanoutInitialBegin(
            long traceId)
        {
            assert state == 0;

            this.initialId = supplyInitialId.applyAsLong(routeId);
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.receiver = router.supplyReceiver(initialId);

            correlations.put(replyId, this::onServerFanoutMessage);
            router.setThrottle(initialId, this::onServerFanoutMessage);
            doBegin(receiver, routeId, initialId, traceId, authorization, 0L,
                ex -> ex.set((b, o, l) -> kafkaBeginExRW.wrap(b, o, l)
                        .typeId(kafkaTypeId)
                        .fetch(f -> f.topic(topic.name())
                                     .progressItem(pi -> pi.partitionId(partition.id())
                                                           .offset$(progressOffset)))
                        .build()
                        .sizeof()));
            state = KafkaState.openingInitial(state);
        }

        private void doServerFanoutInitialEndIfNecessary(
            long traceId)
        {
            if (!KafkaState.initialClosed(state))
            {
                doServerFanoutInitialEnd(traceId);
            }
        }

        private void doServerFanoutInitialEnd(
            long traceId)
        {
            doEnd(receiver, routeId, initialId, traceId, authorization, EMPTY_EXTENSION);

            state = KafkaState.closedInitial(state);
        }

        private void onServerFanoutMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onServerFanoutReplyBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onServerFanoutReplyData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onServerFanoutReplyEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onServerFanoutReplyAbort(abort);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onServerFanoutInitialReset(reset);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onServerFanoutInitialWindow(window);
                break;
            default:
                break;
            }
        }

        private void onServerFanoutReplyBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();
            final OctetsFW extension = begin.extension();
            final ExtensionFW beginEx = extension.get(extensionRO::tryWrap);
            assert beginEx != null && beginEx.typeId() == kafkaTypeId;
            final KafkaBeginExFW kafkaBeginEx = extension.get(kafkaBeginExRO::wrap);
            assert kafkaBeginEx.kind() == KafkaBeginExFW.KIND_FETCH;
            final KafkaFetchBeginExFW kafkaFetchBeginEx = kafkaBeginEx.fetch();
            final ArrayFW<KafkaOffsetFW> progress = kafkaFetchBeginEx.progress();
            final KafkaOffsetFW progressItem = progress.matchFirst(p -> true);
            assert progress.limit() == progressItem.limit();
            final int partitionId = progressItem.partitionId();
            final long progressOffset = progressItem.offset$();

            state = KafkaState.openedReply(state);

            assert partitionId == partition.id();
            assert progressOffset >= 0 && progressOffset >= this.progressOffset;
            this.progressOffset = progressOffset;

            members.forEach(s -> s.doServerReplyBeginIfNecessary(traceId));

            doServerFanoutReplyWindow(traceId, bufferPool.slotCapacity());
        }

        private void onServerFanoutReplyData(
            DataFW data)
        {
            final long traceId = data.traceId();
            final int reserved = data.reserved();
            final int flags = data.flags();
            final OctetsFW payload = data.payload();
            final OctetsFW extension = data.extension();
            final ExtensionFW dataEx = extension.get(extensionRO::tryWrap);
            assert dataEx.typeId() == kafkaTypeId;
            final KafkaDataExFW kafkaDataEx = extension.get(kafkaDataExRO::wrap);
            assert kafkaDataEx.kind() == KafkaDataExFW.KIND_FETCH;
            final KafkaFetchDataExFW kafkaFetchDataEx = kafkaDataEx.fetch();

            if ((flags & FLAGS_INIT) != 0x00)
            {
                final long timestamp = kafkaFetchDataEx.timestamp();
                final KafkaKeyFW key = kafkaFetchDataEx.key();

                partition.writeEntryStart(timestamp, key);
            }

            partition.writeEntryContinue(payload);

            if ((flags & FLAGS_FIN) != 0x00)
            {
                final ArrayFW<KafkaHeaderFW> headers = kafkaFetchDataEx.headers();
                final ArrayFW<KafkaOffsetFW> progress = kafkaFetchDataEx.progress();
                final KafkaOffsetFW progressItem = progress.matchFirst(p -> true);
                assert progress.limit() == progressItem.limit();

                final int partitionId = progressItem.partitionId();
                final long progressOffset = progressItem.offset$();

                // TODO: progress offset > partition offset
                //       need to add KafkaFetchDataEx { KafkaOffset partition }
                final long partitionOffset = progressOffset - 1;

                assert partitionId == partition.id();
                assert progressOffset >= 0 && progressOffset > this.progressOffset;
                this.progressOffset = progressOffset;

                partition.writeEntryFinish(headers, partitionOffset);

                members.forEach(s -> s.doServerReplyFlushIfNecessary(traceId));
            }

            doServerFanoutReplyWindow(traceId, reserved);
        }

        private void onServerFanoutReplyEnd(
            EndFW end)
        {
            final long traceId = end.traceId();

            members.forEach(s -> s.doServerReplyEndIfNecessary(traceId));

            state = KafkaState.closedReply(state);
        }

        private void onServerFanoutReplyAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();

            members.forEach(s -> s.doServerReplyAbortIfNecessary(traceId));

            state = KafkaState.closedReply(state);
        }

        private void onServerFanoutInitialReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();

            // TODO: detect NOT_LEADER_FOR_PARTITION, coordinate to maintain KafkaCachePartition single writer

            members.forEach(s -> s.doServerInitialResetIfNecessary(traceId));

            state = KafkaState.closedInitial(state);

            doServerFanoutReplyResetIfNecessary(traceId);
        }

        private void onServerFanoutInitialWindow(
            WindowFW window)
        {
            if (!KafkaState.initialOpened(state))
            {
                final long traceId = window.traceId();

                state = KafkaState.openedInitial(state);

                members.forEach(s -> s.doServerInitialWindowIfNecessary(traceId, 0L, 0, 0));
            }
        }

        private void doServerFanoutReplyResetIfNecessary(
            long traceId)
        {
            if (!KafkaState.replyClosed(state))
            {
                doServerFanoutReplyReset(traceId);
            }
        }

        private void doServerFanoutReplyReset(
            long traceId)
        {
            correlations.remove(replyId);

            state = KafkaState.closedReply(state);

            doReset(receiver, routeId, replyId, traceId, authorization);
        }

        private void doServerFanoutReplyWindow(
            long traceId,
            int credit)
        {
            state = KafkaState.openedReply(state);

            doWindow(receiver, routeId, replyId, traceId, authorization, 0L, credit, 0);
        }
    }

    private final class KafkaCacheServerFetchStream
    {
        private static final int SIZE_OF_FLUSH_WITH_EXTENSION = 64;
        private final KafkaCacheServerFetchFanout group;
        private final MessageConsumer sender;
        private final long routeId;
        private final long initialId;
        private final long replyId;
        private final long affinity;
        private final long authorization;

        private int state;

        private int replyBudget;
        private long progressOffset;

        KafkaCacheServerFetchStream(
            KafkaCacheServerFetchFanout group,
            MessageConsumer sender,
            long routeId,
            long initialId,
            long affinity,
            long authorization,
            long progressOffset)
        {
            this.group = group;
            this.sender = sender;
            this.routeId = routeId;
            this.initialId = initialId;
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.affinity = affinity;
            this.authorization = authorization;
            this.progressOffset = progressOffset;
        }

        private void onServerMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onServerInitialBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onServerInitialData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onServerInitialEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onServerInitialAbort(abort);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onServerReplyWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onServerReplyReset(reset);
                break;
            default:
                break;
            }
        }

        private void onServerInitialBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();

            state = KafkaState.openingInitial(state);

            group.onServerFanoutMemberOpening(traceId, this);
        }

        private void onServerInitialData(
            DataFW data)
        {
            final long traceId = data.traceId();

            doServerInitialResetIfNecessary(traceId);
            doServerReplyAbortIfNecessary(traceId);

            group.onServerFanoutMemberClosed(traceId, this);
        }

        private void onServerInitialEnd(
            EndFW end)
        {
            final long traceId = end.traceId();

            state = KafkaState.closedInitial(state);

            group.onServerFanoutMemberClosed(traceId, this);

            doServerReplyEndIfNecessary(traceId);
        }

        private void onServerInitialAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();

            state = KafkaState.closedInitial(state);

            group.onServerFanoutMemberClosed(traceId, this);

            doServerReplyAbortIfNecessary(traceId);
        }

        private void doServerInitialResetIfNecessary(
            long traceId)
        {
            if (KafkaState.initialOpening(state) && !KafkaState.initialClosed(state))
            {
                doServerInitialReset(traceId);
            }
        }

        private void doServerInitialReset(
            long traceId)
        {
            state = KafkaState.closedInitial(state);

            doReset(sender, routeId, initialId, traceId, authorization);
        }

        private void doServerInitialWindowIfNecessary(
            long traceId,
            long budgetId,
            int credit,
            int padding)
        {
            if (!KafkaState.initialOpened(state) || credit > 0)
            {
                doServerInitialWindow(traceId, budgetId, credit, padding);
            }
        }

        private void doServerInitialWindow(
            long traceId,
            long budgetId,
            int credit,
            int padding)
        {
            state = KafkaState.openedInitial(state);

            doWindow(sender, routeId, initialId, traceId, authorization,
                    budgetId, credit, padding);
        }

        private void doServerReplyBeginIfNecessary(
            long traceId)
        {
            if (!KafkaState.replyOpening(state))
            {
                doServerReplyBegin(traceId);
            }
        }

        private void doServerReplyBegin(
            long traceId)
        {
            state = KafkaState.openingReply(state);

            this.progressOffset = Math.max(progressOffset, group.progressOffset);

            router.setThrottle(replyId, this::onServerMessage);
            doBegin(sender, routeId, replyId, traceId, authorization, affinity,
                ex -> ex.set((b, o, l) -> kafkaCacheBeginExRW.wrap(b, o, l)
                        .typeId(kafkaCacheTypeId)
                        .topic(group.topic.name())
                        .partitionId(group.partition.id())
                        .progressOffset(progressOffset)
                        .build()
                        .sizeof()));
        }

        private void doServerReplyFlushIfNecessary(
            long traceId)
        {
            if (progressOffset < group.progressOffset &&
                replyBudget >= SIZE_OF_FLUSH_WITH_EXTENSION)
            {
                doServerReplyFlush(traceId, SIZE_OF_FLUSH_WITH_EXTENSION);
            }
        }

        private void doServerReplyFlush(
            long traceId,
            int reserved)
        {
            assert progressOffset < group.progressOffset;
            this.progressOffset = group.progressOffset;

            replyBudget -= reserved;

            assert replyBudget >= 0;

            doFlush(sender, routeId, replyId, traceId, authorization, reserved,
                ex -> ex.set((b, o, l) -> kafkaCacheFlushExRW.wrap(b, o, l)
                        .typeId(kafkaCacheTypeId)
                        .progressOffset(progressOffset)
                        .build()
                        .sizeof()));
        }

        private void doServerReplyEndIfNecessary(
            long traceId)
        {
            if (KafkaState.replyOpening(state) && !KafkaState.replyClosed(state))
            {
                doServerReplyEnd(traceId);
            }
        }

        private void doServerReplyEnd(
                long traceId)
        {
            state = KafkaState.closedReply(state);
            doEnd(sender, routeId, replyId, traceId, authorization, EMPTY_EXTENSION);
        }

        private void doServerReplyAbortIfNecessary(
            long traceId)
        {
            if (KafkaState.replyOpening(state) && !KafkaState.replyClosed(state))
            {
                doServerReplyAbort(traceId);
            }
        }

        private void doServerReplyAbort(
                long traceId)
        {
            state = KafkaState.closedReply(state);
            doAbort(sender, routeId, replyId, traceId, authorization, EMPTY_EXTENSION);
        }

        private void onServerReplyWindow(
            WindowFW window)
        {
            final long traceId = window.traceId();
            final long budgetId = window.budgetId();
            final int credit = window.credit();
            final int padding = window.padding();

            assert budgetId == 0L;
            assert padding == 0;

            state = KafkaState.openedReply(state);

            replyBudget += credit;

            doServerReplyFlushIfNecessary(traceId);
        }

        private void onServerReplyReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();

            state = KafkaState.closedReply(state);

            group.onServerFanoutMemberClosed(traceId, this);

            doServerInitialResetIfNecessary(traceId);
        }
    }
}