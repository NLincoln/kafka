/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.storage;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.runtime.WorkerConfig;
import org.apache.kafka.connect.runtime.distributed.DistributedConfig;
import org.apache.kafka.connect.util.Callback;
import org.apache.kafka.connect.util.KafkaBasedLog;
import org.apache.kafka.connect.util.TopicAdmin;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.apache.kafka.clients.CommonClientConfigs.CLIENT_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.ISOLATION_LEVEL_CONFIG;
import static org.apache.kafka.connect.runtime.distributed.DistributedConfig.EXACTLY_ONCE_SOURCE_SUPPORT_CONFIG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@RunWith(MockitoJUnitRunner.StrictStubs.class)
public class KafkaOffsetBackingStoreTest {
    private static final String CLIENT_ID_BASE = "test-client-id-";
    private static final String TOPIC = "connect-offsets";
    private static final short TOPIC_PARTITIONS = 2;
    private static final short TOPIC_REPLICATION_FACTOR = 5;
    private static final Map<String, String> DEFAULT_PROPS = new HashMap<>();
    private static final DistributedConfig DEFAULT_DISTRIBUTED_CONFIG;
    static {
        DEFAULT_PROPS.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "broker1:9092,broker2:9093");
        DEFAULT_PROPS.put(DistributedConfig.OFFSET_STORAGE_TOPIC_CONFIG, TOPIC);
        DEFAULT_PROPS.put(DistributedConfig.OFFSET_STORAGE_REPLICATION_FACTOR_CONFIG, Short.toString(TOPIC_REPLICATION_FACTOR));
        DEFAULT_PROPS.put(DistributedConfig.OFFSET_STORAGE_PARTITIONS_CONFIG, Integer.toString(TOPIC_PARTITIONS));
        DEFAULT_PROPS.put(DistributedConfig.CONFIG_TOPIC_CONFIG, "connect-configs");
        DEFAULT_PROPS.put(DistributedConfig.CONFIG_STORAGE_REPLICATION_FACTOR_CONFIG, Short.toString(TOPIC_REPLICATION_FACTOR));
        DEFAULT_PROPS.put(DistributedConfig.GROUP_ID_CONFIG, "connect");
        DEFAULT_PROPS.put(DistributedConfig.STATUS_STORAGE_TOPIC_CONFIG, "status-topic");
        DEFAULT_PROPS.put(DistributedConfig.KEY_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.json.JsonConverter");
        DEFAULT_PROPS.put(DistributedConfig.VALUE_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.json.JsonConverter");
        DEFAULT_DISTRIBUTED_CONFIG = new DistributedConfig(DEFAULT_PROPS);

    }
    private static final Map<ByteBuffer, ByteBuffer> FIRST_SET = new HashMap<>();
    static {
        FIRST_SET.put(buffer("key"), buffer("value"));
        FIRST_SET.put(null, null);
    }

    private static final ByteBuffer TP0_KEY = buffer("TP0KEY");
    private static final ByteBuffer TP1_KEY = buffer("TP1KEY");
    private static final ByteBuffer TP2_KEY = buffer("TP2KEY");
    private static final ByteBuffer TP0_VALUE = buffer("VAL0");
    private static final ByteBuffer TP1_VALUE = buffer("VAL1");
    private static final ByteBuffer TP2_VALUE = buffer("VAL2");
    private static final ByteBuffer TP0_VALUE_NEW = buffer("VAL0_NEW");
    private static final ByteBuffer TP1_VALUE_NEW = buffer("VAL1_NEW");

    @Mock
    KafkaBasedLog<byte[], byte[]> storeLog;
    private KafkaOffsetBackingStore store;

    private MockedStatic<WorkerConfig> workerConfigMockedStatic;

    @Captor
    private ArgumentCaptor<String> capturedTopic;
    @Captor
    private ArgumentCaptor<Map<String, Object>> capturedProducerProps;
    @Captor
    private ArgumentCaptor<Map<String, Object>> capturedConsumerProps;
    @Captor
    private ArgumentCaptor<Supplier<TopicAdmin>> capturedAdminSupplier;
    @Captor
    private ArgumentCaptor<NewTopic> capturedNewTopic;
    @Captor
    private ArgumentCaptor<Callback<ConsumerRecord<byte[], byte[]>>> capturedConsumedCallback;
    @Captor
    private ArgumentCaptor<Callback<Void>> storeLogCallbackArgumentCaptor;

    @Before
    public void setup() throws Exception {
        Supplier<TopicAdmin> adminSupplier = () -> {
            fail("Should not attempt to instantiate admin in these tests");
            // Should never be reached; only add this thrown exception to satisfy the compiler
            throw new AssertionError();
        };
        Supplier<String> clientIdBase = () -> CLIENT_ID_BASE;

        store = spy(new KafkaOffsetBackingStore(adminSupplier, clientIdBase));

        doReturn(storeLog).when(store).createKafkaBasedLog(capturedTopic.capture(), capturedProducerProps.capture(),
                capturedConsumerProps.capture(), capturedConsumedCallback.capture(),
                capturedNewTopic.capture(), capturedAdminSupplier.capture());

        workerConfigMockedStatic = mockStatic(WorkerConfig.class, CALLS_REAL_METHODS);
        workerConfigMockedStatic.when(() -> WorkerConfig.lookupKafkaClusterId(any())).thenReturn("test-cluster");
    }

    @After
    public void tearDown() {
        workerConfigMockedStatic.close();
        verifyNoMoreInteractions(storeLog);
    }

    @Test
    public void testStartStop() {
        Map<String, String> settings = new HashMap<>(DEFAULT_PROPS);
        settings.put("offset.storage.min.insync.replicas", "3");
        settings.put("offset.storage.max.message.bytes", "1001");

        store.configure(new DistributedConfig(settings));
        store.start();

        verify(storeLog).start();

        assertEquals(TOPIC, capturedTopic.getValue());
        assertEquals("org.apache.kafka.common.serialization.ByteArraySerializer", capturedProducerProps.getValue().get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals("org.apache.kafka.common.serialization.ByteArraySerializer", capturedProducerProps.getValue().get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", capturedConsumerProps.getValue().get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", capturedConsumerProps.getValue().get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));

        assertEquals(TOPIC, capturedNewTopic.getValue().name());
        assertEquals(TOPIC_PARTITIONS, capturedNewTopic.getValue().numPartitions());
        assertEquals(TOPIC_REPLICATION_FACTOR, capturedNewTopic.getValue().replicationFactor());

        store.stop();

        verify(storeLog).stop();
    }

    @Test
    public void testReloadOnStart() {
        doAnswer(invocation -> {
            capturedConsumedCallback.getValue().onCompletion(null, new ConsumerRecord<>(TOPIC, 0, 0, 0L, TimestampType.CREATE_TIME, 0, 0, TP0_KEY.array(), TP0_VALUE.array(),
                    new RecordHeaders(), Optional.empty()));
            capturedConsumedCallback.getValue().onCompletion(null, new ConsumerRecord<>(TOPIC, 1, 0, 0L, TimestampType.CREATE_TIME, 0, 0, TP1_KEY.array(), TP1_VALUE.array(),
                    new RecordHeaders(), Optional.empty()));
            capturedConsumedCallback.getValue().onCompletion(null, new ConsumerRecord<>(TOPIC, 0, 1, 0L, TimestampType.CREATE_TIME, 0, 0, TP0_KEY.array(), TP0_VALUE_NEW.array(),
                    new RecordHeaders(), Optional.empty()));
            capturedConsumedCallback.getValue().onCompletion(null, new ConsumerRecord<>(TOPIC, 1, 1, 0L, TimestampType.CREATE_TIME, 0, 0, TP1_KEY.array(), TP1_VALUE_NEW.array(),
                    new RecordHeaders(), Optional.empty()));
            return null;
        }).when(storeLog).start();

        store.configure(DEFAULT_DISTRIBUTED_CONFIG);
        store.start();

        HashMap<ByteBuffer, ByteBuffer> data = store.data;
        assertEquals(TP0_VALUE_NEW, data.get(TP0_KEY));
        assertEquals(TP1_VALUE_NEW, data.get(TP1_KEY));

        store.stop();

        verify(storeLog).stop();
    }

    @Test
    public void testGetSet() throws Exception {
        store.configure(DEFAULT_DISTRIBUTED_CONFIG);
        store.start();

        verify(storeLog).start();

        doAnswer(invocation -> {
            // First get() against an empty store
            storeLogCallbackArgumentCaptor.getValue().onCompletion(null, null);
            return null;
        }).when(storeLog).readToEnd(storeLogCallbackArgumentCaptor.capture());

        // Getting from empty store should return nulls
        Map<ByteBuffer, ByteBuffer> offsets = store.get(Arrays.asList(TP0_KEY, TP1_KEY)).get(10000, TimeUnit.MILLISECONDS);
        // Since we didn't read them yet, these will be null
        assertNull(offsets.get(TP0_KEY));
        assertNull(offsets.get(TP1_KEY));
        // Set some offsets
        Map<ByteBuffer, ByteBuffer> toSet = new HashMap<>();
        toSet.put(TP0_KEY, TP0_VALUE);
        toSet.put(TP1_KEY, TP1_VALUE);
        final AtomicBoolean invoked = new AtomicBoolean(false);
        Future<Void> setFuture = store.set(toSet, (error, result) -> invoked.set(true));
        assertFalse(setFuture.isDone());
        // Set offsets
        ArgumentCaptor<org.apache.kafka.clients.producer.Callback> callback0 = ArgumentCaptor.forClass(org.apache.kafka.clients.producer.Callback.class);
        verify(storeLog).send(aryEq(TP0_KEY.array()), aryEq(TP0_VALUE.array()), callback0.capture());
        ArgumentCaptor<org.apache.kafka.clients.producer.Callback> callback1 = ArgumentCaptor.forClass(org.apache.kafka.clients.producer.Callback.class);
        verify(storeLog).send(aryEq(TP1_KEY.array()), aryEq(TP1_VALUE.array()), callback1.capture());
        // Out of order callbacks shouldn't matter, should still require all to be invoked before invoking the callback
        // for the store's set callback
        callback1.getValue().onCompletion(null, null);
        assertFalse(invoked.get());
        callback0.getValue().onCompletion(null, null);
        setFuture.get(10000, TimeUnit.MILLISECONDS);
        assertTrue(invoked.get());

        doAnswer(invocation -> {
            // Second get() should get the produced data and return the new values
            capturedConsumedCallback.getValue().onCompletion(null,
                    new ConsumerRecord<>(TOPIC, 0, 0, 0L, TimestampType.CREATE_TIME, 0, 0, TP0_KEY.array(), TP0_VALUE.array(),
                            new RecordHeaders(), Optional.empty()));
            capturedConsumedCallback.getValue().onCompletion(null,
                    new ConsumerRecord<>(TOPIC, 1, 0, 0L, TimestampType.CREATE_TIME, 0, 0, TP1_KEY.array(), TP1_VALUE.array(),
                            new RecordHeaders(), Optional.empty()));
            storeLogCallbackArgumentCaptor.getValue().onCompletion(null, null);
            return null;
        }).when(storeLog).readToEnd(storeLogCallbackArgumentCaptor.capture());

        // Getting data should read to end of our published data and return it
        offsets = store.get(Arrays.asList(TP0_KEY, TP1_KEY)).get(10000, TimeUnit.MILLISECONDS);
        assertEquals(TP0_VALUE, offsets.get(TP0_KEY));
        assertEquals(TP1_VALUE, offsets.get(TP1_KEY));

        doAnswer(invocation -> {
            // Third get() should pick up data produced by someone else and return those values
            capturedConsumedCallback.getValue().onCompletion(null,
                    new ConsumerRecord<>(TOPIC, 0, 1, 0L, TimestampType.CREATE_TIME, 0, 0, TP0_KEY.array(), TP0_VALUE_NEW.array(),
                            new RecordHeaders(), Optional.empty()));
            capturedConsumedCallback.getValue().onCompletion(null,
                    new ConsumerRecord<>(TOPIC, 1, 1, 0L, TimestampType.CREATE_TIME, 0, 0, TP1_KEY.array(), TP1_VALUE_NEW.array(),
                            new RecordHeaders(), Optional.empty()));
            storeLogCallbackArgumentCaptor.getValue().onCompletion(null, null);
            return null;
        }).when(storeLog).readToEnd(storeLogCallbackArgumentCaptor.capture());

        // Getting data should read to end of our published data and return it
        offsets = store.get(Arrays.asList(TP0_KEY, TP1_KEY)).get(10000, TimeUnit.MILLISECONDS);
        assertEquals(TP0_VALUE_NEW, offsets.get(TP0_KEY));
        assertEquals(TP1_VALUE_NEW, offsets.get(TP1_KEY));

        store.stop();

        verify(storeLog).stop();
    }

    @Test
    public void testGetSetNull() throws Exception {
        // Second get() should get the produced data and return the new values
        doAnswer(invocation -> {
            capturedConsumedCallback.getValue().onCompletion(null,
                    new ConsumerRecord<>(TOPIC, 0, 0, 0L, TimestampType.CREATE_TIME, 0, 0, null, TP0_VALUE.array(),
                            new RecordHeaders(), Optional.empty()));
            capturedConsumedCallback.getValue().onCompletion(null,
                    new ConsumerRecord<>(TOPIC, 1, 0, 0L, TimestampType.CREATE_TIME, 0, 0, TP1_KEY.array(), null,
                            new RecordHeaders(), Optional.empty()));
            storeLogCallbackArgumentCaptor.getValue().onCompletion(null, null);
            return null;
        }).when(storeLog).readToEnd(storeLogCallbackArgumentCaptor.capture());

        store.configure(DEFAULT_DISTRIBUTED_CONFIG);
        store.start();

        verify(storeLog).start();

        // Set offsets using null keys and values
        Map<ByteBuffer, ByteBuffer> toSet = new HashMap<>();
        toSet.put(null, TP0_VALUE);
        toSet.put(TP1_KEY, null);
        final AtomicBoolean invoked = new AtomicBoolean(false);
        Future<Void> setFuture = store.set(toSet, (error, result) -> invoked.set(true));
        assertFalse(setFuture.isDone());

        // Set offsets
        ArgumentCaptor<org.apache.kafka.clients.producer.Callback> callback0 = ArgumentCaptor.forClass(org.apache.kafka.clients.producer.Callback.class);
        verify(storeLog).send(isNull(), aryEq(TP0_VALUE.array()), callback0.capture());
        ArgumentCaptor<org.apache.kafka.clients.producer.Callback> callback1 = ArgumentCaptor.forClass(org.apache.kafka.clients.producer.Callback.class);
        verify(storeLog).send(aryEq(TP1_KEY.array()), isNull(), callback1.capture());
        // Out of order callbacks shouldn't matter, should still require all to be invoked before invoking the callback
        // for the store's set callback
        callback1.getValue().onCompletion(null, null);
        assertFalse(invoked.get());
        callback0.getValue().onCompletion(null, null);
        setFuture.get(10000, TimeUnit.MILLISECONDS);
        assertTrue(invoked.get());
        // Getting data should read to end of our published data and return it
        Map<ByteBuffer, ByteBuffer> offsets = store.get(Arrays.asList(null, TP1_KEY)).get(10000, TimeUnit.MILLISECONDS);
        assertEquals(TP0_VALUE, offsets.get(null));
        assertNull(offsets.get(TP1_KEY));

        store.stop();

        verify(storeLog).stop();
    }

    @Test
    public void testSetFailure() {
        store.configure(DEFAULT_DISTRIBUTED_CONFIG);
        store.start();

        verify(storeLog).start();

        // Set some offsets
        Map<ByteBuffer, ByteBuffer> toSet = new HashMap<>();
        toSet.put(TP0_KEY, TP0_VALUE);
        toSet.put(TP1_KEY, TP1_VALUE);
        toSet.put(TP2_KEY, TP2_VALUE);
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final AtomicBoolean invokedFailure = new AtomicBoolean(false);
        Future<Void> setFuture = store.set(toSet, (error, result) -> {
            invoked.set(true);
            if (error != null)
                invokedFailure.set(true);
        });
        assertFalse(setFuture.isDone());
        // Set offsets
        ArgumentCaptor<org.apache.kafka.clients.producer.Callback> callback0 = ArgumentCaptor.forClass(org.apache.kafka.clients.producer.Callback.class);
        verify(storeLog).send(aryEq(TP0_KEY.array()), aryEq(TP0_VALUE.array()), callback0.capture());
        ArgumentCaptor<org.apache.kafka.clients.producer.Callback> callback1 = ArgumentCaptor.forClass(org.apache.kafka.clients.producer.Callback.class);
        verify(storeLog).send(aryEq(TP1_KEY.array()), aryEq(TP1_VALUE.array()), callback1.capture());
        ArgumentCaptor<org.apache.kafka.clients.producer.Callback> callback2 = ArgumentCaptor.forClass(org.apache.kafka.clients.producer.Callback.class);
        verify(storeLog).send(aryEq(TP2_KEY.array()), aryEq(TP2_VALUE.array()), callback2.capture());
        // Out of order callbacks shouldn't matter, should still require all to be invoked before invoking the callback
        // for the store's set callback
        callback1.getValue().onCompletion(null, null);
        assertFalse(invoked.get());
        callback2.getValue().onCompletion(null, new KafkaException("bogus error"));
        assertTrue(invoked.get());
        assertTrue(invokedFailure.get());
        callback0.getValue().onCompletion(null, null);
        ExecutionException e = assertThrows(ExecutionException.class, () -> setFuture.get(10000, TimeUnit.MILLISECONDS));
        assertNotNull(e.getCause());
        assertTrue(e.getCause() instanceof KafkaException);

        store.stop();

        verify(storeLog).stop();
    }

    @Test
    public void testConsumerPropertiesInsertedByDefaultWithExactlyOnceSourceEnabled() {
        Map<String, String> workerProps = new HashMap<>(DEFAULT_PROPS);
        workerProps.put(EXACTLY_ONCE_SOURCE_SUPPORT_CONFIG, "enabled");
        workerProps.remove(ISOLATION_LEVEL_CONFIG);
        DistributedConfig config = new DistributedConfig(workerProps);

        store.configure(config);

        assertEquals(
                IsolationLevel.READ_COMMITTED.name().toLowerCase(Locale.ROOT),
                capturedConsumerProps.getValue().get(ISOLATION_LEVEL_CONFIG)
        );
    }

    @Test
    public void testConsumerPropertiesOverrideUserSuppliedValuesWithExactlyOnceSourceEnabled() {
        Map<String, String> workerProps = new HashMap<>(DEFAULT_PROPS);
        workerProps.put(EXACTLY_ONCE_SOURCE_SUPPORT_CONFIG, "enabled");
        workerProps.put(ISOLATION_LEVEL_CONFIG, IsolationLevel.READ_UNCOMMITTED.name().toLowerCase(Locale.ROOT));
        DistributedConfig config = new DistributedConfig(workerProps);

        store.configure(config);

        assertEquals(
                IsolationLevel.READ_COMMITTED.name().toLowerCase(Locale.ROOT),
                capturedConsumerProps.getValue().get(ISOLATION_LEVEL_CONFIG)
        );
    }

    @Test
    public void testConsumerPropertiesNotInsertedByDefaultWithoutExactlyOnceSourceEnabled() {
        Map<String, String> workerProps = new HashMap<>(DEFAULT_PROPS);
        workerProps.put(EXACTLY_ONCE_SOURCE_SUPPORT_CONFIG, "disabled");
        workerProps.remove(ISOLATION_LEVEL_CONFIG);
        DistributedConfig config = new DistributedConfig(workerProps);

        store.configure(config);

        assertNull(capturedConsumerProps.getValue().get(ISOLATION_LEVEL_CONFIG));
    }

    @Test
    public void testConsumerPropertiesDoNotOverrideUserSuppliedValuesWithoutExactlyOnceSourceEnabled() {
        Map<String, String> workerProps = new HashMap<>(DEFAULT_PROPS);
        workerProps.put(EXACTLY_ONCE_SOURCE_SUPPORT_CONFIG, "disabled");
        workerProps.put(ISOLATION_LEVEL_CONFIG, IsolationLevel.READ_UNCOMMITTED.name().toLowerCase(Locale.ROOT));
        DistributedConfig config = new DistributedConfig(workerProps);

        store.configure(config);

        assertEquals(
                IsolationLevel.READ_UNCOMMITTED.name().toLowerCase(Locale.ROOT),
                capturedConsumerProps.getValue().get(ISOLATION_LEVEL_CONFIG)
        );

    }

    @Test
    public void testClientIds() {
        Map<String, String> workerProps = new HashMap<>(DEFAULT_PROPS);
        DistributedConfig config = new DistributedConfig(workerProps);

        workerConfigMockedStatic.when(() -> WorkerConfig.lookupKafkaClusterId(any())).thenReturn("test-cluster");

        store.configure(config);

        final String expectedClientId = CLIENT_ID_BASE + "offsets";
        assertEquals(expectedClientId, capturedProducerProps.getValue().get(CLIENT_ID_CONFIG));
        assertEquals(expectedClientId, capturedConsumerProps.getValue().get(CLIENT_ID_CONFIG));
    }

    private static ByteBuffer buffer(String v) {
        return ByteBuffer.wrap(v.getBytes());
    }

}
