package com.heimdall.module.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.json.Payload;
import com.heimdall.core.testing.RecordingTunnelBus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The per-frame byte budget, on its own: packing, carrying, ordering, clearing. */
class FrameBatcherTest {

    /** Items are their own size in bytes. */
    private static FrameBatcher<Integer> batcher(long budget) {
        return new FrameBatcher<Integer>("t", "items", new FrameBatcher.Encoder<Integer>() {
            @Override
            public Payload encode(Integer item) {
                return Payload.builder().put("size", item).build();
            }
        }, 100, 50, new FrameBatcher.Sizer<Integer>() {
            @Override
            public long estimatedBytes(Integer item) {
                return item;
            }
        }, budget);
    }

    private static List<List<Integer>> frames(RecordingTunnelBus bus) {
        List<List<Integer>> out = new ArrayList<List<Integer>>();
        for (RecordingTunnelBus.Sent sent : bus.sent("t")) {
            List<Integer> sizes = new ArrayList<Integer>();
            for (Payload item : sent.payload().children("items")) {
                sizes.add(item.intValue("size", -1));
            }
            out.add(sizes);
        }
        return out;
    }

    @Test
    void packsUpToTheBudgetAndCarriesTheRestInOrder() {
        RecordingTunnelBus bus = new RecordingTunnelBus();
        FrameBatcher<Integer> batcher = batcher(100);
        for (int size : new int[] {40, 50, 30, 20, 90, 5}) {
            batcher.enqueue(size);
        }

        while (batcher.flush(bus)) {
            // drain everything
        }

        assertEquals(Arrays.asList(
                Arrays.asList(40, 50),
                Arrays.asList(30, 20),
                Arrays.asList(90, 5)), frames(bus));
        assertEquals(0, batcher.queuedCount());
    }

    @Test
    void anItemOverTheBudgetStillShipsAloneRatherThanBlockingTheQueue() {
        RecordingTunnelBus bus = new RecordingTunnelBus();
        FrameBatcher<Integer> batcher = batcher(100);
        batcher.enqueue(10);
        batcher.enqueue(500);
        batcher.enqueue(10);

        while (batcher.flush(bus)) {
            // drain everything
        }

        assertEquals(Arrays.asList(Arrays.asList(10), Arrays.asList(500), Arrays.asList(10)),
                frames(bus));
    }

    @Test
    void theCarriedItemCountsAsQueuedAndIsCleared() {
        RecordingTunnelBus bus = new RecordingTunnelBus();
        FrameBatcher<Integer> batcher = batcher(100);
        batcher.enqueue(80);
        batcher.enqueue(80);

        assertTrue(batcher.flush(bus));
        assertEquals(1, batcher.queuedCount(), "the carried item is still waiting");
        assertTrue(batcher.hasQueued(), "and the backlog check sees it, with the queue itself empty");

        batcher.clear();
        assertEquals(0, batcher.queuedCount());
        assertFalse(batcher.flush(bus), "a clear drops the carried item too");
        assertEquals(1, bus.sent("t").size());
    }

    @Test
    void withoutASizerOnlyTheCountBounds() {
        RecordingTunnelBus bus = new RecordingTunnelBus();
        FrameBatcher<Integer> batcher = new FrameBatcher<Integer>("t", "items",
                new FrameBatcher.Encoder<Integer>() {
                    @Override
                    public Payload encode(Integer item) {
                        return Payload.builder().put("size", item).build();
                    }
                }, 100, 50);
        batcher.enqueue(1_000_000);
        batcher.enqueue(1_000_000);

        batcher.flush(bus);

        assertEquals(Arrays.asList(Arrays.asList(1_000_000, 1_000_000)), frames(bus));
    }
}
