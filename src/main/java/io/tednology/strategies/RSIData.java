package io.tednology.strategies;

import com.dukascopy.api.Period;
import com.dukascopy.api.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.time.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Slf4j
public class RSIData {

    private List<IBar> buffer = new ArrayList<>();
    private static double lastRsiValue;

    private double previousRsiValue1 = -1.0;
    private double previousRsiValue2 = -1.0;
    public RSIData(IHistory history, IIndicators indicators, Instrument instrument, ZonedDateTime startTime, ZonedDateTime endTime, int numBars) {
    }

    public static class RSIPair {
        @Getter
        private final ZonedDateTime timestamp;
        private final double value;
        private final boolean left;
        private final boolean right;

        public RSIPair(ZonedDateTime timestamp, double value, boolean left, boolean right) {
            this.timestamp = timestamp;
            this.value = value;
            this.left = left;
            this.right = right;
        }

        public double getValue() {
            return value;
        }

        public boolean getLeft() {
            return left;
        }

        public boolean getRight() {
            return right;
        }
    }

    public static void updateLastRsiValue(double newRsiValue) {
        lastRsiValue = newRsiValue;
        //log.info("Updated lastRsiValue: {} at time: {}", lastRsiValue, ZonedDateTime.now());
    }

    public double calculateRSI(IHistory history, ZonedDateTime actualStartDateTime, ZonedDateTime actualEndDateTime,
                               OfferSide offerSide, int period, double bbPeriod, double bbStdDev) throws JFException {
        //log.info("Calculating RSI with parameters - actualStartDateTime: {}, actualEndDateTime: {}, offerSide: {}, period: {}, bbPeriod: {}, bbStdDev: {}, lastRsiValue: {}",
                //actualStartDateTime.format(formatter), actualEndDateTime.format(formatter), offerSide, period, bbPeriod, bbStdDev, lastRsiValue);

        // Adjust the start time to be earlier by bbPeriod bars
        //ZonedDateTime adjustedStartDateTime = actualStartDateTime.minusMinutes((long) (bbPeriod * Period.ONE_MIN.getInterval()));

        if (bbPeriod <= 0 || bbStdDev <= 0) {
            throw new IllegalArgumentException("Invalid parameters for calculating RSI with Bollinger Bands: bbPeriod=" + bbPeriod + ", bbStdDev=" + bbStdDev);
        }

        if (history == null) {
            throw new IllegalArgumentException("Invalid parameters for calculating RSI with Bollinger Bands: history is null");
        }

        if (actualStartDateTime == null) {
            throw new IllegalArgumentException("Invalid parameters for calculating RSI with Bollinger Bands: actualStartDateTime is null");
        }

        if (actualEndDateTime == null) {
            throw new IllegalArgumentException("Invalid parameters for calculating RSI with Bollinger Bands: actualEndDateTime is null");
        }

        // Check if offerSide is null and set a default value
        if (offerSide == null) {
            log.info("OfferSide is null. Setting default value to OfferSide.BID");
            offerSide = OfferSide.BID;
        }

        // Request historical bars for the time range
        List<IBar> bars = getHistoricalBars(history, Instrument.EURUSD, Period.ONE_MIN, offerSide, actualStartDateTime, actualEndDateTime, bbPeriod);

        if (bars == null || bars.isEmpty() || bars.size() < period) {
            assert bars != null;
            log.info("[{}] Insufficient historical data to calculate RSI with Bollinger Bands. Waiting for more bars to be available. Bar count: {}", actualEndDateTime, bars.size());
            return 0.0;
        }

        // Calculate the current RSI value and return it
        double[] closePrices = new double[bars.size()];
        ZonedDateTime[] timestamps = new ZonedDateTime[bars.size()];

        for (int i = 0; i < bars.size(); i++) {
            IBar bar = bars.get(i);
            closePrices[i] = bar.getClose();
            timestamps[i] = Instant.ofEpochMilli(bar.getTime()).atZone(ZoneOffset.UTC);
        }

        // Log array sizes to investigate the issue
        //log.info("Number of bars: {}", bars.size());
        //log.info("Size of closePrices array: {}", closePrices.length);
        //log.info("Size of timestamps array: {}", timestamps.length);

        // Sort the data based on timestamps in ascending order
        sortDataByTimestamps(closePrices, timestamps);

        // Calculate the Bollinger Bands for the same data
        double[] smaValues = calculateSMAValues(closePrices, (int) bbPeriod);
        double[] upperBollingerBands = calculateUpperBollingerBands(smaValues, bbStdDev, (int) bbPeriod);
        double[] lowerBollingerBands = calculateLowerBollingerBands(smaValues, bbStdDev, (int) bbPeriod);

        // Assign dummy values to avoid unused variable warnings
        double dummyUpperBollingerBand = upperBollingerBands[0];
        double dummyLowerBollingerBand = lowerBollingerBands[0];

        // Log Bollinger Bands details with timestamps
        //log.info("Bollinger Bands calculation:");
        //for (int i = 0; i < closePrices.length; i++) {
        //    log.info("Timestamp: {}, Close Price: {}, SMA: {}, Upper BB: {}, Lower BB: {}", timestamps[i],
        //            closePrices[i], smaValues[i], upperBollingerBands[i], lowerBollingerBands[i]);
        //}

        // Calculate the current RSI value and return it
        double currentRSI = calculateRSISingle(closePrices, period, timestamps);

        // Update lastRsiValue in the RSIData instance
        RSIData.updateLastRsiValue(currentRSI);
        //log.info("Updated lastRsiValue in RSIData to: {}", currentRSI);

        // Add logs to print the RSI value
        //log.info("OfferSideCurrentRSI: {}", offerSide);
        //log.info("Current RSI value RSIData: {}", currentRSI);

        return currentRSI;
    }

