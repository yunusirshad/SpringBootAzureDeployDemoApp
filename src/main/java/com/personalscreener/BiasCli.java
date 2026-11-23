package com.personalscreener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.personalscreener.config.ScreenerProperties;
import com.personalscreener.data.YahooClient;
import com.personalscreener.market.MarketClock;
import com.personalscreener.service.BiasService;
import com.personalscreener.service.Notifier;
import com.personalscreener.store.JsonStore;

import java.nio.file.Files;

/**
 * Prints today's market-bias call and exits, without starting the web app. Used by the scheduled Claude task that
 * sends the call to the Claude app at 9:30 AM ET. Run:
 * <pre>
 * java -Dloader.main=com.personalscreener.BiasCli -cp target/personal-screener.jar \
 *      org.springframework.boot.loader.launch.PropertiesLauncher
 * </pre>
 * The first line is the headline (or "MARKET CLOSED"); the rest lists each factor. Add {@code --json} for JSON.
 */
public final class BiasCli {

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8));
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ScreenerProperties props = new ScreenerProperties();
        props.setDataDir(Files.createTempDirectory("bias").toString());
        MarketClock clock = new MarketClock();
        var today = clock.now().toLocalDate();
        if (!clock.isTradingDay(today)) {
            System.out.println("MARKET CLOSED: " + today + " is not a trading day, so there is no call.");
            return;
        }
        BiasService bias = new BiasService(new YahooClient(mapper, props), clock, new JsonStore(props, mapper),
                new Notifier(mapper, props), new com.personalscreener.data.TradingViewClient(mapper, props));
        if (java.util.Arrays.asList(args).contains("--intraday")) { intraday(bias, clock); return; }
        BiasService.Record r = bias.compute();
        if (args.length > 0 && args[0].equals("--json")) {
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));
        } else {
            System.out.println(BiasService.title(r));
            System.out.println(BiasService.body(r));
        }
    }

    /**
     * The hourly reading for the last whole hour (10:00 AM to 4:00 PM ET), and whether it flipped from the hour
     * before (from the 9:30 call at 10:00). Each scheduled run starts fresh, so the previous reading is recomputed.
     */
    static void intraday(BiasService bias, MarketClock clock) {
        var now = clock.now();
        var cut = now.withMinute(0).withSecond(0).withNano(0);
        if (cut.getHour() < 10 || cut.getHour() > 16) {
            System.out.println("OUTSIDE MARKET HOURS: intraday readings are made on the hour from 10:00 AM to 4:00 PM ET.");
            return;
        }
        BiasService.Intraday r = bias.computeIntraday(cut);
        String before = cut.getHour() == 10 ? bias.compute().bias : bias.computeIntraday(cut.minusHours(1)).bias;
        if (!r.bias.equals(before)) r.flippedFrom = before;
        System.out.println(BiasService.intradayTitle(r, cut.toLocalDate()));
        System.out.println(BiasService.body(r.summary, r.factors));
    }
}
