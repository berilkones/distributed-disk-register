package com.example.family;

import family.Empty;
import family.FamilyServiceGrpc;
import family.FamilyView;
import family.NodeInfo;
import family.ChatMessage;
import family.GetRequest;
import family.GetResponse;
import io.grpc.stub.StreamObserver;

import java.io.*;

public class FamilyServiceImpl extends FamilyServiceGrpc.FamilyServiceImplBase {
    private final NodeRegistry registry;
    private final NodeInfo self;

    public FamilyServiceImpl(NodeRegistry registry, NodeInfo self) {
        this.registry = registry;
        this.self = self;
        this.registry.add(self);
    }

    @Override
    public void join(NodeInfo request, StreamObserver<FamilyView> responseObserver) {
        registry.add(request);
        FamilyView view = FamilyView.newBuilder()
                .addAllMembers(registry.snapshot())
                .build();
        responseObserver.onNext(view);
        responseObserver.onCompleted();
    }

    @Override
    public void getFamily(Empty request, StreamObserver<FamilyView> responseObserver) {
        FamilyView view = FamilyView.newBuilder()
                .addAllMembers(registry.snapshot())
                .build();
        responseObserver.onNext(view);
        responseObserver.onCompleted();
    }

    @Override
    public void receiveChat(ChatMessage request, StreamObserver<Empty> responseObserver) {
        System.out.println("📥 Mesaj alındı: ID=" + request.getId() + " | " + request.getText());
        saveToDisk(request.getId(), request.getText());
        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }

    // YENİ: GET için RPC
    @Override
    public void getMessage(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        String content = searchDiskForMessage(String.valueOf(request.getId()));
        GetResponse response = GetResponse.newBuilder()
                .setFound(content != null)
                .setContent(content != null ? content : "")
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private void saveToDisk(long id, String content) {
        String fileName = "data_" + self.getPort() + ".txt";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true))) {
            writer.write(id + ":" + content);
            writer.newLine();
            System.out.println("💾 Veri kaydedildi: " + fileName);
        } catch (IOException e) {
            System.err.println("❌ Dosya yazma hatası: " + e.getMessage());
        }
    }

    public String searchDiskForMessage(String targetId) {
        String fileName = "data_" + self.getPort() + ".txt";
        File file = new File(fileName);
        if (!file.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(targetId + ":")) {
                    return line.split(":", 2)[1];
                }
            }
        } catch (IOException e) {
            System.err.println("Dosya okuma hatası: " + e.getMessage());
        }
        return null;
    }
}