    private List<IBar> getHistoricalBars(IHistory history, Instrument instrument, Period period, OfferSide offerSide,
                                         ZonedDateTime startDateTime, ZonedDateTime endDateTime, double bbPeriod) throws JFException {
        // Adjust the startDateTime to be earlier by bbPeriod bars
        ZonedDateTime adjustedStartDateTime = startDateTime.minusMinutes((long) (bbPeriod * period.getInterval() / 60000));

        // Convert the ZonedDateTime to milliseconds for the time range
        long extendedBarStartInMillis = adjustedStartDateTime.toInstant().toEpochMilli();
        long extendedBarEndInMillis = endDateTime.toInstant().toEpochMilli();

        // Ensure the adjusted start time is valid
        if (extendedBarStartInMillis >= extendedBarEndInMillis) {
            log.error("Invalid time range for historical bars. Start time: {}, End time: {}", adjustedStartDateTime, endDateTime);
            return null;
        }

        // Convert to bar start times
        long extendedBarStartTime = history.getBarStart(period, extendedBarStartInMillis);
        long extendedBarEndTime = history.getBarStart(period, extendedBarEndInMillis);

        // Ensure the end time does not exceed the last formed bar time
        long lastFormedBarTime = history.getLastTick(instrument).getTime();
        if (extendedBarEndTime > lastFormedBarTime) {
            extendedBarEndTime = lastFormedBarTime;
        }

        // Check for valid time range
        if (extendedBarStartTime == 0 || extendedBarStartTime >= extendedBarEndTime) {
            log.error("Invalid time range for historical bars. Start time: {}, End time: {}", adjustedStartDateTime, endDateTime);
            return null;
        }

        // Calculate the number of bars required
        int requiredBars = calculateRequiredBars(period, startDateTime, endDateTime);

        // Fetch historical bars
        List<IBar> bars = history.getBars(instrument, period, offerSide, Filter.NO_FILTER, extendedBarStartTime, extendedBarEndTime);

        // Check if more bars are needed
        while (bars.size() < requiredBars) {
            // Extend the start time further back
            extendedBarStartTime = history.getPreviousBarStart(period, extendedBarStartTime);

            // Ensure the extended start time is valid
            if (extendedBarStartTime == 0 || extendedBarStartTime >= extendedBarEndTime) {
                log.error("Insufficient historical data. Cannot retrieve required number of bars.");
                return null;
            }

            // Fetch additional bars
            List<IBar> additionalBars = history.getBars(instrument, period, offerSide, Filter.NO_FILTER, extendedBarStartTime, extendedBarEndTime);

            // Add additional bars to the list
            bars.addAll(0, additionalBars);

            // Trim to the required number of bars
            if (bars.size() > requiredBars) {
                bars = bars.subList(bars.size() - requiredBars, bars.size());
            }
        }

        // Sort bars by timestamp
        bars.sort(Comparator.comparingLong(IBar::getTime));

        return bars;
    }

/**
    private List<IBar> getHistoricalBars(IHistory history, Instrument instrument, Period period, OfferSide offerSide,
                                         ZonedDateTime startDateTime, ZonedDateTime endDateTime, double bbPeriod) throws JFException {
        //log.info("Fetching historical bars for: {} to {}", startDateTime, endDateTime);

        // Adjust the startDateTime to be even earlier by bbPeriod bars
        ZonedDateTime adjustedStartDateTime = startDateTime.minusMinutes((long) (bbPeriod));

        // Convert the ZonedDateTime to milliseconds for the extended time range
        long extendedBarStartInMillis = adjustedStartDateTime.toInstant().toEpochMilli();
        long extendedBarEndInMillis = endDateTime.toInstant().toEpochMilli();

        //log.info("Requesting historical bars for the following time range:");
        //log.info("Start Time: {}", formatter.format(startDateTime));
        //log.info("End Time: {}", formatter.format(endDateTime));

        // Use the provided startDateTime and endDateTime for calculating bar start times
        long extendedBarStartTime = history.getBarStart(period, extendedBarStartInMillis);
        long extendedBarEndTime = history.getBarStart(period, extendedBarEndInMillis);

        //log.info("Extended Bar Start Time: {}", formatter.format(Instant.ofEpochMilli(extendedBarStartTime).atZone(ZoneId.systemDefault())));
        //log.info("Extended Bar End Time: {}", formatter.format(Instant.ofEpochMilli(extendedBarEndTime).atZone(ZoneId.systemDefault())));

        // Check if the endDateTime is greater than the time of the last formed bar for this instrument
        long lastFormedBarTime = history.getLastTick(instrument).getTime();
        if (extendedBarEndTime > lastFormedBarTime) {
            // Adjust the endDateTime to be equal to or earlier than the last formed bar time
            extendedBarEndTime = lastFormedBarTime;
        }

        //log.info("Last Formed Bar Time: {}", formatter.format(Instant.ofEpochMilli(lastFormedBarTime).atZone(ZoneId.systemDefault())));

        // Check if the extendedBarStartTime is valid (not zero) and smaller than the extendedBarEndTime
        if (extendedBarStartTime == 0 || extendedBarStartTime >= extendedBarEndTime) {
            log.info("Invalid time range for historical bars. Start time: {}, End time: {}", startDateTime, endDateTime);
            return null;
        }

        // Calculate the number of bars required based on the provided period and the time range
        int requiredBars = calculateRequiredBars(period, startDateTime, endDateTime);

        // Request historical bars for the extended time range
        List<IBar> bars = history.getBars(instrument, period, offerSide, Filter.NO_FILTER, extendedBarStartTime, extendedBarEndTime);

        // Store the retrieved bars in the buffer
        buffer.addAll(bars);

        processBuffer();

        // Sort the bars in ascending order based on timestamps
        bars.sort(Comparator.comparingLong(IBar::getTime));

        // Get the last bar after sorting
        IBar lastBar = bars.get(bars.size() - 1);

        processBuffer();

        /// Format the last bar's time using the formatter
        ZonedDateTime lastBarTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastBar.getTime()), ZoneId.systemDefault());

        // Log the values of the last bar
        //log.info("Last Bar - Time: {}, Open: {}, High: {}, Low: {}, Close: {}", lastBarTime.format(formatter), lastBar.getOpen(), lastBar.getHigh(), lastBar.getLow(), lastBar.getClose());

        processBuffer();
/**
        // Log the actual historical bars retrieved
        log.info("Actual Historical Bars:");
        for (IBar bar : bars) {
            ZonedDateTime barTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(bar.getTime()), ZoneId.systemDefault());
            log.info("Bar Time: {}, Open: {}, High: {}, Low: {}, Close: {}", barTime.format(formatter), bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose());
        }
*/
/**
        processBuffer();

        // If there are not enough bars, try to extend the time range further back
        while (bars.size() < requiredBars) {
            //log.info("Inside loop - bars.size(): {}, requiredBars: {}, extendedBarStartTime: {}, extendedBarEndTime: {}", bars.size(), requiredBars, extendedBarStartTime, extendedBarEndTime);

            // Extend the time range further back by one period
            extendedBarStartTime = history.getPreviousBarStart(period, extendedBarStartTime);

            //log.info("Extended Bar Start Time (Loop): {}", formatter.format(Instant.ofEpochMilli(extendedBarStartTime).atZone(ZoneId.systemDefault())));

            if (extendedBarStartTime == 0 || extendedBarStartTime >= extendedBarEndTime) {
                log.info("Insufficient historical data to calculate RSI. Waiting for more bars to be available.");
                return null;
            }

            // Request historical bars for the extended time range
            //log.info("Requesting historical bars for the following time range:");
            //log.info("Start Time: {}", formatter.format(startDateTime));
            //log.info("End Time: {}", formatter.format(endDateTime));

            List<IBar> additionalBars = history.getBars(instrument, period, offerSide, Filter.NO_FILTER, extendedBarStartTime, extendedBarEndTime);

            // Log the values of the additional bars
            for (IBar additionalBar : additionalBars) {
                ZonedDateTime barTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(additionalBar.getTime()), ZoneId.systemDefault());
                //log.info("Additional Bar - Time: {}, Open: {}, High: {}, Low: {}, Close: {}", barTime.format(formatter), additionalBar.getOpen(), additionalBar.getHigh(), additionalBar.getLow(), additionalBar.getClose());
            }

            processBuffer();

            // Add the additional bars to the existing list of bars
            bars.addAll(0, additionalBars);

            // Trim the list to the required number of bars
            if (bars.size() > requiredBars) {
                bars = bars.subList(bars.size() - requiredBars, bars.size());
            }
        }
        return bars;
    }
    */

