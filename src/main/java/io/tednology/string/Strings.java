package io.tednology.string;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/**
 * @author Edward Smith
 */
@Slf4j
public class Strings {

    private Strings() {}

    public static String toString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PrintStream ps = new PrintStream(baos);
            t.printStackTrace(ps);
            try {
                sb.append(baos.toString("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                log.error("No UTF-8 support?", e);
            }
        } catch (IOException e) {
            log.error("Sure.", e);
        }
        return sb.toString();
    }

}
