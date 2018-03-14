# jfxmirror-bot

Helps contributors to the [OpenJFX GitHub repository](https://github.com/javafxports/openjdk-jfx) get their pull
requests accepted into OpenJFX upstream.

## Setup

A webhook must be added to the `javafxports/openjdk-jfx` GitHub repository so that pull request events are sent (pushed)
to the bot. There is a simple GitHub guide about [creating webhooks](https://developer.github.com/webhooks/creating/).
For our use-case, the payload URL should be set to `http://${SERVER_URL}/ghevent` where `${SERVER_URL}` is the URL of
the server running this bot, the content type should be `application/json`, and the only trigger events that should be
checked are `Pull request` and `Issue comments`.

## Running The Bot

Create a GitHub [personal access token](https://github.com/settings/tokens) for jfxmirror_bot. No scopes
need to be checked. Make sure that the environment variable `jfxmirror_gh_token` is set when running
jfxmirror_bot via `.\gradlew run`.

You can set the environment variable only for the current shell session via:

Windows (powershell): `$env:jfxmirror_gh_token="GH_ACCESS_TOKEN"; .\gradlew run`
*nix: `export jfxmirror_gh_token="GH_ACCESS_TOKEN" && .\gradlew run`

Or you can set the environment variable permanently (I recommend using Rapid Environment Editor on Windows, for *nix
variants they can be set in your shell's `rc` file (e.g. `.bashrc`, `.zshrc`, etc.).

## What The Bot Does

When a pull request is opened, edited, or re-opened to the `javafxports/openjdk-jfx` GitHub repository, the bot
receives a push notification from GitHub's webhooks. It creates a new, pending status check for that PR (similar to how
we have status checks for various CI services) and then does the following:

* Generates a mercurial patch for all of the changes in the PR.
* Imports that patch to a clone of the upstream OpenJFX repository, managed by this bot.
* Generates a webrev for the imported commit.
* Runs jcheck against the imported commit.
* Checks to see if the user who opened the PR has signed the OCA. The bot keeps a record of OCA signers. If we have
no record of them signing the OCA, the bot comments on the PR and helps them through the process.
* Checks to see if any JBS bugs are referenced by the PR (checking the branch name, title, and commit messages) and,
using the JIRA API, if they are actual open JBS bugs for JavaFX.

If anything went wrong, the status of the check is set to error or failed. If not, the status of the check is set to
success. The status check provides a link to the status page that contains all of the information listed above.
