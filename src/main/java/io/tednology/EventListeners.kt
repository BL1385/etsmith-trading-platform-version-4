package io.tednology

import io.tednology.strategies.BarEvent
import io.tednology.strategies.CandleBarEvent
import io.tednology.strategies.TickEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.*

/**
 * @author Edward Smith
 */
abstract class SessionBasedSSESendingEventListener {

    open val sseEmitters: MutableMap<String, SseEmitter> = Collections.synchronizedMap(HashMap<String, SseEmitter>())

    open fun publish(e: Any) {
        val toComplete = ArrayList<String>()
        for ((sessionId, emitter) in sseEmitters) {
            try {
                emitter.send(SseEmitter.event().data(e).build())
            } catch (t: Throwable) {
                toComplete.add(sessionId)
            }
        }
        toComplete.forEach { completeEmitting(it) }
    }

    open fun addEmitter(sessionId: String, emitter: SseEmitter): SseEmitter {
        sseEmitters.put(sessionId, emitter)
        return emitter
    }

    open fun completeEmitting(sessionId: String) {
        sseEmitters.remove(sessionId)?.complete()
    }
}

@Component
open class BarEventListener: SessionBasedSSESendingEventListener() {
    @EventListener
    @Async
    open fun onBar(e: BarEvent) = publish(e)
}

@Component
open class CandleBarEventListener: SessionBasedSSESendingEventListener() {
    @EventListener
    @Async
    open fun onCandleBar(e: CandleBarEvent) = publish(e)
}

@Component
open class TickEventListener : SessionBasedSSESendingEventListener() {
    var latestTick: TickEvent? = null

    @EventListener
    @Async
    open fun onTick(e: TickEvent) {
        if (latestTick == null) {
            latestTick = e
        } else if (latestTick != null && e.time > latestTick!!.time) {
            latestTick = e
        } else {
            latestTick = null
        }
    }

    @Scheduled(initialDelay = 10000, fixedRate = 1000)
    open fun broadcastTick() = latestTick?.let { publish(it) }

}
