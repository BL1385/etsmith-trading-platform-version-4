package io.tednology.init;

import com.dukascopy.api.IContext;
import io.tednology.strategies.StrategyWindow;
import lombok.Getter;
import lombok.Setter;
import org.jooq.lambda.tuple.Range;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Edward Smith
 */
@Getter @Setter
@ConfigurationProperties(prefix = "tednology.system")
public class SystemProperties {

    public Object zoneOffset;
    public IContext slippage;

    private List<String> contactEmails;

    private boolean connectOnStartup = true;

    private LocalTime strategyWindowOpen1 = LocalTime.of(0, 0, 0, 0);

    private LocalTime strategyWindowClose1 = LocalTime.of(23, 59, 59, 999_999_999);

    private LocalTime strategyWindowOpen2 = LocalTime.of(0, 0, 0, 0);

    private LocalTime strategyWindowClose2 = LocalTime.of(23, 59, 59, 999_999_999);

    public List<StrategyWindow> getStrategyWindows() {
        List<StrategyWindow> windows = new ArrayList<>();

        // First strategy window
        StrategyWindow window1 = new StrategyWindow(new Range<>(strategyWindowOpen1, strategyWindowClose1));
        windows.add(window1);

        // Second strategy window
        //StrategyWindow window2 = new StrategyWindow(new Range<>(strategyWindowOpen2, strategyWindowClose2));
        //windows.add(window2);

        return windows;
    }
}
