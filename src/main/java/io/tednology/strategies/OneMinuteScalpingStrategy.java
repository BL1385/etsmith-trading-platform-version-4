package io.tednology.strategies;

import ca.tednology.mail.builder.SimpleEmailBuilder;
import com.dukascopy.api.*;
import io.tednology.analysis.BarData;
import io.tednology.analysis.OrderMeta;
import io.tednology.init.JForexProperties;
import io.tednology.init.SystemProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.javamail.JavaMailSender;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * @author Edward Smith
 */
@Slf4j
public class OneMinuteScalpingStrategy implements IStrategy {

    private final ApplicationEventPublisher eventPublisher;
    private final JavaMailSender mailSender;
    private final ForexEngineRef forexEngineRef;
    private final JForexProperties forexProperties;
    private final List<StrategyWindow> strategyWindow;
    private final SystemProperties systemProperties;
    private final Instrument instrument = Instrument.EURUSD;
    private final boolean tradingEnabled;

    private boolean buyOrderOpened = false; // Flag to track if a sell order has been opened
    private boolean sellOrderOpened = false; // Flag to track if a sell order has been opened
    private boolean orderProcessed = false; // Add this flag as a class member
    private boolean orderOpened = false; // Flag to track if an order (either buy or sell) has been opened
    private boolean isOrderProcessing = false; // Flag to track if an order is currently being processed
    private boolean isLongOrderOpened = false;
    private boolean isShortOrderOpened = false;

    // Use an enumeration to track the type of the current order being processed
    private enum OrderType {NONE, BUY, SELL}

    private OrderType currentOrderType = OrderType.NONE;
    private long pid;
    private long lastProcessedTickTime = 0;

    private BarData lastBidData;
    private BarData lastAskData;
    private OrderManager orderManager;
    @Getter
    private IContext context;
    private IEngine engine;
    private IHistory history;
    private IIndicators indicators;
    private IOrder buyOrder;
    private IOrder sellOrder;
    private ITick lastTick;
    private LocalDateTime lastCrossOver = LocalDateTime.MIN;
    private OrderMeta buyOrderMeta;
    private OrderMeta sellOrderMeta;


    public OneMinuteScalpingStrategy(ApplicationEventPublisher eventPublisher,
                                     JavaMailSender mailSender,
                                     ForexEngineRef forexEngineRef,
                                     SystemProperties systemProperties,
                                     JForexProperties forexProperties) {
        this.eventPublisher = eventPublisher;
        this.mailSender = mailSender;
        this.forexEngineRef = forexEngineRef;
        this.strategyWindow = systemProperties.getStrategyWindows();
        this.forexProperties = forexProperties;
        this.systemProperties = systemProperties;
        this.tradingEnabled = forexProperties.getBackTest().isEnabled() || forexProperties.isTradesEnabled();
    }

