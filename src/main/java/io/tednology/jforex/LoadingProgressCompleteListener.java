package io.tednology.jforex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static io.tednology.time.Temporals.timeOf;

/**
 * @author Edward Smith
 */
@Slf4j @RequiredArgsConstructor
public class LoadingProgressCompleteListener extends LoadingProgressListenerAdapter {

    private final String label;

    @Override
    public void loadingFinished(boolean allDataLoaded, long start, long end, long currentPosition) {
        log.info("PROGRESS:Finished [{}]: {}. Loaded {} to {} at {}.",
            label,
            allDataLoaded,
            timeOf(start),
            timeOf(end),
            timeOf(currentPosition));
    }

}
