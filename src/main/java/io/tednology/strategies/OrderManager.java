package io.tednology.strategies;

import com.dukascopy.api.Period;
import com.dukascopy.api.*;
import io.tednology.analysis.BarData;
import io.tednology.analysis.OrderMeta;
import io.tednology.init.JForexProperties;
import io.tednology.init.SystemProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.jooq.lambda.Seq;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.tednology.time.Temporals.*;
import static java.time.temporal.ChronoUnit.MINUTES;

@Slf4j
public class OrderManager {
    private final Instrument instrument = Instrument.EURUSD;
    private final IContext context;
    private final IIndicators indicators;
    private final IHistory history;
    private final BarData lastAskData;
    private final OfferSide offerSide;
    private final List<StrategyWindow> strategyWindows;
    private final double takeProfitPips;
    private final double stopLossPips;

    private IOrder currentOrder;
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;

    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");


    /**
     * RSI Calculation Related
     */
    private boolean rsiConditionMet = false;
    private boolean rsiThreshold1ConditionsMet = false;
    private boolean rsiThreshold2ConditionsMet = false;
    private final double rsiThreshold1 = 30.0;
    private final double rsiThreshold2 = 70.0;
    private final int numBars = 8;
    private final int period = 8;
    private final int bbPeriod = 14;
    private final int bbStdDev = 2;
    //private long rsiConditionStartTime = 0;

    private long rsiConditionStartTime = System.currentTimeMillis();

    int numBarsToCheck = 2;
    private static final long RSI_TIMER_DURATION = 10 * 60 * 1000; // number of minutes in milliseconds
    private static final long COOLDOWN_TIMER_DURATION = 2 * 60 * 1000; // number of minutes in milliseconds

    private double lastRsiValue = 0.0;
    private double lastRsiUpdateTime = 0.0;

    private long lastRsiCalculationTime;

    //private long lastRsiCalculationTime = System.currentTimeMillis();

    private boolean rsiConditionsActive = false;
    private boolean isRsiConditionsActiveForBuy = false;
    private boolean isRsiConditionsActiveForSell = false;


    private RSIData rsiData;
    private long lastOrderTime = 0;
    private long lastOrderCloseTime;


    /**
     * Trailing Take Profit and Stop Loss Flags_Parameters
     */
    private boolean shouldApplySchedulerForManageTSL = true;
    private boolean shouldStopLossMitigationScheduler = true;


    /**
     * SMA Tick Calculation Parameters
     */
    private double sma10Value = 0;
    private double sma30Value = 0;
    private long sma10UpdateTime = 0L;
    private long sma30UpdateTime = 0L;
    private long lastMinuteBarTime = 0;  // Track the time of the last one-minute bar
    private boolean sma10BelowSma30 = false;
    private boolean sma10AboveSma30 = false;

    private double cachedSMA10Value = Double.NaN;
    private double cachedSMA30Value = Double.NaN;

    private long lastUpdateTime = 0;

    private double previousSMA10Value = Double.NaN;
    private double previousSMA30Value = Double.NaN;
    private long lastSMAUpdateTime = 0;


    private boolean isOrderOpen = false;
    private boolean takeProfitCancelled = false;
    private boolean initialAdjustmentMade = false;
    private boolean initialStopLossAdjustmentMade = false;
    private boolean mitigationApplied = false;
    private boolean stopLossMitigationApplied = false;

    private ScheduledExecutorService trailingStopLossExecutorService;

    private Map<Long, Long> lastStopLossUpdateTimes = new HashMap<>(); // Map to store the last update time for each order's stop loss

    private Map<String, ScheduledExecutorService> orderExecutorServices1 = new ConcurrentHashMap<>();

    private Map<String, ScheduledExecutorService> orderExecutorServices2 = new ConcurrentHashMap<>();

    private final AtomicInteger schedulerExecutionCount1 = new AtomicInteger(0);
    private final AtomicInteger schedulerExecutionCount2 = new AtomicInteger(0);
    private AtomicReference<Double> deltaHolder = new AtomicReference<>(0.0);


    private double cancelTakeProfitThreshold = .20; // Threshold (in percentage) that initial TP is reduced by in order for cancellation to kick in. Example, .9 reflects threshold being ~1.2 pips above/below entry. .
    private double trailingStartPercentage = .1; // Initial Trailing SL .Ad / subtract xx% from entry point. example --> .005 = ~0.5 pips
    private double trailingStopActivationThreshold = 0.0002; // # of pips from entry point
    private double volatilityFactor = 10;

    /**
    Trailing Stop Loss Parameters
     **/
    private double trailingStartForInitialAdjustment = 0.80; // .xx of current profit
    private double trailingStartForSubsequentAdjustments = 0.50; // .xx of current profit


    public OrderManager(JForexProperties forexProperties, SystemProperties systemProperties, IContext context, BarData lastAskData, OfferSide offerSide, IHistory history) throws JFException {
        if (offerSide == null) {
            throw new IllegalArgumentException("offerSide must not be null");
        }
        log.info("OfferSide value OM: {}", offerSide);

        this.context = context;
        this.indicators = context.getIndicators();
        this.lastAskData = lastAskData;
        this.takeProfitPips = forexProperties.getTakeProfit();
        this.stopLossPips = forexProperties.getStopLoss();
        this.strategyWindows = systemProperties.getStrategyWindows();
        this.offerSide = offerSide;
        this.history = history;
        this.rsiData = new RSIData(history, indicators, instrument, startTime, endTime, numBars);

        log.info("OrderManager instance created.");
    }

    /**
     * Track the state of the market for the specified order, adding appropriate data
     * to the OrderMeta object for later mitigation decisions or analysis/reporting.
     */
    OrderMeta trackPostOpen(IIndicators indicators, IOrder order, IBar bar, OrderMeta orderMeta, BarData barData) throws JFException {
        String timestamp = getFormattedTimestamp(bar.getTime());
        log.info("Tracking post-open: Order={}, Bar={}, BarData={}, Timestamp={}", order.getLabel(), bar, barData, timestamp);

        OfferSide side = barData.getSide();
        LocalDateTime opened = timeOf(order.getFillTime());
        LocalDateTime minThreshold = opened.plusMinutes(6);
        LocalDateTime swingThreshold = opened.plusMinutes(20);
        LocalDateTime maxThreshold = opened.plusMinutes(60);
        LocalDateTime now = timeOf(bar.getTime());
        long orderOpened = epochMs(now.minusMinutes(MINUTES.between(opened, now)));

        List<Stoch> stochHistory = barData.stochHistory(
                indicators,
                Period.ONE_MIN,
                side,
                orderOpened,
                bar.getTime());

        Optional<Double> max = stochHistory.stream()
                .filter(s -> s.getTime().isAfter(minThreshold))
                .map(Stoch::getSlowK)
                .max(Double::compareTo);

        Optional<Double> min = stochHistory.stream()
                .filter(s -> s.getTime().isAfter(minThreshold))
                .map(Stoch::getSlowK)
                .min(Double::compareTo);

        long reversalCount = Seq.seq(stochHistory).zipWithIndex()
                .filter(s -> s.v2 > 0)
                .filter(s -> OfferSide.BID == side
                        ? stochHistory.get((int) (s.v2 - 1)).getSlowK() > 20 && s.v1.getSlowK() < 20
                        : stochHistory.get((int) (s.v2 - 1)).getSlowK() < 80 && s.v1.getSlowK() > 80)
                .count();

        if (orderMeta == null || orderMeta.getStrongStochSwing() == null || !orderMeta.getStrongStochSwing()) {
            long oppositeCount = stochHistory.stream()
                    .filter(s -> s.getTime().isAfter(minThreshold))
                    .filter(s -> s.getTime().isBefore(swingThreshold))
                    .map(Stoch::getSlowK)
                    .filter(k -> OfferSide.BID == side ? k > 80 : k < 20)
                    .count();
            if (orderMeta == null) {
                orderMeta = new OrderMeta();
            }
            orderMeta.setStrongStochSwing(oppositeCount >= 2);
        }

        orderMeta.setReversals((int) reversalCount);

        if (orderMeta.getStrongReversal() == null || !orderMeta.getStrongReversal()) {
            orderMeta.setStrongReversal(OfferSide.BID == side
                    ? (orderMeta.getStrongStochSwing() && barData.wentBelow(20))
                    : (orderMeta.getStrongStochSwing() && barData.wentAbove(80)));
        }

        if (orderMeta.getFalseStart() == null || !orderMeta.getFalseStart()) {
            orderMeta.setFalseStart(OfferSide.BID == side
                    ? (max.isPresent() && max.get() < 35)
                    : (min.isPresent() && min.get() > 60));
        }

        orderMeta.setStale(now.isAfter(maxThreshold));

        return orderMeta;
    }

    /**
     * Have the requirements to open an Order been met?
     */

