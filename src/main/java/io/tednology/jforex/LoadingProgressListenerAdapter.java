package io.tednology.jforex;

import com.dukascopy.api.LoadingProgressListener;

/**
 * @author Edward Smith
 */
class LoadingProgressListenerAdapter implements LoadingProgressListener {

    @Override
    public void dataLoaded(long start, long end, long currentPosition, String information) {}

    @Override
    public void loadingFinished(boolean allDataLoaded, long start, long end, long currentPosition) {}

    @Override
    public boolean stopJob() { return false; }
}
