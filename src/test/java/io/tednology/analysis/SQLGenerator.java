package io.tednology.analysis;

import io.tednology.MailConfiguration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * @author Edward Smith
 */
@RunWith(SpringRunner.class)
@Import(MailConfiguration.class)
@DataJpaTest
public class SQLGenerator {

    @Test
    public void zooga() {
        System.out.println("Success!");
    }
}