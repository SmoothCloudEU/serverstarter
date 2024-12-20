package eu.smoothcloud.serverstarter;

import eu.smoothcloud.serverstarter.utils.Server;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;

public class ServerTask {

    private final ConcurrentHashMap<UUID, Server> serverMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Future<?>> serverIdFutureMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Process> processMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, StringBuilder> processLogs = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    /**
     * Starts a server process.
     *
     * @param uniqueId The unique ID of the server.
     * @param server   The server instance.
     */
    public void start(UUID uniqueId, Server server) {
        if (this.serverMap.containsKey(uniqueId) || this.processMap.containsKey(uniqueId)) {
            return;
        }

        String command = buildCommand(server);
        ProcessBuilder processBuilder = new ProcessBuilder(command.split(" "));
        this.processLogs.put(uniqueId, new StringBuilder());

        Future<?> future = this.executorService.submit(() -> {
            try {
                Process process = processBuilder.start();
                this.serverMap.put(uniqueId, server);
                this.processMap.put(uniqueId, process);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    StringBuilder logBuilder = this.processLogs.get(uniqueId);

                    while ((line = reader.readLine()) != null) {
                        if (logBuilder == null) {
                            return;
                        }
                        synchronized (logBuilder) {
                            logBuilder.append("[").append(server.getName()).append("]")
                                    .append(line).append(System.lineSeparator());
                        }
                    }
                }

                process.waitFor();
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                this.cleanupResources(uniqueId);
                System.out.println("[ServerTask] " + uniqueId + " cleaned");
            }
        });

        this.serverIdFutureMap.put(uniqueId, future);
    }

    /**
     * Stops a server process.
     *
     * @param uniqueId The unique ID of the server.
     */
    public void stop(UUID uniqueId) {
        Future<?> future = this.serverIdFutureMap.get(uniqueId);
        Process process = this.processMap.get(uniqueId);

        if (future != null) {
            future.cancel(true);
        }

        if (process == null || !process.isAlive()) {
            System.out.println("No active process found for UUID: " + uniqueId);
            return;
        }

        synchronized (process) {
            OutputStream outputStream = process.getOutputStream();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream))) {
                String stopCommand = this.serverMap.get(uniqueId).isProxy() ? "end\n" : "stop\n";
                writer.write(stopCommand + "\n");
                writer.flush();

                if (process.waitFor(10, TimeUnit.SECONDS)) {
                    System.out.println("Process terminated gracefully.");
                } else {
                    System.out.println("Process did not terminate within timeout. Forcibly terminating...");
                    process.destroyForcibly();
                }
            } catch (IOException e) {
                System.err.println("Failed to send stop command: " + e.getMessage());
            } catch (InterruptedException e) {
                System.err.println("Stop operation interrupted. Attempting to terminate process...");
                Thread.currentThread().interrupt();
            } finally {
                this.cleanupResources(uniqueId);
                System.out.println("[ServerTask] " + uniqueId + " cleaned");
            }
        }
    }


    /**
     * Displays logs of a server process.
     *
     * @param uniqueId The unique ID of the server.
     */
    public void showLogs(UUID uniqueId) {
        StringBuilder logs = this.processLogs.get(uniqueId);
        if (logs == null) {
            System.out.println("No logs available for server: " + uniqueId);
            return;
        }

        System.out.println("Logs for server " + uniqueId + ":\n" + logs);
    }

    /**
     * Executes a command on the running server process.
     *
     * @param uniqueId The unique ID of the server.
     * @param command  The command to execute.
     */
    public void execute(UUID uniqueId, String command) {
        if (this.processMap.get(uniqueId) == null || !this.processMap.get(uniqueId).isAlive()) {
            System.err.println("Skipping cleanup for non-alive process: " + uniqueId);
            return;
        }

        Process process = this.processMap.get(uniqueId);
        if (process == null || !process.isAlive()) {
            System.out.println("Process is no longer alive for UUID: " + uniqueId);
            return;
        }

        synchronized (process) {
            OutputStream outputStream = process.getOutputStream();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream))) {
                writer.write(command + "\n");
                writer.flush();
            } catch (IOException e) {
                System.err.println("Error writing to process stream: " + e.getMessage());
                throw new RuntimeException("Failed to execute command for UUID: " + uniqueId, e);
            }
        }
    }


    /**
     * Builds the command string for the server process.
     *
     * @param server The server instance.
     * @return The complete command string.
     */
    private String buildCommand(Server server) {
        System.out.println("[ServerTask] " + server.getName() + " building command");
        return (server.getJavaPath().isEmpty() ? "java" : server.getJavaPath()) +
                " -Xms" + server.getMinimumMemory() + "M" +
                " -Xmx" + server.getMaximumMemory() + "M" +
                " -XX:+UseG1GC" +
                (server.isProxy() ? "" : " -Dcom.mojang.eula.agree=true -DIReallyKnowWhatIAmDoingISwear") +
                " -jar " + server.getServerSoftware() +
                " --port " + server.getPort() +
                (server.isProxy() ? "" : " nogui");
    }

    /**
     * Cleans up resources associated with a server.
     *
     * @param uniqueId The unique ID of the server.
     */
    private void cleanupResources(UUID uniqueId) {
        Process process = this.processMap.get(uniqueId);
        if (process != null && process.isAlive()) {
            System.err.println("Attempting to clean up resources for a live process: " + uniqueId);
            return;
        }
        this.serverMap.remove(uniqueId);
        this.processMap.remove(uniqueId);
        this.serverIdFutureMap.remove(uniqueId);
        this.processLogs.remove(uniqueId);
    }

}
