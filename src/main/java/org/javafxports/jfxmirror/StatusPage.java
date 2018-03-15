package org.javafxports.jfxmirror;

import java.util.Collection;
import java.util.stream.Collectors;

public class StatusPage {

    public static String getStatusPageHtml(String prNum, String prShaHead, OcaStatus ocaStatus,
                                           Collection<String> jbsBugsReferenced,
                                           Collection<String> jbsBugsReferencedButNotFound) {
        return " <!DOCTYPE html>\n" +
                "<html>\n" +
                "  <head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>Status: PR #" + prNum + " (" + prShaHead + ")</title>\n" +
                "  </head>\n" +
                "  <body>\n" +
                "      <p>OCA: " + ocaStatus.getDescription() + "</p>" +
                "      <p>JBS Bug(s): " + getJbsBugHtml(jbsBugsReferenced, jbsBugsReferencedButNotFound) + "</p>" +
                "      <p>Mercurial Patch: <a href=\"./patch/" + prNum + ".patch\">View</a></p>" +
                "      <p>Webrev: <a href=\"./webrev/\">View</a> | <a href=\"./webrev.zip\">Download</a></p>" +
                "      <p>jcheck: <a href=\"./jcheck.txt\">View</a></p>" +
                "  </body>\n" +
                "</html>\n";
    }

    private static String getJbsBugHtml(Collection<String> jbsBugsReferenced,
                                        Collection<String> jbsBugsReferencedButNotFound) {
        if (jbsBugsReferenced.isEmpty()) {
            return "No JBS bugs referenced in PR commit message, branch name, or title.";
        }

        StringBuilder jbsHtmlBuilder = new StringBuilder();
        jbsHtmlBuilder.append("JBS Bugs referenced by PR:<br><br>");
        jbsHtmlBuilder.append(jbsBugsReferenced.stream().map(jbsBug ->
                "<a href=\"https://bugs.openjdk.java.net/browse/" + jbsBug + "\">" + jbsBug + "</a>").collect(
                Collectors.joining("|")));

        if (!jbsBugsReferencedButNotFound.isEmpty()) {
            jbsHtmlBuilder.append("JBS Bugs referenced by PR that could not be found:<br><br>");
            jbsHtmlBuilder.append(jbsBugsReferencedButNotFound.stream().collect(Collectors.joining(",")));
            jbsHtmlBuilder.append("<br>Make sure for each of the above bugs that they are:<br>")
                    .append("<ul><li>For the \"javafx\" component.</li>")
                    .append("<li>Status is one of \"Open\", \"In Progress\", \"New\", \"Provisional\".</li>");
        }

        return jbsHtmlBuilder.toString();
    }
}
