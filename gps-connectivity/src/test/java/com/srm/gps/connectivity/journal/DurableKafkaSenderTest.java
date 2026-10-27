package com.srm.gps.connectivity.journal;

import com.srm.gps.connectivity.monitoring.PipelineMetrics;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Journal first, then Kafka: what Kafka does not confirm is sent again, in order, until it does. */
class DurableKafkaSenderTest {

    private static final String TOPIC = "gps.raw-device-frames";

    @TempDir
    Path directory;

    @Test
    void aConfirmedRecordLeavesTheJournal() {
        FakeKafka kafka = new FakeKafka();
        DurableKafkaSender sender = sender(kafka);

        sender.send(TOPIC, "imei-1", "frame-1");
        assertEquals(1, sender.status().journal().unacknowledged(), "on disk before Kafka has it");
        kafka.confirm(1);

        assertEquals(0, sender.status().journal().unacknowledged());
        assertFalse(sender.status().catchingUp());
    }

    @Test
    void whatKafkaDidNotTakeIsSentAgainInTheOrderItArrived() {
        FakeKafka kafka = new FakeKafka();
        DurableKafkaSender sender = sender(kafka);

        sender.send(TOPIC, "imei-1", "frame-1");
        kafka.fail(new TimeoutException("Kafka is down"));
        sender.send(TOPIC, "imei-1", "frame-2");
        sender.send(TOPIC, "imei-1", "frame-3");

        assertTrue(sender.status().catchingUp());
        assertEquals(List.of("frame-1"), kafka.values(), "while catching up, new frames wait behind the old ones");
        assertEquals(3, sender.status().journal().unacknowledged());

        sender.drain(true);
        assertEquals(List.of("frame-1", "frame-1", "frame-2", "frame-3"), kafka.values());
        kafka.confirm(3);
        sender.drain(true);

        assertFalse(sender.status().catchingUp());
        assertEquals(0, sender.status().journal().unacknowledged());
        sender.send(TOPIC, "imei-1", "frame-4");
        assertEquals("frame-4", kafka.values().getLast(), "caught up: sent at once again");
    }

    @Test
    void whenKafkaRefusesAtOnceTheCallerIsNotHeldUpAndNothingIsLost() {
        FakeKafka kafka = new FakeKafka();
        kafka.refuse = new TimeoutException("no metadata: Kafka unreachable");
        DurableKafkaSender sender = sender(kafka);

        sender.send(TOPIC, "imei-1", "frame-1");
        sender.send(TOPIC, "imei-1", "frame-2");
        sender.drain(true);

        assertEquals(2, sender.status().journal().unacknowledged());
        assertTrue(sender.status().failedSends() >= 1);

        kafka.refuse = null;
        sender.drain(true);
        kafka.confirm(2);
        sender.drain(true);
        assertEquals(0, sender.status().journal().unacknowledged());
    }

    @Test
    void recordsJournaledBeforeTheServiceDiedAreSentAfterItRestarts() {
        DurableKafkaSender before = sender(new FakeKafka());
        before.send(TOPIC, "imei-1", "frame-1");
        before.send(TOPIC, "imei-2", "frame-2");
        // killed before Kafka confirmed anything

        FakeKafka kafkaAfter = new FakeKafka();
        DurableKafkaSender after = sender(kafkaAfter);
        assertTrue(after.status().catchingUp());
        after.drain(true);

        assertEquals(List.of("frame-1", "frame-2"), kafkaAfter.values());
    }

    private DurableKafkaSender sender(FakeKafka kafka) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new MockProducerFactory<>(kafka));
        return new DurableKafkaSender(new JournalProperties(directory.toString(), 100, true, 1000), template, new PipelineMetrics());
    }

    /**
     * A producer factory over MockProducers that, like the real one, hands out a new producer once the template has
     * closed the previous one (Spring closes a producer after a failed send). Sends are confirmed or failed by hand.
     */
    private static final class FakeKafka implements Supplier<MockProducer<String, String>> {

        private final List<MockProducer<String, String>> producers = new ArrayList<>();
        RuntimeException refuse;

        @Override
        public synchronized MockProducer<String, String> get() {
            MockProducer<String, String> producer = producers.isEmpty() ? null : producers.getLast();
            if (producer == null || producer.closed()) {
                producer = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
                producers.add(producer);
            }
            producer.sendException = refuse;
            return producer;
        }

        void confirm(int records) {
            for (int i = 0; i < records; i++) {
                producers.stream().filter(MockProducer::completeNext).findFirst().orElseThrow();
            }
        }

        void fail(RuntimeException failure) {
            producers.stream().filter(p -> p.errorNext(failure)).findFirst().orElseThrow();
        }

        List<String> values() {
            return producers.stream().flatMap(p -> p.history().stream()).map(ProducerRecord::value).toList();
        }
    }
}
