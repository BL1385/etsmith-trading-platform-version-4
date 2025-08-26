/*package io.tednology.strategies;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;

import org.junit.Before;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Edward Smith
 */
/*public class JSONTests {

    private Stoch stoch;
    private ObjectMapper objectMapper;

    @Before
    public void configure() {
        stoch = new Stoch(LocalDateTime.of(2016, Month.SEPTEMBER, 25, 12, 30), 20.3, 45.4);
        objectMapper = new ObjectMapper()
            .registerModule(new KotlinModule())
            .registerModule(new JavaTimeModule());
    }

    @Test
    public void BarEvent__canSerializeNicely() throws JsonProcessingException {
        long timeInMs = stoch.getTime().toEpochSecond(ZoneOffset.UTC) * 1000;
        String expected =
            String.format(
                "{\n" +
                "  \"time\" : %d,\n" +
                "  \"ema50\" : 1.237,\n" +
                "  \"ema100\" : 1.234,\n" +
                "  \"stoch\" : {\n" +
                "    \"time\" : \"2016-09-25 12:30:00\",\n" +
                "    \"slowK\" : 20.3,\n" +
                "    \"slowD\" : 45.4\n" +
                "  }\n" +
                "}", timeInMs);
        String actual = objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(new BarEvent(timeInMs, 1.237, 1.234, stoch));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void TickEvent__canSerializeNicely() throws JsonProcessingException {
        long timeInMs = stoch.getTime().toEpochSecond(ZoneOffset.UTC) * 1000;
        String expected =
            "{\n" +
            "  \"time\" : \"2016-09-25 12:30:00\",\n" +
            "  \"bid\" : 1.237,\n" +
            "  \"ask\" : 1.234\n" +
            "}";
        String actual = objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(new TickEvent(timeInMs, 1.237, 1.234));

        assertThat(actual).isEqualTo(expected);
    }
}*/