    private int calculateRequiredBars(Period period, ZonedDateTime startDateTime, ZonedDateTime endDateTime) {
        long periodDuration = period.getInterval() * 60 * 1000L; // Convert minutes to milliseconds
        long timeRangeDuration = Duration.between(startDateTime, endDateTime).toMillis();
        int requiredBars = (int) (timeRangeDuration / periodDuration);
        return Math.max(requiredBars, 1); // Ensure a minimum value of 1
    }

    private static double calculateRSISingle(double[] closePrices, int period, ZonedDateTime[] timestamps) {
        int numPriceDiffs = closePrices.length - 1;

        if (numPriceDiffs < period) {
            log.warn("Insufficient data to calculate RSI");
            return 0.0;
        }

        double[] priceDiffs = new double[numPriceDiffs];
        for (int i = 0; i < numPriceDiffs; i++) {
            priceDiffs[i] = closePrices[i + 1] - closePrices[i];
        }

        double[] gains = new double[numPriceDiffs];
        double[] losses = new double[numPriceDiffs];

        for (int i = 0; i < numPriceDiffs; i++) {
            if (priceDiffs[i] >= 0) {
                gains[i] = priceDiffs[i];
            } else {
                losses[i] = Math.abs(priceDiffs[i]);
            }
        }

        double sumOfInitialGains = Arrays.stream(gains).limit(period).sum();
        double sumOfInitialLosses = Arrays.stream(losses).limit(period).sum();

        double initialAvgGain = sumOfInitialGains / period;
        double initialAvgLoss = sumOfInitialLosses / period;

        double alpha = 1.0 / period; // Smoothing factor for subsequent average gains and losses (use 14 for Wilder's RSI)

        double avgGain = initialAvgGain;
        double avgLoss = initialAvgLoss;

        for (int i = period; i < numPriceDiffs; i++) {
            double gain = priceDiffs[i] >= 0 ? priceDiffs[i] : 0.0;
            double loss = priceDiffs[i] < 0 ? Math.abs(priceDiffs[i]) : 0.0;

            avgGain = (alpha * gain) + ((1.0 - alpha) * avgGain);
            avgLoss = (alpha * loss) + ((1.0 - alpha) * avgLoss);
        }

        double rs = avgGain / avgLoss;
        double rsi = 100.0 - (100.0 / (1.0 + rs));

        return rsi;
    }

