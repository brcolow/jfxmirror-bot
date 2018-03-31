package org.javafxports.jfxmirror;

public class OcaComments {
    private OcaComments() {}

    public static String commentWhenFoundUsername(String ocaLine, String botUsername) {
        return "We attempted to determine if you have signed the Oracle Contributor Agreement (OCA) " +
                "and found a signatory with your GitHub username: `" + ocaLine + "`. **If that's you**, " +
                "add a comment on this PR saying: `@" + botUsername + " Yes, that's me`. **Otherwise, " +
                "if that's not you**:\n\n";
    }

    public static String commentWhenNotFoundUsername() {
        return "We attempted to determine if you have signed the Oracle Contributor Agreement (OCA) " +
                "but could not find a signature line with your GitHub username.\n\n";
    }

    public static String defaultComment(String botUsername) {
        return "**If you have already signed the OCA**:\n" +
                "Add a comment on this PR saying: `@" + botUsername + " I have signed the OCA under the name {name}` " +
                "where `{name}` is the first, name-like part of an OCA signature line. For example, the signature line " +
                "`John Smith - OpenJFX - jsmith` has a first, name-like part of `John Smith`.\n\n" +
                "**If you have never signed the OCA before:**\n" +
                "Follow the instructions at http://www.oracle.com/technetwork/community/oca-486395.html for " +
                "doing so. Make sure to fill out the username portion of the form with your GitHub username. " +
                "Once you have signed the OCA and your name has been added to the list of signatures, " +
                "add a comment on this PR saying: `@" + botUsername + " I have now signed the OCA using my GitHub username`.";
    }
}
