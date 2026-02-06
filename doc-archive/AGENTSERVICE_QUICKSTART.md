# AgentService Quick Start

## What You've Just Built

You've successfully integrated **AgentService** into your simple-openai fork! This provides high-level agent orchestration with:

- ✅ OpenAI and Azure OpenAI support
- ✅ Azure multi-instance load balancing
- ✅ Structured output mapping
- ✅ Rate limiting and retry logic
- ✅ Thread management
- ✅ Image generation
- ✅ Comprehensive unit tests (30 tests, all passing!)
- ✅ Complete documentation

---

## Quick Test (30 seconds)

```bash
# Run the new unit tests
export JAVA_HOME="/c/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.2/jbr"
"/c/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.2/plugins/maven/lib/maven3/bin/mvn.cmd" test -Dtest=AgentServiceConfigTest,AgentTest,AgentResultTest
```

**Expected output**: `Tests run: 30, Failures: 0, Errors: 0`

---

## Usage Example

```java
// 1. Create configuration
var config = AgentServiceConfig.forOpenAI(System.getenv("OPENAI_API_KEY"))
    .agentResultClassPackage("com.example.results")
    .requestsPerSecond(10)
    .build();

// 2. Initialize service
var agentService = new AgentService(config);

// 3. Create agent
var agent = Agent.builder()
    .id("101")
    .name("Customer Support Agent")
    .model("gpt-4o")
    .instructions("You are a helpful customer support agent.")
    .temperature(0.7)
    .build();

agentService.createAgent(agent, "user123");

// 4. Send message and get response
String response = agentService.sendMessageToAgentAndGetResponse(
    "101",
    "user123",
    "How can I reset my password?"
);

System.out.println(response);
```

---

## Testing Your Changes

### 1. Unit Tests (No API Keys)

```bash
# Run all tests
mvn clean test

# Run only AgentService tests
mvn test -Dtest=AgentServiceConfigTest,AgentTest,AgentResultTest
```

**What's tested:**
- ✅ Configuration validation
- ✅ Factory methods (forOpenAI, forAzure, forAzureMultiInstance)
- ✅ Agent POJO operations
- ✅ JSON mapping with structured outputs
- ✅ Error handling

### 2. Integration Tests (Requires API Key)

```bash
# Set your API key
export OPENAI_API_KEY=sk-your-key-here

# Run the demo
mvn exec:java -Dexec.mainClass="io.github.sashirestela.openai.demo.AgentServiceDemo"
```

---

## Publishing to Maven Central

### Option 1: Quick Publish (Automated)

1. **Update version in pom.xml:**
   ```xml
   <version>3.18.0</version>  <!-- Was 3.17.0 -->
   ```

2. **Deploy:**
   ```bash
   mvn clean deploy -P release
   ```

   This automatically:
   - ✅ Builds JAR, sources, javadoc
   - ✅ Signs with GPG
   - ✅ Uploads to Maven Central
   - ✅ Auto-releases (thanks to `autoReleaseAfterClose=true`)

3. **Tag and push:**
   ```bash
   git add .
   git commit -m "Release v3.18.0 - Add AgentService"
   git tag -a v3.18.0 -m "AgentService high-level orchestration"
   git push origin main --tags
   ```

### Option 2: Manual Control

1. Change `autoReleaseAfterClose` to `false` in pom.xml
2. Run `mvn clean deploy -P release`
3. Login to https://s01.oss.sonatype.org/
4. Manually close and release the staging repository

---

## What's New in Your Fork

### New Files

**Main Library:**
- `src/main/java/io/github/sashirestela/openai/agent/Agent.java`
- `src/main/java/io/github/sashirestela/openai/agent/AgentDefinition.java`
- `src/main/java/io/github/sashirestela/openai/agent/AgentResult.java`
- `src/main/java/io/github/sashirestela/openai/agent/AgentServiceConfig.java`

**Tests (30 passing tests):**
- `src/test/java/io/github/sashirestela/openai/agent/AgentServiceConfigTest.java` (15 tests)
- `src/test/java/io/github/sashirestela/openai/agent/AgentTest.java` (7 tests)
- `src/test/java/io/github/sashirestela/openai/agent/AgentResultTest.java` (8 tests)

**Demo:**
- `src/demo/java/.../AgentServiceDemo.java` (9 scenarios)

**Documentation:**
- `TESTING_GUIDE.md` - Comprehensive testing and publishing guide
- `AGENTSERVICE_QUICKSTART.md` - This file
- Updated `README.md` - Added AgentService section

### Modified Files

- `src/main/java/io/github/sashirestela/openai/agent/AgentService.java` - Updated to use new config

---

## Pre-Publishing Checklist

Before you publish to Maven Central, verify:

- [x] All unit tests pass: `mvn clean test`
- [x] Code compiles: `mvn clean compile`
- [ ] Update version in pom.xml (suggest 3.18.0)
- [ ] Update developer info in pom.xml if needed
- [ ] Set up GPG key for signing
- [ ] Configure ~/.m2/settings.xml with Sonatype credentials
- [ ] Create Sonatype OSSRH account
- [ ] Request namespace `io.github.Yann-Favin-Leveque` (if not done)
- [ ] Test with real API key (at least one demo scenario)
- [ ] Update CHANGELOG or create release notes

**See TESTING_GUIDE.md for detailed instructions on each step.**

---

## Recommended Version Numbering

**Current:** 3.17.0
**Suggested:** 3.18.0 (minor version bump for new feature)

**Why 3.18.0?**
- AgentService is a **new feature** (not just a bug fix)
- Fully **backward compatible** (no breaking changes)
- Follows semantic versioning: MAJOR.MINOR.PATCH

---

## Next Steps

1. **Test locally:**
   ```bash
   mvn clean test
   ```

2. **Test with real API (optional):**
   - Set `OPENAI_API_KEY` environment variable
   - Uncomment scenarios 6-9 in AgentServiceDemo.java
   - Run: `mvn exec:java -Dexec.mainClass="...AgentServiceDemo"`

3. **Publish to Maven Central:**
   - Follow steps in TESTING_GUIDE.md
   - Or use quick publish commands above

4. **Create GitHub Release:**
   - Tag: v3.18.0
   - Use release notes template from TESTING_GUIDE.md

---

## Support

- **Full Testing Guide:** See `TESTING_GUIDE.md`
- **API Documentation:** Run `mvn javadoc:javadoc` → view `target/site/apidocs/index.html`
- **Demo Examples:** See `src/demo/java/.../AgentServiceDemo.java`
- **Maven Central Docs:** https://central.sonatype.org/publish/publish-guide/

---

## Summary

**What works right now:**
- ✅ All code compiles successfully
- ✅ 30 unit tests pass (100% success rate)
- ✅ Factory methods for clean API
- ✅ Full JavaDoc documentation
- ✅ Demo with 9 usage scenarios
- ✅ README updated

**Ready to publish!** 🚀

Just update the version number, configure your credentials, and run `mvn clean deploy -P release`.
