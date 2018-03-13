package org.javafxports.jfxmirror;

public class StatusPage {
    public static String getStatusPageHtml(String prNum, String prShaHead, OcaStatus ocaStatus) {
        return " <!DOCTYPE html>\n" +
                "<html>\n" +
                "  <head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>Status: PR #" + prNum + " (" + prShaHead + ")</title>\n" +
                "  </head>\n" +
                "  <body>\n" +
                "      <p>OCA: " + ocaStatus.getDescription() + "</p>" +
                "      <p>JBS Bug: </p>" +
                "      <p>Mercurial Patch: <a href=\"./patch/" + prNum + ".patch\">View</a></p>" +
                "      <p>Webrev: <a href=\"./webrev/\">View</a> | <a href=\"./webrev.zip\">Download</a></p>" +
                "      <p>jcheck: <a href=\"./jcheck.txt\">View</a></p>" +
                "  </body>\n" +
                "</html>\n";
    }
}
