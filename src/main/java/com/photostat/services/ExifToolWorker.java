package com.photostat.services;

import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * Persistent background worker for ExifTool.
 * Allows executing commands without the overhead of creating a new OS process every time.
 */
public class ExifToolWorker implements AutoCloseable {
    private static ExifToolWorker instance;
    private final Process process;
    private final PrintWriter stdin;
    private final BufferedReader stdout;
    
    public static synchronized ExifToolWorker getInstance() {
        if (instance == null) {
            try {
                String exifToolPath = ConfigService.getInstance().getExifToolPath();
                instance = new ExifToolWorker(exifToolPath);
                
                // Add shutdown hook
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (instance != null) {
                        instance.close();
                    }
                }));
            } catch (IOException e) {
                System.err.println("Failed to start ExifToolWorker: " + e.getMessage());
            }
        }
        return instance;
    }
    
    private ExifToolWorker(String exifToolPath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(exifToolPath, "-stay_open", "True", "-@", "-");
        pb.redirectErrorStream(true);
        this.process = pb.start();
        this.stdin = new PrintWriter(new OutputStreamWriter(process.getOutputStream()), true);
        this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
    }

    /**
     * Executes arguments on the persistent ExifTool process.
     * @param args The arguments to pass to exiftool (e.g. "-j", "-g", "file.jpg")
     * @return The standard output of the command.
     */
    public synchronized String execute(String... args) throws IOException {
        for (String arg : args) {
            stdin.println(arg);
        }
        stdin.println("-execute");
        
        StringBuilder output = new StringBuilder();
        String line;
        
        // Read until the "{ready}" marker is printed
        while ((line = stdout.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.equals("{ready}")) {
                break;
            }
            // Exiftool can sometimes output ready sequence combined with other output if we don't trim
            // or if it ends exactly with {ready}
            if (line.endsWith("{ready}")) {
                output.append(line.substring(0, line.length() - "{ready}".length()));
                break;
            }
            output.append(line).append("\n");
        }
        return output.toString();
    }

    @Override
    public synchronized void close() {
        try {
            stdin.println("-stay_open");
            stdin.println("False");
        } catch (Exception ignored) {}
        
        stdin.close();
        
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
