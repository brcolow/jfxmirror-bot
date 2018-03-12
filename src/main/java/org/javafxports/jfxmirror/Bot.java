package org.javafxports.jfxmirror;

import java.io.IOException;
import java.io.InputStream;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.aragost.javahg.Repository;
import com.aragost.javahg.RepositoryConfiguration;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

/**
 * jfxmirror_bot helps contributors to the OpenJFX GitHub repository get their pull requests
 * accepted into OpenJFX upstream.
 * <p>
 * jfxmirror_bot runs a HTTP server (via Jersey with Grizzly 2) which is configured to accept
 * GitHub webhook events from the "javafxports/openjdk-jfx" repository.
 */
public class Bot {
    protected static Client httpClient;
    protected static Repository upstreamRepo;
    protected static final URI BASE_URI = URI.create("http://localhost:8433/");
    private static HttpServer httpServer;
    private static final String JCHECK_URL = "http://cr.openjdk.java.net/~kcr/jcheck/bin/jcheck.py";
    private static final String JCHECK_CONF_URL = "http://cr.openjdk.java.net/%7Ekcr/jcheck/conf";
    private static final String WEBREV_URL = "http://hg.openjdk.java.net/code-tools/webrev/raw-file/tip/webrev.ksh";
    private static final String UPSTREAM_REPO_URL = "http://hg.openjdk.java.net/openjfx/jfx-dev/rt";
    private static final Path UPSTREAM_REPO_PATH = Paths.get(System.getProperty("user.home"), "jfxmirror", "upstream");
    private static final Logger logger = LoggerFactory.getLogger(Bot.class);

    public static void main(String[] args) {

        if (System.getenv("jfxmirror_gh_token") == null) {
            logger.error("\u2718 \"JFXMIRROR_GH_TOKEN\" environment variable not set.");
            logger.debug("This must be set to your personal access token created for jfxmirror_bot.");
            logger.debug("You can create an access token by going to: https://github.com/settings/tokens");
            System.exit(1);
        }

        httpClient = ClientBuilder.newClient();

        // It would be nice to use https://developer.github.com/v3/oauth_authorizations/#check-an-authorization
        // for checking the validity of the githubAccessToken, but that requires registering an OAuth App (and that
        // is more complicated than just using a personal access token). So the user will only be notified that their
        // github access token is invalid when a PR event comes in.

        logger.debug("Checking for \"jcheck.py\"...");
        Path jcheckPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "jcheck.py");
        if (!Files.exists(jcheckPath)) {
            logger.debug("Downloading \"jcheck.py\"...");
            try (InputStream in = URI.create(JCHECK_URL).toURL().openStream()) {
                Files.copy(in, jcheckPath);
                logger.info("\u2713 Downloaded \"jcheck.py\" to: " + jcheckPath);
            } catch (IOException e) {
                logger.error("\u2718 Could not download \"jcheck.py\".");
                logger.debug("exception: ", e);
                System.exit(1);
            }
        } else {
            logger.info("\u2713 Found \"jcheck.py\".");
        }

        RepositoryConfiguration repoConf = new RepositoryConfiguration();
        repoConf.addExtension(JCheckExtension.class);
        if (!Files.exists(UPSTREAM_REPO_PATH)) {
            // Probably the first time running, clone the upstream OpenJFX repository.
            logger.debug("Upstream mercurial repository directory not found.");
            try {
                Files.createDirectories(UPSTREAM_REPO_PATH);
                logger.info("\u2713 Created: " + UPSTREAM_REPO_PATH);
            } catch (IOException e) {
                logger.error("\u2718 Could not create mercurial repository directory: " + UPSTREAM_REPO_PATH);
                logger.debug("exception: ", e);
                System.exit(1);
            }

            logger.debug("Cloning upstream OpenJFX mercurial repository...");
            logger.debug("This may take a while as the OpenJFX repository is large.");
            upstreamRepo = Repository.clone(repoConf, UPSTREAM_REPO_PATH.toFile(), UPSTREAM_REPO_URL);
        } else {
            // Repository already exists.
            upstreamRepo = Repository.open(repoConf, UPSTREAM_REPO_PATH.toFile());
        }

