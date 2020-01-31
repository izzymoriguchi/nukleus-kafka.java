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
package org.reaktivity.nukleus.kafka.internal.cache;

import org.reaktivity.nukleus.kafka.internal.KafkaConfiguration;
import org.reaktivity.nukleus.kafka.internal.KafkaNukleus;

public final class KafkaCache
{
    public static final String TYPE_NAME = String.format("%s/cache", KafkaNukleus.NAME);

    private final KafkaConfiguration config;

    public KafkaCache(
        KafkaConfiguration config)
    {
        this.config = config;
    }

    public KafkaCacheReader newReader()
    {
        return new KafkaCacheReader(config);
    }

    public KafkaCacheWriter newWriter()
    {
        return new KafkaCacheWriter(config);
    }
}