    @Override
    public void onStart(IContext context) {
        this.context = context;
        this.history = context.getHistory();
        this.indicators = context.getIndicators();
        this.forexEngineRef.setEngine(context.getEngine());
        this.engine = context.getEngine();
        //this.orderManager = new OrderManager(context, engine);

        try {
            // Subscribe to the instrument
            context.setSubscribedInstruments(Collections.singleton(instrument), true);

            // Calculate the end time as the current time
            long endTime = context.getTime();
            //this.time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(endTime), ZoneOffset.UTC);

            // Calculate the start time as one minute before the end time
            long startTime = endTime - Period.ONE_MIN.getInterval();

            // Calculate the shift value
            int shift = (int) ((endTime - startTime) / Period.ONE_MIN.getInterval());

            // Retrieve the last bid bar
            IBar lastBidBar = history.getBar(instrument, Period.ONE_MIN, OfferSide.BID, shift);
            if (lastBidBar == null) {
                log.warn("No bid bar loaded for the specified time interval. Skipping initialization.");
                return;
            }
            lastBidData = new BarData(history, indicators, Period.ONE_MIN, lastBidBar, OfferSide.BID);

            // Retrieve the last ask bar
            IBar lastAskBar = history.getBar(instrument, Period.ONE_MIN, OfferSide.ASK, shift);
            if (lastAskBar == null) {
                log.warn("No ask bar loaded for the specified time interval. Skipping initialization.");
                return;
            }
            lastAskData = new BarData(history, indicators, Period.ONE_MIN, lastAskBar, OfferSide.ASK);

            OfferSide offerSide = OfferSide.BID;
            // Initialize the order manager
            this.orderManager = new OrderManager(forexProperties, systemProperties, context, lastAskData, offerSide, history);

            log.info("Starting {} Strategy", this.getClass().getSimpleName());
        } catch (JFException e) {
            log.warn("Failed to retrieve last bid/ask bar", e);
        }
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        log.info("Entering onTick method");
        if (this.instrument != instrument) {
            log.info("Instrument mismatch. Current instrument: {}, received instrument: {}", this.instrument, instrument);
            return;
        }

        if (lastProcessedTickTime == tick.getTime()) {
            log.info("Duplicate tick detected. Skipping order processing.");
            return;
        }

        lastProcessedTickTime = tick.getTime();
        this.lastTick = tick;

        log.info("Before shouldOpenOrderOnTick call (BID)");
        boolean shouldOpenLong = orderManager.shouldOpenOrderOnTick(lastBidData, OfferSide.BID, tick);
        log.info("After shouldOpenOrderOnTick call (BID). Result: {}", shouldOpenLong);

        log.info("Before shouldOpenOrderOnTick call (ASK)");
        boolean shouldOpenShort = orderManager.shouldOpenOrderOnTick(lastAskData, OfferSide.ASK, tick);
        log.info("After shouldOpenOrderOnTick call (ASK). Result: {}", shouldOpenShort);

        // Check flags relevant to onBar method
        if (buyOrderOpened || sellOrderOpened) {
            resetOrderFlags();
        }

        if (orderOpened) {
            orderOpened = false; // Reset the orderOpened flag on a new tick
            log.info("Order opened flag reset.");
        }

        // Handle long position based on tick data
        if (shouldOpenLong) {
            log.info("Attempting to handle long position...");
            handleLongPosition(tick);
            log.info("Long position handled successfully.");
            orderProcessed = true;
        }

        // Handle short position based on tick data
        if (shouldOpenShort) {
            log.info("Attempting to handle short position...");
            handleShortPosition(tick);
            log.info("Short position handled successfully.");
            orderProcessed = true;
        }

        if (!forexProperties.getBackTest().isEnabled()) {
            log.info("Publishing TickEvent...");
            eventPublisher.publishEvent(new TickEvent(tick.getTime(), tick.getAsk(), tick.getBid()));

            log.info("Exiting onTick method");
        }
    }


    /** ORIGINAl
    @Override
    public void onTick(Instrument instrument, ITick tick) {
        if (this.instrument != instrument) {
            log.info("Instrument mismatch. Current instrument: {}, received instrument: {}", this.instrument, instrument);
            return;
        }

        // Check if the current tick has the same timestamp as the last processed tick
        if (lastProcessedTickTime == tick.getTime()) {
            log.info("Duplicate tick detected. Skipping handleLongPosition and handleShortPosition.");
            return;
        }

        lastProcessedTickTime = tick.getTime(); // Update the last processed tick timestamp
        this.lastTick = tick; // Store the current tick in the member variable

        if (orderOpened || !strategyWindow.isOpen(localTime(tick)) || lastBidData == null || lastAskData == null) {
            log.trace("Skipping order handling.");
            if (orderOpened) {
                log.info("Order is already opened.");
            }
            strategyWindow.isOpen(localTime(tick));//log.info("Strategy window is not open.");
            if (lastBidData == null || lastAskData == null) {
                log.info("lastBidData or lastAskData is null.");
            }
            return;
        }

        try {
            if (orderManager.shouldOpen(lastBidData, OfferSide.BID, lastTick)) {
                log.info("Attempting to handle long position...");
                handleLongPosition(lastTick); // Use tick object
                log.info("Long position handled successfully.");
                orderProcessed = true;
            }

            if (orderManager.shouldOpen(lastAskData, OfferSide.ASK, lastTick)) {
                log.info("Attempting to handle short position...");
                handleShortPosition(lastTick); // Use tick object
                log.info("Short position handled successfully.");
                orderProcessed = true;
            }
        } catch (JFException e) {
            log.error("Forex API Error occurred on TICK!", e);
        }

        if (!forexProperties.getBackTest().isEnabled()) {
            log.info("Publishing TickEvent...");
            eventPublisher.publishEvent(new TickEvent(tick.getTime(), tick.getAsk(), tick.getBid()));
        }
    }
*/