    public double calculateCurrentRsiValue(IHistory history, OfferSide offerSide, int period, ZonedDateTime currentBarTime, double bbPeriod, double bbStdDev) throws JFException {
        // Calculate the end time of the current bar
        ZonedDateTime currentEndTime = currentBarTime.minusMinutes(1);

        // Calculate the start time of the period (8 minutes ago from the current bar time)
        ZonedDateTime previousStartTime = currentEndTime.minusMinutes(period);

        // Calculate RSI using data from the last completed 8-minute bar
        return calculateRSI(history, previousStartTime, currentEndTime, offerSide, period, bbPeriod, bbStdDev);
    }

    private double calculatePreviousRsiValue(IHistory history, OfferSide offerSide, int period, ZonedDateTime currentBarTime, double bbPeriod, double bbStdDev) throws JFException {
        // Calculate the end time of the previous bar
        ZonedDateTime previousEndTime = currentBarTime.minusMinutes(2);

        // Calculate the start time of the period (10 minutes ago from the current bar time)
        ZonedDateTime previousStartTime = previousEndTime.minusMinutes(period);

        // Calculate RSI using data from the 8-minute period before the last complete bar
        return calculateRSI(history, previousStartTime, previousEndTime, offerSide, period, bbPeriod, bbStdDev);
    }

