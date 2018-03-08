package org.javafxports.jfxmirror;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;

import com.aragost.javahg.Repository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

public class Bot {

    protected static Client httpClient;
    protected static Repository upstreamRepo;
    private static final String githubApi = "https://api.github.com";
    private static final String githubAccept = "application/vnd.github.v3+json";
    private static final String upstreamRepoUrl = "http://hg.openjdk.java.net/openjfx/jfx-dev/rt";
    private static final Path upstreamRepoPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "upstream");

    public static void main(String[] args) {
        String githubAccessToken = System.getenv("jfxmirror_gh_token");
        if (githubAccessToken == null) {
            System.err.println("Environment variable \"jfxmirror_gh_token\" not set.");
            System.err.println("This must be set to your personal access token created for jfxmirror_bot.");
            System.err.println("You can create an access token by going to: https://github.com/settings/tokens");
            System.exit(1);
        }

        httpClient = ClientBuilder.newClient();

        // Move this to PullRequestService. It would be nice to use https://developer.github.com/v3/oauth_authorizations/#check-an-authorization
        // for checking the validity of the githubAccessToken, but that requires registering an OAuth App (and that
        // is more complicated than just using a personal access token). So, once this is moved to PullRequestService,
        // if the token is invalid the user won't know until a PR event comes in.
        ObjectNode statusObject = JsonNodeFactory.instance.objectNode();
        statusObject.put("state", "pending"); // error, failure, pending, or success
        statusObject.put("target_url", "http://jfxmirror_bot.com/123");
        statusObject.put("description", "Checking for upstream mergeability...");
        statusObject.put("context", "jfxmirror_bot");

        Response statusResponse = httpClient.target(String.format(
                "%s/repos/%s/%s/statuses/%s", githubApi, "brcolow", "openjfx", "7e3bd03605db8298cfa7ce10835b40b8bef90466"))
                .request()
                .header("Authorization", "token " + githubAccessToken)
                .accept(githubAccept)
                .post(Entity.json(statusObject.toString()));

        if (statusResponse.getStatus() == 404) {
            System.err.println("GitHub API authentication failed, are you sure the \"jfxmirror_gh_token\"\n" +
                    "environment variable is set correctly?");
            System.exit(1);
        }

        if (!Files.exists(upstreamRepoPath)) {
            // Probably the first time running, clone the upstream OpenJFX repository.
            System.out.println("Upstream mercurial repository directory not found, creating it...");
            System.out.println("Creating " + upstreamRepoPath);
            try {
                Files.createDirectories(upstreamRepoPath);
            } catch (IOException e) {
                System.err.println("Could not create mercurial repository directory: " + upstreamRepoPath);
                e.printStackTrace();
                System.exit(1);
            }

            System.out.println("Cloning upstream OpenJFX mercurial repository...");
            upstreamRepo = Repository.clone(upstreamRepoPath.toFile(), upstreamRepoUrl);
        } else {
            // Repository already exists.
            upstreamRepo = Repository.open(upstreamRepoPath.toFile());
        }

        System.out.println("Initialized OpenJFX upstream repository: " + upstreamRepo.getDirectory());
        System.out.println("Using mercurial version: " + upstreamRepo.getHgVersion());

        ResourceConfig resourceConfig = new ResourceConfig()
                .packages("org.javafxports.jfxmirror")
                .property(ServerProperties.WADL_FEATURE_DISABLE, true)
                .register(JacksonJaxbJsonProvider.class);
        HttpServer httpServer = GrizzlyHttpServerFactory.createHttpServer(getBaseURI(), resourceConfig);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> cleanup(httpServer), "shutdownHook"));

        try {
            httpServer.start();
            System.out.println("HTTP server started, press Ctrl+C to shut down.");
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace();
            cleanup(httpServer);
            System.exit(1);
        }
    }

    private static void cleanup(HttpServer httpServer) {
        upstreamRepo.close();
        System.out.println("Stopping HTTP server..");
        httpServer.shutdownNow();
    }

    private static URI getBaseURI() {
        return UriBuilder.fromUri("http://localhost/").port(8433).build();
    }
}
