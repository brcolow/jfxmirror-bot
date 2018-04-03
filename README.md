# jfxmirror-bot

Helps contributors to the [OpenJFX GitHub repository](https://github.com/javafxports/openjdk-jfx) get their pull
requests accepted into [OpenJFX upstream](http://hg.openjdk.java.net/openjfx/jfx/rt/).

## Setup

A webhook must be added to the `javafxports/openjdk-jfx` GitHub repository so that pull request events are sent (pushed)
to the bot. Follow the GitHub guide about [creating webhooks](https://developer.github.com/webhooks/creating/), making
the following substitutions:

* Payload URL: `http://${SERVER_URL}/ghevent` where `${SERVER_URL}` is the URL of the server running this bot
* Content Type: `application/json`
* Events: Select (only) `Pull request` and `Issue comments`

## Running The Bot

Create a GitHub [personal access token](https://github.com/settings/tokens) for jfxmirror_bot. No scopes
need to be checked. The `JFXMIRROR_GH_TOKEN` environment variable must be set to that access token to run jfxmirror-bot.

To set the environment variable for the current shell and run the bot you can use:

* Windows (powershell): `$env:jfxmirror_gh_token="GH_ACCESS_TOKEN"; .\gradlew run`
* Windows (cmd.exe):    `set JFXMIRROR_GH_TOKEN="GH_ACCESS_TOKEN" && .\gradlew run`
* *nix: `export jfxmirror_gh_token="GH_ACCESS_TOKEN" && ./gradlew run`

Or you can set the environment variable permanently. I recommend using Rapid Environment Editor on Windows. For *nix
variants, they can be set as usual in your shell's `rc` file (e.g. `.bashrc`, `.zshrc`, etc.). The bot can be run
most easily by using `gradlew run`. It should also be possible to create a fat/uber JAR and run it using `java -jar`
using something like the gradle plugin [shadow](https://github.com/johnrengelman/shadow).

## What The Bot Does

When a pull request is opened, edited, or re-opened on the `javafxports/openjdk-jfx` GitHub repository, the bot
receives a notification from GitHub's webhooks. It then creates a new, pending status check for that PR (similar to how
we have status checks for Travis CI and Appveyor) and then does the following:

* Generates a mercurial patch encompassing all of the changes in the PR.
* Imports that patch in to a local mercurial clone of the upstream OpenJFX repository, managed by this bot.
* Generates a webrev for the imported patch.
* Runs jcheck against the imported patch.
* Checks to see if the user who opened the PR has signed the OCA. The bot keeps a record of OCA signers. If we have
no record of them signing the OCA, the bot comments on the PR and helps them through the process.
* Checks to see if any JBS bugs are referenced by the PR (checking the branch name, title, and commit messages) and,
using the JIRA API, if those founds are indeed actual valid JBS bugs for JavaFX.

If the patch does not apply cleanly to upstream the status check is set to "failure". If the bot encounters an error
that is not the user who opened the PR's fault (such as file permission issues) the status is set to "error". Otherwise,
the status is set to "success". The status check provides a link to the status page that contains all of the generated
data listed above.
