# Contributing to CONTRIBUTING.md

First off, thanks for taking the time to contribute! ❤️

All types of contributions are encouraged and valued. See the [Table of Contents](#table-of-contents) for different ways to help and details about how this project handles them. Please make sure to read the relevant section before making your contribution. It will make it a lot easier for us maintainers and smooth out the experience for all involved. The community looks forward to your contributions. 🎉

> And if you like the project, but just don't have time to contribute, that's fine. There are other easy ways to support the project and show your appreciation, which we would also be very happy about:
> - Star the project
> - Tweet about it
> - Refer this project in your project's readme
> - Mention the project at local meetups and tell your friends/colleagues

---

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [I Have a Question](#i-have-a-question)
- [I Want to Contribute](#i-want-to-contribute)
  - [Need Ideas to Contribute?](#need-ideas-to-contribute)
  - [Your First Code Contribution](#your-first-code-contribution)
  - [Setup](#setup)
  - [Improving The Documentation](#improving-the-documentation)
  - [Reporting Bugs](#reporting-bugs)
  - [How Do I Submit a Good Bug Report?](#how-do-i-submit-a-good-bug-report)
  - [Suggesting Enhancements](#suggesting-enhancements)
    - [Before Submitting an Enhancement](#before-submitting-an-enhancement)
    - [How Do I Submit a Good Enhancement Suggestion?](#how-do-i-submit-a-good-enhancement-suggestion?)
  - [Working With Git](#working-with-git)
    - [Keeping Your Branch Up to Date](#keeping-your-branch-up-to-date)
- [Styleguide](#styleguide)
  - [Code Style](#code-style)
    - [General Principles](#general-principles)
    - [Immutability](#immutability)
    - [Type Inference](#type-inference)
    - [Null Safety](#null-safety)
    - [Function Style](#function-style)
    - [Private First](#private-first)
  - [Ktlint](#ktlint)
    - [Running Gradle commands](#running-gradle-commands)
    - [Recommended: pre-commit hook](#recommended-pre-commit-hook)
  - [Commit Messages](#commit-messages)
- [Join The Project Team](#join-the-project-team)
- [Attribution](#attribution)

---

## Code of Conduct

This project and everyone participating in it is governed by the
[Code of Conduct](/blob/develop/CODE_OF_CONDUCT.md).
By participating, you are expected to uphold this code. Please report unacceptable behavior.

## I Have a Question

Before you ask a question, it is best to search for existing [Issues](https://github.com/WenuLink/wenu-link-android/issues) that might help you. In case you have found a suitable issue and still need clarification, you can write your question in this issue. It is also advisable to search the internet for answers first.

If you then still feel the need to ask a question and need clarification, we recommend the following:

- Open an [Issue](https://github.com/WenuLink/wenu-link-android/issues/new).
- Provide as much context as you can about what you're running into.
- Provide project and platform versions (Android platform, AndroidStudio, Aircraft, etc), depending on what seems relevant.

We will then take care of the issue as soon as possible.

## I Want to Contribute

> ### Legal Notice
> When contributing to this project, you must agree that you have authored 100% of the content, that you have the necessary rights to the content and that the content you contribute may be provided under the project license.

### Need Ideas to Contribute?

Unless you have a good idea to implement, you can browse the [beginner issues](https://github.com/WenuLink/wenu-link-android/issues?utf8=%E2%9C%93&q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) and select a feature or bug fix that should be fairly easy to implement. Once you will become an experienced developer, you will be able to address more complex issues.

### Your First Code Contribution

To prepare your first code contribution is required to know about [git flow](https://www.atlassian.com/git/tutorials/comparing-workflows/gitflow-workflow) and [branches management](https://git-scm.com/book/en/v2/Git-Branching-Branch-Management).
This project follows a common git flow initialization setup with the following branches:

- `main` for stable releases
- `develop` for "next release" development
- `feature/*` for Feature branches
- `release/*` for Release branches
- `hotfix/*` for Hotfix branches
- `support/*` for Support branches
- no version tag prefix defined

In this regard, you can fork any branch that you are interested in contributing to. For example, if a new feature will be added, the fork must be done from the `develop` branch. If the idea is to contribute to Work In Progress (WIP) features, the fork must be done from the any `feature/*` branch. Current development stage is focused in core aspects of the application, this is why **test cases with other Aircraft and Android devices** is essential to ensure a wide compatibility range. After achieving a first stable version, the development must be focused on including the DJI SDK v5, aiming to increase the number of compatible Aircraft.

More information will be added.

### Setup

Before building the project, you need to provide a DJI developer API key.

1. Copy `local.properties.example` to `local.properties` in the project root (this file is git-ignored and must never be commited).
2. Register at https://developer.dji.com/ and create an app with the package name `org.WenuLink`.
3. Fill in the `dji.api.key` value in your `local.properties`.

Android Studio will automatically populate `sdk.dir` when you open the project. If building from command line, set in manually to your Android SDK path.

### Improving The Documentation

Current documentation is focused for initial users to have an starting point on how the application works.
Documentation writing for end-users and developers are welcome. The documentation roadmap is being discussed on [issue #5](https://github.com/WenuLink/wenu-link-android/issues/5) over a few defined use cases that guides the application development.

Developers documentation should focus on a more technical aspect relating architecture design and the patterns used for the current code development. The idea is to encourage good coding practices to keeps the project organized in terms of packages, classes, and methods names definition.

### Reporting Bugs

A good bug report shouldn't leave others needing to chase you up for more information. Therefore, we ask you to investigate carefully, collect information and describe the issue in detail in your report. Please complete the following steps in advance to help us fix any potential bug as fast as possible.

- Make sure that you are using the latest version.
- Determine if your bug is really a bug and not an error on your side e.g. using incompatible environment components/versions (Make sure that you have read the [Getting started](https://github.com/WenuLink/wenu-link-android/wiki/GettingStarted). If you are looking for support, you might want to check [this section](#i-have-a-question)).
- To see if other users have experienced (and potentially already solved) the same issue you are having, check if there is not already a bug report existing for your bug or error in the [bug tracker](https://github.com/WenuLink/wenu-link-android/issues?q=label%3Abug).
- Also make sure to search the internet (including Stack Overflow) to see if users outside of the GitHub community have discussed the issue.
- Collect information about the bug:
- Stack trace (Traceback)
- Android device information such as version and model.
- OS, Platform and Version for development (Windows, Linux, macOS, x86, ARM)
- Version of AndroidStudio or IDE, compiler, SDK, runtime environment, package manager, depending on what seems relevant.
- Possibly your logcat output
- Can you reliably reproduce the issue? And can you also reproduce it with older versions?

### How Do I Submit a Good Bug Report?

> You must never report security related issues, vulnerabilities or bugs including sensitive information to the issue tracker, or elsewhere in public.

We use GitHub issues to track bugs and errors. If you run into an issue with the project:

- Open an [Issue](https://github.com/WenuLink/wenu-link-android/issues/new). (Since we can't be sure at this point whether it is a bug or not, we ask you not to talk about a bug yet and not to label the issue.)
- Explain the behavior you would expect and the actual behavior.
- Please provide as much context as possible and describe the *reproduction steps* that someone else can follow to recreate the issue on their own. This usually includes your code. For good bug reports you should isolate the problem and create a reduced test case.
- Provide the information you collected in the previous section.

Once it's filed:

- The project team will label the issue accordingly.
- A team member will try to reproduce the issue with your provided steps. If there are no reproduction steps or no obvious way to reproduce the issue, the team will ask you for those steps and mark the issue as `needs-info`. Bugs with the `needs-info` tag will not be addressed until they are reproduced.
- If the team is able to reproduce the issue, it will be marked `needs-fix`, as well as possibly other tags (such as `critical`), and the issue will be left to be [implemented by someone](#your-first-code-contribution).

### Suggesting Enhancements

This section guides you through submitting an enhancement suggestion **including completely new features and minor improvements to existing functionality**. Following these guidelines will help maintainers and the community to understand your suggestion and find related suggestions.

#### Before Submitting an Enhancement

- Make sure that you are using the latest version.
- Perform a [search](https://github.com/WenuLink/wenu-link-android/issues) to see if the enhancement has already been suggested. If it has, add a comment to the existing issue instead of opening a new one.
- Find out whether your idea fits with the scope and aims of the project. It's up to you to make a strong case to convince the project's developers of the merits of this feature. Keep in mind that we want features that will be useful to the majority of our users and not just a small subset. If you're just targeting a minority of users, consider writing an add-on/plugin library.

#### How Do I Submit a Good Enhancement Suggestion?

Enhancement suggestions are tracked as [GitHub issues](https://github.com/WenuLink/wenu-link-android/issues).

- Use a **clear and descriptive title** for the issue to identify the suggestion.
- Provide a **step-by-step description of the suggested enhancement** in as many details as possible.
- **Describe the current behavior** and **explain which behavior you expected to see instead** and why. At this point you can also tell which alternatives do not work for you.
- You may want to **include screenshots and animated GIFs** which help you demonstrate the steps or point out the part which the suggestion is related to. You can use [this tool](https://www.cockos.com/licecap/) to record GIFs on macOS and Windows, and [this tool](https://github.com/colinkeenan/silentcast) or [this tool](https://github.com/GNOME/byzanz) on Linux.
- **Explain why this enhancement would be useful** to most WenuLink users. You may also want to point out the other projects that solved it better and which could serve as inspiration.

### Working With Git

#### Keeping Your Branch Up to Date

If your feature branch falls behind `develop` during review, prefer rebasing over merging:

```bash
git fetch upstream
git rebase upstream/develop
git push --force-with-lease
```
Rebasing keeps the commit history linear and ensures that a pull request clearly reflects only the changes introduced by the feature branch.

Merging `develop` into a feature branch can introduce additional commits into the history, which may make the review process less clear.

Force-pushing after a rebase is expected for feature branches. Prefer `--force-with-lease` over `--force` to avoid accidentally overwriting others’ work.

## Styleguide

The overall coding style is based on actual Kotlin/Android development specifications. Specific definitions need to be made for a more detailed style guide. For now the only requirement is to follow the package and visibility aspects.

In terms of package organization:

- `adapters` package contains everything related to the DJI SDK and MAVLink interfacing.
- `mavlink` package comprise all classes for MAVLink protocol support.
- `sdk` package comprise wrapping classes for DJI SDK v4 support.
- `views` package comprise all classes for interfacing logic-level and UI-level information.
- `webrtc` package comprise all classes for WebRTC protocol support.

Regarding class naming convention:

- `Service` suffix will be used with classes that handle specific communication protocol or long-time running operations.
- `Controller` suffix will be used with classes that address different [MAVLink's microservice](https://mavlink.io/en/services/) messaging interface.
- `Handler` suffix will be used with classes that deals with asynchronous operations.
- `ViewModel` suffix will be used with classes that performs logic-level operations and updates UI-level elements.
- `Manager` suffix will be used with classes that wraps SDK objects and methods for easy-to-use code implementation.

### Code Style

This project follows the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
and the [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide).

Code style is enforced via **ktlint**, additionally the following conventions are applied during code review to ensure consistency across the project.

#### General Principles

- Prefer readability and consistency over personal preference.
- Align with existing patterns in the surrounding code unless there is a clear reason to improve them.

#### Immutability

- Prefer `val` over `var` whenever possible.
- Use mutable state only when necessary and keep its scope minimal.

#### Type Inference

- Omit explicit types when they are obvious from the right-hand side.
- Keep type annotations where they improve readability or clarity.

#### Null Safety

- Prefer safe calls (`?.`) and scope functions (`let`, `also`, etc.) over non-null assertions (`!!`).
- Avoid patterns that obscure the difference between `null` and actual values.

#### Function Style

- Prefer expression bodies for simple functions.
- Use block bodies when logic becomes more complex or requires multiple steps.

#### Private First

- Prefer all classes, functions, properties, and nested types as private by default. Only elevate visibility when necessary.
- Hide internal state, helper functions, and implementation-specific classes behind private scopes and sealed types.

### Ktlint

Before submitting a PR, run:

    ./gradlew ktlintCheck

To auto-fix most formatting issues:

    ./gradlew ktlintFormat

The project's `.editorconfig` configures indentation and line length automatically in both Android Studio and VS Code, no manual IDE configuration needed.

#### Running Gradle commands

`./gradlew` commands must be run using the same JDK that Android Studio uses for building. The easiest way is to use Android Studio's built-in terminal, then set JAVA_HOME to point to Android Studio's bundled JDK.
The path varies by OS:

- Windows: `C:\Program Files\Android\Android Studio\jbr`
- macOS: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- Linux: `/opt/android-studio/jbr`

#### Recommended: Pre-Commit Hook

To have ktlint check your code automatically on every `git commit`, run this once after cloning the repository:

    ./gradlew addKtlintCheckGitPreCommitHook

This installs a local git hook that aborts the commit if any style violations are found. Run `./gradlew ktlintFormat` to fix them, then commit again. The hook is not committed to the repository, so each contributor who wants it must run this command once themselves.

Optionally, install the [ktlint Android Studio plugin](https://plugins.jetbrains.com/plugin/15057-ktlint)
for real-time violation highlighting in the editor.

### Commit Messages

A well-formatted commit message is essential for maintaining a clean project history and facilitating collaboration. Below are some practices that you should use in commit messages:

- Use Imperative Mood: Write your commit message as if you're giving an order (e.g., "Update README" instead of "Updated README").
- Be Descriptive: Clearly explain what the change is and why it was made, providing context for future developers.
- Keep It Concise and Relevant: Avoid unnecessary detail but provide enough context that someone else can easily understand the change.
- Reference Issues: If the commit addresses a specific issue, include that reference in the footer using phrases like "Fixes #issue-number" or "Related to #issue-number".

For the cases of additions or with a stack of changes made, the message should be formated as follows:
1. Title: A short summary of the changes.
   - Format: Use the imperative mood (e.g., "Add feature," "Fix bug").
   - Length: Keep it under 50 characters.
2. Body: A more detailed explanation of the changes, if necessary.
   - Format: Explain the "what" and "why" of the changes.
   - Length: Wrap at 72 characters per line for readability.
3. Footer: Include any relevant references or issue numbers.
   - Format: Use keywords to reference issues (e.g., "Fixes #123").

For the common case for a commit focused to an specific feature, hot fix, or bug, the message must be the Title and the Footer combined in a single sentence around 50 characters.

**Example Commit Message**
```
Add user authentication

Implemented user registration and login features. This refactor introduces JWT tokens for session management, improving security and user experience.

Fixes #42
```

## Join The Project Team

As the initial team, we are accepting new members that would commit in improving the development of the presented project. Authorization and roles must be requested by opening an [Issue](https://github.com/WenuLink/wenu-link-android/issues/new) with the subject: `Request for participation of role: <Role,Maintainer>. For developers, all that you need to do is to create a fork of the repository and Pull Request to the development branch.

## Attribution
This guide is based on the **contributing.md**. [Make your own](https://contributing.md/)!
