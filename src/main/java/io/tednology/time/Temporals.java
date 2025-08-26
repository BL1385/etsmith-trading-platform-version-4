package io.tednology.time;

import com.dukascopy.api.IBar;
import com.dukascopy.api.ITick;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * @author Edward Smith
 */
public class Temporals {

    private Temporals() {}

    public static long epoch(LocalDateTime time) {
        return time.toEpochSecond(ZoneOffset.UTC);
    }

    public static long epochMs(LocalDateTime time) {
        return epoch(time) * 1000;
    }

    public static LocalDateTime timeOf(long val) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(val), ZoneOffset.UTC);
    }

    public static LocalTime localTime(ITick tick) {
        return timeOf(tick.getTime()).toLocalTime();
    }

    public static LocalTime localTime(IBar bar) {
        return timeOf(bar.getTime()).toLocalTime();
    }
}
