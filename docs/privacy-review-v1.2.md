# v1.2 publication privacy review

The publication review covered the source tree selected for the v1.2 commit, the twelve release JARs and accompanying release text, and the twelve pre-existing Git commits.

- Gitleaks 8.30.1 reported no findings in the candidate source snapshot or existing Git history.
- A separate extraction collected readable text from 5,104 unique packaged payloads, including class files, native helpers and nested JAR contents. Gitleaks reported no findings in those extracted strings.
- Targeted checks inspected 13,322 release-JAR entries and candidate source files for personal mailbox data, local Windows user paths, private keys, common provider tokens and complete live invitation patterns. No findings were recorded.
- Runtime profiles, local worlds, logs, account configuration, private launch helpers and certificate/key files are excluded from the source commit and release assets.
- All pre-existing commit authors and committers use GitHub no-reply email addresses. The v1.2 release commit also uses a GitHub no-reply identity.

These checks assess the files and commit data being published. They do not establish that the program is free of security vulnerabilities or audit externally hosted demonstration videos and attachments. Invitations copied during actual use remain private viewing credentials.

This release is appended to the existing history. Earlier commits, tags and release assets are retained.
