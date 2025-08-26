(function ($, Plotly) {
    document.addEventListener("DOMContentLoaded", function () {

        const currentScript = document.querySelector('#js-main');

        fetch('/bar-data', { method: 'GET' })
            .then((responseObj) => { return responseObj.json(); }).then((data) => {

            const candlesUrl = currentScript.getAttribute('data-candles-url');
            const candlesSource = new EventSource(candlesUrl);
            const bids = data.map((e) => { return e.bid });
            const asks = data.map((e) => { return e.ask });
            const bidCandleData = {
                open: bids.map((b) => { return b.open }),
                high: bids.map((b) => { return b.high }),
                low: bids.map((b) => { return b.low }),
                close: bids.map((b) => { return b.close }),
                dates: bids.map((b) => { return moment(b.time, "YYYY-MM-DD HH:mm:ss").toDate() })
            };
            const askCandleData = {
                open: asks.map((b) => { return b.open }),
                high: asks.map((b) => { return b.high }),
                low: asks.map((b) => { return b.low }),
                close: asks.map((b) => { return b.close }),
                dates: asks.map((b) => { return moment(b.time, "YYYY-MM-DD HH:mm:ss").toDate() })
            };
            const bidFig = PlotlyFinance.createCandlestick({
                open: bidCandleData.open,
                high: bidCandleData.high,
                low: bidCandleData.low,
                close: bidCandleData.close,
                dates: bidCandleData.dates.map((date) => {
                    return moment(date, "YYYY-MM-DD HH:mm:ss").toDate();
                })
            });
            var askFig = PlotlyFinance.createCandlestick({
                open: askCandleData.open,
                high: askCandleData.high,
                low: askCandleData.low,
                close: askCandleData.close,
                dates: askCandleData.dates.map((date) => {
                    return moment(date, "YYYY-MM-DD HH:mm:ss").toDate();
                })
            });
            Plotly.newPlot('bid-candle-chart', bidFig.data, bidFig.layout);
            Plotly.newPlot('ask-candle-chart', askFig.data, askFig.layout);

            fetch('/order-data/1.5_5.0/2016-06-27/to/2016-06-27', { method: 'GET' })
                .then((responseObj) => { return responseObj.json(); }).then((data) => {
                    let bidOrders = data
                        .filter(orderMeta => orderMeta.side == 'BID')
                        .map(orderMeta => {
                            moment(orderMeta.createTime, "YYYY-MM-DD HH:mm:ss").toDate();
                            return {
                                x: [], // Date/Time
                                y: [], // Value
                                mode: 'lines+markers',
                                line: {shape: 'linear', color: orderMeta.successfull ? 'green' : 'red'},
                                name: 'bid'
                            };
                        });
                        
                    if (bidOrders?.length > 0) {
                        Plotly.newPlot('bid-candle-chart', bidOrders);
                    }
                    
                    /*if (askOrders) {
                        Plotly.newPlot('ask-candle-chart', askOrders);
                    }*/
                });

            candlesSource.addEventListener('message', (e) => {
                if (!isValid(e)) return;
            
                const data = JSON.parse(e.data)[1].data;
                bidCandleData.open.push(data.bid.open);
                bidCandleData.high.push(data.bid.high);
                bidCandleData.low.push(data.bid.low);
                bidCandleData.close.push(data.bid.close);
                bidCandleData.dates.push(data.bid.time);
                askCandleData.open.push(data.ask.open);
                askCandleData.high.push(data.ask.high);
                askCandleData.low.push(data.ask.low);
                askCandleData.close.push(data.ask.close);
                askCandleData.dates.push(data.ask.time);
                const bidFig = PlotlyFinance.createCandlestick(
                    {
                        open: bidCandleData.open,
                        high: bidCandleData.high,
                        low: bidCandleData.low,
                        close: bidCandleData.close,
                        dates: bidCandleData.dates.map((date) => {
                            return moment(date, "YYYY-MM-DD HH:mm:ss").toDate();
                        })
                    }
                );
                Plotly.newPlot('bid-candle-chart', bidFig.data, bidFig.layout);
                var askFig = PlotlyFinance.createCandlestick(
                    {
                        open: bidCandleData.open,
                        high: bidCandleData.high,
                        low: bidCandleData.low,
                        close: bidCandleData.close,
                        dates: bidCandleData.dates.map((date) => {
                            return moment(date, "YYYY-MM-DD HH:mm:ss").toDate();
                        })
                    }
                );
                Plotly.newPlot('ask-candle-chart', askFig.data, askFig.layout);
            });

            candlesSource.addEventListener('open', onOpen('candles'), false);
            candlesSource.addEventListener('error', onError(candlesSource, 'Candles'), false);
        });

        fetch('/tick-data', { method: 'GET' })
            .then((responseObj) => { return responseObj.json() }).then((tickData) => {

                // x = Date/Time
                // y = Value
                const asks = {
                    x: tickData.map((tick) => {
                        return tick.time
                    }),
                    y: tickData.map((tick) => {
                        return tick.ask;
                    }),
                    mode: 'lines+markers',
                    line: {shape: 'spline', color: 'green'},
                    name: 'ask'
                };
                const bids = {
                    x: tickData.map((tick) => {
                        return tick.time
                    }),
                    y: tickData.map((tick) => {
                        return tick.bid;
                    }),
                    mode: 'lines+markers',
                    line: {shape: 'spline', color: 'red'},
                    name: 'bid'
                };

                const layout = {
                    title: 'EUR/USD Tick Data',
                    xaxis: { title: 'Time', showgrid: true, zeroline: true },
                    yaxis: { title: 'Price', showgrid: true, showline: true }
                };

                const tickChart = document.querySelector('#tick-chart');
                Plotly.plot(tickChart, [asks, bids], layout);

                const ticksUrl = currentScript.getAttribute('data-ticks-url');
                const tickSource = new EventSource(ticksUrl);
                
                tickSource.addEventListener('message', (e) => {
                    if (!isValid(e)) return;
                
                    const data = JSON.parse(e.data)[1].data;
                    tickChart.data[0].x.push(data.time);
                    tickChart.data[0].y.push(data.ask);
                    tickChart.data[1].x.push(data.time);
                    tickChart.data[1].y.push(data.bid);
                });
                setInterval(function() {
                    Plotly.redraw('tick-chart');
                }, 3500);
                
                tickSource.addEventListener('open', onOpen('ticks'), false);
                tickSource.addEventListener('error', onError(tickSource, 'Ticks'), false);
            });
    });

    function onOpen(sourceName) {
        return (e) => {
            console.log(`OK - connected to ${sourceName}`);
        }
    }

    function onError(source, sourceName) {
        return (e) => {
            source.close();
            console.error(e);
            if (e.readyState == EventSource.CLOSED) {
                // Connection was closed.
                console.log(`${sourceName} connection has closed.`)
            }
        }
    }

    function isValid(sseEvent) {
        if (sseEvent.origin != 'http://localhost:8080'
            && sseEvent.origin != 'http://tednology-trading-dev.cfapps.io'
            && sseEvent.origin != 'https://tednology-trading-dev.cfapps.io'
            && sseEvent.origin != 'http://trading.tednology.io'
            && sseEvent.origin != 'https://trading.tednology.io') {
            alert('Event origin `' + sseEvent.origin + '` is invalid.');
            return false;
        }
        return true;
    }
}(Zepto, Plotly));
