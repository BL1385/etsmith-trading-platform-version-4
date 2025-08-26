package io.tednology.strategies;

import com.dukascopy.api.*;

/**
 * @author Edward Smith
 */
class EMA {

    private EMA() {}

    static double fifty(IIndicators indicators, OfferSide offerSide) throws JFException {
        return indicators.ema(Instrument.EURUSD, Period.ONE_MIN, offerSide, IIndicators.AppliedPrice.CLOSE, 50, 1);
    }

    static double hundred(IIndicators indicators, OfferSide offerSide) throws JFException {
        return indicators.ema(Instrument.EURUSD, Period.ONE_MIN, offerSide, IIndicators.AppliedPrice.CLOSE, 100, 1);
    }
}
