package io.tednology.init;

import io.tednology.strategies.StrategyMode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.URL;
import org.jooq.lambda.tuple.Tuple2;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Past;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static java.time.temporal.ChronoUnit.MINUTES;

/**
 * @author Edward Smith
 */
@Getter @Setter
@ConfigurationProperties(prefix="jforex.system")
public class JForexProperties {

    @NotNull
    @URL
    private String url;
    @NotNull
    private String username;
    @NotNull
    private String password;
    private boolean tradesEnabled = true;
    @NestedConfigurationProperty
    private BackTest backTest;


    @Getter
    @Setter
    public static class BackTest {

        private boolean enabled = true;

        @Past
        private LocalDate start;
        private int durationDays = 0;

        private LocalTime startTime;
        private LocalTime endTime;

        public Tuple2<LocalDateTime, LocalDateTime> period() {
            LocalDate startDate = getStart();
            LocalDateTime start = getStartTime() != null
                    ? startDate.atTime(getStartTime())
                    : startDate.atStartOfDay();

            LocalDateTime end = getDurationDays() > 0
                    ? start.plusDays(getDurationDays())
                    : startDate.atTime(getEndTime());

            return new Tuple2<>(start, end);
        }

        String timeWindow() {
            if (startTime != null && endTime != null) {
                return String.valueOf(MINUTES.between(startTime, endTime));
            }
            return "";
        }

    }

    private boolean autoreconnect = true;
    private int reconnects = 3;

    private double takeProfit = 12.0;
    private double stopLoss = 3.0;
    private double emaDelta = 0.00007;
    private double emaSlopeThreshold = 0.000025;
    private StrategyMode strategyMode = StrategyMode.CONSERVATIVE;

}
