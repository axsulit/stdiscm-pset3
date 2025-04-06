package com.shared.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public class ConfigLoader {
    private static final Properties properties = new Properties();
    private static final Set<String> loadedProperties = new HashSet<>();

    static {
        try (InputStream input = ConfigLoader.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                System.out.println("Could not find config.properties");
            } else {
                // Read the file line by line to detect duplicates and load properties
                Set<String> duplicateKeys = new HashSet<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue; // Skip empty lines and comments
                        }
                        
                        int equalsIndex = line.indexOf('=');
                        if (equalsIndex > 0) {
                            String key = line.substring(0, equalsIndex).trim();
                            String value = line.substring(equalsIndex + 1).trim();
                            
                            // Check for duplicates
                            if (loadedProperties.contains(key)) {
                                duplicateKeys.add(key);
                            }
                            loadedProperties.add(key);
                            
                            // Store the property
                            properties.setProperty(key, value);
                        }
                    }
                }
                
                if (!duplicateKeys.isEmpty()) {
                    throw new IllegalArgumentException("Duplicate properties found in config.properties: " + 
                        String.join(", ", duplicateKeys));
                }
                
                validateEnvironment();
            }
        } catch (IOException ex) {
            System.out.println("❌ Error loading config: " + ex.getMessage());
        }
    }

    public static String get(String key) {
        return properties.getProperty(key);
    }

    public static int getInt(String key, int defaultVal) {
        try {
            return Integer.parseInt(properties.getProperty(key));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static void validateEnvironment() {
        // Check if required properties exist
        if (!loadedProperties.contains("environment")) {
            throw new IllegalArgumentException("environment is missing from config.properties");
        }

        String environment = properties.getProperty("environment");
        if (environment == null || (!environment.equals("local") && !environment.equals("docker"))) {
            throw new IllegalArgumentException("Invalid environment: " + environment + ". Must be either 'local' or 'docker'");
        }
    }

    // validate producer properties
    public static void validateProducerProperties() {
        // Check if required properties exist
        if (!loadedProperties.contains("producer.threads")) {
            throw new IllegalArgumentException("producer.threads is missing from config.properties");
        }
        if (!loadedProperties.contains("producer.rootvideopath")) {
            throw new IllegalArgumentException("producer.rootvideopath is missing from config.properties");
        }
        
        int threads = getInt("producer.threads", -1);
        if (threads <= 0) {
            throw new IllegalArgumentException("producer.threads must be set to a positive number");
        }
        
        int maxThreads = Runtime.getRuntime().availableProcessors() * 3;
        if (threads > maxThreads) {
            throw new IllegalArgumentException("producer.threads must not exceed " + maxThreads + " (available CPU cores * 3)");
        }
        
        // videopath
        String basePath = get("producer.rootvideopath");
        if (basePath == null || basePath.trim().isEmpty()) {
            throw new IllegalArgumentException("producer.rootvideopath must be set in config.properties");
        }

        File baseDir = new File(basePath);
        if (!baseDir.isAbsolute()) {
            throw new IllegalArgumentException("producer.rootvideopath must be an absolute path");
        }

        if (!baseDir.exists()) {
            throw new IllegalArgumentException("Video directory does not exist: " + baseDir.getAbsolutePath());
        }

        if (!baseDir.isDirectory()) {
            throw new IllegalArgumentException("Specified path is not a directory: " + baseDir.getAbsolutePath());
        }
    }

    // validate consumer properties
    public static void validateConsumerProperties() {
        // Check if required properties exist
        if (!loadedProperties.contains("consumer.threads")) {
            throw new IllegalArgumentException("consumer.threads is missing from config.properties");
        }
        if (!loadedProperties.contains("queue.length")) {
            throw new IllegalArgumentException("queue.length is missing from config.properties");
        }

        int threads = getInt("consumer.threads", -1);
        if (threads <= 0) {
            throw new IllegalArgumentException("consumer.threads must be set to a positive number");
        }
        
        int maxThreads = Runtime.getRuntime().availableProcessors() + 2;
        if (threads > maxThreads) {
            throw new IllegalArgumentException("consumer.threads must not exceed " + maxThreads + " (available CPU cores + 2)");
        }

        int queueLength = getInt("queue.length", -1);
        if (queueLength <= 0) {
            throw new IllegalArgumentException("queue.length must be set to a positive number");
        }

        if (queueLength > maxThreads) {
            throw new IllegalArgumentException("queue.length must not exceed " + maxThreads + " (available CPU cores)");
        }
    }
}