        logger.debug("Initialized OpenJFX upstream repository: " + upstreamRepo.getDirectory());
        logger.debug("Using mercurial version: " + upstreamRepo.getHgVersion());

        Path jcheckDir = Bot.upstreamRepo.getDirectory().toPath().resolve(".jcheck");
        if (!Files.isDirectory(Bot.upstreamRepo.getDirectory().toPath().resolve(".jcheck"))) {
            try {
                Files.createDirectory(jcheckDir);
            } catch (IOException e) {
                logger.error("\u2718 Could not create \".jcheck\" directory at root of upstream hg repository.");
                logger.debug("exception: ", e);
                System.exit(1);
            }
        }

        Path jcheckConfPath = Bot.upstreamRepo.getDirectory().toPath().resolve(".jcheck").resolve("conf");
        if (!Files.exists(jcheckConfPath)) {
            logger.debug("Downloading jcheck config file...");
            try (InputStream in = URI.create(JCHECK_CONF_URL).toURL().openStream()) {
                Files.copy(in, jcheckConfPath);
                logger.info("\u2713 Downloaded jcheck config file to: " + jcheckConfPath);
            } catch (IOException e) {
                logger.error("\u2718 Could not download jcheck config file.");
                logger.debug("exception: ", e);
                System.exit(1);
            }
        } else {
            logger.info("\u2713 Found jcheck config file: " + jcheckConfPath);
        }

        logger.debug("Checking for \"webrev.ksh\"...");
        Path webrevPath = Paths.get(System.getProperty("user.home"), "jfxmirror", "webrev");
        if (!Files.exists(webrevPath)) {
            try {
                Files.createDirectories(webrevPath);
            } catch (IOException e) {
                logger.error("\u2718 Could not create directory for \"webrev.ksh\".");
                logger.debug("exception: ", e);
                System.exit(1);
            }
        }

        if (!Files.exists(webrevPath.resolve("webrev.ksh"))) {
            logger.debug("Downloading \"webrev.ksh\"...");
            try (InputStream in = URI.create(WEBREV_URL).toURL().openStream()) {
                Files.copy(in, webrevPath.resolve("webrev.ksh"));
                logger.info("\u2713 Downloaded \"webrev.ksh\" to: " + webrevPath.resolve("webrev.ksh"));
            } catch (IOException e) {
                logger.error("\u2718 Could not download \"webrev.ksh\".");
                logger.debug("exception: ", e);
                System.exit(1);
            }
        } else {
            logger.info("\u2713 Found \"webrev.ksh\".");
        }

        logger.debug("Checking for OCA signature file...");
        java.nio.file.Path ocaFile = Paths.get(System.getProperty("user.home"), "jfxmirror", "oca.txt");
        if (!Files.exists(ocaFile)) {
            logger.debug("Creating OCA signature file: " + ocaFile);
            try {
                Files.createFile(ocaFile);
            } catch (IOException e) {
                logger.error("\u2718 Could not create OCA signature file: " + ocaFile);
                logger.debug("exception: ", e);
                System.exit(1);
            }
        } else {
            logger.info("\u2713 Found OCA signature file: \"" + ocaFile + "\"");
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
        httpServer = GrizzlyHttpServerFactory.createHttpServer(BASE_URI, resourceConfig);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cleanup();
            // Print the ANSI reset escape code so that, after terminating, the user's prompt is not tampered with.
            System.out.println("\u001B[0m");
        }, "shutdownHook"));

        try {
            httpServer.start();
            logger.debug("HTTP server started, press Ctrl+C to shut down.");
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace();
            cleanup();
            System.exit(1);
        }
    }

    protected static void cleanup() {
        upstreamRepo.close();
        if (httpServer.isStarted()) {
            logger.debug("Stopping HTTP server...");
            httpServer.shutdownNow();
        }
    }
}
