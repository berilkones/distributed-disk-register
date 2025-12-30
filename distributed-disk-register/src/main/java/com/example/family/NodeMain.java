package com.example.family;

import family.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

public class NodeMain {
    private static final int START_PORT = 5555;
    private static final int PRINT_INTERVAL_SECONDS = 10;

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = findFreePort(START_PORT);

        NodeInfo self = NodeInfo.newBuilder()
                .setHost(host)
                .setPort(port)
                .build();

        NodeRegistry registry = new NodeRegistry();
        FamilyServiceImpl service = new FamilyServiceImpl(registry, self);

        Server server = ServerBuilder.forPort(port)
                .addService(service)
                .build()
                .start();

        System.out.printf("Node started on %s:%d%n", host, port);

        if (port == START_PORT) {
            startLeaderTextListener(registry, self);
        }

        discoverExistingNodes(host, port, registry, self);
        startFamilyPrinter(registry, self);
        startHealthChecker(registry, self);

        server.awaitTermination();
    }

    private static void startLeaderTextListener(NodeRegistry registry, NodeInfo self) {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(6666)) {
                System.out.printf("Leader listening for text on TCP %s:%d%n", self.getHost(), 6666);
                while (true) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClientTextConnection(client, registry, self)).start();
                }
            } catch (IOException e) {
                System.err.println("Error in leader text listener: " + e.getMessage());
            }
        }, "LeaderTextListener").start();
    }

    private static void handleClientTextConnection(Socket client, NodeRegistry registry, NodeInfo self) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter writer = new PrintWriter(client.getOutputStream(), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(" ", 3);
                if (parts.length < 2) continue;

                String command = parts[0].toUpperCase();
                long messageId = Long.parseLong(parts[1]);

                if (command.equals("SET") && parts.length == 3) {
                    String content = parts[2];
                    System.out.println(" SET komutu: ID=" + messageId + " | " + content);

                    ChatMessage msg = ChatMessage.newBuilder()
                            .setText(content)
                            .setFromHost(self.getHost())
                            .setFromPort(self.getPort())
                            .setTimestamp(System.currentTimeMillis())
                            .setId(messageId)
                            .build();

                    broadcastWithTolerance(registry, self, msg);
                    writer.println("OK");
                } else if (command.equals("GET")) {
                    System.out.println(" GET sorgusu: ID=" + messageId);
                    String foundContent = null;

                    List<NodeInfo> members = registry.snapshot();
                    // Yük dengesi için ID'ye göre sıralı tarama
                    members.sort(Comparator.comparingInt(NodeInfo::getPort));

                    for (NodeInfo member : members) {
                        ManagedChannel channel = null;
                        try {
                            channel = ManagedChannelBuilder.forAddress(member.getHost(), member.getPort())
                                    .usePlaintext()
                                    .build();
                            FamilyServiceGrpc.FamilyServiceBlockingStub stub = FamilyServiceGrpc.newBlockingStub(channel);
                            GetResponse result = stub.getMessage(GetRequest.newBuilder().setId(messageId).build());
                            if (result.getFound()) {
                                foundContent = result.getContent();
                                break;
                            }
                        } catch (Exception e) {
                            System.err.println(" + member.getPort() + " unreachable.");
                        } finally {
                            if (channel != null) channel.shutdownNow();
                        }
                    }

                    String response = (foundContent != null) ? foundContent : "NOT_FOUND";
                    writer.println(response);
                }
            }
        } catch (IOException e) {
            System.err.println("Client connection error: " + e.getMessage());
        }
    }

    private static int findFreePort(int startPort) {
        int port = startPort;
        while (true) {
            try (ServerSocket ignored = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                port++;
            }
        }
    }

    private static void discoverExistingNodes(String host, int selfPort, NodeRegistry registry, NodeInfo self) {
        for (int port = START_PORT; port < selfPort; port++) {
            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder.forAddress(host, port)
                        .usePlaintext()
                        .build();
                FamilyServiceGrpc.FamilyServiceBlockingStub stub = FamilyServiceGrpc.newBlockingStub(channel);
                FamilyView view = stub.join(self);
                registry.addAll(view.getMembersList());
                System.out.printf("Joined through %s:%d, family size: %d%n", host, port, registry.snapshot().size());
            } catch (Exception ignored) {
            } finally {
                if (channel != null) channel.shutdownNow();
            }
        }
    }

    private static void startFamilyPrinter(NodeRegistry registry, NodeInfo self) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            List<NodeInfo> members = registry.snapshot();
            System.out.println("======================================");
            System.out.printf("Family at %s:%d (me)%n", self.getHost(), self.getPort());
            System.out.println("Time: " + LocalDateTime.now());
            System.out.println("Members (" + members.size() + "):");
            for (NodeInfo n : members) {
                boolean isMe = n.getPort() == self.getPort();
                System.out.printf(" - %s:%d%s%n", n.getHost(), n.getPort(), isMe ? " (me)" : "");
            }
            System.out.println("======================================");
        }, 3, PRINT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static void startHealthChecker(NodeRegistry registry, NodeInfo self) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            List<NodeInfo> members = new ArrayList<>(registry.snapshot());
            for (NodeInfo n : members) {
                if (n.getPort() == self.getPort()) continue;
                ManagedChannel channel = null;
                try {
                    channel = ManagedChannelBuilder.forAddress(n.getHost(), n.getPort())
                            .usePlaintext()
                            .build();
                    FamilyServiceGrpc.FamilyServiceBlockingStub stub = FamilyServiceGrpc.newBlockingStub(channel);
                    stub.getFamily(Empty.newBuilder().build());
                } catch (Exception e) {
                    System.out.printf("Node %s:%d unreachable, removing...%n", n.getHost(), n.getPort());
                    registry.remove(n);
                } finally {
                    if (channel != null) channel.shutdownNow();
                }
            }
        }, 5, 10, TimeUnit.SECONDS);
    }

    private static void broadcastWithTolerance(NodeRegistry registry, NodeInfo self, ChatMessage msg) {
        List<NodeInfo> members = new ArrayList<>(registry.snapshot());
        members.removeIf(n -> n.getPort() == self.getPort());

        int tolerance = 2;
        try {
            List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get("tolerance.conf"));
            if (!lines.isEmpty()) tolerance = Integer.parseInt(lines.get(0).trim());
        } catch (Exception e) {
            System.out.println("⚠️ tolerance.conf okunamadı, varsayılan 2");
        }

        // Yük dengeleme: ID'ye göre başlangıç indeksi
        members.sort(Comparator.comparingInt(NodeInfo::getPort));
        int startIndex = (int) (Math.abs(msg.getId() % members.size()));

        int sentCount = 0;
        for (int i = 0; i < members.size() && sentCount < tolerance; i++) {
            NodeInfo target = members.get((startIndex + i) % members.size());
            ManagedChannel channel = null;
            try {
                channel = ManagedChannelBuilder.forAddress(target.getHost(), target.getPort())
                        .usePlaintext()
                        .build();
                FamilyServiceGrpc.FamilyServiceBlockingStub stub = FamilyServiceGrpc.newBlockingStub(channel);
                stub.receiveChat(msg);
                System.out.printf("Mesaj ID=%d → %d portuna replike edildi.%n", msg.getId(), target.getPort());
                sentCount++;
            } catch (Exception e) {
                System.err.printf(" %d portuna replikasyon başarısız.%n", target.getPort());
            } finally {
                if (channel != null) channel.shutdownNow();
            }
        }
    }
}
