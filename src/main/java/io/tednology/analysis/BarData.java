package io.tednology.analysis;

import com.dukascopy.api.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.tednology.strategies.Delta;
import io.tednology.strategies.Direction;
import io.tednology.strategies.Stoch;
import lombok.Getter;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.jooq.lambda.Seq;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static io.tednology.time.Temporals.timeOf;

/**
 * Determines the direction that the Stochastic Oscillator is currently trending. This
 * will be either rising or falling, but in some cases may be indeterminate if the trend
 * is not clear based on the stochastic historical values.
 *
 * @author Edward Smith
 */
@Getter
public class BarData {

    @JsonFormat(pattern = "yyyy-MM-dd kk:mm:ss")
    private final LocalDateTime time;
    private final OfferSide side;
    private final List<Stoch> stochHistory;
    private final double stochSlopeShort;
    private final double stochSlopeLong;
    private final List<Double> ema50History;
    private final List<Double> ema100History;
    private final Direction stochDirection;
    private final Delta emaDelta;
    private final double open;
    private final double close;
    private final double high;
    private final double low;
    private final boolean crossedOver;

    public BarData(IBar bar, OfferSide offerSide) throws JFException {
        time = timeOf(bar.getTime());
        side = offerSide;
        open = bar.getOpen();
        close = bar.getClose();
        high = bar.getHigh();
        low = bar.getLow();
        stochHistory = Collections.emptyList();
        ema50History = Collections.emptyList();
        ema100History = Collections.emptyList();
        stochDirection = Direction.INDETERMINATE;
        stochSlopeShort = 0.0;
        stochSlopeLong = 0.0;
        emaDelta = Delta.INDETERMINATE;
        crossedOver = false;
    }

    public BarData(IHistory history, IIndicators indicators, Period period, IBar bar, OfferSide offerSide) throws JFException {
        time = timeOf(bar.getTime());
        side = offerSide;
        open = bar.getOpen();
        close = bar.getClose();
        high = bar.getHigh();
        low = bar.getLow();

        long sixthBarTime = history.getBar(INSTRUMENT, period, offerSide, 6).getTime();
        long lastBarTime = history.getBar(INSTRUMENT, period, offerSide, 1).getTime();

        // Calculate trends for EMAs (e.g. assess EMA convergence/divergence - market direction turning over)
        double[] ema50s = indicators.ema(INSTRUMENT, period, offerSide, IIndicators.AppliedPrice.CLOSE, 50, sixthBarTime, lastBarTime);
        double[] ema100s = indicators.ema(INSTRUMENT, period, offerSide, IIndicators.AppliedPrice.CLOSE, 100, sixthBarTime, lastBarTime);
        ema50History = Arrays.stream(ema50s).mapToObj(d -> d).collect(Collectors.toList());
        ema100History = Arrays.stream(ema100s).mapToObj(d -> d).collect(Collectors.toList());

        SimpleRegression deltaRegression = new SimpleRegression();
        Seq.seq(ema50History).zip(Seq.seq(ema100History))
                .map(emas -> emas.v1 - emas.v2)
                .map(Math::abs)
                .zipWithIndex()
                .forEach(delta -> deltaRegression.addData(delta.v2, delta.v1));

        // Examine last ema50/100 to determine whether a crossover occurred on the last bar.
        // In this case, they are not converging or diverging - the emaDelta is indeterminate.
        double ema50 = ema50s[ema50s.length - 1];
        double ema100 = ema100s[ema100s.length - 1];
        double lastEma50 = ema50s[ema50s.length - 2];
        double lastEma100 = ema100s[ema100s.length - 2];

        crossedOver = (ema50 > ema100 && lastEma50 < lastEma100) || (ema50 < ema100 && lastEma50 > lastEma100);
        if (!crossedOver) {
            if (deltaRegression.getSlope() > .000001) {
                emaDelta = Delta.DIVERGING;
            } else if (deltaRegression.getSlope() < -.000001) {
                emaDelta = Delta.CONVERGING;
            } else {
                emaDelta = Delta.INDETERMINATE;
            }
        } else {
            emaDelta = Delta.INDETERMINATE;
        }

        stochHistory = stochHistory(indicators, period, offerSide, sixthBarTime, lastBarTime);

        List<Stoch> last3Stoch = Seq.seq(stochHistory)
                .reverse()
                .slice(0, 3)
                .reverse()
                .toList();

        SimpleRegression shortStochRegression = new SimpleRegression();
        last3Stoch
                .forEach(s -> shortStochRegression.addData(
                        s.getTime().toEpochSecond(ZoneOffset.UTC) * 1000,
                        s.getSlowK()));
        this.stochSlopeShort = shortStochRegression.getSlope();
        SimpleRegression longStochRegression = new SimpleRegression();
        stochHistory
                .forEach(s -> longStochRegression.addData(
                        s.getTime().toEpochSecond(ZoneOffset.UTC) * 1000,
                        s.getSlowK()));
        this.stochSlopeLong = longStochRegression.getSlope();

        if (shortStochRegression.getSlope() > .0001) {
            stochDirection = Direction.RISING;
        } else if (shortStochRegression.getSlope() < -.0001) {
            stochDirection = Direction.FALLING;
        } else {
            stochDirection = Direction.INDETERMINATE;
        }
//        log.info("{}", stochHistory);
//        log.info("{} trend: {}, slope: {}", offerSide, stochDirection, regression.getSlope());
    }