    public boolean shouldOpenOrderOnTick(BarData lastBarData, OfferSide side, ITick tick) {
        log.info("Entering shouldOpenOrderOnTick method");

        try {
            initializeSMAValues(); // Initialize SMA values

            if (!isValidInput(lastBarData, tick) || existingOpenOrders()) {
                log.info("Skipping new order opening due to invalid input or existing open orders.");
                return false;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("UTC"));

            log.info("RSI conditions checked. Time: {}", formatter.format(Instant.ofEpochMilli(tick.getTime())));

            long currentTime = tick.getTime();
            long elapsedTime = currentTime - rsiConditionStartTime;

            log.info("Current Time: {}, RSI Condition Start Time: {}", formatter.format(Instant.ofEpochMilli(currentTime)), formatter.format(Instant.ofEpochMilli(rsiConditionStartTime)));
            //log.info("Elapsed time since last RSI calculation: {} ms", elapsedTime);

            // Calculate RSI conditions for buy and sell separately
            Pair<Boolean, Boolean> rsiConditions = calculateRsiConditions(history, offerSide, period, tick);
            boolean isRsiMetForBuy = rsiConditions.getLeft();
            boolean isRsiMetForSell = rsiConditions.getRight();

            log.info("RSI condition for buy: {}", isRsiMetForBuy);
            log.info("RSI condition for sell: {}", isRsiMetForSell);

            boolean isRsiTimerMet = elapsedTime <= RSI_TIMER_DURATION;
            log.info("Is RSI Timer Met: {}", isRsiTimerMet);
            log.info("Time elapsed since RSI condition: {} ms", elapsedTime);

            if (isRsiMetForBuy) {
                // Reset the timer whenever the condition is met again
                rsiConditionStartTime = currentTime;
                // Set the flag only if it was not previously set
                if (!isRsiConditionsActiveForBuy) {
                    log.info("RSI conditions for buy are met. Updating flags and resetting timer.");
                    isRsiConditionsActiveForBuy = true;
                    // Reset flag for sell if buy condition is met
                    isRsiConditionsActiveForSell = false;
                    rsiThreshold1ConditionsMet = true; // Update rsiThreshold1ConditionsMet
                    log.info("RSI conditions for buy are met. Resetting threshold conditions flags.");
                }
            } else {
                // Unset the flag if previously set
                if (isRsiConditionsActiveForBuy && !isRsiTimerMet) {
                    isRsiConditionsActiveForBuy = false; // Reset flag for sell
                    rsiThreshold1ConditionsMet = false; // Update rsiThreshold1ConditionsMet
                    log.info("RSI conditions for buy are not met. Resetting flags.");
                }
            }

            if (isRsiMetForSell) {
                // Reset the timer whenever the condition is met again
                rsiConditionStartTime = currentTime;
                if (!isRsiConditionsActiveForSell) {
                    log.info("RSI conditions for sell are met. Updating flags and resetting timer.");
                    rsiConditionStartTime = currentTime;
                    isRsiConditionsActiveForSell = true;
                    // Reset flag for buy if sell condition is met
                    isRsiConditionsActiveForBuy = false;
                    rsiThreshold2ConditionsMet = true; // Update rsiThreshold2ConditionsMet
                    log.info("RSI conditions for sell are met. Resetting threshold conditions flags.");
                }
            } else {
                // Unset the flag if previously set
                if (isRsiConditionsActiveForSell && !isRsiTimerMet) {
                    isRsiConditionsActiveForSell = false; // Reset flag for buy
                    rsiThreshold2ConditionsMet = false; // Update rsiThreshold2ConditionsMet
                    log.info("RSI conditions for sell are not met. Resetting flags.");
                }
            }

            log.info("RSI conditions active - Buy: {}, Sell: {} - Time: {}", isRsiConditionsActiveForBuy, isRsiConditionsActiveForSell,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(currentTime)));

            // Proceed only if RSI conditions for buying or selling are active and the timer is met
            if ((isRsiConditionsActiveForBuy || isRsiConditionsActiveForSell) && isRsiTimerMet) {
                log.info("Checking SMA conditions. Time: {}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(tick.getTime())));


                boolean isGoodForBuy = determineIsGoodForBuy();
                boolean isGoodForSell = determineIsGoodForSell();

                log.info("isGoodForBuy: {}", isGoodForBuy);
                log.info("isGoodForSell: {}", isGoodForSell);

                // Delegate decision making to the decide method
                boolean shouldOpenResult = decide(lastBarData, isGoodForBuy, isGoodForSell, isRsiMetForBuy, isRsiMetForSell, elapsedTime, side);

                log.info("Result of decide method: {}", shouldOpenResult);

                if (shouldOpenResult && isCooldownTimerExpired(tick)) {
                    lastOrderTime = lastOrderCloseTime;
                    log.info("Order should be opened. Time: {}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(tick.getTime())));
                    return true;
                }

                log.info("Order should NOT be opened. Time: {}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(tick.getTime())));
                return false;
            }

            log.info("Exiting shouldOpenOrderOnTick method. Result: false");
            return false;
        } catch (Exception ex) {
            log.error("An error occurred in shouldOpenOrderOnTick method: {}", ex.getMessage());
            // Handle the exception as needed, e.g., log the error and return false
            return false;
        }
    }

/**
    public boolean shouldOpenOrderOnTick(BarData lastBarData, OfferSide side, ITick tick) {
        log.info("Entering shouldOpenOrderOnTick method");

        try {
            initializeSMAValues(); // Initialize SMA values

            if (!isValidInput(lastBarData, tick) || existingOpenOrders()) {
                log.info("Skipping new order opening due to invalid input or existing open orders.");
                return false;
            }

            log.info("RSI conditions checked. Time: {}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(tick.getTime())));

            long currentTime = tick.getTime();
            long elapsedTime = currentTime - rsiConditionStartTime;

            log.info("Current Time: {}, RSI Condition Start Time: {}", currentTime, rsiConditionStartTime);
            log.info("Elapsed time since last RSI calculation: {} ms", elapsedTime);

            Pair<Boolean, Boolean> rsiConditions = calculateRsiConditions(history, offerSide, period, tick);
            boolean isRsiMetForBuy = rsiConditions.getLeft();
            boolean isRsiMetForSell = rsiConditions.getRight();

            log.info("rsiMet value shouldOpen (Buy): {}", isRsiMetForBuy);
            log.info("rsiMet value shouldOpen (Sell): {}", isRsiMetForSell);

            boolean isRsiTimerMet = elapsedTime <= RSI_TIMER_DURATION;
            log.info("Is RSI Timer Met: {}", isRsiTimerMet);
            log.info("Time elapsed since RSI condition: {} ms", elapsedTime);

            // Check if any RSI conditions are met
            if (isRsiMetForBuy || isRsiMetForSell) {
                // Set the flag only if it was not previously set
                if (!rsiConditionsActive) {
                    log.info("RSI conditions were not previously active. Updating flags.");
                    rsiConditionStartTime = currentTime;
                    rsiThreshold1ConditionsMet = isRsiMetForBuy;
                    rsiThreshold2ConditionsMet = isRsiMetForSell;
                    rsiConditionsActive = true;
                    log.info("RSI conditions met. Resetting threshold conditions flags.");
                } else {
                    log.info("RSI conditions were previously active. Not updating flags.");
                }
            } else {
                // Unset the flag only if it was previously set and the timer is not met
                if (rsiConditionsActive && !isRsiTimerMet) {
                    log.info("RSI conditions are not met or timer expired. Resetting flags.");
                    rsiThreshold1ConditionsMet = false;
                    rsiThreshold2ConditionsMet = false;
                    rsiConditionsActive = false;
                    rsiConditionStartTime = currentTime;
                } else {
                    log.info("No need to reset flags. RSI conditions are not active or timer is met.");
                }
            }

            log.info("RSI conditions active - Buy: {}, Sell: {} - Time: {}", rsiThreshold1ConditionsMet, rsiThreshold2ConditionsMet,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(currentTime)));

            // Only proceed if RSI conditions are active and the timer is met
            if (rsiConditionsActive && isRsiTimerMet) {
                log.info("Checking SMA conditions. Time: {}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(tick.getTime())));

                boolean isGoodForBuy;
                boolean isGoodForSell;
                boolean shouldOpenResult;

                try {
                    isGoodForBuy = determineIsGoodForBuy();
                    isGoodForSell = determineIsGoodForSell();

                    log.info("isGoodForBuy: {}", isGoodForBuy);
                    log.info("isGoodForSell: {}", isGoodForSell);

                    shouldOpenResult = decide(lastBarData, isGoodForBuy, isGoodForSell, isRsiMetForBuy, isRsiMetForSell, elapsedTime, side);

                    log.info("Result of decide method: {}", shouldOpenResult);

                    if (shouldOpenResult && isCooldownTimerExpired(tick)) {
                        lastOrderTime = lastOrderCloseTime;
                        log.info("Order should be opened. Time: {}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(tick.getTime())));
                        return true;
                    }

                    log.info("Order should NOT be opened. Time: {}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(tick.getTime())));
                    return false;
                } catch (Exception e) {
                    log.error("Error occurred while evaluating SMA conditions: {}", e.getMessage());
                    // Handle the exception as needed, e.g., log the error and return false
                    return false;
                }
            }

            log.info("Exiting shouldOpenOrderOnTick method. Result: false");
            return false;
        } catch (Exception ex) {
            log.error("An error occurred in shouldOpenOrderOnTick method: {}", ex.getMessage());
            // Handle the exception as needed, e.g., log the error and return false
            return false;
        }
    }
*/

    /** ORIGINAL
    public boolean shouldOpen(BarData lastBarData, OfferSide side, ITick tick) throws JFException {
        log.info("shouldOpen method called with parameters - lastBarData: {}, side: {}", lastBarData, side);

        if (!isValidInput(lastBarData, tick)) {
            return false;
        }

        // Calculate the elapsed time in milliseconds using the correct timestamps
        long elapsedTime = tick.getTime() - rsiConditionStartTime;

        log.info("RSI Condition Start Time: {}", getFormattedTimestamp(rsiConditionStartTime));
        log.info("Elapsed Time (ms): {}", elapsedTime);
        log.info("RSI Timer Duration (ms): {}", RSI_TIMER_DURATION);

        boolean isRsiTimerMet = elapsedTime <= RSI_TIMER_DURATION;
        log.info("Is RSI Timer Met: {}", isRsiTimerMet);

        String lastTickFormattedTime = getFormattedTimestamp(tick.getTime());
        String rsiConditionStartFormattedTime = getFormattedTimestamp(rsiConditionStartTime);
        log.info("Last Tick Time: {}, RSI Condition Start Time: {}, Elapsed Time: {}, Timer Duration: {}, Is RSI Timer Met: {}",
                lastTickFormattedTime, rsiConditionStartFormattedTime, elapsedTime, RSI_TIMER_DURATION, isRsiTimerMet);

        Pair<Boolean, Boolean> rsiConditions = calculateRsiConditions(tick);
        boolean isRsiMetForBuy = rsiConditions.getLeft();
        boolean isRsiMetForSell = rsiConditions.getRight();

        log.info("rsiMet value shouldOpen (Buy): {}", isRsiMetForBuy);
        log.info("rsiMet value shouldOpen (Sell): {}", isRsiMetForSell);

        // Reset flags when RSI conditions are met
        if (isRsiMetForBuy || isRsiMetForSell) {
            rsiConditionStartTime = tick.getTime();
            rsiThreshold1ConditionsMet = isRsiMetForBuy;
            rsiThreshold2ConditionsMet = isRsiMetForSell;
            log.info("RSI conditions met. Resetting threshold conditions flags.");
        } else if (!isRsiTimerMet) {
            // Reset RSI condition flags and timer if timer expired
            rsiThreshold1ConditionsMet = false;
            rsiThreshold2ConditionsMet = false;
            log.info("RSI conditions not met or timer expired. Resetting flags.");
        }

        boolean isGoodForBuy = determineIsGoodForBuy(side);
        boolean isGoodForSell = determineIsGoodForSell(side);

        log.info("isGoodForBuy: {}", isGoodForBuy);
        log.info("isGoodForSell: {}", isGoodForSell);
        log.info("isRsiTimerMet: {}", isRsiTimerMet);

        boolean shouldOpenResult = decide(lastBarData, isGoodForBuy, isGoodForSell, isRsiMetForBuy, isRsiMetForSell, isRsiTimerMet, side);

        log.info("Order should {}be opened. Current Time: {}, Last Order CLose Time: {}, Cool down Timer Expired: {}",
                shouldOpenResult ? "" : "NOT ", getFormattedTimestamp(tick.getTime()),
                getFormattedTimestamp(lastOrderTime), isCooldownTimerExpired(tick));

        if (shouldOpenResult && isRsiTimerMet && isCooldownTimerExpired(tick)) {
            log.info("Order should be opened.");
            lastOrderTime = lastOrderCloseTime;

            return true;
        }

        log.info("Order should NOT be opened.");
        return false;
    }
*/

    private boolean decide(BarData lastBarData, boolean isGoodForBuy, boolean isGoodForSell, boolean isRsiMetForBuy, boolean isRsiMetForSell, long elapsedTimeForOrder, OfferSide side) {
        try {
            log.info("Decide function inputs: lastBarData={}, isGoodForBuy={}, isGoodForSell={}, isRsiMetForBuy={}, isRsiMetForSell={}, elapsedTime={}, side={}",
                    lastBarData, isGoodForBuy, isGoodForSell, isRsiMetForBuy, isRsiMetForSell, elapsedTimeForOrder, side);

            boolean isBuyOrder = (side == OfferSide.BID);

            log.info("{} order criteria: isGood={}, isRsiMet={}", isBuyOrder ? "Buy" : "Sell", isBuyOrder ? isGoodForBuy : isGoodForSell, isBuyOrder ? isRsiMetForBuy : isRsiMetForSell);

            // Calculate minimum criteria
            boolean minimumCriteria;
            if (isBuyOrder) {
                minimumCriteria = isGoodForBuy && isRsiConditionsActiveForBuy;
            } else {
                minimumCriteria = isGoodForSell && isRsiConditionsActiveForSell;
            }

            log.info("{} order minimumCriteria: {}", isBuyOrder ? "Buy" : "Sell", minimumCriteria);

            // Check if the elapsed time is within the timer duration (in milliseconds)
            boolean isRsiTimerMet = elapsedTimeForOrder <= RSI_TIMER_DURATION;

            // Combine RSI condition and timer condition
            boolean shouldOpenOrder = isRsiTimerMet && minimumCriteria;
            log.info("Order should {}be opened. RSI Timer Met: {}, Minimum Criteria Met: {}", shouldOpenOrder ? "" : "NOT ", isRsiTimerMet, minimumCriteria);

            return shouldOpenOrder;

        } catch (Exception e) {
            log.error("Error occurred in decide method", e);
            throw new RuntimeException("Error occurred in decide method", e);
        }
    }


    /**
     * Open an order on the appropriate side with configured TP/SL.
     **/
    IOrder open(ITick lastTick, String label, OfferSide side) throws JFException {
        try {
            // Reset stop loss update times before processing each order
            resetStopLossUpdateTimes();

            log.info("Entering open method - lastTick: {}, label: {}, side: {}", lastTick, label, side);

            // Convert the ITick timestamp to a formatted String in UTC
            String timestamp = getFormattedTimestamp(lastTick.getTime());

            // Check if the offerSide is null and set a default value
            if (side == null) {
                log.info("OfferSide is null. Setting default value to OfferSide.BID");
                side = OfferSide.BID;
            }

            // Calculate RSI values and check RSI conditions
            Pair<Boolean, Boolean> rsiConditions = calculateRsiConditions(history, offerSide, period, lastTick);
            boolean isRsiMetForBuy = rsiConditions.getLeft();
            boolean isRsiMetForSell = rsiConditions.getRight();

            log.info("rsiMet value shouldOpen (Buy) in open method: {}", isRsiMetForBuy);
            log.info("rsiMet value shouldOpen (Sell) in open method: {}", isRsiMetForSell);

            // Check the conditions for opening a buy or sell order and whether RSI threshold conditions are met
            IOrder order = null;  // Initialize order to null

            try {

                if ((side == OfferSide.BID && isRsiConditionsActiveForBuy && rsiThreshold1ConditionsMet)
                        || (side == OfferSide.ASK && isRsiConditionsActiveForSell && rsiThreshold2ConditionsMet)) {

                    // Determine the order command (BUY or SELL)
                    IEngine.OrderCommand command = side == OfferSide.ASK ? IEngine.OrderCommand.SELL : IEngine.OrderCommand.BUY;

                    // Submit the order
                    log.info("Submitting order with label: {}, instrument: {}, command: {}, amount: {}", label, instrument, command, 1.0);
                    order = context.getEngine().submitOrder(label, instrument, command, 1.0, 0, 10);

                    // Wait for the order to be updated
                    log.info("Waiting for order update...");
                    order.waitForUpdate(3, TimeUnit.SECONDS);

                    // Check if the order was successfully filled
                    if (order.getState() == IOrder.State.FILLED || order.getState() == IOrder.State.OPENED) {
                        // Store the reference to the current order
                        currentOrder = order;

                        // Calculate and set the initial stop loss and take profit
                        double initialStopLoss = calculateInitialStopLoss(side, currentOrder);
                        double initialTakeProfit = calculateInitialTakeProfit(side, currentOrder, lastTick.getTime());

                        currentOrder.setStopLossPrice(initialStopLoss);
                        currentOrder.setTakeProfitPrice(initialTakeProfit);

                        log.info("[{}] Initial Stop Loss Price: {}", getCurrentFormattedTimestamp(lastTick.getTime()), initialStopLoss);
                        log.info("[{}] Initial Take Profit Price: {}", getCurrentFormattedTimestamp(lastTick.getTime()), initialTakeProfit);

                        initialAdjustmentMade = false;
                        takeProfitCancelled = false;

                        OfferSide finalSide = side;

                        if (isOrderOpen) {
                            log.info("Skipping order open. There is already an open order.");
                            return null;
                        }
/**
                        if (shouldApplySchedulerForManageTSL) {
                            log.info("Before startTrailingStopLossScheduler method call - Order state: {}", order.getState());
                            startTrailingStopLossScheduler(currentOrder, finalSide, lastTick);
                            log.info("After startTrailingStopLossScheduler method call - Order state: {}", order.getState());
                        }
*/
                        log.info("Exiting open method");
                        return order;
                    }

                }
            } catch (Exception e) {
                log.error("Error occurred during order processing", e);
                throw new JFException("Error occurred during order processing", e);
            } finally {
                // Cleanup code (always executed)
                isOrderOpen = false;
            }
            return null;
        } catch (Exception e) {
            log.error("Error occurred in open method", e);
            throw new JFException("Error occurred in open method", e);
        }
    }

    public boolean areAnyWindowsOpen(List<StrategyWindow> windows, ITick lastTick) {
        for (StrategyWindow window : windows) {
            if (window.isOpen(localTime(lastTick))) {
                return true; // At least one window is open
            }
        }
        return false; // No windows are open
    }

    private boolean isValidInput(BarData lastBarData, ITick lastTick) {
        if (lastBarData == null) {
            log.info("lastBarData is null");
            return false;
        }

        if (areAnyWindowsOpen(strategyWindows, lastTick)) {
            log.info("At least one strategy window is open.");
            return true;
        } else {
            log.info("All strategy windows are closed.");
            return false;
        }
    }


/**
    private boolean isGoodForBuy() throws JFException {
        initializeLastMinuteBarTime();

        if (isNewMinute()) {
            updateSMAValues();
        }

        return sma10Value > sma30Value;
    }

    private boolean isGoodForSell() throws JFException {
        initializeLastMinuteBarTime();

        if (isNewMinute()) {
            updateSMAValues();
        }

        return sma10Value < sma30Value;
    }

    private void updateSMAValues() throws JFException {
        IBar lastCompleteBar = history.getBar(instrument, Period.ONE_MIN, OfferSide.BID, 1);
        sma10Value = SMA.ten(history, indicators, instrument, OfferSide.BID);
        sma30Value = SMA.thirty(history, indicators, instrument, OfferSide.BID);
        sma10UpdateTime = lastCompleteBar.getTime();
        sma30UpdateTime = lastCompleteBar.getTime();
        lastMinuteBarTime = lastCompleteBar.getTime();
    }

    private void initializeLastMinuteBarTime() throws JFException {
        if (lastMinuteBarTime == 0) {
            lastMinuteBarTime = history.getLastTick(instrument).getTime();
        }
    }

    private boolean isNewMinute() throws JFException {
        long currentTime = history.getLastTick(instrument).getTime();
        boolean isNewMinute = currentTime / 60000 > lastMinuteBarTime / 60000;
        if (isNewMinute) {
            lastMinuteBarTime = currentTime;
        }
        return isNewMinute;
    }
   */
/**
    private boolean determineIsGoodForBuy() throws JFException {
        log.info("Entering determineIsGoodForBuy");

        // Get the current time
        long currentTime = history.getLastTick(instrument).getTime();
        //log.info("Current Time: {}", currentTime);
        //log.info("Before initializeLastMinuteBarTime - lastMinuteBarTime: {}", lastMinuteBarTime);
        initializeLastMinuteBarTime();
        //log.info("After initializeLastMinuteBarTime - lastMinuteBarTime: {}", lastMinuteBarTime);


        // Check if a new one-minute bar has started
        //log.info("Last Minute Bar Time: {}", lastMinuteBarTime);
        if (currentTime / 60000 > lastMinuteBarTime / 60000) {
            // A new minute has started, update SMA values
            IBar lastCompleteBar = history.getBar(instrument, Period.ONE_MIN, OfferSide.BID, 1);
            sma10Value = SMA.ten(history, indicators, instrument, OfferSide.BID);
            sma30Value = SMA.thirty(history, indicators, instrument, OfferSide.BID);
            sma10UpdateTime = lastCompleteBar.getTime();
            sma30UpdateTime = lastCompleteBar.getTime();
            lastMinuteBarTime = currentTime;

            log.info("determineIsGoodForBuy - Updating SMA values - sma10Value: {}, sma30Value: {}", sma10Value, sma30Value);
        } else {
            log.info("Condition not met for a new one-minute bar.");
        }

        // Determine if it's good for a buy order based on SMA values
        boolean isGoodForBuy = sma10Value > sma30Value;

        log.info("determineIsGoodForBuy - isGoodForBuy: {}", isGoodForBuy);

        log.info("Exiting determineIsGoodForBuy");
        return isGoodForBuy;
    }

    private boolean determineIsGoodForSell() throws JFException {
        log.info("Entering determineIsGoodForSell");

        // Get the current time
        long currentTime = history.getLastTick(instrument).getTime();
        //log.info("Current Time: {}", currentTime);
        //log.info("Before initializeLastMinuteBarTime - lastMinuteBarTime: {}", lastMinuteBarTime);
        initializeLastMinuteBarTime();
        //log.info("After initializeLastMinuteBarTime - lastMinuteBarTime: {}", lastMinuteBarTime);


        // Check if a new one-minute bar has started
        //log.info("Last Minute Bar Time: {}", lastMinuteBarTime);
        if (currentTime / 60000 > lastMinuteBarTime / 60000) {
            // A new minute has started, update SMA values
            IBar lastCompleteBar = history.getBar(instrument, Period.ONE_MIN, OfferSide.BID, 1);
            sma10Value = SMA.ten(history, indicators, instrument, OfferSide.BID);
            sma30Value = SMA.thirty(history, indicators, instrument, OfferSide.BID);
            sma10UpdateTime = lastCompleteBar.getTime();
            sma30UpdateTime = lastCompleteBar.getTime();
            lastMinuteBarTime = currentTime;

            log.info("determineIsGoodForSell - Updating SMA values - sma10Value: {}, sma30Value: {}", sma10Value, sma30Value);
        } else {
            log.info("Condition not met for a new one-minute bar.");
        }

        // Determine if it's good for a sell order based on SMA values
        boolean isGoodForSell = sma10Value < sma30Value;

        log.info("determineIsGoodForSell - isGoodForSell: {}", isGoodForSell);

        log.info("Exiting determineIsGoodForSell");
        return isGoodForSell;
    }
*/

    void initializeSMAValues() throws JFException {
        updateSMAValuesIfNeeded();
    }

    void updateSMAValuesIfNeeded() throws JFException {
        // Calculate the current SMA values

        double currentSMA10Value = SMA.seven(history, indicators, instrument, OfferSide.BID, Period.ONE_MIN);
        double currentSMA30Value = SMA.thirty(history, indicators, instrument, OfferSide.BID, Period.ONE_MIN);

        //double currentSMA10Value = SMA.ten(history, indicators, instrument, OfferSide.BID);
        //double currentSMA30Value = SMA.thirty(history, indicators, instrument, OfferSide.BID);

        // Log current SMA values before the update
        log.info("Current SMA10 (before update): {}, Current SMA30 (before update): {}", cachedSMA10Value, cachedSMA30Value);

        // Check if the current SMA values have changed
        if (currentSMA10Value != cachedSMA10Value || currentSMA30Value != cachedSMA30Value) {
            // Log previous SMA values
            log.info("Previous SMA10: {}, Previous SMA30: {}", cachedSMA10Value, cachedSMA30Value);

            // Update the previous SMA values
            previousSMA10Value = cachedSMA10Value;
            previousSMA30Value = cachedSMA30Value;

            // Update the current SMA values
            cachedSMA10Value = currentSMA10Value;
            cachedSMA30Value = currentSMA30Value;

            // Log updated SMA values
            log.info("Updated SMA10: {}, Updated SMA30: {}", cachedSMA10Value, cachedSMA30Value);
        }

        // Log current SMA values after the update
        log.info("Current SMA10 (after update): {}, Current SMA30 (after update): {}", cachedSMA10Value, cachedSMA30Value);
    }

    private boolean determineIsGoodForBuy() {
        log.info("Entering determineIsGoodForBuy method");

        if (Double.isNaN(previousSMA10Value) || Double.isNaN(previousSMA30Value)) {
            // Handle case where previous SMA values are not initialized
            log.warn("Previous SMA values are not initialized. Returning false.");
            return false;
        }

        boolean isGoodForBuy = cachedSMA10Value > cachedSMA30Value && previousSMA10Value < previousSMA30Value;

        //boolean isGoodForBuy = cachedSMA10Value > cachedSMA30Value;

        log.info("isGoodForBuy? {}", isGoodForBuy);
        log.info("Cached SMA10 Buy: {}, Cached SMA30: {}", cachedSMA10Value, cachedSMA30Value);
        log.info("Previous SMA10 Buy: {}, Previous SMA30: {}", previousSMA10Value, previousSMA30Value);
        log.info("Comparison of immediate values: SMA10 > SMA30: {}", cachedSMA10Value > cachedSMA30Value);
        log.info("Comparison of previous values: SMA10 > SMA30: {}", previousSMA10Value > previousSMA30Value);

        return isGoodForBuy;
    }

    private boolean determineIsGoodForSell() {
        log.info("Entering determineIsGoodForSell method");

        if (Double.isNaN(previousSMA10Value) || Double.isNaN(previousSMA30Value)) {
            // Handle case where previous SMA values are not initialized
            log.warn("Previous SMA values are not initialized. Returning false.");
            return false;
        }

        boolean isGoodForSell = cachedSMA10Value < cachedSMA30Value && previousSMA10Value > previousSMA30Value;

        //boolean isGoodForSell = cachedSMA10Value < cachedSMA30Value;

        log.info("isGoodForSell? {}", isGoodForSell);
        log.info("Cached SMA10 Sell: {}, Cached SMA30: {}", cachedSMA10Value, cachedSMA30Value);
        log.info("Previous SMA10 Sell: {}, Previous SMA30: {}", previousSMA10Value, previousSMA30Value);
        log.info("Comparison of immediate values: SMA10 < SMA30: {}", cachedSMA10Value < cachedSMA30Value);
        log.info("Comparison of previous values: SMA10 < SMA30: {}", previousSMA10Value < previousSMA30Value);

        return isGoodForSell;
    }


    /**
    private boolean determineIsGoodForBuy() throws JFException {
        // Get the current time
        long currentTime = history.getLastTick(instrument).getTime();
        initializeLastMinuteBarTime();

        // Check if a new one-minute bar has started
        if (currentTime / 60000 > lastMinuteBarTime / 60000) {
            // A new minute has started, update SMA values
            IBar lastCompleteBar = history.getBar(instrument, Period.ONE_MIN, OfferSide.BID, 1);
            double currentSMA10Value = SMA.ten(history, indicators, instrument, OfferSide.BID);
            double currentSMA30Value = SMA.thirty(history, indicators, instrument, OfferSide.BID);

            // Update previous SMA values before updating current ones
            previousSMA10Value = sma10Value;
            previousSMA30Value = sma30Value;

            sma10Value = currentSMA10Value;
            sma30Value = currentSMA30Value;

            sma10UpdateTime = lastCompleteBar.getTime();
            sma30UpdateTime = lastCompleteBar.getTime();
            lastMinuteBarTime = currentTime;
        }

        // Log current and previous SMA values
        log.info("Current SMA10: {}, Previous SMA10: {}", sma10Value, previousSMA10Value);
        log.info("Current SMA30: {}, Previous SMA30: {}", sma30Value, previousSMA30Value);

        // Determine if it's good for a buy order based on SMA values
        boolean isGoodForBuy = sma10Value > sma30Value && wasPreviouslyBelowSMA(sma10Value, sma30Value);

        return isGoodForBuy;
    }

    private boolean determineIsGoodForSell() throws JFException {
        // Get the current time
        long currentTime = history.getLastTick(instrument).getTime();
        initializeLastMinuteBarTime();

        // Check if a new one-minute bar has started
        if (currentTime / 60000 > lastMinuteBarTime / 60000) {
            // A new minute has started, update SMA values
            IBar lastCompleteBar = history.getBar(instrument, Period.ONE_MIN, OfferSide.BID, 1);
            double currentSMA10Value = SMA.ten(history, indicators, instrument, OfferSide.BID);
            double currentSMA30Value = SMA.thirty(history, indicators, instrument, OfferSide.BID);

            // Update previous SMA values before updating current ones
            previousSMA10Value = sma10Value;
            previousSMA30Value = sma30Value;

            sma10Value = currentSMA10Value;
            sma30Value = currentSMA30Value;

            sma10UpdateTime = lastCompleteBar.getTime();
            sma30UpdateTime = lastCompleteBar.getTime();
            lastMinuteBarTime = currentTime;
        }

        // Log current and previous SMA values
        log.info("Current SMA10: {}, Previous SMA10: {}", sma10Value, previousSMA10Value);
        log.info("Current SMA30: {}, Previous SMA30: {}", sma30Value, previousSMA30Value);

        // Determine if it's good for a sell order based on SMA values
        boolean isGoodForSell = sma10Value < sma30Value && wasPreviouslyAboveSMA(sma10Value, sma30Value);

        return isGoodForSell;
    }

    private boolean wasPreviouslyBelowSMA(double sma10Value, double sma30Value) {
        // Check if SMA 10 was previously below SMA 30
        boolean wasBelowSMA = sma10Value < sma30Value && previousSMA10Value < previousSMA30Value;

        if (wasBelowSMA) {
            sma10BelowSma30 = true;
            sma10AboveSma30 = false; // Reset the opposite state
        } else {
            sma10BelowSma30 = false; // Reset if not below SMA
        }

        log.info("Was previously below SMA: {}", wasBelowSMA);

        return wasBelowSMA;
    }

    private boolean wasPreviouslyAboveSMA(double sma10Value, double sma30Value) {
        // Check if SMA 10 was previously above SMA 30
        boolean wasAboveSMA = sma10Value > sma30Value && previousSMA10Value > previousSMA30Value;

        if (wasAboveSMA) {
            sma10AboveSma30 = true;
            sma10BelowSma30 = false; // Reset the opposite state
        } else {
            sma10AboveSma30 = false; // Reset if not above SMA
        }

        log.info("Was previously above SMA: {}", wasAboveSMA);

        return wasAboveSMA;
    }
*/

    private void initializeLastMinuteBarTime() throws JFException {
        IBar lastCompleteBar = history.getBar(instrument, Period.ONE_MIN, OfferSide.BID, 1);
        lastMinuteBarTime = lastCompleteBar.getTime();
        //log.info("Initializing lastMinuteBarTime to: {}", ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastMinuteBarTime), ZoneOffset.UTC));
    }

    public Pair<Boolean, Boolean> calculateRsiConditions(IHistory history, OfferSide offerSide, int period, ITick tick) throws JFException {
        // Get the timestamp of the last completed bar
        ZonedDateTime currentBarTime = getLastCompletedBarTime();

        // Calculate RSI for the last completed bar
        double currentRsiValue = rsiData.calculateCurrentRsiValue(history, offerSide, period, currentBarTime, bbPeriod, bbStdDev);

        // Check RSI conditions
        return checkRsiConditions(currentRsiValue, currentBarTime);
    }

    private ZonedDateTime getLastCompletedBarTime() {
        // Get the current time
        ZonedDateTime currentTime = ZonedDateTime.now(ZoneOffset.UTC);

        // Round down to the nearest minute to get the end time of the last completed bar
        return currentTime.truncatedTo(ChronoUnit.MINUTES);
    }

    private Pair<Boolean, Boolean> checkRsiConditions(double currentRsiValue, ZonedDateTime currentBarTime) throws JFException {
        ZonedDateTime logTimestamp = currentBarTime;
        log.info("checkRsiConditions method called at time: {}", logTimestamp);

        ZonedDateTime previousBarTime = currentBarTime.minusMinutes(1);

        // Calculate RSI conditions based on the updated RSI values
        Pair<Boolean, Boolean> rsiConditions = rsiData.isRsiConditionMet(history, offerSide, period, rsiThreshold1, rsiThreshold2, previousBarTime,
                currentRsiValue, logTimestamp, numBarsToCheck, currentBarTime, bbPeriod, bbStdDev);

        boolean isRsiMetForBuy = rsiConditions.getLeft();
        boolean isRsiMetForSell = rsiConditions.getRight();

        log.info("isRsiMetForBuy: {}, isRsiMetForSell: {}", isRsiMetForBuy, isRsiMetForSell);

        if (isRsiMetForBuy || isRsiMetForSell) {
            // Update your RSI flags or perform any necessary actions here
            lastRsiValue = currentRsiValue;
            lastRsiCalculationTime = logTimestamp.toInstant().toEpochMilli();
            log.info("Updated lastRsiValue: {} at time: {}", lastRsiValue, logTimestamp);
            log.info("Updated lastRsiCalculationTime: {} at time: {}", lastRsiCalculationTime, logTimestamp);
        }

        log.info("checkRsiConditions - isRsiMetForBuy: {}, isRsiMetForSell: {}", isRsiMetForBuy, isRsiMetForSell);
        return Pair.of(isRsiMetForBuy, isRsiMetForSell);
    }

    private boolean isCooldownTimerExpired(ITick lastTick) {
        return lastTick.getTime() - lastOrderTime >= COOLDOWN_TIMER_DURATION;
    }

    private void startTrailingStopLossScheduler(IOrder order, OfferSide side, ITick lastTick) {
        log.info("Entering startTrailingStopLossScheduler for order: {}, State: {}", order != null ? order.getLabel() : "null", order != null ? order.getState() : "null");

        if (order != null && (IOrder.State.FILLED.equals(order.getState()) || IOrder.State.OPENED.equals(order.getState()))) {
            // Check if the executor service is already running for the order
            if (trailingStopLossExecutorService == null || trailingStopLossExecutorService.isShutdown()) {
                log.info("Creating a new executor service for order: {}", order.getLabel());
                log.info("[{}] Take Profit Level in startTrailingStopLossScheduler: {}", getCurrentFormattedTimestamp(lastTick.getTime()), order.getTakeProfitPrice());

                // Configurable scheduling parameters
                long initialDelay = 0;
                long delayBetweenExecutions = 300; // delay in milliseconds

                // Create a new executor service for trailing stop loss management
                trailingStopLossExecutorService = Executors.newScheduledThreadPool(1);

                log.info("Scheduler started for order: {}", order.getLabel());

                AtomicLong taskExecutionTime = new AtomicLong();

                trailingStopLossExecutorService.scheduleWithFixedDelay(() -> {
                    try {
                        log.info("Scheduled task for managing trailing stop loss is running for order: {}", order.getLabel());

                        // Record the start time of the task
                        long startTime = System.currentTimeMillis();

                        // Check if the order is still active
                        if (order.getState() == IOrder.State.FILLED || order.getState() == IOrder.State.OPENED) {
                            manageTrailingStopLoss(order, side, lastTick);
                            log.info("manageTrailingStopLoss executed for order: {}", order.getLabel());
                            log.info("[{}] Take Profit Level in manageTrailingStopLoss: {}", getCurrentFormattedTimestamp(lastTick.getTime()), order.getTakeProfitPrice());
                        } else {
                            log.info("Order is no longer active. Shutting down stop loss executor for order: {}", order.getLabel());
                            stopTrailingStopLossScheduler(); // Shut down the scheduler when the order is no longer active
                        }

                        // Record the end time of the task
                        long endTime = System.currentTimeMillis();

                        // Calculate the time taken by the task
                        taskExecutionTime.set(endTime - startTime);
                        log.info("Task execution time: {} milliseconds for order: {}", taskExecutionTime.get(), order.getLabel());
                    } catch (Exception e) {
                        log.error("Error in scheduled task for managing trailing stop loss for order: {}", order.getLabel(), e);
                    }
                }, initialDelay, delayBetweenExecutions, TimeUnit.MILLISECONDS);
            } else {
                log.info("Trailing stop loss scheduler is already running for order: {}", order.getLabel());
            }
        } else {
            log.warn("Cannot start trailing stop loss scheduler. Order is null or not in FILLED state.");
        }

        log.info("Exiting startTrailingStopLossScheduler for order: {}", order != null ? order.getLabel() : "null");
    }

    private void stopTrailingStopLossScheduler() {
        if (trailingStopLossExecutorService != null && !trailingStopLossExecutorService.isShutdown()) {
            trailingStopLossExecutorService.shutdown(); // Shut down the executor service
            try {
                // Wait for the executor service to terminate
                if (!trailingStopLossExecutorService.awaitTermination(1, TimeUnit.SECONDS)) {
                    // Forcefully shut down the executor service if it doesn't terminate within the specified time
                    trailingStopLossExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                // Preserve interrupt status
                Thread.currentThread().interrupt();
                // (Re-)Cancel if current thread also interrupted
                trailingStopLossExecutorService.shutdownNow();
            }
            log.info("Scheduler shut down for trailing stop loss.");
        }
    }


    private void manageTrailingStopLoss(IOrder order, OfferSide side, ITick lastTick) {
        try {
            if (order.getState() != IOrder.State.FILLED && order.getState() != IOrder.State.OPENED) {
                log.info("Order is neither in FILLED nor OPENED state. Stopping trailing stop loss management for order: {}", order.getLabel());
                return;
            }

            ITick latestTick = history.getLastTick(order.getInstrument());

            if (latestTick == null) {
                log.warn("Latest tick is null. Exiting manageTrailingStopLoss for order: {}", order.getLabel());
                return;
            }

            ZonedDateTime logTimestamp = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(latestTick.getTime()), ZoneOffset.UTC
            );

            log.info("[{}] Entering manageTrailingStopLoss for order: {}", logTimestamp, order.getLabel());
            log.info("[{}] Latest tick fetched at: {}", logTimestamp, getCurrentFormattedTimestamp(latestTick.getTime()));
            log.info("[{}] Latest tick fetched successfully.", logTimestamp);

            // Submit a task to the main strategy thread's executor service
            context.executeTask(() -> {
                try {
                    // Call adjustStopLoss method from the main strategy thread
                    double newStopLoss = adjustStopLoss(order, side, lastTick.getTime());

                    // Check the distance from entry for activating trailing stop loss
                    double entryPrice = order.getOpenPrice();
                    double currentMarketPrice = order.isLong() ? latestTick.getAsk() : latestTick.getBid();
                    double priceDistance = Math.abs(currentMarketPrice - entryPrice);

                    log.info("[{}] Entry Price: {}", logTimestamp, entryPrice);
                    log.info("[{}] Current Market Price: {}", logTimestamp, currentMarketPrice);
                    log.info("[{}] Price Distance: {}", logTimestamp, priceDistance);
                    log.info("[{}] Trailing Stop Activation Threshold: {}", logTimestamp, trailingStopActivationThreshold);

                    // Check if the new stop loss is different from the initial stop loss due to mitigation
                    boolean mitigationTriggered = mitigationApplied;
                    log.info("[{}] New Stop Loss: {}, Current Stop Loss: {}, Mitigation triggered: {}",
                            logTimestamp, newStopLoss, order.getStopLossPrice(), mitigationTriggered);

                    // Update stop loss if mitigation is triggered
                    if (mitigationTriggered) {
                        initialStopLossAdjustmentMade = true;
                        log.info("[{}] Initial Stop Loss adjusted due to mitigation to: {}", logTimestamp, newStopLoss);
                        // Call updateStopLossIfNeeded to update the stop loss
                        updateStopLossIfNeededMitigation(order, order.getStopLossPrice(), newStopLoss, latestTick);
                    }

                    // Check if the trailing stop loss should be activated
                    if (priceDistance > trailingStopActivationThreshold) {
                        // Activate trailing stop loss
                        log.info("[{}] Activating trailing stop loss.", logTimestamp);
                        handleTakeProfitCancellation(order, lastTick.getTime(), latestTick, calculateProfit(order, latestTick), side);
                        log.info("[{}] New Take Profit Level after handleTakeProfitCancellation: {}", getCurrentFormattedTimestamp(latestTick.getTime()), order.getTakeProfitPrice());

                        // Update stop loss for trailing stop loss without considering mitigation
                        updateStopLossIfNeededTrailing(order, order.getStopLossPrice(), newStopLoss, latestTick, side);

                        if (!initialStopLossAdjustmentMade) {
                            initialStopLossAdjustmentMade = true; // Set the flag after the initial adjustment
                        }
                    } else {
                        log.info("Distance from entry is below the threshold. Trailing stop not activated.");
                    }
                } catch (JFException e) {
                    log.error("Error in adjustStopLoss method", e);
                } catch (Exception e) {
                    log.error("Error in managing trailing stop loss for order: {}", order.getLabel(), e);
                }
                return null;
            });

        } catch (Exception e) {
            log.error("Error in managing trailing stop loss for order: {}", order.getLabel(), e);
        }
    }

    private void updateStopLossIfNeededMitigation(IOrder order, double currentStopLoss, double newStopLoss, ITick latestTick) {
        log.info("Before stop loss update - Current Stop Loss: {}", currentStopLoss);
        log.info("Updating stop loss to: {}", newStopLoss);

        // Check if the order is modifiable and filled
        if (order.getState() == IOrder.State.FILLED && isOrderModifiable(order)) {
            log.info("Order is modifiable and filled.");

            if (currentStopLoss != newStopLoss) {
                log.info("Current Stop Loss ({}) is different from New Stop Loss ({}).", currentStopLoss, newStopLoss);

                // Update the stop loss
                updateStopLoss(order, newStopLoss, latestTick.getTime());

                // Log the updated stop loss and profit
                double updatedStopLoss = order.getStopLossPrice();
                double profit = calculateProfit(order, latestTick);
                log.info("Stop Loss updated to: {}", updatedStopLoss);
                log.info("Profit after stop loss update: {}", String.format("%.8f", profit));

                // Log that the stop loss was adjusted due to mitigation
                log.info("Stop Loss adjusted due to mitigation for order {}: from {} to {}",
                        order.getLabel(), currentStopLoss, newStopLoss);
            } else {
                log.info("Current Stop Loss is the same as the New Stop Loss. Skipping stop loss update.");
            }
        } else {
            log.info("Order is not modifiable or not in a filled state. Skipping stop loss update.");
        }
    }

    private void updateStopLossIfNeededTrailing(IOrder order, double currentStopLoss, double newStopLoss, ITick latestTick, OfferSide side) throws JFException {
        log.info("Before stop loss update - Current Stop Loss: {}", currentStopLoss);
        log.info("Updating stop loss to: {}", newStopLoss);
        log.info("[{}] New Take Profit Level after cancellation in updateStopLossIfNeeded: {}", getCurrentFormattedTimestamp(latestTick.getTime()), order.getTakeProfitPrice());

        double currentProfit = calculateProfit(order, latestTick);
        log.info("Calculated Profit: {}", String.format("%.8f", currentProfit));

        double trailingStart;
        if (initialStopLossAdjustmentMade) {
            // Use the trailingStartForSubsequentAdjustments for subsequent adjustments
            trailingStart = round(trailingStartForSubsequentAdjustments * Math.abs(currentProfit), 8);
        } else {
            // Use the trailingStartForInitialAdjustment for the initial adjustment
            trailingStart = round(trailingStartForInitialAdjustment * Math.abs(currentProfit), 8);
        }
        log.info("trailingStart in updateStopLossIfNeeded: {}", String.format("%.8f", trailingStart));

        // Calculate the trailing stop loss
        double trailingStopLoss = calculateTrailingStopLoss(order, trailingStart, latestTick);
        log.info("Calculated Trailing Stop Loss: {}", trailingStopLoss);
        log.info("[{}] Calculate Trailing Stop Loss: {}, Last Tick - Bid: {}, Ask: {}", getCurrentFormattedTimestamp(latestTick.getTime()), trailingStopLoss, latestTick.getBid(), latestTick.getAsk());

        // Check if the order is modifiable and filled
        if (isOrderModifiableAndFilled(order, currentProfit)) {
            log.info("Order is modifiable and filled.");

            boolean shouldUpdateStopLoss = false;

            // Determine if the trailing stop loss should be updated based on the position side (long or short)
            if (order.isLong() && trailingStopLoss > currentStopLoss) {
                shouldUpdateStopLoss = true;
            } else if (!order.isLong() && trailingStopLoss < currentStopLoss) {
                shouldUpdateStopLoss = true;
            }

            if (shouldUpdateStopLoss) {
                log.info("Current Stop Loss ({}) is different from Trailing Stop Loss ({}).", currentStopLoss, trailingStopLoss);

                double minStopLoss = side == OfferSide.BID ?
                        latestTick.getAsk() - trailingStart :
                        latestTick.getBid() + trailingStart;

                log.info("Min Stop Loss: {}", minStopLoss);

                trailingStopLoss = side == OfferSide.BID ?
                        Math.max(trailingStopLoss, minStopLoss) :
                        Math.min(trailingStopLoss, minStopLoss);

                log.info("Adjusted Trailing Stop Loss: {}", trailingStopLoss);

                double roundedCurrentStopLoss = roundAndAdjust(currentStopLoss, trailingStart, !order.isLong());
                double roundedNewStopLoss = roundAndAdjust(trailingStopLoss, trailingStart, !order.isLong());

                log.info("Rounded Current Stop Loss: {}", roundedCurrentStopLoss);
                log.info("Rounded Trailing Stop Loss: {}", roundedNewStopLoss);

                double currentMarketPrice = side == OfferSide.BID ? latestTick.getBid() : latestTick.getAsk();
                log.info("Current Market Price: {}", currentMarketPrice);

                if (roundedCurrentStopLoss != roundedNewStopLoss) {
                    log.info("Updating stop loss...");
                    updateStopLoss(order, roundedNewStopLoss, latestTick.getTime());
                    double profit = calculateProfit(order, latestTick);
                    log.info("Profit after stop loss update: {}", String.format("%.8f", profit));
                } else {
                    log.info("Rounded values are the same. Skipping stop loss update.");
                }
            } else {
                log.info("Trailing stop loss would move backwards. Skipping stop loss update.");
            }
        } else {
            log.info("Order is not modifiable or current profit is not valid. Skipping stop loss update.");
        }
    }


    /**
     * Adjust the stop loss for an order, including trailing logic.
     */
    private synchronized double adjustStopLoss(IOrder order, OfferSide side, long lastTickTime) throws JFException {
        ZonedDateTime logTimestamp = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastTickTime), ZoneOffset.UTC);

        try {
            log.info("[{}] adjustStopLoss method invoked for order: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel());

            // Check if the order state is FILLED
            if (order.getState() != IOrder.State.FILLED) {
                log.warn("[{}] Invalid order state. Cannot adjust stop loss.", logTimestamp);
                return 0.0;
            }

            // Check if the stop loss was updated less than one second ago
            String orderIdStr = order.getId();
            Long orderId = Long.valueOf(orderIdStr);
            if (lastStopLossUpdateTimes.containsKey(orderId)) {
                long lastUpdateTime = lastStopLossUpdateTimes.get(orderId);
                long elapsedTime = lastTickTime - lastUpdateTime;
                if (elapsedTime < 1000) { // One second in milliseconds
                    log.warn("[{}] Cannot change stop loss more than once per second for order: {}", logTimestamp, order.getLabel());
                    return order.getStopLossPrice(); // Return the current stop loss without updating
                }
            }

            // Update the last stop loss update time for the order
            long orderIdLong = Long.parseLong(order.getId());
            lastStopLossUpdateTimes.put(orderIdLong, lastTickTime);

            // Calculate initial stop loss and take profit
            double initialStopLoss = calculateInitialStopLoss(side, order);

            // Ensure initial adjustment is made only once after the order is filled
            if (!initialAdjustmentMade) {
                order.setStopLossPrice(initialStopLoss);
                log.info("[{}] Initial Stop Loss adjusted to {}", logTimestamp, initialStopLoss);
                initialAdjustmentMade = true;
            } else {
                // Calculate new stop loss
                double newStopLoss = calculateAdjustedStopLoss(order, initialStopLoss, lastTickTime, deltaHolder);
                log.info("[{}] Adjusted Stop Loss (within limits): {}", getCurrentFormattedTimestamp(lastTickTime), newStopLoss);
                log.info("[{}] Difference with current Stop Loss: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", Math.abs(newStopLoss - initialStopLoss)));

                // Log current market price
                ITick latestTick = history.getLastTick(order.getInstrument());
                if (latestTick != null) {
                    double currentMarketPrice = side == OfferSide.BID ? latestTick.getBid() : latestTick.getAsk();
                    log.info("[{}] Current Market Price in adjustStopLoss: {}", getCurrentFormattedTimestamp(lastTickTime), currentMarketPrice);
                } else {
                    log.warn("[{}] Latest tick is null. Cannot determine current market price.", logTimestamp);
                }

                // Update stop loss only if it has changed
                if (newStopLoss != initialStopLoss) {
                    try {
                        order.setStopLossPrice(newStopLoss);
                        log.info("[{}] Stop Loss adjusted to {}", logTimestamp, newStopLoss);
                    } catch (JFException ex) {
                        // Handle the case where the order is in an immutable state
                        log.warn("[{}] Cannot modify stop loss. Order state is immutable.", logTimestamp);
                        return 0.0;
                    }
                } else {
                    log.info("[{}] Stop Loss adjustment skipped.", logTimestamp);
                }
            }

            // Always return the latest stop loss
            return order.getStopLossPrice();
        } catch (JFException e) {
            log.error("[{}] Error occurred in adjustStopLoss method", logTimestamp, e);
            throw new JFException("Error occurred in adjustStopLoss method", e);
        }
    }