    @Override
    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) {
        if (this.instrument != instrument || Period.ONE_MIN != period) {
            //log.info("Instrument or period mismatch. Current instrument: {}, received instrument: {}, period: {}", this.instrument, instrument, period);
            return;
        }

        buyOrderOpened = false;
        sellOrderOpened = false;
        orderOpened = false;

        try {
            //log.info("Updating lastBidData and lastAskData for new bar");
            lastBidData = new BarData(history, indicators, period, bidBar, OfferSide.BID);
            lastAskData = new BarData(history, indicators, period, askBar, OfferSide.ASK);

            if (lastBidData.isCrossedOver()) {
                lastCrossOver = lastBidData.getTime();
            }

            if (buyIsOpen()) {
                log.info("Tracking post-open for buy order...");
                buyOrderMeta = orderManager.trackPostOpen(indicators, buyOrder, bidBar, buyOrderMeta, lastBidData);
            }

            if (sellIsOpen()) {
                log.info("Tracking post-open for sell order...");
                sellOrderMeta = orderManager.trackPostOpen(indicators, sellOrder, askBar, sellOrderMeta, lastAskData);
            }

            eventPublisher.publishEvent(new CandleBarEvent(lastBidData, lastAskData));

            if (orderProcessed) {
                return;
            }



/** Part of Original Code
            // Check if a buy order is already open, and if not, attempt to open one
            if (!buyIsOpen() && orderManager.shouldOpen(lastBidData, OfferSide.BID, lastTick, lastBidData)) {
                log.info("Before calling handleLongPosition");
                handleLongPosition(lastTick); // Use tick object
                log.info("After calling handleLongPosition");
            }

            // Check if a sell order is already open, and if not, attempt to open one
            if (!sellIsOpen() && orderManager.shouldOpen(lastAskData, OfferSide.ASK, lastTick, lastAskData)) {
                log.info("Before calling handleShortPosition");
                handleShortPosition(lastTick); // Use tick object
                log.info("After calling handleShortPosition");
            }
*/
              orderProcessed = true;

            checkOrderStatus(); // Check order status and update the flags

            log.info("Bar event received for instrument: {}, period: {}, askBar: {}, bidBar: {}", instrument, period, askBar, bidBar);

        } catch (JFException e) {
            log.error("Forex API Error occurred on bar!", e);
        }

    }

    private void handlePosition(OrderType orderType, ITick tick, BarData lastBarData, OfferSide side) throws JFException {
        log.info("Entering handlePosition method");

        long tickTimestamp = tick.getTime();
        //log.info("Inside handlePosition method at {} - Order Type: {}", formatTimestamp(tickTimestamp), orderType);

        //log.info("Current state - buyIsOpen: {}, sellIsOpen: {}, orderOpened: {}, isOrderProcessing: {}",
                //buyIsOpen(), sellIsOpen(), orderOpened, isOrderProcessing);

        if (buyIsOpen() || sellIsOpen() || orderOpened || isOrderProcessing) {
            log.info("An order is already open or being processed. Skipping handlePosition.");
            return;
        }

        isOrderProcessing = true;
        currentOrderType = orderType;

        // Add a log statement to display the tick data
        log.info("Tick data: {}", tick);

        if (shouldOpenOrder(tick, lastBarData, side)) {
            String orderLabel = createLabel(orderType == OrderType.BUY ? "Buy" : "Sell");
            IOrder newOrder = orderManager.open(tick, orderLabel, side);

            if (newOrder != null) {
                log.info("{} order opened - ID: {} at {}", orderType, newOrder.getId(), formatTimestamp(tickTimestamp));
                eventPublisher.publishEvent(new OrderEvent(newOrder));
                orderOpened = true;

                if (orderType == OrderType.BUY) {
                    buyOrderMeta = new OrderMeta(newOrder, lastBarData, lastCrossOver, forexProperties);
                    buyOrder = newOrder;  // Set the reference to the new buy order
                } else {
                    sellOrderMeta = new OrderMeta(newOrder, lastBarData, lastCrossOver, forexProperties);
                    sellOrder = newOrder;  // Set the reference to the new sell order
                }
            } else {
                log.info("Failed to open {} order. Order object is null.", orderType);
            }
        }

        // Log the current state after processing
        logCurrentState(orderType);

        isOrderProcessing = false;
        currentOrderType = OrderType.NONE;
        //log.info("Exiting handlePosition method at {} - Order Type: {}", formatTimestamp(tickTimestamp), orderType);

        //log.info("Exiting handlePosition method");
    }

