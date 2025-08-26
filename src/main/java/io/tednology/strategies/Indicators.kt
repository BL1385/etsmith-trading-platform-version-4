package io.tednology.strategies

import com.dukascopy.api.IEngine
import com.dukascopy.api.IEngine.OrderCommand
import com.dukascopy.api.IOrder
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnore
import io.tednology.analysis.BarData
import org.jooq.lambda.tuple.Range
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter


@Service
class ForexEngineRef {
    var engine: IEngine? = null
}


class StrategyWindow(val strategyWindow: Range<LocalTime>) {
    val isOpen: Boolean
        get() {
            val now = LocalTime.now()
            return now.isAfter(strategyWindow.v1()) && now.isBefore(strategyWindow.v2())
        }

    fun isOpen(time: LocalTime) = time.isAfter(strategyWindow.v1()) && time.isBefore(strategyWindow.v2())

    override fun toString(): String {
        return "StrategyWindow{${strategyWindow.v1} <=> ${strategyWindow.v2}}"
    }

    fun asShortString(): String {
        val start = strategyWindow.v1().format(DateTimeFormatter.ofPattern("HH"))
        val end = strategyWindow.v2().format(DateTimeFormatter.ofPattern("HH"))
        return "$start-$end"
    }
}

enum class StrategyMode { SAFE, CONSERVATIVE, AGGRESSIVE }

enum class Direction { RISING, FALLING, INDETERMINATE }

enum class Delta { CONVERGING, DIVERGING, INDETERMINATE }

enum class StochasticThreshold { RISEN_ABOVE, FALLEN_BELOW, INDETERMINATE }

data class Stoch(@JsonFormat(pattern = "yyyy-MM-dd kk:mm:ss")
                 val time: LocalDateTime,
                 val slowK: Double,
                 val slowD: Double)

class TickEvent(time: Long,
                val bid: Double,
                val ask: Double) {
    @JsonFormat(pattern = "yyyy-MM-dd kk:mm:ss")
    val time: LocalDateTime
    init {
        this.time = time.toLocalDateTime()
    }
}

class BarEvent(val time: Long,
               val ema50: Double,
               val ema100: Double,
               val stoch: Stoch)

class CandleBarEvent(val ask: BarData, val bid: BarData)

open class OrderEvent(order: IOrder) {
    val id: String
    val long: Boolean
    val short: Boolean
    val amount: Double
    val createdAt: Long
    val side: String
    val state: String
    val open: Double
    val close: Double
    val stopLoss: Double
    val takeProfit: Double
    val profit: Double

    init {
        amount = order.amount
        createdAt = order.creationTime
        id = order.id
        long = order.isLong
        short = !long
        side = order.orderCommand.toString()
        state = order.state.toString()
        open = order.openPrice
        close = order.closePrice
        stopLoss = order.stopLossPrice
        takeProfit = order.takeProfitPrice
        profit = order.profitLossInPips
    }
}

val EMPTY_TICK = Tick(LocalDateTime.MIN, 0.0, 0.0, DoubleArray(0), DoubleArray(0))
class Tick(@JsonFormat(pattern = "yyyy-MM-dd kk:mm:ss")
           val time: LocalDateTime,
           val ask: Double,
           val bid: Double,
           val asks: DoubleArray,
           val bids: DoubleArray) {
    @JsonIgnore
    fun isEmpty() = EMPTY_TICK == this
}

fun Long.toLocalDateTime(): LocalDateTime {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneOffset.UTC)
}