    public Pair<Boolean, Boolean> isRsiConditionMet(IHistory history, OfferSide offerSide, int period, double rsiThreshold1, double rsiThreshold2,
                                                    ZonedDateTime currentStartTime, double currentRsiValue, ZonedDateTime logTimestamp, int numBarsToCheck,
                                                    ZonedDateTime currentBarTime, double bbPeriod, double bbStdDev) throws JFException {

        log.info("Inside isRsiConditionMet method");

        // Variables to track the status of the conditions
        boolean buyCondition1Met = false;
        boolean sellCondition1Met = false;

        if (currentStartTime == null) {
            log.error("currentStartTime is null. Unable to calculate RSI conditions.");
            return Pair.of(buyCondition1Met, sellCondition1Met);
        }

        log.info("Current RSI value {} at current time: {}", currentRsiValue, ZonedDateTime.now(ZoneOffset.UTC));
        //log.info("[{}] Threshold 1: {}", logTimestamp, rsiThreshold1);
        //log.info("[{}] Threshold 2: {}", logTimestamp, rsiThreshold2);

        if (currentRsiValue > rsiThreshold1) {
            //log.info("Current RSI value {} is above threshold 1 at current time: {}", currentRsiValue, ZonedDateTime.now(ZoneOffset.UTC));

            double previousRsiValue = calculatePreviousRsiValue(history, offerSide,  period, currentBarTime, bbPeriod, bbStdDev);
            //log.info("Previous RSI value {} for Threshold 1 at current time: {}", previousRsiValue, ZonedDateTime.now(ZoneOffset.UTC));

            if (previousRsiValue < rsiThreshold1) {
                //log.info("Buy condition 1 is met. Previous RSI value {} crossed below threshold 1 at timestamp: {}", previousRsiValue, previousStartTime);
                buyCondition1Met = true;
            }
        }

        if (currentRsiValue < rsiThreshold2) {
            //log.info("Current RSI value {} is above threshold 2 at current time: {}", currentRsiValue, ZonedDateTime.now(ZoneOffset.UTC));

            double previousRsiValue = calculatePreviousRsiValue(history, offerSide,  period, currentBarTime, bbPeriod, bbStdDev);
            //log.info("Previous RSI value {} for Threshold 2 at current time: {}", previousRsiValue, ZonedDateTime.now(ZoneOffset.UTC));

            if (previousRsiValue > rsiThreshold2) {
                //log.info("Sell condition 1 is met. Previous RSI value {} crossed above threshold 2 at timestamp: {}", previousRsiValue, previousStartTime);
                sellCondition1Met = true;
            }
        }

        // Log the results
        //log.info("Buy condition 1 met: {}", buyCondition1Met);
        //log.info("Sell condition 1 met: {}", sellCondition1Met);

        // Log the current RSI value for reference
        //log.info("Current RSI value for isRsiConditionMet: {} at time: {}", currentRsiValue, logTimestamp);

        // Return the modified RSI condition results for both Buy and Sell
        return Pair.of(buyCondition1Met, sellCondition1Met);
    }


    private void sortDataByTimestamps(double[] closePrices, ZonedDateTime[] timestamps) {
        // Combine closePrices and timestamps into a single array of RSIPair for sorting
        RSIPair[] dataWithTimestamps = new RSIPair[closePrices.length];
        for (int i = 0; i < closePrices.length; i++) {
            dataWithTimestamps[i] = new RSIPair(timestamps[i], closePrices[i], false, false);
        }

        // Sort the data based on timestamps
        Arrays.sort(dataWithTimestamps, Comparator.comparing(RSIPair::getTimestamp));

        // Copy sorted data back to closePrices and timestamps arrays
        for (int i = 0; i < closePrices.length; i++) {
            RSIPair pair = dataWithTimestamps[i];
            timestamps[i] = pair.getTimestamp();
            closePrices[i] = pair.getValue();
        }
    }

