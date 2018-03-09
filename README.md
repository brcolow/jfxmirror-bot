# jfxmirror-bot

Helps contributors to the OpenJFX GitHub get their changes accepted into OpenJFX upstream.

## Setup

A webhook must be added to the `javafxports/openjdk-jfx` GitHub repository so that pull request events are sent (pushed)
to the bot. There is a simple GitHub guide about [creating webhooks](https://developer.github.com/webhooks/creating/).
For our use-case, the payload URL should be set to `http://${SERVER_URL}/pr` where `${SERVER_URL}` is the URL of the
server running this bot, the content type should be `application/json`, and the only trigger event that should be
checked is `Pull request`.

## Running The Bot

Create a GitHub [personal access token](https://github.com/settings/tokens) for jfxmirror_bot. No scopes
need to be checked. Make sure that the environment variable `jfxmirror_gh_token` is set when running
jfxmirror_bot via `.\gradlew run`.

You can set the environment variable only for the current shell session via:

Windows (powershell): `$env:jfxmirror_gh_token="GH_ACCESS_TOKEN"; .\gradlew run`
*nix: `export jfxmirror_gh_token="GH_ACCESS_TOKEN" && .\gradlew run`

Or you can set the environment variable permanently (I recommend using Rapid Environment Editor on Windows, for *nix
variants they can be set in your shell's `rc` file (e.g. `.bashrc`, `.zshrc`, etc.).
