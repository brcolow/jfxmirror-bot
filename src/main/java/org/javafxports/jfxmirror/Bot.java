package org.javafxports.jfxmirror;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
    protected static org.eclipse.jgit.lib.Repository mirrorRepo;
    private static HttpServer httpServer;
    protected static final URI BASE_URI = URI.create("http://localhost:8433/");
    private static final String JCHECK_URL = "http://cr.openjdk.java.net/~kcr/jcheck/bin/jcheck.py";
    private static final String JCHECK_CONF_URL = "http://cr.openjdk.java.net/%7Ekcr/jcheck/conf";
    private static final String WEBREV_URL = "http://hg.openjdk.java.net/code-tools/webrev/raw-file/tip/webrev.ksh";
    private static final String UPSTREAM_REPO_URL = "http://hg.openjdk.java.net/openjfx/jfx-dev/rt";
    private static final String USER_HOME = System.getProperty("user.home");
    private static final Path UPSTREAM_REPO_PATH = Paths.get(USER_HOME, "jfxmirror", "upstream");
    // private static final String MIRROR_REPO_URL = "https://github.com/javafxports/openjdk-jfx";
    private static final String MIRROR_REPO_URL = "https://github.com/brcolow/openjdk-jfx"; // FIXME: For testing
    private static final Path MIRROR_REPO_PATH = Paths.get(USER_HOME, "jfxmirror", "mirror");
    private static final Logger logger = LoggerFactory.getLogger(Bot.class);

    private Bot() {}

    public static void main(String[] args) {
        if (System.getenv("JFXMIRROR_GH_TOKEN") == null) {
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

        if (!Files.exists(MIRROR_REPO_PATH)) {
            try {
                // Probably the first time running, clone the git mirror repository.
                logger.debug("Git mirror repository not found.");
                logger.debug("Cloning git mirror repository...");
                logger.debug("This may take a while (like 20 or more minutes) as the OpenJFX repository is large.");
                Git git = Git.cloneRepository()
                        .setURI(MIRROR_REPO_URL)
                        .setDirectory(MIRROR_REPO_PATH.toFile())
                        .call();
                logger.debug("Cloned git mirror repository to: " + MIRROR_REPO_PATH);
                git.close();
            } catch (GitAPIException e) {
                exitWithError("Could not clone javafxports git mirror repository.", e, 1);
            }
        }

        try {
            mirrorRepo = new FileRepositoryBuilder()
                    .setGitDir(MIRROR_REPO_PATH.resolve(".git").toFile())
                    .readEnvironment()
                    .build();
            logger.debug("Initialized git mirror repository: " + mirrorRepo.getDirectory());
        } catch (IOException e) {
            exitWithError("Could not initialize javafxports git mirror repository.", e, 1);
        }

        logger.debug("Checking for \"jcheck.py\"...");
        Path jcheckPath = Paths.get(USER_HOME, "jfxmirror", "jcheck.py");
        if (!Files.exists(jcheckPath)) {
            logger.debug("Downloading \"jcheck.py\"...");
            try (InputStream in = URI.create(JCHECK_URL).toURL().openStream()) {
                Files.copy(in, jcheckPath);
                logger.info("\u2713 Downloaded \"jcheck.py\" to: " + jcheckPath);
            } catch (IOException e) {
                exitWithError("Could not download \"jcheck.py\".", e, 1);
            }
        } else {
            logger.info("\u2713 Found \"jcheck.py\".");
        }

        RepositoryConfiguration repoConf = new RepositoryConfiguration();
        repoConf.addExtension(JCheckExtension.class);
        if (!Files.exists(UPSTREAM_REPO_PATH)) {
            // Probably the first time running, clone the upstream OpenJFX repository.
            logger.debug("Upstream mercurial repository not found.");
            try {
                Files.createDirectories(UPSTREAM_REPO_PATH);
                logger.info("\u2713 Created: " + UPSTREAM_REPO_PATH);
            } catch (IOException e) {
                exitWithError("Could not create mercurial repository directory: \"" + UPSTREAM_REPO_PATH + "\"", e, 1);
            }

            logger.debug("Cloning upstream OpenJFX mercurial repository...");
            logger.debug("This may take a while (like 20 or more minutes) as the OpenJFX repository is large.");
            upstreamRepo = Repository.clone(repoConf, UPSTREAM_REPO_PATH.toFile(), UPSTREAM_REPO_URL);

            // Add the necessary hg config for using jcheck and the strip extension.
            Path hgRcPath = upstreamRepo.getDirectory().toPath().resolve(".hg").resolve("hgrc");
            logger.debug("Adding config to \"" + hgRcPath + "\" for using jcheck...");
            try {
                // Use platform-specific line separator so that line endings are not mixed as we are appending to hgrc.
                Files.write(hgRcPath, (System.lineSeparator() + "[extensions]" + System.lineSeparator() + "jcheck = " +
                        jcheckPath + System.lineSeparator() + "strip =" + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            } catch (IOException e) {
                exitWithError("Could not write to: " + hgRcPath, e, 1);
            }
            logger.debug("Added config to: \"" + hgRcPath + "\".");
        } else {
            // Repository already exists.
            upstreamRepo = Repository.open(repoConf, UPSTREAM_REPO_PATH.toFile());
        }

        logger.debug("Initialized OpenJFX upstream repository: " + upstreamRepo.getDirectory());
        logger.debug("Using mercurial version: " + upstreamRepo.getHgVersion());

        Path jcheckDir = upstreamRepo.getDirectory().toPath().resolve(".jcheck");
        if (!Files.isDirectory(upstreamRepo.getDirectory().toPath().resolve(".jcheck"))) {
            try {
                Files.createDirectory(jcheckDir);
            } catch (IOException e) {
                exitWithError("Could not create \".jcheck\" directory at root of upstream hg repository.", e, 1);
            }
        }

        Path jcheckConfPath = upstreamRepo.getDirectory().toPath().resolve(".jcheck").resolve("conf");
        if (!Files.exists(jcheckConfPath)) {
            logger.debug("Downloading jcheck config file...");
            try (InputStream in = URI.create(JCHECK_CONF_URL).toURL().openStream()) {
                Files.copy(in, jcheckConfPath);
                logger.info("\u2713 Downloaded jcheck config file to: " + jcheckConfPath);
            } catch (IOException e) {
                exitWithError("Could not download jcheck config file.", e, 1);
            }
        } else {
            logger.info("\u2713 Found jcheck config file: " + jcheckConfPath);
        }

        logger.debug("Checking for \"webrev.ksh\"...");
        Path webrevPath = Paths.get(USER_HOME, "jfxmirror", "webrev");
        if (!Files.exists(webrevPath)) {
            try {
                Files.createDirectories(webrevPath);
            } catch (IOException e) {
                exitWithError("Could not create directory: \"" + webrevPath + "\"", e, 1);
            }
        }

        if (!Files.exists(webrevPath.resolve("webrev.ksh"))) {
            logger.debug("Downloading \"webrev.ksh\"...");
            try (InputStream in = URI.create(WEBREV_URL).toURL().openStream()) {
                Files.copy(in, webrevPath.resolve("webrev.ksh"));
                logger.info("\u2713 Downloaded \"webrev.ksh\" to: " + webrevPath.resolve("webrev.ksh"));
            } catch (IOException e) {
                exitWithError("Could not download \"webrev.ksh\".", e, 1);
            }
        } else {
            logger.info("\u2713 Found \"webrev.ksh\".");
        }

        logger.debug("Checking for OCA signature file...");
        java.nio.file.Path ocaFile = Paths.get(USER_HOME, "jfxmirror", "oca.txt");
        if (!Files.exists(ocaFile)) {
            logger.debug("Creating OCA signature file: " + ocaFile);
            try {
                Files.createFile(ocaFile);
            } catch (IOException e) {
                exitWithError("Could not create OCA signature file: \"" + ocaFile + "\"", e, 1);
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

    private static void exitWithError(String errorMessage, Exception exception, int exitCode) {
        logger.error("\u2718 " + errorMessage);
        logger.debug("exception: ", exception);
        System.exit(exitCode);
    }

    protected static void cleanup() {
        mirrorRepo.close();
        upstreamRepo.close();
        if (httpServer.isStarted()) {
            logger.debug("Stopping HTTP server...");
            httpServer.shutdownNow();
        }
    }
}
