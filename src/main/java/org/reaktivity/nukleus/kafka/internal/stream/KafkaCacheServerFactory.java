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

import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.ToIntFunction;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.kafka.internal.KafkaConfiguration;
import org.reaktivity.nukleus.kafka.internal.KafkaNukleus;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheWriter;
import org.reaktivity.nukleus.kafka.internal.types.OctetsFW;
import org.reaktivity.nukleus.kafka.internal.types.cache.KafkaCacheBeginExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.ExtensionFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaBeginExFW;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;

public final class KafkaCacheServerFactory implements StreamFactory
{
    private final BeginFW beginRO = new BeginFW();
    private final ExtensionFW extensionRO = new ExtensionFW();
    private final KafkaBeginExFW kafkaBeginExRO = new KafkaBeginExFW();
    private final KafkaCacheBeginExFW kafkaCacheBeginExRO = new KafkaCacheBeginExFW();

    private final int kafkaTypeId;
    private final int kafkaCacheTypeId;
    private final Long2ObjectHashMap<MessageConsumer> correlations;
    private final Int2ObjectHashMap<StreamFactory> streamFactoriesByKind;

    KafkaCacheServerFactory(
        KafkaConfiguration config,
        KafkaCacheWriter cache,
        RouteManager router,
        MutableDirectBuffer writeBuffer,
        BufferPool bufferPool,
        LongUnaryOperator supplyInitialId,
        LongUnaryOperator supplyReplyId,
        LongSupplier supplyTraceId,
        ToIntFunction<String> supplyTypeId,
        LongFunction<KafkaCacheRoute> supplyCacheRoute)
    {
        final Long2ObjectHashMap<MessageConsumer> correlations = new Long2ObjectHashMap<>();
        final Int2ObjectHashMap<StreamFactory> streamFactoriesByKind = new Int2ObjectHashMap<>();

        streamFactoriesByKind.put(KafkaBeginExFW.KIND_META, new KafkaCacheMetaFactory(
                config, router, writeBuffer, bufferPool, supplyInitialId, supplyReplyId,
                supplyTraceId, supplyTypeId, supplyCacheRoute, correlations));

        streamFactoriesByKind.put(KafkaBeginExFW.KIND_DESCRIBE, new KafkaCacheDescribeFactory(
                config, router, writeBuffer, bufferPool, supplyInitialId, supplyReplyId,
                supplyTraceId, supplyTypeId, supplyCacheRoute, correlations));

        streamFactoriesByKind.put(KafkaBeginExFW.KIND_FETCH, new KafkaCacheServerFetchFactory(
                config, cache, router, writeBuffer, bufferPool, supplyInitialId, supplyReplyId,
                supplyTraceId, supplyTypeId, supplyCacheRoute, correlations));

        this.kafkaTypeId = supplyTypeId.applyAsInt(KafkaNukleus.NAME);
        this.kafkaCacheTypeId = supplyTypeId.applyAsInt(KafkaCacheWriter.TYPE_NAME);
        this.correlations = correlations;
        this.streamFactoriesByKind = streamFactoriesByKind;
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
        final long streamId = begin.streamId();

        MessageConsumer newStream = null;

        if ((streamId & 0x0000_0000_0000_0001L) != 0L)
        {
            newStream = newInitialStream(begin, sender);
        }
        else
        {
            newStream = newReplyStream(begin, sender);
        }

        return newStream;
    }

    private MessageConsumer newInitialStream(
        BeginFW begin,
        MessageConsumer sender)
    {
        final OctetsFW extension = begin.extension();
        final ExtensionFW beginEx = extension.get(extensionRO::tryWrap);
        assert beginEx != null;
        final int typeId = beginEx.typeId();
        assert beginEx != null && (typeId == kafkaTypeId || beginEx.typeId() == kafkaCacheTypeId);

        MessageConsumer newStream = null;

        StreamFactory streamFactory = null;
        if (typeId == kafkaCacheTypeId)
        {
            final KafkaCacheBeginExFW kafkaCacheBeginEx = extension.get(kafkaCacheBeginExRO::tryWrap);
            if (kafkaCacheBeginEx != null)
            {
                streamFactory = streamFactoriesByKind.get(KafkaBeginExFW.KIND_FETCH);
            }
        }
        else if (typeId == kafkaTypeId)
        {
            final KafkaBeginExFW kafkaBeginEx = extension.get(kafkaBeginExRO::tryWrap);
            if (kafkaBeginEx != null)
            {
                streamFactory = streamFactoriesByKind.get(kafkaBeginEx.kind());
            }
        }

        if (streamFactory != null)
        {
            newStream = streamFactory.newStream(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof(), sender);
        }

        return newStream;
    }

    private MessageConsumer newReplyStream(
        BeginFW begin,
        MessageConsumer sender)
    {
        final long streamId = begin.streamId();

        return correlations.remove(streamId);
    }
}
