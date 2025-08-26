package io.tednology;

import ca.tednology.mail.DestinationOverride;
import ca.tednology.mail.DevSafeJavaMailSender;
import ca.tednology.mail.SLF4JavaMailSender;
import ca.tednology.mail.SimpleDestinationOverride;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration @Slf4j
public class MailConfiguration {

    @Configuration
    @Profile({ "test" })
    static class ConsoleMailConfig {
        @Bean
        public JavaMailSender consoleJavaMailSender() {
            log.info("Configuring SLF4J logging JavaMailSender; no email will be sent.");
            JavaMailSenderImpl javaMailSender = new SLF4JavaMailSender("info");
            javaMailSender.setDefaultEncoding("UTF-8");
            return javaMailSender;
        }
    }

    @Configuration
    @Profile({"local", "staging"})
    @EnableConfigurationProperties(MailProperties.class)
    static class MailConfig {

        private final MailProperties properties;

        public MailConfig(MailProperties properties) {
            this.properties = properties;
        }

        @Bean
        public JavaMailSender nonProductionJavaMailSender() {
            log.info("Configuring Development-Safe JavaMailSender; no email will be sent to intended destinations, only to specified destinations.");

            DestinationOverride destinationOverride = new SimpleDestinationOverride("brandon.lusignan@gmail.com");
            JavaMailSenderImpl sender = new DevSafeJavaMailSender(destinationOverride, "info");

            log.info("Using provided Spring JavaMail Properties {}", properties);
            sender.setHost(this.properties.getHost());
            if (this.properties.getPort() != null) {
                sender.setPort(this.properties.getPort());
            }
            sender.setUsername(this.properties.getUsername());
            sender.setPassword(this.properties.getPassword());
            sender.setDefaultEncoding(this.properties.getDefaultEncoding().displayName());
            if (!this.properties.getProperties().isEmpty()) {
                Properties properties = new Properties();
                properties.putAll(this.properties.getProperties());
                sender.setJavaMailProperties(properties);
            }
            return sender;
        }
    }
}
