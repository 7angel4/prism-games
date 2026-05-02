package learning;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class Logger {
    protected PrintWriter logWriter;

    public Logger(String modelFilePath, int propertyIndex, String subdir) {
        try {
            String root = Paths.get("").toAbsolutePath().toString() + "/prism-examples/csgs/learning/";
            Path rootDir = Paths.get(root);
            Path modelPath = Paths.get(modelFilePath);
            // Extract filename and change extension
            String logFileName = modelPath.getFileName().toString().replaceFirst("\\.[^.]+$", "") + propertyIndex; // remove extension
            logFileName += ".csv";

            // Build all logs directory path
            Path logsDir = rootDir.resolve("logs");
            // Ensure logs directory exists
            Files.createDirectories(logsDir);
            // Add subdir for each type of experiment
            Path experimentDir = logsDir.resolve(subdir);
            Files.createDirectories(experimentDir);

            // log file path
            Path logFilePath = experimentDir.resolve(logFileName);
            System.out.println("Logging to: " + logFilePath);

            logWriter = new PrintWriter(new FileWriter(logFilePath.toFile()));
            logWriter.println("episode,deltaT,maxRadius,avgRadius,numUnknownSlots,propUnknown,numSamples,totalNumSamples");

        } catch (IOException e) {
            throw new RuntimeException("Failed to open log file", e);
        }
    }

    protected void logEpisode(int episode, double deltaT, int numSamples, int totalNumSamples, double maxRadius, double avgRadius, int numUnknownSlots, double coverage) {
        logWriter.println(
                episode + "," +
                        deltaT + "," +
                        maxRadius + "," +
                        avgRadius + "," +
                        numUnknownSlots + "," +
                        coverage + "," +
                        numSamples + "," +
                        totalNumSamples
        );
        logWriter.flush();
    }

    protected void close() {
        if (logWriter != null) logWriter.close();
    }
}
