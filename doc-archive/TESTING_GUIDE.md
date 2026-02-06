# AgentService Testing & Publishing Guide

## Table of Contents
1. [Testing Your AgentService Implementation](#testing)
2. [Publishing to Maven Central](#publishing)
3. [Quick Start Testing](#quick-start)

---

## Testing

### 1. Unit Tests (No API Keys Required)

Run the existing test suite to verify your code doesn't break existing functionality:

```bash
mvn clean test
```

The library uses **Mockito** for mocking HTTP responses. All tests run without real API calls.

### 2. Integration Tests (Requires API Keys)

Create a test file with your real API credentials:

**src/test/resources/test.properties**
```properties
# For OpenAI
openai.api.key=sk-your-key-here

# For Azure (optional)
azure.api.key1=your-azure-key-1
azure.api.key2=your-azure-key-2
azure.base.url1=https://resource1.openai.azure.com/
azure.base.url2=https://resource2.openai.azure.com/
azure.api.version=2024-08-01-preview
```

**⚠️ Important**: Add `test.properties` to `.gitignore` to avoid committing secrets!

### 3. Manual Testing with AgentServiceDemo

The demo file (`src/demo/java/.../AgentServiceDemo.java`) has 9 scenarios:

**Scenarios 1-5**: Safe to run without API keys (just show configuration)
- ✅ Demo 1: OpenAI configuration
- ✅ Demo 2: Azure single instance
- ✅ Demo 3: Azure multi-instance
- ✅ Demo 4: Structured output configuration
- ✅ Demo 5: Startup loading pattern

**Scenarios 6-9**: Require API keys (currently commented out)
- 🔑 Demo 6: Create agent and run conversation
- 🔑 Demo 7: Structured output with WeatherResult
- 🔑 Demo 8: Batch operations with thread management
- 🔑 Demo 9: Image generation

**To test with real API:**

1. Uncomment demos 6-9 in `AgentServiceDemo.java`
2. Set your API key as environment variable:
   ```bash
   export OPENAI_API_KEY=sk-your-key-here
   ```
3. Run the demo:
   ```bash
   mvn exec:java -Dexec.mainClass="io.github.sashirestela.openai.demo.AgentServiceDemo"
   ```

### 4. Testing Checklist

Before publishing, verify:

- ✅ All unit tests pass: `mvn clean test`
- ✅ Code compiles without warnings: `mvn clean compile`
- ✅ Javadoc builds successfully: `mvn javadoc:javadoc`
- ✅ Manual test of at least one AgentService scenario
- ✅ Check code formatting: `mvn spotless:check`
- ✅ Apply code formatting: `mvn spotless:apply`

---

## Publishing to Maven Central

Your fork is already configured for Maven Central publishing! Here's how to release:

### Prerequisites

1. **Sonatype OSSRH Account**
   - Create account at https://issues.sonatype.org
   - File a ticket to claim your namespace: `io.github.Yann-Favin-Leveque`
   - Wait for approval (~2 business days)

2. **GPG Key for Signing**
   ```bash
   # Generate GPG key
   gpg --gen-key

   # List keys
   gpg --list-keys

   # Upload public key to keyserver
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   ```

3. **Maven Settings Configuration**

   Edit `~/.m2/settings.xml`:
   ```xml
   <settings>
     <servers>
       <server>
         <id>ossrh</id>
         <username>your-sonatype-username</username>
         <password>your-sonatype-password</password>
       </server>
     </servers>
     <profiles>
       <profile>
         <id>ossrh</id>
         <activation>
           <activeByDefault>true</activeByDefault>
         </activation>
         <properties>
           <gpg.executable>gpg</gpg.executable>
           <gpg.passphrase>your-gpg-passphrase</gpg.passphrase>
         </properties>
       </profile>
     </profiles>
   </settings>
   ```

### Publishing Steps

1. **Update Version Number**

   Edit `pom.xml` line 9:
   ```xml
   <version>3.17.1</version>  <!-- Increment from 3.17.0 -->
   ```

   **Version Numbering Strategy:**
   - **Patch** (3.17.0 → 3.17.1): Bug fixes, minor improvements
   - **Minor** (3.17.0 → 3.18.0): New features (like AgentService!)
   - **Major** (3.17.0 → 4.0.0): Breaking API changes

   **Recommendation**: Use `3.18.0` for your AgentService addition.

2. **Build and Verify**
   ```bash
   mvn clean verify
   ```

3. **Deploy to Staging**
   ```bash
   mvn clean deploy -P release
   ```

   This will:
   - Build the JAR
   - Generate sources JAR
   - Generate Javadoc JAR
   - Sign all artifacts with GPG
   - Upload to Sonatype staging repository

4. **Release from Staging**

   The `pom.xml` has `autoReleaseAfterClose=true`, so it will automatically:
   - Close the staging repository
   - Release to Maven Central
   - Sync to central (takes ~30 minutes to 2 hours)

   **To manually control release:**
   - Change `autoReleaseAfterClose` to `false` in pom.xml
   - Login to https://s01.oss.sonatype.org/
   - Navigate to "Staging Repositories"
   - Find your repository, close it, then release it

5. **Tag the Release in Git**
   ```bash
   git add pom.xml
   git commit -m "Release version 3.18.0 - Add AgentService"
   git tag -a v3.18.0 -m "Version 3.18.0 - AgentService support"
   git push origin main --tags
   ```

6. **Create GitHub Release**
   - Go to your fork on GitHub
   - Click "Releases" → "Create a new release"
   - Select tag `v3.18.0`
   - Add release notes (see template below)

### Release Notes Template

```markdown
## Version 3.18.0 - AgentService High-Level Orchestration

### New Features
- **AgentService**: High-level abstraction for OpenAI Assistants API
  - Simplified agent creation and management
  - Support for OpenAI and Azure OpenAI
  - Azure multi-instance load balancing
  - Structured output mapping with `AgentResult`
  - Rate limiting and retry logic with exponential backoff
  - Thread management and conversation handling
  - Image generation with automatic prompt sanitization
  - Agent definition loading from JSON files

### API Additions
- `Agent` - Agent configuration POJO
- `AgentDefinition` - JSON-serializable agent config
- `AgentResult` - Interface for typed agent responses
- `AgentServiceConfig` - Builder-based configuration with factory methods
  - `AgentServiceConfig.forOpenAI(apiKey)`
  - `AgentServiceConfig.forAzure(apiKey, baseUrl, apiVersion)`
  - `AgentServiceConfig.forAzureMultiInstance(apiKeys, baseUrls, apiVersion)`
- `AgentService` - Main orchestration service

### Documentation
- Comprehensive README section for AgentService
- 9 usage scenarios in AgentServiceDemo
- JavaDoc for all new classes

### Dependencies
- No new dependencies added (uses existing Bucket4j, Jackson, etc.)

### Breaking Changes
- None (fully backward compatible)

### Usage Example
```java
// OpenAI
var config = AgentServiceConfig.forOpenAI(apiKey)
    .agentResultClassPackage("com.example.results")
    .build();

// Azure Multi-Instance
var config = AgentServiceConfig.forAzureMultiInstance(
    List.of("key1", "key2"),
    List.of("url1", "url2"),
    "2024-08-01-preview"
).build();

var agentService = new AgentService(config);
```

**Full Changelog**: https://github.com/Yann-Favin-Leveque/simple-openai/compare/v3.17.0...v3.18.0
```

---

## Quick Start Testing

### Minimal Test (30 seconds)

```bash
# 1. Run unit tests
mvn clean test -Dtest=AgentServiceConfigTest

# 2. Verify compilation
mvn clean compile

# 3. Done!
```

### Full Test Suite (2-3 minutes)

```bash
# 1. Run all tests
mvn clean test

# 2. Check code coverage
mvn jacoco:report
# View: target/site/jacoco/index.html

# 3. Verify Javadoc
mvn javadoc:javadoc
# View: target/site/apidocs/index.html

# 4. Check code formatting
mvn spotless:check

# 5. Full package build
mvn clean package
```

### Real API Test (requires API key)

```bash
# Set API key
export OPENAI_API_KEY=sk-your-key-here

# Run demo (uncomment scenarios 6-9 first)
mvn exec:java -Dexec.mainClass="io.github.sashirestela.openai.demo.AgentServiceDemo"
```

---

## Troubleshooting

### Common Issues

**1. GPG Signing Fails**
```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-gpg-plugin
```
**Solution**: Ensure GPG passphrase is in `~/.m2/settings.xml` or run with `-Dgpg.passphrase=yourpassphrase`

**2. Sonatype Authentication Fails**
```
[ERROR] Failed to deploy artifacts: Could not transfer artifact
```
**Solution**: Verify credentials in `~/.m2/settings.xml` match your OSSRH account

**3. Tests Fail with API Errors**
```
java.util.concurrent.CompletionException: SimpleUncheckedException
```
**Solution**: Unit tests use mocks and shouldn't call real API. If integration tests fail, check API key and rate limits.

**4. Code Formatting Fails**
```
[ERROR] There are 5 files that are not formatted correctly
```
**Solution**: Run `mvn spotless:apply` to auto-format

---

## CI/CD Integration (Optional)

For automated testing on GitHub Actions, create `.github/workflows/maven.yml`:

```yaml
name: Java CI with Maven

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v3

    - name: Set up JDK 11
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'

    - name: Build with Maven
      run: mvn -B package --file pom.xml

    - name: Run tests
      run: mvn -B test
```

---

## Questions?

- **Maven Central Guide**: https://central.sonatype.org/publish/publish-guide/
- **GPG Signing**: https://central.sonatype.org/publish/requirements/gpg/
- **simple-openai Issues**: https://github.com/Yann-Favin-Leveque/simple-openai/issues
