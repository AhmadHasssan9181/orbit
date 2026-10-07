# Contributing to Orbit Desktop

Thank you for your interest in contributing to Orbit! Orbit is an open-source Matrix client designed for real-time collaboration, voice, video, and community spaces, built with Kotlin Multiplatform and Compose Multiplatform.

---

## Code of Conduct

This project adheres to the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

---

## Reporting Security Issues

Please **do not** open public GitHub issues for security vulnerabilities. Review our [Security Policy](SECURITY.md) for instructions on confidential disclosure.

---

## Development Environment

### Prerequisites
- **Operating System:** Windows 10/11 (x64)
- **JDK:** OpenJDK 17 or JDK 21 (Eclipse Temurin recommended)
- **Gradle:** Wrapper included (`gradlew.bat` / `./gradlew`)
- **Git**

---

## Building and Testing

### 1. Build the Application
```bash
./gradlew :app:jvmJar
```

### 2. Run the Desktop App
```bash
./gradlew :app:run
```

### 3. Run Unit Tests
Orbit maintains a strict test suite. All tests must pass prior to submitting PRs:
```bash
./gradlew :app:jvmTest
```

---

## Pull Request Guidelines

1. **Branch Naming**: Use descriptive branch names (`fix/call-disconnect-race`, `feat/room-filters`).
2. **Commit Messages**: Follow standard conventional commits format:
   - `fix(...)`: Bug fixes
   - `feat(...)`: New features
   - `docs(...)`: Documentation improvements
   - `test(...)`: Adding or updating test suites
   - `refactor(...)`: Code refactoring without behavior change
3. **No Committed Secrets or Transient Artifacts**:
   - Never commit API keys, personal access tokens, homeserver session tokens, or local test dumps.
   - Run the test suite before submitting: `./gradlew :app:jvmTest`.
4. **Documentation**:
   - Update `README.md` or related architectural documentation when adding user-facing features or modifying build steps.

---

## Questions & Discussions

For general questions and discussions regarding architecture or design proposals, feel free to open a [GitHub Discussion](https://github.com/AhmadHasssan9181/orbit/discussions) or join our Matrix room.
