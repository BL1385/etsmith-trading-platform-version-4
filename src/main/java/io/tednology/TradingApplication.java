package io.tednology;

import io.tednology.analysis.OrderMetaRepository;
import io.tednology.init.JForexProperties;
import io.tednology.init.SystemProperties;
import io.tednology.strategies.ForexEngineRef;
import io.tednology.strategies.OneMinuteScalpingStrategy;
import org.jooq.lambda.tuple.Tuple2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.jpa.convert.threeten.Jsr310JpaConverters;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.AsyncConfigurerSupport;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;

@EnableScheduling
@EnableJpaRepositories
@EnableAsync(proxyTargetClass = true)
@SpringBootApplication@EntityScan(basePackageClasses = {TradingApplication.class, Jsr310JpaConverters.class})
@EnableConfigurationProperties({SystemProperties.class, JForexProperties.class})
public class TradingApplication extends AsyncConfigurerSupport {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(TradingApplication.class, args);
    }

    @Bean
    CommandLineRunner onBootWipeOrderMeta(JForexProperties jForexProperties,
                                          OrderMetaRepository orderMetaRepository) {
        return (args) -> {
            if (jForexProperties.getBackTest().isEnabled()) {
                Tuple2<LocalDateTime,LocalDateTime> period = jForexProperties.getBackTest().period();
                LocalDateTime start = period.v1();
                LocalDateTime end = period.v2();
                String sltp = String.format("%s_%s", jForexProperties.getTakeProfit(), jForexProperties.getStopLoss());
                orderMetaRepository.deleteByCreateTimeBetweenAndSltpEquals(start, end, sltp);
            }
        };
    }

    @Bean
    @Profile("!test")
    OneMinuteScalpingStrategy oneMinuteScalpingStrategy(ApplicationEventPublisher eventPublisher,
                                                        JavaMailSender javaMailSender,
                                                        ForexEngineRef forexEngineRef,
                                                        SystemProperties systemProperties,
                                                        JForexProperties jForexProperties) {

        return new OneMinuteScalpingStrategy(
            eventPublisher,
            javaMailSender,
            forexEngineRef,
            systemProperties,
            jForexProperties);
    }

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(15);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("Schedule+Async-");
        executor.initialize();
        return executor;
    }

    @Bean
    @ConfigurationPropertiesBinding
    Converter<String,LocalTime> localTimeConverter() {
        return new Converter<String,LocalTime>() {
            @Override
            public LocalTime convert(String source) {
                return LocalTime.from(DateTimeFormatter.ofPattern("hh:mm:ss.n a").parse(source));
            }
        };
    }

    @Bean
    @ConfigurationPropertiesBinding
    Converter<String,LocalDate> localDateConverter() {
        return new Converter<String,LocalDate>() {
            @Override
            public LocalDate convert(String source) {
                return LocalDate.from(DateTimeFormatter.ofPattern("yyyy-MM-dd").parse(source));
            }
        };
    }
}
