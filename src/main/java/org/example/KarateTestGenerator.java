package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class KarateTestGenerator {

    private static final String REPO_URL = "https://github.com/saivijaykumar/BankManagement.git";
    private static final String LOCAL_REPO = "temp-repo";
    private static final String BRANCH_NAME = "karate-tests-poc";
    private static final String FEATURE_PATH = "src/test/resources/karate/";


    private static final String SYSTEM_PROMPT = """
        You are an expert software engineer with deep knowledge of Java Spring Boot, REST APIs, and Karate DSL.
        Generate clean, runnable Karate test scripts for the REST endpoints defined in the given controller class.
        """;

    public static void main(String[] args) throws Exception {
        cloneRepo();
        List<Path> controllers = findControllerFiles();
        for (Path controller : controllers) {
            String javaCode = Files.readString(controller);
            String featureCode = generateKarateTest(javaCode);
            writeFeatureFile(controller.getFileName().toString().replace(".java", ".feature"), featureCode);
        }
        commitAndPush();
    }

    private static void cloneRepo() throws GitAPIException, IOException {
        Path repoPath = Paths.get(LOCAL_REPO);
        if (Files.exists(repoPath)) {
            deleteDirectory(repoPath);
        }
        Git.cloneRepository().setURI(REPO_URL).setDirectory(repoPath.toFile()).call();
    }

    private static List<Path> findControllerFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(Paths.get(LOCAL_REPO))) {
            return walk
                    .filter(p -> p.toString().endsWith("Controller.java"))
                    .collect(Collectors.toList());
        }
    }

    private static String generateKarateTest(String controllerCode) throws IOException, InterruptedException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode systemMessage = mapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.put("content", SYSTEM_PROMPT);

        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", "Here is a controller:\n\n" + controllerCode);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", "gpt-4");
        body.set("messages", mapper.valueToTree(List.of(systemMessage, userMessage)));
        body.put("temperature", 0.3);
        String OPENAI_API_KEY = System.getenv("OPENAI_API_KEY");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + OPENAI_API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        ObjectNode json = (ObjectNode) mapper.readTree(response.body());
        return json.get("choices").get(0).get("message").get("content").asText();
    }

    private static void writeFeatureFile(String fileName, String content) throws IOException {
        Path featureDir = Paths.get(LOCAL_REPO, FEATURE_PATH);
        Files.createDirectories(featureDir);
        Path featureFile = featureDir.resolve(fileName);
        Files.writeString(featureFile, content, StandardCharsets.UTF_8);
        System.out.println("Generated test: " + featureFile);
    }

    private static void commitAndPush() throws Exception {
        Git git = Git.open(new File(LOCAL_REPO));
        git.checkout().setCreateBranch(true).setName(BRANCH_NAME).call();
        git.add().addFilepattern(".").call();
        git.commit().setMessage("Add generated Karate tests").call();

        // Use GitHub PAT here
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null) {
            throw new RuntimeException("GITHUB_TOKEN environment variable not set");
        }

        git.push()
                .setRemote("origin")
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider("your-github-username", githubToken))
                .add(BRANCH_NAME)
                .call();

        System.out.println("✅ Pushed branch: " + BRANCH_NAME);
    }

    private static void deleteDirectory(Path path) throws IOException {
        Files.walk(path)
                .sorted((a, b) -> b.compareTo(a)) // delete children before parents
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}