    public Stoch latest() {
        return stochHistory.get(stochHistory.size() - 1);
    }

    public double latestEma50() {
        return ema50History.get(ema50History.size() - 1);
    }

    public double latestEma100() {
        return ema100History.get(ema100History.size() - 1);
    }

    public boolean isStochStrong() {
        if (OfferSide.ASK == side) {
            return stochHistory.stream()
                    .filter(s -> s.getSlowK() > 85)
                    .count() > 2;
        } else if (OfferSide.BID == side) {
            return stochHistory.stream()
                    .filter(s -> s.getSlowK() < 15)
                    .count() > 2;
        }
        return false;
    }

    public boolean isTrendingUp() {
        return Direction.RISING == stochDirection;
    }

    public boolean isTrendingDown() {
        return Direction.FALLING == stochDirection;
    }

    public boolean wentUp() {
        return latest().getSlowK() > stochHistory.get(stochHistory.size() - 2).getSlowK();
    }

    public boolean wentAbove(double threshold) {
        return latest().getSlowK() > threshold;
    }

    public boolean wentDown() {
        return latest().getSlowK() < stochHistory.get(stochHistory.size() - 2).getSlowK();
    }

    public boolean wentBelow(double threshold) {
        return latest().getSlowK() < threshold;
    }

    public boolean emasTrendingUp() {
        return ema50Slope() > 0 && ema100Slope() > 0;
    }

    public boolean emasTrendingDown() {
        return ema50Slope() < 0 && ema100Slope() < 0;
    }

    public Direction ema50Trend() {
        double slope = ema50Slope();
        if (slope > 0) return Direction.RISING;
        if (slope < 0) return Direction.FALLING;
        return Direction.INDETERMINATE;
    }

    public Direction ema100Trend() {
        double slope = ema100Slope();
        if (slope > 0) return Direction.RISING;
        if (slope < 0) return Direction.FALLING;
        return Direction.INDETERMINATE;
    }

    public boolean isEmaTrendCorrect() {
        return OfferSide.ASK == side
                ? emasTrendingDown()
                : emasTrendingUp();
    }

    public boolean areEmaSlopesStrong(double threshold) {
        return OfferSide.ASK == side
                ? ema50Slope() < -threshold && ema100Slope() < -threshold
                : ema50Slope() > threshold && ema100Slope() > threshold;
    }

    public double ema50Slope() {
        SimpleRegression deltaRegression = new SimpleRegression();
        Seq.seq(ema50History)
                .zipWithIndex()
                .forEach(delta -> deltaRegression.addData(delta.v2, delta.v1));
        return deltaRegression.getSlope();
    }

    public double ema100Slope() {
        SimpleRegression deltaRegression = new SimpleRegression();
        Seq.seq(ema100History)
                .zipWithIndex()
                .forEach(delta -> deltaRegression.addData(delta.v2, delta.v1));
        return deltaRegression.getSlope();
    }

/**
    public boolean emasConverging() {
        return Delta.CONVERGING == emaDelta;
    }

    public boolean emasDiverging() {
        return Delta.CONVERGING == emaDelta;
    }
*/
    public List<Stoch> stochHistory(IIndicators indicators, Period period, OfferSide offerSide, long from, long to) throws JFException {
        double[][] rawHistory = indicators.stoch(Instrument.EURUSD, period, offerSide, 5, 3, IIndicators.MaType.SMA, 3, IIndicators.MaType.SMA, from, to);
        List<Double> slowKHistory = Arrays.stream(rawHistory[0]).mapToObj(d -> d).collect(Collectors.toList());
        List<Double> slowDHistory = Arrays.stream(rawHistory[1]).mapToObj(d -> d).collect(Collectors.toList());

        Seq<Long> times = Seq.seq(LongStream.iterate(from, t -> t + 60000).boxed()).limitWhile(t -> t <= to);
        return times.zip(Seq.seq(slowKHistory).zip(Seq.seq(slowDHistory)))
                .map(raw -> new Stoch(timeOf(raw.v1), raw.v2.v1, raw.v2.v2))
                .collect(Collectors.toList());
    }


    private static final Instrument INSTRUMENT = Instrument.EURUSD;

    public int getBarCount() {
        // Replace this with your actual implementation to retrieve the bar count
        int barCount = 0;
        // Add logic to calculate or retrieve the bar count
        // ...
        return barCount;
    }
}