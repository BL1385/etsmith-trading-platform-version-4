package io.tednology

import com.dukascopy.api.*
import io.tednology.analysis.BarData
import io.tednology.analysis.OrderMeta
import io.tednology.analysis.OrderMetaRepository
import io.tednology.init.JForexPlatform
import io.tednology.strategies.*
import org.springframework.stereotype.Controller
import org.springframework.ui.ModelMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.stream.Collectors
import javax.servlet.http.HttpSession

@Controller
class RootController(val forexEngineRef: ForexEngineRef,
                     val jForexPlatform: JForexPlatform,
                     val barEventListener: BarEventListener,
                     val candleBarEventListener: CandleBarEventListener,
                     val tickEventListener: TickEventListener,
                     val orderMetaRepository: OrderMetaRepository) {

    @GetMapping("/")
    fun index(model: ModelMap): String {
        val orders = forexEngineRef.engine?.getOrders(Instrument.EURUSD) ?: emptyList()
        val orderDtos = orders.map(IOrder::toDto)
        model.put("orders", orderDtos)
        return "index"
    }

    @GetMapping("/stop-strategy") @ResponseBody
    fun stopStrategy() = jForexPlatform.stopStrategy()

    @GetMapping("/restart-strategy") @ResponseBody
    fun restartStrategy() = jForexPlatform.restartStrategy()

    @GetMapping("/tick-data") @ResponseBody
    fun tickData() = jForexPlatform.getTicks(2).map(ITick::toDto)

    @GetMapping("/bar-data")
    @ResponseBody
    fun barData(): List<CandleBarEvent> {
        val askbars = jForexPlatform.getCandles(30, OfferSide.ASK).map { it.toDto(OfferSide.ASK) }
        val bidBars = jForexPlatform.getCandles(30, OfferSide.BID).map { it.toDto(OfferSide.BID) }
        return askbars.zip(bidBars).map { CandleBarEvent(it.first, it.second) }
    }

    @GetMapping("/bar-data/{fromYear:\\d+}-{fromMonth:\\d+}-{fromDay:\\d+}/to/{toYear:\\d+}-{toMonth:\\d+}-{toDay:\\d+}")
    @ResponseBody
    fun barDataInRange(@PathVariable fromYear: Int, @PathVariable fromMonth: Int, @PathVariable fromDay: Int,
                       @PathVariable toYear: Int, @PathVariable toMonth: Int, @PathVariable toDay: Int): List<CandleBarEvent> {
        val from = LocalDate.of(fromYear, fromMonth, fromDay).atStartOfDay()
        val to = LocalDate.of(toYear, toMonth, toDay).atTime(23, 59, 59, 0)
        val askbars = jForexPlatform.getCandles(from, to, OfferSide.ASK).map { it.toDto(OfferSide.ASK) }
        val bidBars = jForexPlatform.getCandles(from, to, OfferSide.BID).map { it.toDto(OfferSide.BID) }
        return askbars.zip(bidBars).map { CandleBarEvent(it.first, it.second) }
    }

    @GetMapping("/order-data/{sltp}/{fromYear:\\d+}-{fromMonth:\\d+}-{fromDay:\\d+}/to/{toYear:\\d+}-{toMonth:\\d+}-{toDay:\\d+}")
    @ResponseBody
    fun orderData(@PathVariable sltp: String,
                  @PathVariable fromYear: Int, @PathVariable fromMonth: Int, @PathVariable fromDay: Int,
                  @PathVariable toYear: Int, @PathVariable toMonth: Int, @PathVariable toDay: Int): List<OrderMeta> {
        val from = LocalDate.of(fromYear, fromMonth, fromDay).atStartOfDay()
        val to = LocalDate.of(toYear, toMonth, toDay).atTime(23, 59, 59, 999999999)
        return orderMetaRepository.findByCreateTimeBetweenAndSltpEqualsOrderByCreateTime(
            from, to, sltp)
    }

    @RequestMapping("/subscribe/bars")
    fun subscribeToBarIndicators(session: HttpSession) =
        barEventListener.addEmitter(session.id, SseEmitter(SSE_TIMEOUT))

    @RequestMapping("/subscribe/candles")
    fun subscribeToCandleBars(session: HttpSession) =
        candleBarEventListener.addEmitter(session.id, SseEmitter(SSE_TIMEOUT))

    @RequestMapping("/subscribe/ticks")
    fun subscribeToTicks(session: HttpSession) =
        tickEventListener.addEmitter(session.id, SseEmitter(SSE_TIMEOUT))
}

class OrderDto(order: IOrder) : (OrderEvent)(order)
fun IOrder.toDto(): OrderDto = OrderDto(this)
fun ITick.toDto(): Tick = Tick(time.toLocalDateTime(), ask, bid, asks, bids)
fun IBar.toDto(offerSide: OfferSide): BarData = BarData(this, offerSide)

private val SSE_TIMEOUT = Long.MAX_VALUE