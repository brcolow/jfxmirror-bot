# jfxmirror-bot

Helps contributors to the OpenJFX GitHub get their changes accepted into OpenJFX upstream.

## Setup

Follow the AWS Guide ["Dynamic GitHub Actions with AWS Lambda"](https://aws.amazon.com/blogs/compute/dynamic-github-actions-with-aws-lambda/)

After creating a Lambda function (and selecting Java 8 as the runtime), upload the zip file
`.\build\distributions\jfxmirror-bot.zip` that is built when running `.\gradlew build`.

When building, make sure `JAVA_HOME` is set to Java 8. If not, you must pass the `-Dorg.gradle.java.home`
argument to `.\gradlew`.

* Windows (cmd.exe): `-Dorg.gradle.java.home="%ProgramFiles%\Java\jdk1.8.0_152"`
* Windows (powershell): `.\gradlew build "-Dorg.gradle.java.home=$env:PROGRAMFILES\Java\jdk1.8.0_152"`
* *nix:

Make sure to enter "org.javafxports.jfxmirror.Handler" as the handler.
