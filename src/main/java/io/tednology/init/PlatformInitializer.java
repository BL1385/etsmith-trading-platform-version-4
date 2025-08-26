package io.tednology.init;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @author Edward Smith
 */
@Component
@EnableConfigurationProperties(SystemProperties.class)
class PlatformInitializer implements CommandLineRunner {

    private final JForexPlatform connector;
    private final SystemProperties systemProperties;

    public PlatformInitializer(JForexPlatform connector, SystemProperties systemProperties) {
        this.connector = connector;
        this.systemProperties = systemProperties;
    }

    @Override
    public void run(String... args) throws Exception {
        if (systemProperties.isConnectOnStartup()) {
            connector.connect();
        }
    }
}
