package io.tednology

import org.springframework.context.ApplicationEvent

/**
 * @author Edward Smith
 */
abstract class ForexEvent(source: Any?) : (ApplicationEvent)(source) {
    init {
        this.source = source!!
    }
}

class ConnectedEvent(source: Any?) : (ForexEvent)(source)

class DisconnectedEvent(source: Any?) : (ForexEvent)(source)

class ReconnectingEvent(source: Any?) : (ForexEvent)(source)

class ConnectionFailedEvent(source: Any?) : (ForexEvent)(source)

class StrategyStartedEvent(source: Any?) : (ForexEvent)(source)

class StrategyStoppedEvent(source: Any?) : (ForexEvent)(source)

class AllStrategiesStoppedEvent(source: Any?) : (ForexEvent)(source)
