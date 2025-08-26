package io.tednology.init;

import com.dukascopy.api.*;
import com.dukascopy.api.system.*;
import io.tednology.*;
import io.tednology.jforex.LoadingProgressCompleteListener;
import io.tednology.strategies.ForexEngineRef;
import io.tednology.strategies.OneMinuteScalpingStrategy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jooq.lambda.tuple.Tuple2;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.tednology.time.Temporals.epochMs;
import static io.tednology.time.Temporals.timeOf;

/**
 * @author Edward Smith
 */
@Slf4j
@Service
@EnableConfigurationProperties({SystemProperties.class, JForexProperties.class})
public class JForexPlatform implements ISystemListener {

    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final AtomicLong strategyPid = new AtomicLong(0);
    private int reconnects = 3;

    private final ForexEngineRef forexEngineRef;
    @Getter
    private final OneMinuteScalpingStrategy strategy;
    private final SystemProperties systemProperties;
    private final JForexProperties jForexProperties;
    private final ApplicationEventPublisher eventPublisher;

    private final IClient client;
    private final ITesterClient testClient;

    public JForexPlatform(ForexEngineRef forexEngineRef,
                          OneMinuteScalpingStrategy strategy,
                          SystemProperties systemProperties,
                          JForexProperties jForexProperties,
                          ApplicationEventPublisher eventPublisher) throws IllegalAccessException, InstantiationException, ClassNotFoundException {
        this.forexEngineRef = forexEngineRef;
        this.strategy = strategy;
        this.jForexProperties = jForexProperties;
        this.systemProperties = systemProperties;
        this.eventPublisher = eventPublisher;
        this.client = !jForexProperties.getBackTest().isEnabled()
            ? ClientFactory.getDefaultInstance()
            : null;
        this.testClient = jForexProperties.getBackTest().isEnabled()
            ? TesterFactory.getDefaultInstance()
            : null;
    }

    private IClient currentClient() {
        return jForexProperties.getBackTest().isEnabled() ? testClient : client;
    }

    @Async
    void connect() throws Exception {
        currentClient().setSystemListener(this);

        log.info("Connecting to Dukascopy platform: {}", jForexProperties.getUrl());
        currentClient().connect(
            jForexProperties.getUrl(),
            jForexProperties.getUsername(),
            jForexProperties.getPassword());
        waitForConnection();
    }

    private void waitForConnection() throws InterruptedException {

        if (jForexProperties.getBackTest().isEnabled()) {
            log.info("Downloading back-test data...");
            Future<?> future = testClient.downloadData(new LoadingProgressCompleteListener("DOWNLOAD"));
            try {
                // Block and wait for downloading to complete
                future.get();
            } catch (ExecutionException e) {
                log.error(e.getMessage(), e);
            }
        } else {
            // Wait up to 25 seconds for connection
            int i = 25;
            while (i > 0 && !currentClient().isConnected()) {
                log.info("i=" + i);
                Thread.sleep(1000);
                i--;
            }
        }
        if (!currentClient().isConnected()) {
            log.error("Failed to connect to Dukascopy servers!");
            eventPublisher.publishEvent(new ConnectionFailedEvent(this));
            return;
        }
        afterConnected();
    }

    private void afterConnected() {
        final Instrument[] instrArr = new Instrument[]{Instrument.EURUSD};
        Set<Instrument> instruments = new HashSet<>(Arrays.asList(instrArr));

        log.info("Subscribing to instruments {}", instruments);

        currentClient().setSubscribedInstruments(instruments);

        JForexProperties.BackTest backTest = jForexProperties.getBackTest();
        if (backTest.isEnabled()) {
            Tuple2<LocalDateTime,LocalDateTime> period = backTest.period();
            LocalDateTime start = period.v1();
            LocalDateTime end = period.v2();

            log.info("Running in Back Test mode, from {} to {}.", start, end);

            long from = start.toEpochSecond(ZoneOffset.UTC) * 1000;
            long to = end.toEpochSecond(ZoneOffset.UTC) * 1000;
            testClient.setDataInterval(ITesterClient.DataLoadingMethod.ALL_TICKS, from, to);
            testClient.setInitialDeposit(Instrument.EURUSD.getSecondaryJFCurrency(), 50000);
            testClient.setLeverage(80);
            testClient.startStrategy(strategy, new LoadingProgressCompleteListener("TESTING"));
        } else {
            NewsFilter newsFilter = new NewsFilter();
            newsFilter.setTimeFrame(NewsFilter.TimeFrame.TODAY);
            newsFilter.getCurrencies().add(INewsFilter.Currency.EUR);
            newsFilter.getCurrencies().add(INewsFilter.Currency.USD);
            client.startStrategy(strategy);
            client.addNewsFilter(newsFilter);
        }
    }

    public boolean restartStrategy() {
        if (currentClient().getStartedStrategies().isEmpty()) {
            currentClient().startStrategy(strategy);
            return true;
        }
        return false;
    }

    public boolean stopStrategy() {
        if (strategyPid.get() > 0) {
            currentClient().stopStrategy(strategyPid.get());
            return true;
        }
        return false;
    }

