package io.tednology.analysis;

import com.dukascopy.api.IOrder;
import com.dukascopy.api.OfferSide;
import io.tednology.init.JForexProperties;
import io.tednology.strategies.Delta;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.persistence.*;
import java.time.LocalDateTime;

import static io.tednology.time.Temporals.timeOf;
import static java.time.temporal.ChronoUnit.SECONDS;

/**
 * @author Edward Smith
 */
@Entity
@Table(name="orders")
@Getter @Setter @ToString(of={"sltp", "createTime", "side", "profitLoss"})
public class OrderMeta {

    // Not persisted
    private transient BarData barData;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String orderId;
    private String label;
    private String sltp; // stop-loss/take-profit as a grouping key

    /*
     * At-open attributes of the order; they must not change.
     */

    @Enumerated(EnumType.STRING)
    private OfferSide side;

    private boolean emaSlopeStrong;
    private double ema50Slope;
    private double ema100Slope;
    private boolean emaTrendCorrect;
    private double emaDelta;
    @Enumerated(EnumType.STRING)
    private Delta emaDeltaDirection;
    private LocalDateTime lastEmaCrossOver;

    private boolean stochStrong;
    private double stochSlopeShort;
    private double stochSlopeLong;

    /*
     * Post-open attributes, can be tracked/set after the order has opened and/or is closed.
     */

    private LocalDateTime createTime;
    private LocalDateTime closeTime;
    private LocalDateTime fillTime;
    private long duration; // in seconds

    private Boolean strongStochSwing;
    private int reversals;
    private Boolean strongReversal;
    private Boolean falseStart;
    private Boolean stale;

    private double openPrice;
    private double closePrice;
    private double profitLoss;
    private boolean successful;
    private boolean mitigationApplied = false;

    public boolean adjustmentCandidate() {
        return (reversals >= 2) || strongStochSwing || strongReversal || falseStart || stale;
    }

    public OrderMeta() {}

    public OrderMeta(IOrder order, BarData barData, LocalDateTime lastEmaCrossOver, JForexProperties forexProperties) {
        this.orderId = order.getId();
        this.label = order.getLabel();
        this.createTime = timeOf(order.getCreationTime());
        this.barData = barData;
        this.lastEmaCrossOver = !LocalDateTime.MIN.equals(lastEmaCrossOver) ? lastEmaCrossOver : null;
        this.side = barData.getSide();
        this.ema50Slope = barData.ema50Slope();
        this.ema100Slope = barData.ema100Slope();
        this.emaTrendCorrect = barData.isEmaTrendCorrect();
        this.emaSlopeStrong = barData.areEmaSlopesStrong(forexProperties.getEmaSlopeThreshold());
        this.emaDelta = Math.abs(barData.latestEma50() - barData.latestEma100());
        this.emaDeltaDirection = barData.getEmaDelta();
        this.stochStrong = barData.isStochStrong();
        this.stochSlopeShort = barData.getStochSlopeShort();
        this.stochSlopeLong = barData.getStochSlopeLong();
        this.sltp = forexProperties.getTakeProfit() + "_" + forexProperties.getStopLoss();
    }

    public OrderMeta finalize(IOrder order) {
        if (!order.getId().equals(orderId)) throw new AssertionError("WRONG ID! "+order.getId());
        openPrice = order.getOpenPrice();
        closePrice = order.getClosePrice();
        fillTime = timeOf(order.getFillTime());
        closeTime = timeOf(order.getCloseTime());
        duration = SECONDS.between(fillTime, closeTime);
        profitLoss = order.getProfitLossInPips();
        successful = profitLoss > 0;
        if (falseStart == null) falseStart = false;
        if (strongStochSwing == null) strongStochSwing = false;
        if (strongReversal == null) strongReversal = false;
        if (stale == null) stale = false;
        return this;
    }

    public boolean isStale() {
        return stale != null && stale;
    }

    public boolean isFalseStart() {
        return falseStart != null && falseStart;
    }

    public boolean isStrongStochSwing() {
        return strongStochSwing != null && strongStochSwing;
    }

    public boolean isStrongReversal() {
        return strongReversal != null && strongReversal;
    }

    public boolean hasBeenMitigated() {
        return mitigationApplied;
    }

}
