/**
 * Copyright 2016-2017 The Reaktivity Project
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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.agrona.DirectBuffer;
import org.agrona.collections.LongLongConsumer;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.kafka.internal.types.OctetsFW;

public class KeyMessageDispatcher implements MessageDispatcher
{
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[0]);

    private Map<UnsafeBuffer, MessageDispatcher> dispatchersByKey = new HashMap<>();

    @Override
    public int dispatch(
                 int partition,
                 long requestOffset,
                 long messageOffset,
                 DirectBuffer key,
                 Function<DirectBuffer, DirectBuffer> supplyHeader,
                 LongLongConsumer acknowledge,
                 DirectBuffer value)
    {
        buffer.wrap(key, 0, key.capacity());
        MessageDispatcher result = dispatchersByKey.get(buffer);
        return result == null ? 0 :
            result.dispatch(partition, requestOffset, messageOffset, key, supplyHeader, acknowledge, value);
    }

    public MessageDispatcher addIfAbsent(OctetsFW key, MessageDispatcher dispatcher)
    {
        buffer.wrap(key.buffer(), key.offset(), key.sizeof());
        MessageDispatcher existing = dispatchersByKey.get(buffer);
        if (existing == null)
        {
            UnsafeBuffer keyCopy = new UnsafeBuffer(new byte[key.sizeof()]);
            keyCopy.putBytes(0,  key.buffer(), key.offset(), key.sizeof());
            dispatchersByKey.put(keyCopy, dispatcher);
        }
        return existing;
    }

    public MessageDispatcher remove(OctetsFW key)
    {
        buffer.wrap(key.buffer(), key.offset(), key.sizeof());
        return dispatchersByKey.remove(buffer);
    }

}
