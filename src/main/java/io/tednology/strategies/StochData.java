package io.tednology.strategies;

import com.dukascopy.api.*;

import static io.tednology.time.Temporals.timeOf;

/**
 * @author Edward Smith/Gumbo
 */

class StochData {

    private StochData() {}

    static Stoch calculate(IIndicators indicators, long time, OfferSide offerSide) throws JFException {
        double[] stochValue = indicators.stoch(Instrument.EURUSD, Period.ONE_MIN, offerSide, 5, 3, IIndicators.MaType.SMA, 3, IIndicators.MaType.SMA, 0);
        return new Stoch(timeOf(time), stochValue[0], stochValue[1]);
    }
}

