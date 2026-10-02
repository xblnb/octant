package com.octant.common.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamStampTest {

    @Test
    void emptyStreamKeepsDesired() {
        assertEquals(12_345L, StreamStamp.monotonic(12_345L, Long.MIN_VALUE));
        assertFalse(StreamStamp.wasRaised(12_345L, Long.MIN_VALUE));
    }

    @Test
    void laterTimestampIsUntouched() {
        assertEquals(9_000L, StreamStamp.monotonic(9_000L, 5_000L));
        assertFalse(StreamStamp.wasRaised(9_000L, 5_000L));
    }

    @Test
    void earlierTimestampIsRaisedAndReported() {
        assertEquals(30_000L, StreamStamp.monotonic(29_850L, 30_000L));
        assertTrue(StreamStamp.wasRaised(29_850L, 30_000L), "抬高必须可数，否则「不精确」会变成无声");
    }

    @Test
    void equalIsNotRaised() {
        assertEquals(7L, StreamStamp.monotonic(7L, 7L));
        assertFalse(StreamStamp.wasRaised(7L, 7L));
    }

    @Test
    void theRealDroppedEncounterWouldNowBeWritten() {
        long realStart = 32_600L - 2_750L;
        long lastWritten = 30_000L;
        assertTrue(realStart < lastWritten, "这一条必须确实撞线，否则这条判据没有判别力");
        long written = StreamStamp.monotonic(realStart, lastWritten);
        assertEquals(lastWritten, written, "抬到末尾即可写；事件不再丢");
        assertTrue(StreamStamp.wasRaised(realStart, lastWritten));
    }
}
