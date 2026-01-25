package com.photostat;

/**
 * Launcher class for creating executable JAR.
 *
 * JavaFX requires this workaround because the module system prevents
 * launching an Application class directly from a shaded JAR.
 * This class doesn't extend Application, so it bypasses that restriction.
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
