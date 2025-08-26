package io.tednology.strategies;

import com.dukascopy.api.*;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Gumbo
 */
@Slf4j
class SMA {

    public SMA() {}

    public static double calculateSMA(IHistory history, IIndicators indicators, Instrument instrument, OfferSide offerSide, Period period, int barShift, int periodCount) throws JFException {
        //log.info("Calculating SMA - Bar Shift: " + barShift + ", Period Count: " + periodCount);

        double[] sma = new double[]{indicators.sma(
                instrument,
                period,
                offerSide,
                IIndicators.AppliedPrice.CLOSE,
                periodCount,
                barShift + 1  // Increase the period count by 1 to include the desired barShift index
        )};
        return sma[0];
    }

    public static double ten(IHistory history, IIndicators indicators, Instrument instrument, OfferSide offerSide, Period period) throws JFException {
        double sma10 = calculateSMA(history, indicators, instrument, offerSide, Period.ONE_MIN, 0, 10);
        //log.info("SMA 10: {}", sma10);
        return sma10;
    }

    public static double seven(IHistory history, IIndicators indicators, Instrument instrument, OfferSide offerSide, Period period) throws JFException {
        double sma07 = calculateSMA(history, indicators, instrument, offerSide, Period.ONE_MIN, 0, 7);
        //log.info("SMA 10: {}", sma10);
        return sma07;
    }
    public static double thirty(IHistory history, IIndicators indicators, Instrument instrument, OfferSide offerSide, Period period) throws JFException {
        double sma30 = calculateSMA(history, indicators, instrument, offerSide, Period.ONE_MIN, 0, 30);
        //log.info("SMA 30: {}", sma30);
        return sma30;
    }
}

/**
public class SMA {
    private final Map<Instrument, Map<OfferSide, Map<Integer, Double>>> smaValues;

    public SMA() {
        smaValues = new ConcurrentHashMap<>();
    }

    private double calculateSMA(IHistory history, Instrument instrument, OfferSide offerSide, int periodCount, int shift) throws JFException {
        IBar[] bars = history.getBars(instrument, Period.ONE_MIN, offerSide, Filter.WEEKENDS, shift + periodCount, history.getBar(instrument, Period.ONE_MIN, offerSide, shift).getTime(), 0).toArray(new IBar[0]);
        double sum = 0;

        for (int i = 0; i < periodCount; i++) {
            sum += bars[i].getClose();
        }

        return sum / periodCount;
    }

    private double getSMAValue(IHistory history, Instrument instrument, OfferSide offerSide, int periodCount, int shift) throws JFException {
        smaValues.putIfAbsent(instrument, new ConcurrentHashMap<>());
        smaValues.get(instrument).putIfAbsent(offerSide, new ConcurrentHashMap<>());

        long currentTime = history.getLastTick(instrument).getTime();
        int currentMinute = (int) (currentTime % 60000);

        if (!smaValues.get(instrument).get(offerSide).containsKey(periodCount) || currentMinute == 0) {
            double smaValue = calculateSMA(history, instrument, offerSide, periodCount, shift);
            smaValues.get(instrument).get(offerSide).put(periodCount, smaValue);
        }

        return smaValues.get(instrument).get(offerSide).get(periodCount);
    }

    public double ten(IHistory history, Instrument instrument, OfferSide offerSide) throws JFException {
        double sma10 = getSMAValue(history, instrument, offerSide, 10, 0);
        log.info("SMA 10: {}", sma10);
        return sma10;
    }

    public double thirty(IHistory history, Instrument instrument, OfferSide offerSide) throws JFException {
        double sma30 = getSMAValue(history, instrument, offerSide, 30, 0);
        log.info("SMA 30: {}", sma30);
        return sma30;
    }
}
 */
/**
public class SMA {
    private final Map<Instrument, Map<OfferSide, Map<Integer, Double>>> smaValues; // Store SMA values by instrument, offer side, and period

    public SMA() {
        smaValues = new HashMap<>();
    }

     private double calculateSMA(IHistory history, Instrument instrument, OfferSide offerSide, int periodCount, int shift) throws JFException {
        IBar[] bars = history.getBars(instrument, Period.ONE_MIN, offerSide, Filter.WEEKENDS, shift + periodCount, history.getBar(instrument, Period.ONE_MIN, offerSide, shift).getTime(), 0).toArray(new IBar[0]);
        double sum = 0;

        for (int i = 0; i < periodCount; i++) {
            sum += bars[i].getClose();
        }

        return sum / periodCount;
    }

    // Method to get SMA value for a specific period
    private double getSMAValue(IHistory history, Instrument instrument, OfferSide offerSide, int periodCount, int shift) throws JFException {
        if (!smaValues.containsKey(instrument)) {
            smaValues.put(instrument, new HashMap<>());
        }
        if (!smaValues.get(instrument).containsKey(offerSide)) {
            smaValues.get(instrument).put(offerSide, new HashMap<>());
        }

        // Calculate SMA if not already calculated or update if the current bar is complete
        int currentMinute = (int) (history.getLastTick(instrument).getTime() % 60000); // Get current minute
        if (!smaValues.get(instrument).get(offerSide).containsKey(periodCount) || currentMinute == 0) {
            double smaValue = calculateSMA(history, instrument, offerSide, periodCount, shift);
            smaValues.get(instrument).get(offerSide).put(periodCount, smaValue);
        }

        return smaValues.get(instrument).get(offerSide).get(periodCount);
    }

    public double ten(IHistory history, Instrument instrument, OfferSide offerSide) throws JFException {
        double sma10 = getSMAValue(history, instrument, offerSide, 10, 0);
        log.info("SMA 10: {}", sma10);
        return sma10;
    }

    public double thirty(IHistory history, Instrument instrument, OfferSide offerSide) throws JFException {
        double sma30 = getSMAValue(history, instrument, offerSide, 30, 0);
        log.info("SMA 30: {}", sma30);
        return sma30;
    }
}
 */