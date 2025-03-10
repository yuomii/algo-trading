package de.y3om11.algotrading.adapter.binance;

import com.binance.api.client.BinanceApiClientFactory;
import de.y3om11.algotrading.domain.constants.MarketPair;
import de.y3om11.algotrading.domain.constants.TimeInterval;
import de.y3om11.algotrading.domain.gateway.MarketProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.ta4j.core.*;
import org.ta4j.core.num.PrecisionNum;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

import static java.lang.String.format;

@Service
public class MarketProviderImpl implements MarketProvider {

    final static Logger log = LoggerFactory.getLogger(MarketProviderImpl.class);
    private final Map<MarketPair, BarSeries> barSeriesMap = new ConcurrentHashMap<>();
    private final Map<MarketPair, Long> closeTimeCache = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

    @Autowired
    public MarketProviderImpl(@Qualifier("threadPoolExecutor") final ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    @Override
    public BarSeries getBarSeries(final MarketPair marketPair, final TimeInterval timeInterval, final int maxBarCount) {

        if(barSeriesMap.containsKey(marketPair)) return barSeriesMap.get(marketPair);

        final var barList = new ArrayList<Bar>();
        final var baseBarSeries = new BaseBarSeriesBuilder()
                .withBars(barList)
                .withMaxBarCount(maxBarCount)
                .withName(MarketPairMapper.map(marketPair))
                .build();
        barSeriesMap.put(marketPair, baseBarSeries);

        executor.submit(() -> {
            final var client = BinanceApiClientFactory.newInstance().newWebSocketClient();
            client.onCandlestickEvent(MarketPairMapper.map(marketPair), TimeIntervalMapper.map(timeInterval), response -> {
                try {
                    final var bar = BaseBar.builder()
                            .openPrice(PrecisionNum.valueOf(response.getOpen()))
                            .closePrice(PrecisionNum.valueOf(response.getClose()))
                            .highPrice(PrecisionNum.valueOf(response.getHigh()))
                            .lowPrice(PrecisionNum.valueOf(response.getLow()))
                            .volume(PrecisionNum.valueOf(response.getVolume()))
                            .amount(PrecisionNum.valueOf(response.getVolume()))
                            .trades(response.getNumberOfTrades().intValue())
                            .endTime(ZonedDateTime.ofInstant(Instant.ofEpochMilli(response.getCloseTime()), ZoneId.of("UTC")))
                            .timePeriod(Duration.ofMillis(response.getCloseTime() - response.getOpenTime() + 1))
                            .build();

                    if(!closeTimeCache.containsKey(marketPair)) closeTimeCache.put(marketPair, response.getCloseTime());

                    if(closeTimeCache.get(marketPair).equals(response.getCloseTime())){
                        baseBarSeries.addBar(bar, true);
                    } else {
                        baseBarSeries.addBar(bar);
                        closeTimeCache.put(marketPair, response.getCloseTime());
                        log.info(format("Adding new Bar %s for Pair %s", bar, marketPair));
                    }
                } catch (RuntimeException e){
                    log.error(e.getMessage());
                }
            });
        });
        return baseBarSeries;
    }
}
