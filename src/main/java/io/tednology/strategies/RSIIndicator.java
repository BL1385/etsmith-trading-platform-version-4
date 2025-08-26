package io.tednology.strategies;

import com.dukascopy.api.DefaultColors;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.indicators.*;
import io.tednology.analysis.BarData;

/**
 * @author Edward Smith/Gumbo
 */

/**
 * <b>NOTE: </b> The calculate logic of this indicator is implemented in JavaScript.
 * Please, update the corresponding JS code in case of updating of this class.
 * @author anatoly.pokusayev
 *
 */
public class RSIIndicator implements IIndicator {

    private IndicatorInfo indicatorInfo;

    private InputParameterInfo[] inputParameterInfos;
    private OptInputParameterInfo[] optInputParameterInfos;
    private OutputParameterInfo[] outputParameterInfos;

    private final double[][] inputs = new double[1][];
    private final double[][] outputs = new double[1][];
    private int timePeriod = 8;
    private BarData barData; // Declare barData as an instance variable
    private OfferSide offerSide;
    public RSIIndicator() {
        // Initialize the indicator without the context
        // You can provide default values or leave the fields uninitialized
        // if they are not required for the calculation
    }

    public void onStart(IIndicatorContext context) {
        indicatorInfo = new IndicatorInfo("RSI", "Relative Strength Index", "Momentum Indicators", false, false, true, 1, 1, 1);

        inputParameterInfos = new InputParameterInfo[] {
                new InputParameterInfo("Price", InputParameterInfo.Type.DOUBLE)
        };

        optInputParameterInfos = new OptInputParameterInfo[] {
                new OptInputParameterInfo("Time period", OptInputParameterInfo.Type.OTHER, new IntegerRangeDescription(8, 2, 2000, 1))
        };

        outputParameterInfos = new OutputParameterInfo[] {
                new OutputParameterInfo("out", OutputParameterInfo.Type.DOUBLE, OutputParameterInfo.DrawingStyle.LINE)
        };

        indicatorInfo.setDefaultLevelsInfo(new LevelInfo[] {
                new LevelInfo("", 40, OutputParameterInfo.DrawingStyle.DASH_LINE, DefaultColors.GRAY, 1, 1),
                new LevelInfo("", 60, OutputParameterInfo.DrawingStyle.DASH_LINE, DefaultColors.GRAY, 1, 1)
        });
    }

    public IndicatorResult calculate(int startIndex, int endIndex) {
        int lookbackPeriod = getLookback(); // Get the look back period

        if (startIndex - getLookback() < 1) {
            startIndex = getLookback();
        }
        if (startIndex > endIndex) {
            return new IndicatorResult(1, 0);
        }

        // Call the calculateRSI method here
        double rsiValue = calculateRSI(barData, offerSide);

        int i, today = startIndex - getLookback(), outIdx = 0;
        double prevValue = inputs[0][today], prevGain = 0, prevLoss = 0, tempValue1, tempValue2;
        today++;

        for (i = timePeriod; i > 1; i--) {
            tempValue1 = inputs[0][today++];
            tempValue2 = tempValue1 - prevValue;
            prevValue  = tempValue1;
            if( tempValue2 < 0 ) prevLoss -= tempValue2;
            else prevGain += tempValue2;
        }
        prevGain /= timePeriod;
        prevLoss /= timePeriod;

        if( today > startIndex){
            tempValue1 = prevGain + prevLoss;
            outputs[0][outIdx++] = tempValue1 == 0 ? 0 : 100 * (prevGain / tempValue1);
        }

        while(today <= endIndex){
            tempValue1 = inputs[0][today++];
            tempValue2 = tempValue1 - prevValue;
            prevValue  = tempValue1;

            prevLoss *= (timePeriod - 1);
            prevGain *= (timePeriod - 1);

            if( tempValue2 < 0 ) prevLoss -= tempValue2;
            else prevGain += tempValue2;

            prevLoss /= timePeriod;
            prevGain /= timePeriod;
            tempValue1 = prevLoss + prevGain;
            outputs[0][outIdx++] = tempValue1 == 0 ? 0 : 100 * (prevGain / tempValue1);
        }
        return new IndicatorResult(startIndex, outIdx);
    }

    public double calculateRSI(BarData barData, OfferSide offerSide){
        // Check if barData has at least one bar
        if (barData.getBarCount() < 1) {
            // Return a default RSI value or throw an exception, depending on your requirements
            return 1; // Modify this line according to your needs
        }

        // Prepare the necessary inputs for the RSI calculation
        setInputParameter(0, barData.getClose());

        // Set the time period based on the desired RSI period
        setOptInputParameter(0, timePeriod);

        // Prepare the output buffer for the RSI calculation
        setOutputParameter(0, new double[barData.getBarCount()]);

        // Perform the RSI calculation
        calculate(0, barData.getBarCount() - 1);

        // Retrieve the calculated RSI value
        double[] rsiOutput = outputs [0];
        double rsiValue = rsiOutput[barData.getBarCount() - 1];

        return rsiValue;
    }

    public IndicatorInfo getIndicatorInfo() {
        return indicatorInfo;
    }

    public InputParameterInfo getInputParameterInfo(int index) {
        if (index < inputParameterInfos.length) {
            return inputParameterInfos[index];
        }
        return null;
    }

    public OptInputParameterInfo getOptInputParameterInfo(int index) {
        if (index < optInputParameterInfos.length) {
            return optInputParameterInfos[index];
        }
        return null;
    }

    public OutputParameterInfo getOutputParameterInfo(int index) {
        if (index < outputParameterInfos.length) {
            return outputParameterInfos[index];
        }
        return null;
    }

    public void setInputParameter(int index, Object array) {
        if (array instanceof double[]) {
            inputs[index] = (double[]) array;
        } else if (array instanceof int[]) {
            int[] intArray = (int[]) array;
            double[] doubleArray = new double[intArray.length];
            for (int i = 0; i < intArray.length; i++) {
                doubleArray[i] = intArray[i];
            }
            inputs[index] = doubleArray;
        } else {
            throw new IllegalArgumentException("Unsupported input parameter type");
        }
    }

    public void setOptInputParameter(int index, Object value) {
        timePeriod = (Integer) value;
    }

    public void setOutputParameter(int index, Object array) {
        outputs[index] = (double[]) array;
    }

    public int getLookback() {
        return timePeriod;
    }

    public int getLookforward() {
        return 0;
    }

    public double[] getResult() {
        return new double[0];
    }
}