    private double calculateAdjustedStopLoss(IOrder order, double initialStopLoss, long lastTickTime, AtomicReference<Double> deltaHolder) throws JFException {
        try {

            double pipValue = order.getInstrument().getPipValue();
            double currentStopLoss = initialStopLoss;
            log.info("[{}] Current Stop Loss: {}", getCurrentFormattedTimestamp(lastTickTime), currentStopLoss);
            logOrderDetails(order);

            double maxStopLoss = calculateMaxStopLoss(order);
            double minStopLossValue = currentStopLoss; // Use the initial stop loss as a base
            double directionMultiplier = order.isLong() ? 1 : -1;

            log.info("[{}] Before Delta Calculation - Initial Stop Loss: {}, Trailing Start Percentage: {}, Direction Multiplier: {}, Pip Value: {}",
                    getCurrentFormattedTimestamp(lastTickTime), initialStopLoss, trailingStartPercentage, directionMultiplier, pipValue);

            double delta = calculateDelta(initialStopLoss, trailingStartPercentage, directionMultiplier, pipValue);
            double maxDelta = calculateMaxDelta(order, pipValue);

            log.info("[{}] Delta: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", delta));
            log.info("[{}] Max Delta: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", maxDelta));

            log.info("[{}] Delta before startStopLossMitigationScheduler: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", delta));

            // Check if stop loss mitigation scheduler should be used
            if (!stopLossMitigationApplied && shouldStopLossMitigationScheduler) {
                deltaHolder.set(delta);
                log.info("Before startStopLossMitigationScheduler method call - Order state: {}", order.getState());
                log.info("Delta holder before startStopLossMitigationScheduler: {}", String.format("%.8f", deltaHolder.get()));

                // Create a CompletableFuture to execute startStopLossMitigationScheduler asynchronously
                CompletableFuture<Void> future = startStopLossMitigationScheduler(order, lastTickTime, deltaHolder, directionMultiplier);

                // Wait for the completion of the asynchronous task
                future.join();

                log.info("After startStopLossMitigationScheduler method call - Order state: {}", order.getState());
                log.info("Delta holder after startStopLossMitigationScheduler: {}", String.format("%.8f", deltaHolder.get()));

                // Retrieve the updated delta value after the scheduler task completes
                final double updatedDelta = deltaHolder.get();
                log.info("[{}] Retrieved updated delta value: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", updatedDelta));

                delta = updatedDelta;
                log.info("[{}] Delta variable updated with the updated value: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", delta));

            }

            // Calculate new stop loss using retrieved delta
            double newStopLoss = calculateNewStopLoss(currentStopLoss, directionMultiplier, delta, maxDelta, minStopLossValue, maxStopLoss);
            log.info("[{}] New Stop Loss after calculation: {}", getCurrentFormattedTimestamp(lastTickTime), newStopLoss);

            if (mitigationApplied) {
                log.info("[{}] Mitigation logic applied to determine newStopLoss.", getCurrentFormattedTimestamp(lastTickTime));
            }

            return newStopLoss;

        } catch (Exception e) {
            log.error("[{}] Exception in calculateAdjustedStopLoss", getCurrentFormattedTimestamp(lastTickTime), e);
            return 0.0;
        }
    }

    private double calculateTrailingStart(double initialStopLoss, double trailingStartPercentage, OfferSide side) {
        // Ensure the percentage is within a valid range (0-100)
        trailingStartPercentage = Math.max(0, Math.min(100, trailingStartPercentage));

        // Calculate the trailing start based on the percentage and direction (long or short)
        return side == OfferSide.BID ?
                initialStopLoss + (initialStopLoss * trailingStartPercentage / 100.0) :
                initialStopLoss - (initialStopLoss * trailingStartPercentage / 100.0);
    }

    private double calculateNewStopLoss(double currentStopLoss, double directionMultiplier, double delta, double maxDelta, double minStopLossValue, double maxStopLoss) {
        log.info("Input values: Current Stop Loss={}, Direction Multiplier={}, Delta={}, Max Delta={}", currentStopLoss, directionMultiplier, String.format("%.8f", delta), String.format("%.8f", maxDelta));

        // Calculate the potential new stop loss based on the current stop loss and delta
        double potentialNewStopLoss = currentStopLoss + directionMultiplier * Math.abs(delta);
        log.info("Potential New Stop Loss: {}", potentialNewStopLoss);

        // Adjust the potential new stop loss to ensure it doesn't exceed the maximum or minimum allowed values
        double newStopLoss;
        if (directionMultiplier > 0) {
            // For BUY orders, ensure the potential new stop loss is above minStopLossValue
            newStopLoss = Math.min(potentialNewStopLoss, minStopLossValue);
            log.info("Adjusted Stop Loss for BUY order: {}", newStopLoss);
        } else if (directionMultiplier < 0) {
            // For SELL orders, ensure the potential new stop loss is below maxStopLoss
            newStopLoss = Math.max(potentialNewStopLoss, maxStopLoss);
            log.info("Adjusted Stop Loss for SELL order: {}", newStopLoss);
        } else {
            // Invalid direction, keep the current stop loss unchanged
            newStopLoss = currentStopLoss;
            log.warn("Invalid direction multiplier: {}", directionMultiplier);
        }

        // Round the new stop loss to 5 decimal places
        newStopLoss = roundTo5DecimalPlaces(newStopLoss);
        log.info("Final Adjusted Stop Loss: {}", newStopLoss);

        return newStopLoss;
    }

    private void handleTakeProfitCancellation(IOrder order, long lastTickTime, ITick latestTick, double currentProfit, OfferSide side) {
        ZonedDateTime logTimestamp = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastTickTime), ZoneOffset.UTC);

        // Check if take profit has already been canceled
        if (takeProfitCancelled) {
            log.info("Take profit has already been canceled. Skipping cancellation process.");
            return;
        }

        if (latestTick != null) {
            double currentMarketPrice;

            if (order.isLong()) {
                currentMarketPrice = latestTick.getAsk();
            } else {
                currentMarketPrice = latestTick.getBid();
            }

            log.info("[{}] Current Market Price: {}", getCurrentFormattedTimestamp(lastTickTime), currentMarketPrice);

            // Check if currentMarketPrice is a valid and non-NaN value
            if (!Double.isNaN(currentMarketPrice)) {
                try {
                    // Validate if currentMarketPrice is a finite number
                    if (Double.isFinite(currentMarketPrice)) {
                        log.info("[{}] Market price is a finite number.", logTimestamp);

                        double pipValue = order.getInstrument().getPipValue();
                        double originalTakeProfit = calculateInitialTakeProfit(side, order, lastTickTime);
                        double cancelTakeProfitPips = takeProfitPips * cancelTakeProfitThreshold;

                        log.info("[{}] Pip Value: {}", logTimestamp, pipValue);
                        log.info("[{}] Original Take Profit: {}", logTimestamp, originalTakeProfit);
                        log.info("[{}] Cancel Take Profit Pips: {}", logTimestamp, cancelTakeProfitPips);

                        double cancelTakeProfitLevel;

                        if (order.isLong()) {
                            cancelTakeProfitLevel = originalTakeProfit - (cancelTakeProfitPips * pipValue);
                        } else {
                            cancelTakeProfitLevel = originalTakeProfit + (cancelTakeProfitPips * pipValue);
                        }

                        log.info("[{}] Cancel Take Profit Level: {}", logTimestamp, cancelTakeProfitLevel);

                        if (order.isLong() && currentMarketPrice >= cancelTakeProfitLevel) {
                            log.info("[{}] Cancelling Take Profit because Market price ({}) is above cancelTakeProfitLevel ({}).", logTimestamp, currentMarketPrice, cancelTakeProfitLevel);
                            cancelTakeProfit(order, lastTickTime, currentProfit);
                            log.info("[{}] New Take Profit Level after cancelTakeProfit long: {}", getCurrentFormattedTimestamp(lastTickTime), order.getTakeProfitPrice());

                        } else if (!order.isLong() && currentMarketPrice <= cancelTakeProfitLevel) {
                            log.info("[{}] Cancelling Take Profit because Market price ({}) is below cancelTakeProfitLevel ({}).", logTimestamp, currentMarketPrice, cancelTakeProfitLevel);
                            cancelTakeProfit(order, lastTickTime, currentProfit);
                            log.info("[{}] New Take Profit Level after cancelTakeProfit short: {}", getCurrentFormattedTimestamp(lastTickTime), order.getTakeProfitPrice());

                            takeProfitCancelled = true;

                        } else {
                            log.info("[{}] Market price ({}) is outside cancelTakeProfitLevel ({}). Cancelling Take Profit skipped.", logTimestamp, currentMarketPrice, cancelTakeProfitLevel);
                        }
                    } else {
                        log.warn("[{}] Market price is not a finite number. Unable to check against cancelTakeProfitLevel.", logTimestamp);
                    }
                } catch (Exception e) {
                    log.error("[{}] Error during cancelTakeProfit: {}", logTimestamp, e.getMessage());
                }
            } else {
                log.warn("[{}] Market price returned NaN or invalid value. Unable to check against cancelTakeProfitLevel.", logTimestamp);
            }
        } else {
            log.warn("[{}] Unable to fetch the latest tick. Cancelling Take Profit skipped.", logTimestamp);
        }
    }

    private void cancelTakeProfit(IOrder order, long lastTickTime, double currentProfit) {
        try {
            log.info("[{}] Attempting to cancel Take Profit for order: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel());

            synchronized (order) {
                // Validate if the order is modifiable and filled
                if (isOrderModifiableAndFilled(order, currentProfit)) {
                    log.info("[{}] Order state before cancellation: {}", getCurrentFormattedTimestamp(lastTickTime), order.getState());

                    // Extract pip value
                    double pipValue = order.getInstrument().getPipValue();

                    // Adjust the take profit based on order type
                    double newTakeProfit = order.isLong() ? order.getOpenPrice() + (100.0 * pipValue) : order.getOpenPrice() - (100.0 * pipValue);

                    log.info("[{}] Attempting to set new Take Profit: {}", getCurrentFormattedTimestamp(lastTickTime), newTakeProfit);

                    // Set the new take profit
                    order.setTakeProfitPrice(newTakeProfit);

                    log.info("[{}] New Take Profit Level set successfully: {}, Order ID: {}", getCurrentFormattedTimestamp(lastTickTime), newTakeProfit, order.getId());

                    // Log the order state after setting the new take profit
                    log.info("[{}] Order state after setting new Take Profit: {}", getCurrentFormattedTimestamp(lastTickTime), order.getState());


                    // Create a new executor service for each order
                    ExecutorService executorService = Executors.newSingleThreadExecutor();

                    // Execute waiting period in a separate thread
                    executorService.submit(() -> {
                        try {
                            // Introduce a waiting period
                            int maxWaitAttempts = 10;
                            int waitIntervalMillis = 200;  // Adjust as needed

                            long startTime = System.currentTimeMillis();  // Record start time

                            // Log the current state of the order before initiating the update
                            log.info("[{}] Current state of the order before update - Take Profit Price: {}", getCurrentFormattedTimestamp(lastTickTime), order.getTakeProfitPrice());

                            for (int attempt = 0; attempt < maxWaitAttempts; attempt++) {
                                // Check if the order's take profit has been updated on the server
                                if (order.getTakeProfitPrice() == newTakeProfit) {
                                    long endTime = System.currentTimeMillis();  // Record end time
                                    log.info("[{}] Server has acknowledged the Take Profit update for Order ID: {}.", getCurrentFormattedTimestamp(lastTickTime), order.getId());
                                    log.info("[{}] Duration for server acknowledgment: {} milliseconds", getCurrentFormattedTimestamp(lastTickTime), endTime - startTime);

                                    // Introduce a short delay before fetching order details
                                    //Thread.sleep(1000);  // Adjust as needed (in milliseconds)

                                    // Fetch the latest order details from the server
                                    IOrder updatedOrder = fetchOrderDetailsFromServer(order.getLabel(), lastTickTime);

                                    // Log the server's response
                                    log.info("[{}] Server Response - Take Profit Price: {}", getCurrentFormattedTimestamp(lastTickTime),
                                            (updatedOrder != null) ? updatedOrder.getTakeProfitPrice() : "N/A");
                                    log.info("Updated Take Profit Price: {}",
                                            (updatedOrder != null) ? updatedOrder.getTakeProfitPrice() : "N/A");


                                    // Log the current state of the order after the update
                                    log.info("[{}] Current state of the order after update - Take Profit Price: {}", getCurrentFormattedTimestamp(lastTickTime),
                                            (updatedOrder != null) ? updatedOrder.getTakeProfitPrice() : "N/A");

                                    break;
                                }
                                // Log the current attempt and sleep duration
                                log.info("[{}] Waiting for server acknowledgment. Attempt: {}", getCurrentFormattedTimestamp(lastTickTime), attempt + 1);
                                Thread.sleep(waitIntervalMillis);

                            }

                            log.info("[{}] Order state after cancellation: {}", getCurrentFormattedTimestamp(lastTickTime), order.getState());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();  // Restore interrupted status
                            log.error("[{}] Thread interrupted during cancelTakeProfit waiting period.", getCurrentFormattedTimestamp(lastTickTime));
                        } finally {
                            // Shutdown the executor service after the waiting period
                            executorService.shutdown();
                        }
                    });

                } else {
                    log.warn("[{}] Unable to cancel Take Profit. Invalid order state or not modifiable. Order: {} State: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel(), order.getState());
                }
            }
        } catch (JFException jfe) {
            log.error("[{}] Error during cancelTakeProfit for order: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel(), jfe);
        } catch (Exception e) {
            log.error("[{}] Unexpected error during cancelTakeProfit for order: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel(), e);
        }
    }

    private double calculateInitialStopLoss(OfferSide side, IOrder currentOrder) {
        double openPrice = currentOrder.getOpenPrice();
        BigDecimal pipValue = new BigDecimal(Double.toString(currentOrder.getInstrument().getPipValue()));
        BigDecimal stopLossPipsBigDecimal = new BigDecimal(Double.toString(stopLossPips));

        BigDecimal stopLossValue = stopLossPipsBigDecimal.multiply(pipValue);

        log.info("Calculating initial stop loss:");
        log.info("Open Price: {}", openPrice);
        log.info("Pip Value: {}", pipValue);
        log.info("Stop Loss Pips: {}", stopLossPips);
        log.info("Stop Loss Value: {}", stopLossValue);

        BigDecimal initialStopLossBigDecimal;
        if (side == OfferSide.BID) {
            initialStopLossBigDecimal = new BigDecimal(Double.toString(openPrice)).subtract(stopLossValue);
            log.info("Initial Stop Loss Calculation (BID): openPrice - stopLossValue");
        } else {
            initialStopLossBigDecimal = new BigDecimal(Double.toString(openPrice)).add(stopLossValue);
            log.info("Initial Stop Loss Calculation (ASK): openPrice + stopLossValue");
        }

        double initialStopLoss = initialStopLossBigDecimal.doubleValue();
        log.info("Initial Stop Loss: {}", initialStopLoss);

        return initialStopLoss;
    }

    private double calculateInitialTakeProfit(OfferSide side, IOrder currentOrder, long lastTickTime) {
        double openPrice = currentOrder.getOpenPrice();
        BigDecimal pipValue = new BigDecimal(Double.toString(currentOrder.getInstrument().getPipValue()));
        BigDecimal takeProfitPipsBigDecimal = new BigDecimal(Double.toString(takeProfitPips));

        log.info("Calculating initial take profit:");
        log.info("Open Price: {}", openPrice);
        log.info("Pip Value: {}", pipValue);
        log.info("Take Profit Pips: {}", takeProfitPips);

        BigDecimal takeProfitValue = takeProfitPipsBigDecimal.multiply(pipValue);
        log.info("Take Profit Value: {}", takeProfitValue);

        BigDecimal openPriceBigDecimal = new BigDecimal(Double.toString(openPrice));
        BigDecimal initialTakeProfitBigDecimal;

        if (side == OfferSide.BID) {
            initialTakeProfitBigDecimal = openPriceBigDecimal.add(takeProfitValue);
            log.info("Initial Take Profit Calculation (BID): openPrice + takeProfitValue");
        } else {
            initialTakeProfitBigDecimal = openPriceBigDecimal.subtract(takeProfitValue);
            log.info("Initial Take Profit Calculation (ASK): openPrice - takeProfitValue");
        }

        double initialTakeProfit = initialTakeProfitBigDecimal.doubleValue();
        log.info("Initial Take Profit: {}", initialTakeProfit);

        return initialTakeProfit;
    }

    /**
     * Calculate the maximum allowable stop loss based on the initial stop loss.
     */
    private double calculateMaxStopLoss(IOrder order) {
        double initialStopLoss = order.getStopLossPrice();
        double pipValue = order.getInstrument().getPipValue();
        double openPrice = order.getOpenPrice();

        return order.isLong()
                ? openPrice - pipValue * initialStopLoss
                : openPrice + pipValue * initialStopLoss;
    }

    private double calculateDelta(double initialStopLoss, double trailingStartPercentage, double directionMultiplier, double pipValue) {
        double trailingStart = calculateTrailingStart(initialStopLoss, trailingStartPercentage, directionMultiplier > 0 ? OfferSide.BID : OfferSide.ASK);
        return directionMultiplier * trailingStart * pipValue;
    }

    /**
     * Calculate the maximum allowable delta for stop loss adjustment based on the initial stop loss.
     */
    private double calculateMaxDelta(IOrder order, double pipValue) throws JFException {
        double initialStopLoss = order.getStopLossPrice();
        double openPrice = order.getOpenPrice();
        double directionMultiplier = order.isLong() ? 1 : -1;

        // Get the current market bid and ask prices
        ITick lastTick = history.getLastTick(order.getInstrument());

        // Check if the last tick is available
        if (lastTick == null) {
            log.error("Last tick is null. Unable to calculate max delta.");
            return 0.0;
        }

        double currentBidPrice = lastTick.getBid();
        double currentAskPrice = lastTick.getAsk();

        // Validate values to ensure they are not NaN
        if (Double.isNaN(initialStopLoss) || Double.isNaN(openPrice) || Double.isNaN(currentBidPrice) || Double.isNaN(currentAskPrice)) {
            log.error("Invalid values for calculation. Unable to calculate max delta.");
            return 0.0;
        }

        // Calculate the maximum allowable delta based on the current market conditions
        double maxDeltaBid = directionMultiplier * pipValue * Math.abs(currentBidPrice - openPrice);
        double maxDeltaAsk = directionMultiplier * pipValue * Math.abs(currentAskPrice - openPrice);
        double maxDelta = Math.max(maxDeltaBid, maxDeltaAsk);

        return maxDelta;
    }

    private double calculateProfit(IOrder order, ITick lastTick) {
        ZonedDateTime logTimestamp = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastTick.getTime()), ZoneOffset.UTC);

        log.info("[{}] Inside calculateProfit method", logTimestamp);

        try {
            // Check if the order is null
            if (order == null) {
                log.error("[{}] Order is null. Returning profit: 0.0", logTimestamp);
                return 0.0;
            }

            // Check if the order is filled
            if (order.getState() != IOrder.State.FILLED) {
                log.warn("[{}] Order state is not FILLED. Returning profit: 0.0", logTimestamp);
                return 0.0;
            }

            // Check if take profit is set
            if (order.getTakeProfitPrice() == -1.0) {
                log.warn("[{}] Take profit is not set for the order. Returning profit: 0.0", logTimestamp);
                return 0.0;
            } else {
                log.info("[{}] Take profit is set. Take Profit Price calculateProfit 1: {}", logTimestamp, order.getTakeProfitPrice());
            }

            // Fetch the latest tick
            ITick latestTick = history.getLastTick(order.getInstrument());

            // Check if the current tick is not null
            if (latestTick == null) {
                log.warn("[{}] Latest tick is null. Returning profit: 0.0", logTimestamp);
                return 0.0;
            }

            log.info("[{}] Latest tick fetched successfully in calculateProfit method.", logTimestamp);

            BigDecimal openPrice = BigDecimal.valueOf(order.getOpenPrice());
            BigDecimal currentPrice = order.isLong() ? BigDecimal.valueOf(latestTick.getAsk()) : BigDecimal.valueOf(latestTick.getBid());

            // Additional logging for order details with extended decimal places
            log.info("[{}] Order Label: {}, Open Price: {}, Current Price: {}",
                    logTimestamp, order.getLabel(),
                    openPrice,
                    currentPrice);
            log.info("[{}] Order Type: {}", logTimestamp, order.getOrderCommand());
            log.info("[{}] Instrument: {}", logTimestamp, order.getInstrument());
            log.info("[{}] Last Tick - Bid: {}, Ask: {}", logTimestamp, latestTick.getBid(), latestTick.getAsk());

            // Calculate profit
            BigDecimal profit = order.isLong() ? currentPrice.subtract(openPrice) : openPrice.subtract(currentPrice);
            profit = order.isLong() ? profit : profit.negate();
            profit = profit.setScale(10, RoundingMode.HALF_UP);

            // Round the profit to 10 decimal places
            double roundedProfit = round2(profit.doubleValue(), 10);

            log.info("[{}] Calculated Profit: {}", getCurrentFormattedTimestamp(lastTick.getTime()), String.format("%.8f", roundedProfit));
            log.info("[{}] Take profit is set. Take Profit Price calculateProfit 2: {}", logTimestamp, order.getTakeProfitPrice());

            return roundedProfit;
        } catch (JFException e) {
            log.error("Error occurred in calculateProfit: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Update the stop loss of the order and log the change.
     */
    private void updateStopLoss(IOrder order, double newStopLoss, long lastTickTime) {
        try {
            // Log the initial stop loss before updating
            log.info("[{}] Initial Stop Loss before update: {}", getCurrentFormattedTimestamp(lastTickTime), order.getStopLossPrice());

            if (order.getState() == IOrder.State.FILLED || order.getState() == IOrder.State.OPENED) {
                log.info("[{}] Updating Stop Loss for order: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel());

                // Validate new stop loss value
                if (!Double.isFinite(newStopLoss)) {
                    log.warn("[{}] Invalid new stop loss value. Stop Loss update skipped.", getCurrentFormattedTimestamp(lastTickTime));
                    return; // Exit early if the new stop loss value is invalid
                }

                // Synchronously update the stop loss
                order.waitForUpdate(30000);

                log.info("[{}] Order is modifiable. Continuing with Stop Loss update.", getCurrentFormattedTimestamp(lastTickTime));

                // Update the stop loss
                order.setStopLossPrice(newStopLoss);

                // Log the updated stop loss
                log.info("[{}] Stop Loss updated successfully. New Stop Loss: {}", getCurrentFormattedTimestamp(lastTickTime), newStopLoss);
            } else {
                log.info("[{}] Invalid order state for updating Stop Loss. Order: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel());
            }
        } catch (JFException e) {
            log.error("Error in updateStopLoss method for order: {}", order.getLabel(), e);
        }
    }

    public CompletableFuture<Void> startStopLossMitigationScheduler(IOrder order, long lastTick, AtomicReference<Double> deltaHolder, double directionMultiplier) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            log.info("Entering startStopLossMitigationScheduler for order: {}, State: {}", order != null ? order.getLabel() : "null", order != null ? order.getState() : "null");

            if (order != null && IOrder.State.FILLED.equals(order.getState())) {
                // Check if the executor service is already running for the order
                if (!orderExecutorServices2.containsKey(order.getLabel())) {
                    log.info("Creating a new executor service (startStopLossMitigationScheduler) for order: {}", order.getLabel());

                    long initialDelay = 0;
                    long delayBetweenExecutions = 50;

                    ScheduledExecutorService stopLossExecutorService = Executors.newScheduledThreadPool(1);

                    log.info("Scheduler (startStopLossMitigationScheduler) started for order: {}", order.getLabel());

                    // Use CompletableFuture to track the completion of the scheduled task
                    CompletableFuture<Void> taskCompletionFuture = new CompletableFuture<>();

                    stopLossExecutorService.scheduleWithFixedDelay(() -> {
                        try {
                            schedulerExecutionCount2.incrementAndGet();

                            log.info("Scheduled task for applying stop loss mitigation is running for order: {}. Execution count: {}",
                                    order.getLabel(), schedulerExecutionCount2.get());

                            // Check if the order is still active
                            if (order.getState() == IOrder.State.FILLED) {
                                log.info("Applying stop loss mitigation for order: {}", order.getLabel());

                                double newDelta = applyStopLossMitigation(order, lastTick, deltaHolder.get(), directionMultiplier); // Capture the updated delta value
                                log.info("New delta calculated: {}", String.format("%.8f", newDelta));

                                deltaHolder.set(newDelta); // Update the delta value in the holder array
                                log.info("Adjusted delta after stop loss mitigation: {}", String.format("%.8f", deltaHolder.get()));

                                log.info("applyStopLossMitigation executed for order: {}", order.getLabel());
                            } else {
                                log.info("Order is no longer active. Shutting down stop loss executor for order: {}", order.getLabel());
                                stopLossExecutorService.shutdown();
                                log.info("Scheduler shut down for order: {}", order.getLabel());
                            }

                            // Complete the CompletableFuture to signal task completion
                            taskCompletionFuture.complete(null);
                        } catch (Exception e) {
                            log.error("Error in scheduled task for applying stop loss mitigation for order: {}", order.getLabel(), e);
                            // Complete the CompletableFuture exceptionally if there's an error
                            taskCompletionFuture.completeExceptionally(e);
                        }
                    }, initialDelay, delayBetweenExecutions, TimeUnit.MILLISECONDS);

                    orderExecutorServices2.put(order.getLabel(), stopLossExecutorService);

                    // Wait for the completion of the task before returning the future
                    taskCompletionFuture.join();
                } else {
                    log.info("Stop loss mitigation scheduler is already running for order: {}", order.getLabel());
                }
            } else {
                log.warn("Cannot start stop loss mitigation scheduler. Order is null or not in FILLED state.");
            }

            log.info("Exiting startStopLossMitigationScheduler for order: {}", order != null ? order.getLabel() : "null");
        });

        return future;
    }

/**
 //GOOD ONE
    public void startStopLossMitigationScheduler(IOrder order, long lastTick, AtomicReference<Double> deltaHolder, double directionMultiplier) {
        log.info("Entering startStopLossMitigationScheduler for order: {}, State: {}", order != null ? order.getLabel() : "null", order != null ? order.getState() : "null");

        if (order != null && IOrder.State.FILLED.equals(order.getState())) {
            // Check if the executor service is already running for the order
            if (!orderExecutorServices2.containsKey(order.getLabel())) {
                log.info("Creating a new executor service (startStopLossMitigationScheduler) for order: {}", order.getLabel());

                long initialDelay = 0;
                long delayBetweenExecutions = 50;

                ScheduledExecutorService stopLossExecutorService = Executors.newScheduledThreadPool(1);

                log.info("Scheduler (startStopLossMitigationScheduler) started for order: {}", order.getLabel());

                stopLossExecutorService.scheduleWithFixedDelay(() -> {
                    try {
                        schedulerExecutionCount2.incrementAndGet();

                        log.info("Scheduled task for applying stop loss mitigation is running for order: {}. Execution count: {}",
                                order.getLabel(), schedulerExecutionCount2.get());

                        // Check if the order is still active
                        if (order.getState() == IOrder.State.FILLED) {
                            log.info("Applying stop loss mitigation for order: {}", order.getLabel());

                            double newDelta = applyStopLossMitigation(order, lastTick, deltaHolder.get(), directionMultiplier); // Capture the updated delta value
                            log.info("New delta calculated: {}", String.format("%.8f", newDelta));

                            deltaHolder.set(newDelta); // Update the delta value in the holder array
                            log.info("Adjusted delta after stop loss mitigation: {}", String.format("%.8f", deltaHolder.get()));

                            log.info("applyStopLossMitigation executed for order: {}", order.getLabel());
                        } else {
                            log.info("Order is no longer active. Shutting down stop loss executor for order: {}", order.getLabel());
                            stopLossExecutorService.shutdown();
                            log.info("Scheduler shut down for order: {}", order.getLabel());
                        }

                    } catch (Exception e) {
                        log.error("Error in scheduled task for applying stop loss mitigation for order: {}", order.getLabel(), e);
                    }
                }, initialDelay, delayBetweenExecutions, TimeUnit.MILLISECONDS);

                orderExecutorServices2.put(order.getLabel(), stopLossExecutorService);
            } else {
                log.info("Stop loss mitigation scheduler is already running for order: {}", order.getLabel());
            }
        } else {
            log.warn("Cannot start stop loss mitigation scheduler. Order is null or not in FILLED state.");
        }

        log.info("Exiting startStopLossMitigationScheduler for order: {}", order != null ? order.getLabel() : "null");
    }
*/

    /**
     * Apply stop loss mitigation to the order.
     */
    private double applyStopLossMitigation(IOrder order, long lastTickTime, double deltaHolder, double directionMultiplier) throws JFException {
        log.info("[{}] Applying stop loss mitigation for order: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel());

        double entryPrice = order.getOpenPrice();
        ITick lastTick = history.getLastTick(order.getInstrument());

        if (lastTick == null) {
            log.warn("[{}] Latest tick is null. Stop loss mitigation skipped.", getCurrentFormattedTimestamp(lastTickTime));
            return deltaHolder; // Return the current value of deltaHolder
        }

        // Calculate a dynamic mitigation threshold based on market conditions
        double dynamicMitigationDistance = calculateDynamicMitigationDistance(order, lastTick);

        // Retrieve the original delta value from deltaHolder
        double delta = deltaHolder;

        // Log order details
        log.info("[{}] Entry Price: {}", getCurrentFormattedTimestamp(lastTickTime), entryPrice);
        log.info("[{}] Direction of the Order: {}", getCurrentFormattedTimestamp(lastTickTime), order.isLong() ? "LONG" : "SHORT");

        // Calculate rate of change
        double currentPrice = order.isLong() ? lastTick.getBid() : lastTick.getAsk();
        double priceChange = currentPrice - entryPrice;

        // Check if price change triggers mitigation
        if (shouldMitigate(priceChange, delta, directionMultiplier)) {
            // Mitigate stop loss
            delta = dynamicMitigationDistance * directionMultiplier;

            stopLossMitigationApplied = true;
            mitigationApplied = true;
            log.info("[{}] Mitigation logic applied for order: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel());
            log.info("[{}] Delta after Mitigation Applied: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", delta));
        }

        return delta;
    }

    private boolean shouldMitigate(double priceChange, double delta, double directionMultiplier) {
        // Check if price change exceeds a certain threshold relative to delta
        double threshold = Math.abs(delta * 0.5); // Example threshold: 50% of delta
        boolean exceedsThreshold = Math.abs(priceChange) > threshold;

        // Check if price change is in the opposite direction of the order
        boolean isOppositeDirection = priceChange * directionMultiplier < 0;

        return exceedsThreshold && isOppositeDirection;
    }
/**
    private double applyStopLossMitigation(IOrder order, long lastTickTime, double deltaHolder, double directionMultiplier) throws JFException {
        log.info("[{}] Applying stop loss mitigation for order: {}", getCurrentFormattedTimestamp(lastTickTime), order.getLabel());

        double entryPrice = order.getOpenPrice();
        ITick lastTick = history.getLastTick(order.getInstrument());

        if (lastTick == null) {
            log.warn("[{}] Latest tick is null. Stop loss mitigation skipped.", getCurrentFormattedTimestamp(lastTickTime));
            return deltaHolder; // Return the current value of deltaHolder
        }

        // Calculate a dynamic mitigation threshold based on market conditions
        double dynamicMitigationDistance = calculateDynamicMitigationDistance(order, lastTick);

        // Retrieve the original delta value from deltaHolder
        double delta = deltaHolder;

        // Log order details
        log.info("[{}] Entry Price: {}", getCurrentFormattedTimestamp(lastTickTime), entryPrice);
        log.info("[{}] Direction of the Order: {}", getCurrentFormattedTimestamp(lastTickTime), order.isLong() ? "LONG" : "SHORT");

        if ((order.isLong() && lastTick.getBid() < entryPrice) || (!order.isLong() && lastTick.getAsk() > entryPrice)) {
            // Price is moving against the position, adjust delta based on dynamic mitigation distance
            double newDelta = dynamicMitigationDistance * directionMultiplier;

            // Log delta calculation details
            log.info("[{}] Order is moving against the position.", getCurrentFormattedTimestamp(lastTickTime));
            log.info("[{}] Last Bid Price: {}", getCurrentFormattedTimestamp(lastTickTime), lastTick.getBid());
            log.info("[{}] Last Ask Price: {}", getCurrentFormattedTimestamp(lastTickTime), lastTick.getAsk());
            log.info("[{}] Delta: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", delta));
            log.info("[{}] Dynamic Mitigation Distance: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", dynamicMitigationDistance));
            log.info("[{}] Direction Multiplier: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", directionMultiplier));
            log.info("[{}] New Delta: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", newDelta));

            // Log the comparison result between delta and newDelta using absolute values
            if (Math.abs(newDelta) > Math.abs(delta)) {
                log.info("[{}] New Delta ({}) has greater magnitude than Delta ({}). Updating Delta.", getCurrentFormattedTimestamp(lastTickTime), newDelta, String.format("%.8f", delta));
                delta = newDelta; // Update delta with newDelta

                stopLossMitigationApplied = true;
                mitigationApplied = true; // Mitigation logic applied
                String orderId = order.getLabel();
                log.info("[{}] Mitigation logic applied for order: {}", getCurrentFormattedTimestamp(lastTickTime), orderId);

                log.info("[{}] Delta after Mitigation Applied: {}", getCurrentFormattedTimestamp(lastTickTime), String.format("%.8f", delta));

            } else {
                log.info("[{}] New Delta ({}) does not have greater magnitude than Delta ({}). Delta remains unchanged.", getCurrentFormattedTimestamp(lastTickTime), newDelta, String.format("%.8f", delta));
            }
        }
        return delta;
    }
*/
    private double calculateDynamicMitigationDistance(IOrder order, ITick lastTick) throws JFException {
        log.info("Calculating dynamic mitigation distance for order: {}", order.getLabel());

        // Calculate dynamic mitigation distance based on market conditions, such as volatility or recent price movements
        double volatility = calculateVolatility(order, lastTick);
        // Adjust the mitigation distance based on volatility or other factors
        double dynamicMitigationDistance = volatility * volatilityFactor;
        log.info("Volatility calculated: {}", String.format("%.8f", volatility));
        log.info("VolatilityFactor: {}", volatilityFactor);
        log.info("Dynamic mitigation distance calculated: {}", String.format("%.8f", dynamicMitigationDistance));

        return dynamicMitigationDistance;
    }

    private double calculateVolatility(IOrder order, ITick lastTick) throws JFException {
        log.info("Calculating volatility for order: {}", order.getLabel());

        // Get the timestamp from the last tick
        long lastTickTime = lastTick.getTime();

        // Retrieve the bar start time for the last bar in the one-minute period based on the last tick time
        long lastBarTime = history.getBarStart(Period.ONE_MIN, lastTickTime);

        // Check if the lastBarTime is valid
        if (lastBarTime > 0) {
            // Calculate the start time for 60 minutes ago at 00:00:00 UTC
            ZonedDateTime sixtyMinutesAgoUTC = Instant.ofEpochMilli(lastBarTime).atZone(ZoneId.of("UTC")).minusMinutes(60);

            // Convert the ZonedDateTime objects to milliseconds
            long from = sixtyMinutesAgoUTC.toInstant().toEpochMilli();
            long to = lastTickTime;

            // Ensure 'to' does not exceed the last tick time
            if (to > lastBarTime) {
                to = lastBarTime;
            }

            // Check if 'to' is within the available historical data range
            if (to < from) {
                log.warn("No historical data available for the specified period and instrument.");
                return 0.0; // Or handle this case according to your requirements
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss SSS");
            String fromDateString = sixtyMinutesAgoUTC.format(formatter);
            String toDateString = ZonedDateTime.ofInstant(Instant.ofEpochMilli(to), ZoneId.of("UTC")).format(formatter);

            log.info("Fetching historical data from {} to {}", fromDateString, toDateString);

            List<IBar> bars = history.getBars(order.getInstrument(), Period.ONE_MIN, OfferSide.BID, from, to);

            // Check if there are enough bars to calculate volatility
            if (bars.size() < 50) {
                log.warn("Insufficient bars available for volatility calculation");
                return 0.0; // Or handle this case according to your requirements
            }

            // Calculate price changes
            double[] priceChanges = new double[bars.size() - 1];
            for (int i = 1; i < bars.size(); i++) {
                double priceChange = bars.get(i).getClose() - bars.get(i - 1).getClose();
                priceChanges[i - 1] = priceChange;
            }

            // Calculate standard deviation of price changes
            double volatility = getVolatility(priceChanges);

            return volatility;
        } else {
            log.warn("No bars available for the specified period and instrument.");
            return 0.0;
        }
    }

    private static double getVolatility(double[] priceChanges) {
        double sum = 0.0;
        for (double priceChange : priceChanges) {
            sum += priceChange;
        }
        double mean = sum / priceChanges.length;

        double squaredDifferenceSum = 0.0;
        for (double priceChange : priceChanges) {
            squaredDifferenceSum += Math.pow(priceChange - mean, 2);
        }
        double variance = squaredDifferenceSum / priceChanges.length;
        double volatility = Math.sqrt(variance);
        return volatility;
    }

    private double calculateTrailingStopLoss(IOrder order, double trailingStart, ITick latestTick) {
        double currentBidPrice = latestTick.getBid();
        double currentAskPrice = latestTick.getAsk();

        if (Double.isNaN(currentBidPrice) || Double.isNaN(currentAskPrice)) {
            log.error("Invalid bid or ask price. Unable to calculate trailing stop loss.");
            return 0.0;
        }

        double trailingStopLoss;
        if (order.isLong()) {
            trailingStopLoss = currentBidPrice - trailingStart;
            log.info("Trailing Stop Loss calculation for LONG order: currentBidPrice - trailingStart = {} - {} = {}", currentBidPrice, String.format("%.8f", trailingStart), trailingStopLoss);

            double previousTrailingStopLoss = order.getStopLossPrice();
            trailingStopLoss = Math.max(trailingStopLoss, Math.min(currentBidPrice - trailingStart, previousTrailingStopLoss));
            log.info("Adjusted Trailing Stop Loss (LONG order): {}", trailingStopLoss);
        } else {
            trailingStopLoss = currentAskPrice + trailingStart;
            log.info("Trailing Stop Loss calculation for SHORT order: currentAskPrice + trailingStart = {} + {} = {}", currentAskPrice, String.format("%.8f", trailingStart), trailingStopLoss);

            double previousTrailingStopLoss = order.getStopLossPrice();
            trailingStopLoss = Math.min(trailingStopLoss, Math.max(currentAskPrice + trailingStart, previousTrailingStopLoss));
            log.info("Adjusted Trailing Stop Loss (SHORT order): {}", trailingStopLoss);
        }

        //double dynamicTrailingStopLoss = calculateDynamicTrailingStopLoss(order, trailingStopLoss, latestTick);

        log.info("Initial Trailing Stop Loss: {}", trailingStopLoss);
        log.info("[{}] New Take Profit Level end of calculateTrailingStopLoss: {}", getCurrentFormattedTimestamp(latestTick.getTime()), order.getTakeProfitPrice());


        //return dynamicTrailingStopLoss;

        return trailingStopLoss;
    }
/**
    private double calculateDynamicTrailingStopLoss(IOrder order, double trailingStopLoss, ITick latestTick) {
        // Implement your dynamic trailing stop loss calculation logic here
        // This could involve adjusting the trailing stop loss based on market conditions, indicators, or other factors
        // You can use trailingStopLoss as a starting point and calculate the dynamic value based on your strategy
        // Example: Adjust based on recent price movement, volatility, or other market factors

        // For demonstration purposes, let's say we adjust the trailing stop loss based on recent price movement
        double currentMarketPrice = order.isLong() ? latestTick.getAsk() : latestTick.getBid();

        // Calculate the price movement since the last tick
        double priceMovement = Math.abs(currentMarketPrice - trailingStopLoss);

        // Adjust the trailing stop loss based on the price movement
        // For simplicity, let's say we adjust it by a fixed percentage of the price movement
        double adjustmentFactor = 0.8; // Adjust by 50% of the price movement
        double dynamicTrailingStopLoss = order.isLong() ? trailingStopLoss + (priceMovement * adjustmentFactor) : trailingStopLoss - (priceMovement * adjustmentFactor);
        log.info("adjustment factor: {}", adjustmentFactor);

        // Ensure the dynamic trailing stop loss stays within a reasonable range
        // For example, it should not be lower than the current market price for a long position
        if (order.isLong()) {
            dynamicTrailingStopLoss = Math.max(dynamicTrailingStopLoss, currentMarketPrice);
        } else {
            dynamicTrailingStopLoss = Math.min(dynamicTrailingStopLoss, currentMarketPrice);
        }

        // Check if the dynamic trailing stop loss is tighter than the previous one and retain the previous one if necessary
        double previousTrailingStopLoss = order.getStopLossPrice();
        if (order.isLong()) {
            dynamicTrailingStopLoss = Math.max(dynamicTrailingStopLoss, previousTrailingStopLoss);
        } else {
            dynamicTrailingStopLoss = Math.min(dynamicTrailingStopLoss, previousTrailingStopLoss);
        }

        // Log the dynamic trailing stop loss adjustment
        log.info("Dynamic Trailing Stop Loss adjusted from {} to {}", trailingStopLoss, dynamicTrailingStopLoss);

        // Return the dynamically adjusted trailing stop loss
        return dynamicTrailingStopLoss;
    }
 */

    private String getFormattedTimestamp(long timestamp) {
        LocalDateTime dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDateTime();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        return dateTime.format(formatter);
    }

    private static String getCurrentFormattedTimestamp(long backtestTimestamp) {
        LocalDateTime dateTime = Instant.ofEpochMilli(backtestTimestamp).atZone(ZoneOffset.UTC).toLocalDateTime();
        return dateTimeFormatter.format(dateTime);
    }

    public void updateLastOrderCloseTimeFromOMSS(long closeTime) {
        lastOrderCloseTime = closeTime;
        log.info("Last order close time updated: {}", getFormattedTimestamp(closeTime));
    }

    private boolean isOrderModifiable(IOrder order) {
        return order != null && (order.getState() == IOrder.State.FILLED || order.getState() == IOrder.State.OPENED) && order.getState() != IOrder.State.CLOSED;
    }

    private boolean isOrderModifiableAndFilled(IOrder order, Double currentProfit) {
        return isOrderModifiable(order)
                && order.getState() == IOrder.State.FILLED
                && currentProfit != null
                && !Double.isNaN(currentProfit)
                && Math.abs(currentProfit) >= 0.0; // 1e-7
    }

    private double round(double value, int decimalPlaces) {
        double multiplier = Math.pow(10, decimalPlaces);
        return Math.round(value * multiplier) / multiplier;
    }

    private double roundAndAdjust(double value, double adjustment, boolean isLongOrder) {
        double roundedValue = round(value, 5); // Adjusted to round to 5 decimal places
        double adjustedValue;

        if (isLongOrder) {
            // Adjust downwards for buy (long) orders
            adjustedValue = Math.max(roundedValue, value - adjustment); // Ensure adjusted value is not less than original value
        } else {
            // Adjust upwards for sell (short) orders
            adjustedValue = Math.min(roundedValue, value + adjustment); // Ensure adjusted value is not more than original value
        }
        return adjustedValue;
    }

    private double round2(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();

        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private double roundTo5DecimalPlaces(double value) {
        return Math.round(value * 1e5) / 1e5;
    }

    private IOrder fetchOrderDetailsFromServer(String orderLabel, long lastTickTime) {
        try {
            // Ensure that the context object is properly initialized and has access to the trading engine
            IEngine engine = context.getEngine();

            // Log the order label being fetched
            log.info("[{}] Fetching order details for Order Label: {}", getCurrentFormattedTimestamp(lastTickTime), orderLabel);

            // Get all open orders
            List<IOrder> openOrders = engine.getOrders();

            // Find the order with the matching label
            Optional<IOrder> matchingOrder = openOrders.stream()
                    .filter(order -> order.getLabel().equals(orderLabel))
                    .findFirst();

            // Check if the order with the label was found
            if (matchingOrder.isPresent()) {
                IOrder updatedOrder = matchingOrder.get();

                // Check if the order is still open
                if (updatedOrder.getState() == IOrder.State.CLOSED) {
                    log.warn("[{}] Order with Label {} is already closed", getCurrentFormattedTimestamp(lastTickTime), orderLabel);
                    return null;
                }

                // Log the fetched details for debugging purposes
                log.info("Before logOrderDetailsInternal");
                logOrderDetailsInternal(updatedOrder, lastTickTime);
                log.info("After logOrderDetailsInternal");

                return updatedOrder;
            } else {
                log.error("[{}] Order with Label {} does not exist", getCurrentFormattedTimestamp(lastTickTime), orderLabel);
                return null;
            }
        } catch (Exception e) {
            log.error("[{}] Error in fetchOrderDetailsFromServer for Order Label: {}. Exception: {}", getCurrentFormattedTimestamp(lastTickTime), orderLabel, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Log order details for debugging.
     */
    private void logOrderDetails(IOrder order) {
        log.info("Order Label: {}", order.getLabel());
        log.info("Order State: {}", order.getState());
        log.info("Order Command: {}", order.getOrderCommand());
        //log.info("Order Stop Loss Price: {}", order.getStopLossPrice());
        //log.info("Order Take Profit Price: {}", order.getTakeProfitPrice());
    }

    // Helper method to log order details
    private void logOrderDetailsInternal(IOrder order, long lastTickTime) {
        log.info("[{}] Fetched Order ID: {}", getCurrentFormattedTimestamp(lastTickTime), order.getId());
        //log.info("[{}] Instrument: {}", getCurrentFormattedTimestamp(lastTickTime), order.getInstrument());
        //log.info("[{}] Order Command: {}", getCurrentFormattedTimestamp(lastTickTime), order.getOrderCommand());
        //log.info("[{}] Order Type: {}", getCurrentFormattedTimestamp(lastTickTime), order.isLong() ? "BUY" : "SELL");
        //log.info("[{}] Order State: {}", getCurrentFormattedTimestamp(lastTickTime), order.getState());
        //log.info("[{}] Original Amount: {}", getCurrentFormattedTimestamp(lastTickTime), order.getOriginalAmount());
        log.info("[{}] Open Price: {}", getCurrentFormattedTimestamp(lastTickTime), order.getOpenPrice());
        log.info("[{}] Take Profit Price: {}", getCurrentFormattedTimestamp(lastTickTime), order.getTakeProfitPrice());
        log.info("[{}] Stop Loss Price: {}", getCurrentFormattedTimestamp(lastTickTime), order.getStopLossPrice());
    }

    private boolean existingOpenOrders() {
        try {
            // Get all open positions from the order manager
            List<IOrder> openOrders = context.getEngine().getOrders();
            return !openOrders.isEmpty();
        } catch (JFException e) {
            log.error("Error while checking for existing open orders: {}", e.getMessage());
            return true; // Return true to indicate that there might be open orders
        }
    }

    private void resetStopLossUpdateTimes() {
        lastStopLossUpdateTimes.clear();
    }

}