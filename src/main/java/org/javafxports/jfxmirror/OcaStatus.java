package org.javafxports.jfxmirror;

/**
 * Enum representing the possible states that the OCA signature status of a user
 * who opened a PR can be in.
 */
public enum OcaStatus {
    FOUND_PENDING("Found GitHub username on OCA page, waiting for user confirmation."),
    NOT_FOUND_PENDING("Could not find GitHub username on OCA page, waiting for user response."),
    SIGNED("User has signed OCA.");

    private final String description;

    OcaStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
