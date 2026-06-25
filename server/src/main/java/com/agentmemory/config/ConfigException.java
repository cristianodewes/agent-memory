package com.agentmemory.config;

/**
 * Thrown when configuration is invalid and the server must not start (fail-fast).
 *
 * <p>The message is intended to be read by an operator at the top of a crash log: it should name
 * the offending setting and how to fix it (which config key / environment variable to change).
 * Raised during bean creation so Spring aborts startup with this as the root cause.
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