/**
    private void handleLongPosition(ITick tick) throws JFException {
        long tickTimestamp = tick.getTime();
        log.info("Inside handleLongPosition method at {}", formatTimestamp(tickTimestamp));

        log.info("Current state - buyIsOpen: {}, sellIsOpen: {}, orderOpened: {}, isOrderProcessing: {}",
                buyIsOpen(), sellIsOpen(), orderOpened, isOrderProcessing);

        if (buyIsOpen() || sellIsOpen() || orderOpened || isOrderProcessing) {
            log.info("An order is already open or being processed. Skipping handleLongPosition.");
            return;
        }

        isOrderProcessing = true;
        currentOrderType = OrderType.BUY;

        log.info("HandleLongPosition: Current Order Type={}", currentOrderType);

        // Add a log statement to display the tick data
        log.info("Tick data: {}", tick);

        if (shouldOpenOrder(tick, lastBidData, OfferSide.BID)) {
            String orderLabel = createLabel("Buy");
            IOrder newBuyOrder = orderManager.open(tick, orderLabel, OfferSide.BID);

            if (newBuyOrder != null) {
                log.info("Buy order opened - ID: {} at {}", newBuyOrder.getId(), formatTimestamp(tickTimestamp));
                eventPublisher.publishEvent(new OrderEvent(newBuyOrder));
                orderOpened = true;
                buyOrderMeta = new OrderMeta(newBuyOrder, lastBidData, lastCrossOver, forexProperties);
                buyOrder = newBuyOrder;  // Set the reference to the new buy order
            } else {
                log.info("Failed to open buy order. Order object is null.");
            }
        }

        // Log the current state after processing
        logCurrentStateBuy();

        isOrderProcessing = false;
        currentOrderType = OrderType.NONE;
        log.info("Exiting handleLongPosition method at {}", formatTimestamp(tickTimestamp));
    }

    private void handleShortPosition(ITick tick) throws JFException {
        long tickTimestamp = tick.getTime();
        log.info("Inside handleShortPosition method at {}", formatTimestamp(tickTimestamp));

        log.info("Current state - buyIsOpen: {}, sellIsOpen: {}, orderOpened: {}, isOrderProcessing: {}",
                buyIsOpen(), sellIsOpen(), orderOpened, isOrderProcessing);

        if (buyIsOpen() || sellIsOpen() || orderOpened || isOrderProcessing) {
            log.info("An order is already open or being processed. Skipping handleShortPosition.");
            return;
        }

        isOrderProcessing = true;
        currentOrderType = OrderType.SELL;

        log.info("HandleShortPosition: Current Order Type={}", currentOrderType);

        // Add a log statement to display the tick data
        log.info("Tick data: {}", tick);

        if (shouldOpenOrder(tick, lastAskData, OfferSide.ASK)) {
            String orderLabel = createLabel("Sell");
            IOrder newSellOrder = orderManager.open(tick, orderLabel, OfferSide.ASK);

            if (newSellOrder != null) {
                log.info("Sell order opened - ID: {} at {}", newSellOrder.getId(), formatTimestamp(tickTimestamp));
                eventPublisher.publishEvent(new OrderEvent(newSellOrder));
                orderOpened = true;
                sellOrderMeta = new OrderMeta(newSellOrder, lastAskData, lastCrossOver, forexProperties);
                sellOrder = newSellOrder;  // Set the reference to the new sell order
            } else {
                log.info("Failed to open sell order. Order object is null.");
            }
        }

        // Log the current state after processing
        logCurrentStateSell();

        isOrderProcessing = false;
        currentOrderType = OrderType.NONE;
        log.info("Exiting handleShortPosition method at {}", formatTimestamp(tickTimestamp));
    }
 */

    @Override
    public void onMessage(IMessage message) {
        log.debug("Received message: {}.", message);

        if (message.getOrder() != null) {
            IOrder order = message.getOrder();
            orderManager.updateLastOrderCloseTimeFromOMSS(order.getCloseTime());
            log.info("Updated lastOrderCloseTime in OrderManager from strategy: {}", order.getCloseTime());

            log.info("Order state: {}", order.getState());
            log.info("Instrument: {}", order.getInstrument());
            log.info("Stop Loss Price: {}", order.getStopLossPrice());

            if (order.getState() == IOrder.State.CLOSED) {
                log.info("{}", message.getOrder());
                Date openTime = new Date(order.getCreationTime());
                Date closeTime = new Date(order.getCloseTime());

                SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                log.info("Order closed: {}", order.getLabel());
                log.info("Instrument: {}", order.getInstrument());
                log.info("Amount: {}", order.getAmount());
                log.info("Order Type: {}", order.getOrderCommand());
                log.info("Open Price: {}", order.getOpenPrice());
                log.info("Close Price: {}", order.getClosePrice());
                log.info("Profit/Loss: {}", order.getProfitLossInAccountCurrency());
                log.info("Profit/Loss in Pips: {}", order.getProfitLossInPips());
                log.info("Open Time: {}", sdf.format(openTime));
                log.info("Close Time: {}", sdf.format(closeTime));

                // Check if the closed order matches the buyOrder or sellOrder
                if (buyOrderMeta != null && order.getId().equals(buyOrderMeta.getOrderId())) {
                    // Log that the buy order is closed
                    log.info("Buy order closed - ID: {}", order.getId());

                    // Reset buyOrderOpened flag to false after the buy order is closed
                    buyOrderOpened = false;

                    // Reset isBuyOrderOpened flag to false after the sell order is closed
                    isLongOrderOpened = false;

                    // Set buyOrderMeta to null as the order is closed
                    buyOrderMeta = null;
                } else if (sellOrderMeta != null && order.getId().equals(sellOrderMeta.getOrderId())) {
                    // Log that the sell order is closed
                    log.info("Sell order closed - ID: {}", order.getId());

                    // Reset sellOrderOpened flag to false after the sell order is closed
                    sellOrderOpened = false;

                    // Reset isShortOrderOpened flag to false after the sell order is closed
                    isShortOrderOpened = false;

                    // Set sellOrderMeta to null as the order is closed
                    sellOrderMeta = null;

                    // Update the last order close time in the OrderManager instance
                    //orderManager.updateLastOrderCloseTimeFromOMSS(order.getCloseTime());
                    //log.info("Updated lastOrderCloseTime in OrderManager from strategy: {}", order.getCloseTime());
                }
            }
        } else if (message.getType().equals(IMessage.Type.NEWS)) {
            INewsMessage news = ((INewsMessage) message);
            log.trace("News! {}", news);

            if (!news.getCurrencies().isEmpty() || news.isHot()) {
                mailSender.send(
                        new SimpleEmailBuilder(mailSender)
                                .from("brandon.lusignan@gmail.com")
                                .to("brandon.lusignan@gmail.com")
                                .subject("We got some news for ya...")
                                .body(news.toString())
                                .build());
            }
        }
    }

    @Override
    public void onAccount(IAccount account) {
        log.debug("Received account change: {}.", account);
    }

    @Override
    public void onStop() {
        log.info("Stopping {} Strategy", this.getClass().getSimpleName());
    }

    private void checkOrderStatus() throws JFException {
        List<IOrder> orders = engine.getOrders(this.instrument);

        orderOpened = false;

        for (IOrder order : orders) {
            if (order.getState() != IOrder.State.CLOSED) {
                orderOpened = true;
                break;  // No need to continue checking once we find an open order
            }
        }
    }

    private boolean shouldOpenOrder(ITick tick, BarData lastBarData, OfferSide side) throws JFException {
        boolean shouldOpen = orderManager.shouldOpenOrderOnTick(lastBarData, side, tick) && tradingEnabled;

        log.info("Should open order: {} (Trading enabled: {})", shouldOpen, tradingEnabled);

        if (!orderManager.shouldOpenOrderOnTick(lastBarData, side, tick)) {
            log.info("Condition 1 not met: RSI conditions or other relevant conditions are not satisfied.");
        }

        if (!tradingEnabled) {
            log.info("Condition 2 not met: Trading is currently disabled.");
        }

        return shouldOpen;
    }

    /** ORIGINAL
    private boolean shouldOpenOrder(ITick tick, BarData barData, OfferSide side) throws JFException {
        if (tick != null) {
            return orderManager.shouldOpen(barData, side, tick, null) && tradingEnabled;
        } else {
            return orderManager.shouldOpen(barData, side, null, barData) && tradingEnabled;
        }
}
*/

    private boolean buyIsOpen() {
        return buyOrder != null && buyOrder.getState() == IOrder.State.FILLED;
    }

    private boolean sellIsOpen() {
        return sellOrder != null && sellOrder.getState() == IOrder.State.FILLED;
    }

    private String createLabel(String buyOrSell) {
        return String.format("Auto%s%sOrder%s", buyOrSell, pid, UUID.randomUUID().toString().substring(0, 6));
    }

    private static final Set<IOrder.State> NO_OP_ORDER_STATES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(IOrder.State.CREATED, IOrder.State.OPENED, IOrder.State.FILLED))
    );

    private String formatTimestamp(long timestamp) {
        LocalDateTime dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDateTime();
        return dateTime.toString();
    }

    public void setPid(long pid) {
        this.pid = pid;
    }

    private void logCurrentState(OrderType orderType) {
        //log.info("Current state - Buy: {}, Sell: {}, Order Opened: {}, Is Order Processing: {}, Current Order Type: {}",
                //buyIsOpen(), sellIsOpen(), orderOpened, isOrderProcessing, orderType);
    }

    private void resetOrderFlags() {
        buyOrderOpened = false;
        sellOrderOpened = false;
        log.info("Buy and sell order flags reset.");
    }

    private void handleLongPosition(ITick tick) throws JFException {
        log.info("Entering handleLongPosition method");
        handlePosition(OrderType.BUY, tick, lastBidData, OfferSide.BID);
        log.info("Exiting handleLongPosition method");
    }

    private void handleShortPosition(ITick tick) throws JFException {
        log.info("Entering handleShortPosition method");
        handlePosition(OrderType.SELL, tick, lastAskData, OfferSide.ASK);
        log.info("Exiting handleShortPosition method");
    }

}