    public List<ITick> getTicks(int minusMinutes) throws JFException {
        if (strategy.getContext() == null) return Collections.emptyList();
        long timeOfLastTick = strategy.getContext().getHistory().getTimeOfLastTick(Instrument.EURUSD);
		return strategy.getContext().getHistory().getTicks(
            Instrument.EURUSD,
            timeOfLastTick - ((long) minusMinutes * 60 * 1000),
            timeOfLastTick
        );
    }

    public List<IBar> getCandles(LocalDateTime from, LocalDateTime to, OfferSide side) throws JFException {
        if (strategy.getContext() == null) return Collections.emptyList();
        return strategy.getContext().getHistory().getBars(
            Instrument.EURUSD,
            Period.ONE_MIN,
            side,
            epochMs(from),
            epochMs(to)
        );
    }

    public List<IBar> getCandles(int shift, OfferSide side) throws JFException {
        if (strategy.getContext() == null) return Collections.emptyList();
        long from = strategy.getContext().getHistory().getBar(Instrument.EURUSD, Period.ONE_MIN, side, shift).getTime();
        long to = strategy.getContext().getHistory().getBar(Instrument.EURUSD, Period.ONE_MIN, side, 1).getTime();
        return strategy.getContext().getHistory().getBars(
            Instrument.EURUSD,
            Period.ONE_MIN,
            side,
            from,
            to
        );
    }

    @Scheduled(initialDelay = 4*60*60000, fixedRate = 4*60*60000)
    void refreshSession() throws Exception {
        if (shutdownRequested.compareAndSet(false, true)) {
            currentClient().disconnect();
            connect();
        }
    }

    @EventListener
    void disconnect(ContextClosedEvent contextClosedEvent) throws Exception {
        shutdownRequested.set(true);
        currentClient().disconnect();
    }

    @Override
    public void onStart(long processId) {
        log.info("Strategy {} started.", processId);
        strategyPid.set(processId);
        strategy.setPid(processId);
        eventPublisher.publishEvent(new StrategyStartedEvent(this));
    }

    @Override
    public void onStop(long processId) {
        log.info("Strategy {} stopped.", processId);
        strategyPid.set(0);
        eventPublisher.publishEvent(new StrategyStoppedEvent(this));
        JForexProperties.BackTest backTest = jForexProperties.getBackTest();
        if (backTest.isEnabled()) {
            try {
                String home = System.getProperty("user.home");
                ITesterReportData reportData = testClient.getReportData(processId);
                log.info("Take Profit {}, Stop Loss {}", jForexProperties.getTakeProfit(), jForexProperties.getStopLoss());
                log.info("Execution window: {}", systemProperties.getStrategyWindows());
                log.info("Initial Deposit: {}", reportData.getInitialDeposit());
                log.info("Finished Deposit: {}", reportData.getFinishDeposit());
                log.info("Successful Orders: {}", reportData.getClosedOrders().stream().filter(o -> o.getProfitLossInPips() > 0).count());
                log.info("Unsuccessful Orders: {}", reportData.getClosedOrders().stream().filter(o -> o.getProfitLossInPips() < 0).count());

                LocalDateTime start = timeOf(reportData.getFrom());
                File reportDir = new File(
                    String.format("%s/backtest-reports(TP_%s,ST_%s)/%s",
                        home,
                        jForexProperties.getTakeProfit(),
                        jForexProperties.getStopLoss(),
                        start.format(DateTimeFormatter.ofPattern("yyyy-MM")))
                );
                if (!reportDir.exists()) reportDir.mkdirs();

                DateTimeFormatter monthFormat = DateTimeFormatter.ofPattern("yyyy-MM");
                File reportFile = new File(String.format("%s/backtest-reports(TP_%s,ST_%s)/%s/%s(%s_%s)-backtest.html",
                    home,
                    jForexProperties.getTakeProfit(),
                    jForexProperties.getStopLoss(),
                    start.format(monthFormat),
                    start.format(DateTimeFormatter.ofPattern("dd")),
                    backTest.getDurationDays() > 0 ? backTest.getDurationDays() : backTest.timeWindow(),
                    backTest.getDurationDays() > 0 ? "days" : "minutes")
                );

                testClient.createReport(processId, reportFile);

                log.info("Report file location: {}", reportFile.getAbsolutePath());

                disconnect(null);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        if (currentClient().getStartedStrategies().isEmpty()) {
            forexEngineRef.setEngine(null);
            eventPublisher.publishEvent(new AllStrategiesStoppedEvent(this));
        }
    }

    @Override
    public void onConnect() {
        log.info("Connected.");
        eventPublisher.publishEvent(new ConnectedEvent(this));
        reconnects = jForexProperties.getReconnects();
    }

    @Override
    public void onDisconnect() {
        eventPublisher.publishEvent(new DisconnectedEvent(this));
        log.warn("Disconnected.");
        if (shutdownRequested.get()) return;

        if (reconnects > 0) {
            log.info("Reconnecting...");
            currentClient().reconnect();
            eventPublisher.publishEvent(new ReconnectingEvent(this));
            --reconnects;
        } else {
            try {
                // Sleep for 5 seconds before attempting to reconnect
                Thread.sleep(5000);
            } catch (InterruptedException e) { /* Don't care */}
            try {
                currentClient().connect(
                    jForexProperties.getUrl(),
                    jForexProperties.getUsername(),
                    jForexProperties.getPassword());
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        try {
            waitForConnection();
        } catch (InterruptedException e) {
            log.error("Could not reconnect to the platform.", e);
        }
    }

}
