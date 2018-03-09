package org.javafxports.jfxmirror;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.logging.LoggingFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.aragost.javahg.Repository;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

public class Bot {

    protected static Client httpClient;
    protected static Repository upstreamRepo;
    private static HttpServer httpServer;
    private static final String upstreamRepoUrl = "http://hg.openjdk.java.net/openjfx/jfx-dev/rt";
    private static final Path upstreamRepoPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "upstream");

    public static void main(String[] args) {
        if (System.getenv("jfxmirror_gh_token") == null) {
            System.err.println("Environment variable \"jfxmirror_gh_token\" not set.");
            System.err.println("This must be set to your personal access token created for jfxmirror_bot.");
            System.err.println("You can create an access token by going to: https://github.com/settings/tokens");
            System.exit(1);
        }

        httpClient = ClientBuilder.newClient();

        // It would be nice to use https://developer.github.com/v3/oauth_authorizations/#check-an-authorization
        // for checking the validity of the githubAccessToken, but that requires registering an OAuth App (and that
        // is more complicated than just using a personal access token). So the user will only be notified that their
        // github access token is invalid when a PR event comes in.

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

        System.out.println("Checking for \"webrev.ksh\"...");
        Path webrevPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "webrev");
        if (!Files.exists(webrevPath)) {
            try {
                Files.createDirectories(webrevPath);
            } catch (IOException e) {
                System.err.println("Could not create directory for \"webrev.ksh\":");
                e.printStackTrace();
                System.exit(1);
            }

            System.out.println("Downloading \"webrev.ksh\"...");
            try (InputStream in = URI.create("hg.openjdk.java.net/code-tools/webrev/raw-file/tip/webrev.ksh").toURL().openStream()) {
                Files.copy(in, webrevPath.resolve("webrev.ksh"));
            } catch (IOException e) {
                System.err.println("Could not download \"webrev.ksh\":");
                e.printStackTrace();
                System.exit(1);
            }
        } else {
            System.out.println("Found \"webrev.ksh\"");
        }

        // Jersey uses java.util.logging - bridge to slf4
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        ResourceConfig resourceConfig = new ResourceConfig()
                .packages("org.javafxports.jfxmirror")
                .property(ServerProperties.WADL_FEATURE_DISABLE, true)
                .property(ServerProperties.TRACING, "ALL")
                .property(ServerProperties.TRACING_THRESHOLD, "VERBOSE")
                .register(LoggingFeature.class)
                .register(JacksonJaxbJsonProvider.class);
        httpServer = GrizzlyHttpServerFactory.createHttpServer(URI.create("http://localhost:8433/"), resourceConfig);

        Runtime.getRuntime().addShutdownHook(new Thread(Bot::cleanup, "shutdownHook"));

        try {
            httpServer.start();
            System.out.println("HTTP server started, press Ctrl+C to shut down.");
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace();
            cleanup();
            System.exit(1);
        }
    }

    protected static void cleanup() {
        upstreamRepo.close();
        System.out.println("Stopping HTTP server..");
        httpServer.shutdownNow();
    }
}
