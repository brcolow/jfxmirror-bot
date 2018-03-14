package org.javafxports.jfxmirror;

public class OcaReplies {
    private OcaReplies() {}

    public static String replyWhenFoundName(String name) {
        return "Okay, thanks :thumbsup:. We have updated our records that you have signed the OCA " +
                "under the name `" + name + "`.";
    }

    public static String replyWhenNotFoundName(String name, String botUsername) {
        return "You said that you signed the OCA under your name `" + name + "`, but we weren't " +
                "able to find that name on the " +
                "[OCA signatures page](http://www.oracle.com/technetwork/community/oca-486395.html) " +
                ":flushed:. Make sure it is correct and try again with the correct name by adding a " +
                "comment to this PR of the form: `@" + botUsername + " I have signed the OCA under " +
                "the name {name}`.";
    }

    public static String replyWhenFoundUsername() {
        return "Okay, thanks :thumbsup:. We have updated our records that you have signed the OCA " +
                "using your GitHub username.";
    }

    public static String replyWhenNotFoundUsername(String username, String botUsername) {
        return "You said that you signed the OCA under your GitHub username `" + username + "`, but we " +
                "weren't able to find that username on the " +
                "[OCA signatures page](http://www.oracle.com/technetwork/community/oca-486395.html) " +
                ":flushed:. Make sure it is correct and try again with the correct name by adding a " +
                "comment to this PR of the form: `@" + botUsername + " I have now signed the OCA using " +
                "my GitHub username`.";
    }

    public static String replyWhenNotFoundNameInQuotes(String botUsername) {
        return "Sorry, we could not understand your response because we could not find a name in double " +
                "quotes. Please try again by adding a comment to this PR of the form: " +
                "`@" + botUsername + " I have signed the OCA under the name {name}`.";
    }

    public static String replyWhenUserConfirmsIdentity() {
        return "Okay, thanks. We won't ask again.";
    }

    public static String replyWhenCantUnderstandResponse() {
        return "Sorry, we could not understand your response :confused:.";
    }
}
