/**
 * Copyright 2016-2020 The Reaktivity Project
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

import org.reaktivity.nukleus.kafka.internal.KafkaConfiguration;

public final class KafkaCacheTopicConfig
{
    public volatile String cleanupPolicy;
    public volatile int maxMessageBytes;
    public volatile int segmentBytes;
    public volatile int segmentIndexBytes;
    public volatile long segmentMillis;
    public volatile long retentionBytes;
    public volatile long retentionMillis;

    public KafkaCacheTopicConfig(
        KafkaConfiguration config)
    {
        this.cleanupPolicy = config.cacheCleanupPolicy();
        this.maxMessageBytes = config.cacheMaxMessageBytes();
        this.segmentBytes = config.cacheSegmentBytes();
        this.segmentIndexBytes = config.cacheSegmentIndexBytes();
        this.segmentMillis = config.cacheSegmentMillis();
        this.retentionBytes = config.cacheRetentionBytes();
        this.retentionMillis = config.cacheRetentionMillis();
    }
}