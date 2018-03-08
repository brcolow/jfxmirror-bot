# jfxmirror-bot

Helps contributors to the OpenJFX GitHub get their changes accepted into OpenJFX upstream.

## Running The Bot

Create a GitHub [personal access token](https://github.com/settings/tokens) for jfxmirror_bot. No scopes
need to be checked. Make sure that the environment variable `jfxmirror_gh_token` is set when running
jfxmirror_bot via `.\gradlew run`.

You can set the environment variable only for the current shell session via:

Windows (powershell): `$env:jfxmirror_gh_token="GH_ACCESS_TOKEN"; .\gradlew run`
*nix: `export jfxmirror_gh_token="GH_ACCESS_TOKEN" && .\gradlew run`

Or you can set the environment variable permanently (I recommend using Rapid Environment Editor on Windows, for *nix
variants they can be set in your shell's `rc` file (e.g. `.bashrc`, `.zshrc`, etc.).