    private double[] calculateSMAValues(double[] closePrices, int period) {
        if (closePrices == null || closePrices.length < period) {
            return new double[0];
        }

        double[] smaValues = new double[closePrices.length];

        // Calculate SMA for the bars
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += closePrices[i];
        }

        for (int i = period; i < closePrices.length; i++) {
            sum += closePrices[i] - closePrices[i - period]; // Subtract the oldest value
            smaValues[i] = sum / period;

        }

        return smaValues;
    }

    private double[] calculateUpperBollingerBands(double[] smaValues, double stdDev, int bbPeriod) {
        if (smaValues == null || smaValues.length == 0 || bbPeriod > smaValues.length) {
            log.info("Invalid input data for calculating upper Bollinger Bands");
            return new double[0];
        }

        double[] upperBollingerBands = new double[smaValues.length];

        //log.info("calculateUpperBollingerBands - bbPeriod: {}, smaValues.length: {}", bbPeriod, smaValues.length);

        for (int i = bbPeriod - 1; i < smaValues.length; i++) {
            //log.info("calculateUpperBollingerBands - calculating for index: {}", i);
            double sd = calculateStandardDeviation(smaValues, i - bbPeriod + 1, i);
            upperBollingerBands[i] = smaValues[i] + stdDev * sd;
        }

        return upperBollingerBands;
    }

    private double[] calculateLowerBollingerBands(double[] smaValues, double stdDev, int bbPeriod) {
        if (smaValues == null || smaValues.length == 0 || bbPeriod > smaValues.length) {
            log.info("Invalid input data for calculating lower Bollinger Bands");
            return new double[0];
        }

        double[] lowerBollingerBands = new double[smaValues.length];

        //log.info("calculateLowerBollingerBands - bbPeriod: {}, smaValues.length: {}", bbPeriod, smaValues.length);

        for (int i = bbPeriod - 1; i < smaValues.length; i++) {
            //log.info("calculateLowerBollingerBands - calculating for index: {}", i);
            double sd = calculateStandardDeviation(smaValues, i - bbPeriod + 1, i);
            lowerBollingerBands[i] = smaValues[i] - stdDev * sd;
        }

        return lowerBollingerBands;
    }

    private double calculateStandardDeviation(double[] values, int start, int end) {
        //log.info("calculateStandardDeviation - start: {}, end: {}", start, end);

        if (start < 0) {
            start = 0;
        }
        if (end >= values.length) {
            end = values.length - 1;
        }

        double mean = 0.0;
        for (int i = start; i <= end; i++) {
            mean += values[i];
        }
        mean /= (end - start + 1);

        double squaredDiffSum = 0.0;
        for (int i = start; i <= end; i++) {
            double diff = values[i] - mean;
            squaredDiffSum += diff * diff;
        }

        double variance = squaredDiffSum / (end - start + 1);
        double standardDeviation = Math.sqrt(variance);

        // Add logs to show calculated values and indices
        //log.info("Standard Deviation Calculation:");
        //log.info("Start Index: {}, End Index: {}", start, end);
        //log.info("Mean: {}", mean);
        //log.info("Squared Difference Sum: {}", squaredDiffSum);
        //log.info("Variance: {}", variance);

        //log.info("calculateStandardDeviation - result: {}", standardDeviation);

        return standardDeviation;
    }
    private void processBuffer() {
        if (!buffer.isEmpty()) {
            IBar lastBar = buffer.get(buffer.size() - 1);

            // Check if the current time has reached the closing time of the bar
            ZonedDateTime barClosingTime = Instant.ofEpochMilli(lastBar.getTime()).atZone(ZoneId.systemDefault()).plusMinutes(1);
            ZonedDateTime currentTime = ZonedDateTime.now();

            if (currentTime.isAfter(barClosingTime)) {
                // Bar has closed, process and display OHLC values
                //log.info("Bar Time: {}", formatter.format(barClosingTime));
                //log.info("Open: {}", lastBar.getOpen());
                //log.info("High: {}", lastBar.getHigh());
                //log.info("Low: {}", lastBar.getLow());
                //log.info("Close: {}", lastBar.getClose());

                // Clear the buffer
                buffer.clear();
            }
        }
    }
}

