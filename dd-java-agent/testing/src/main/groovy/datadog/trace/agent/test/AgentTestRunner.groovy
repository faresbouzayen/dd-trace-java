package datadog.trace.agent.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.util.ContextInitializer
import com.google.common.collect.Sets
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.checkpoints.TestEndpointCheckpointer
import datadog.trace.agent.test.datastreams.MockFeaturesDiscovery
import datadog.trace.agent.test.datastreams.RecordingDatastreamsPayloadWriter
import datadog.trace.agent.tooling.AgentInstaller
import datadog.trace.agent.tooling.Instrumenter
import datadog.trace.agent.tooling.TracerInstaller
import datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnores
import datadog.trace.api.Config
import datadog.trace.api.DDSpanId
import datadog.trace.api.IdGenerationStrategy
import datadog.trace.api.StatsDClient
import datadog.trace.api.WellKnownTags
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.time.SystemTimeSource
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.common.metrics.EventListener
import datadog.trace.common.metrics.Sink
import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.Writer
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.PendingTrace
import datadog.trace.bootstrap.instrumentation.api.DataStreamsMonitoring
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring
import datadog.trace.bootstrap.instrumentation.api.NoopDataStreamsMonitoring
import datadog.trace.test.util.DDSpecification
import de.thetaphi.forbiddenapis.SuppressForbidden
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.utility.JavaModule
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import org.spockframework.mock.MockUtil
import spock.lang.Shared

import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closePrevious
/**
 * A spock test runner which automatically applies instrumentation and exposes a global trace
 * writer.
 *
 * <p>To use, write a regular spock test, but extend this class instead of {@link
 * spock.lang.Specification}. <br>
 * This will cause the following to occur before test startup:
 *
 * <ul>
 *   <li>All {@link Instrumenter}s on the test classpath will be applied. Matching preloaded classes
 *       will be retransformed.
 *   <li>{@link AgentTestRunner#TEST_WRITER} will be registered with the global tracer and available
 *       in an initialized state.
 * </ul>
 */
// CodeNarc incorrectly thinks ".class" is unnecessary in @RunWith
@SuppressWarnings('UnnecessaryDotClass')
@RunWith(SpockRunner.class)
abstract class AgentTestRunner extends DDSpecification implements AgentBuilder.Listener {
  private static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10)

  protected static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.getInstrumentation()

  static {
    configureLoggingLevels()
  }

  /**
   * For test runs, agent's global tracer will report to this list writer.
   *
   * <p>Before the start of each test the reported traces will be reset.
   */
  @SuppressWarnings('PropertyName')
  @Shared
  ListWriter TEST_WRITER

  @SuppressWarnings('PropertyName')
  @Shared
  DDAgentWriter TEST_AGENT_WRITER

  @SuppressWarnings('PropertyName')
  @Shared
  Boolean USE_TEST_AGENT_WRITER

  @SuppressWarnings('PropertyName')
  @Shared
  TracerAPI TEST_TRACER

  @SuppressWarnings('PropertyName')
  @Shared
  StatsDClient STATS_D_CLIENT = Mock(StatsDClient)

  @SuppressWarnings('PropertyName')
  @Shared
  Set<String> TRANSFORMED_CLASSES_NAMES = Sets.newConcurrentHashSet()

  @SuppressWarnings('PropertyName')
  @Shared
  Set<TypeDescription> TRANSFORMED_CLASSES_TYPES = Sets.newConcurrentHashSet()

  @SuppressWarnings('PropertyName')
  @Shared
  AtomicInteger INSTRUMENTATION_ERROR_COUNT = new AtomicInteger(0)

  @SuppressWarnings('PropertyName')
  @Shared
  TestEndpointCheckpointer TEST_CHECKPOINTER = Spy(new TestEndpointCheckpointer())

  // don't use mocks because it will break too many exhaustive interaction-verifying tests
  @SuppressWarnings('PropertyName')
  @Shared
  TestProfilingContextIntegration TEST_PROFILING_CONTEXT_INTEGRATION = new TestProfilingContextIntegration()

  @SuppressWarnings('PropertyName')
  @Shared
  Set<DDSpanId> TEST_SPANS = Sets.newHashSet()

  @SuppressWarnings('PropertyName')
  @Shared
  RecordingDatastreamsPayloadWriter TEST_DATA_STREAMS_WRITER

  @SuppressWarnings('PropertyName')
  @Shared
  DataStreamsMonitoring TEST_DATA_STREAMS_MONITORING

  @Shared
  ClassFileTransformer activeTransformer

  @Shared
  boolean isLatestDepTest = Boolean.getBoolean('test.dd.latestDepTest')

  boolean originalAppSecRuntimeValue

  protected boolean isDataStreamsEnabled() {
    return false
  }

  private static void configureLoggingLevels() {
    def logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
    if (!(logger instanceof Logger)) {
      return
    }
    final Logger rootLogger = logger
    if (!rootLogger.iteratorForAppenders().hasNext()) {
      try {
        // previous test wiped out the logging config bring it back for the next test
        new ContextInitializer(rootLogger.getLoggerContext()).autoConfig()
      } catch (final Exception e) {
        e.printStackTrace()
      }
    }

    rootLogger.setLevel(Level.WARN)
    ((Logger) LoggerFactory.getLogger("datadog")).setLevel(Level.DEBUG)
    ((Logger) LoggerFactory.getLogger("org.testcontainers")).setLevel(Level.DEBUG)
  }

  @SuppressForbidden
  void setupSpec() {
    // If this fails, it's likely the result of another test loading Config before it can be
    // injected into the bootstrap classpath. If one test extends AgentTestRunner in a module, all tests must extend
    assert Config.getClassLoader() == null: "Config must load on the bootstrap classpath."

    configurePreAgent()

    TEST_DATA_STREAMS_WRITER = new RecordingDatastreamsPayloadWriter()
    DDAgentFeaturesDiscovery features = new MockFeaturesDiscovery(true)

    Sink sink = new Sink() {
        void accept(int messageCount, ByteBuffer buffer) {}

        void register(EventListener listener) {}
      }
    TEST_DATA_STREAMS_MONITORING = new NoopDataStreamsMonitoring()
    if (isDataStreamsEnabled()) {
      // Fast enough so tests don't take forever
      long bucketDuration = TimeUnit.MILLISECONDS.toNanos(50)
      WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "my-env", "service", "version", "language")
      TEST_DATA_STREAMS_MONITORING = new DefaultDataStreamsMonitoring(sink, features, SystemTimeSource.INSTANCE, wellKnownTags, TEST_DATA_STREAMS_WRITER, bucketDuration)
    }
    TEST_WRITER = new ListWriter()

    USE_TEST_AGENT_WRITER = System.getenv("CI_USE_TEST_AGENT").equals("true") && !(System.getProperty("CI_USE_TEST_AGENT").equals("false"))
    if (USE_TEST_AGENT_WRITER) {
      // emit traces to the APM Test-Agent for Cross-Tracer Testing Trace Checks
      TEST_AGENT_WRITER = DDAgentWriter.builder().build()
    }

    TEST_TRACER =
      Spy(
      CoreTracer.builder()
      .writer(TEST_WRITER)
      .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
      .statsDClient(STATS_D_CLIENT)
      .strictTraceWrites(useStrictTraceWrites())
      .dataStreamsMonitoring(TEST_DATA_STREAMS_MONITORING)
      .profilingContextIntegration(TEST_PROFILING_CONTEXT_INTEGRATION)
      .build())
    TEST_TRACER.registerCheckpointer(TEST_CHECKPOINTER)
    TracerInstaller.forceInstallGlobalTracer(TEST_TRACER)

    TEST_TRACER.startSpan(*_) >> {
      def agentSpan = callRealMethod()
      TEST_SPANS.add(agentSpan.spanId)
      agentSpan
    }

    assert ServiceLoader.load(Instrumenter, AgentTestRunner.getClassLoader())
    .iterator()
    .hasNext(): "No instrumentation found"
    activeTransformer = AgentInstaller.installBytebuddyAgent(
      INSTRUMENTATION, true, AgentInstaller.getEnabledSystems(), this)
  }

  /** Override to set config before the agent is installed */
  protected void configurePreAgent() {
    injectSysConfig(TracerConfig.SCOPE_ITERATION_KEEP_ALIVE, "1") // don't let iteration spans linger
  }

  void setup() {
    configureLoggingLevels()

    assertThreadsEachCleanup = false

    closePrevious(true) // in case the previous test left a lingering iteration span
    assert TEST_TRACER.activeSpan() == null: "Span is active before test has started: " + TEST_TRACER.activeSpan()

    // Config is reset before each test. Thus, configurePreAgent() has to be called before each test
    // even though the agent is already installed
    configurePreAgent()

    println "Starting test: ${getSpecificationContext().getCurrentIteration().getName()}"
    TEST_TRACER.flush()
    TEST_SPANS.clear()

    if (USE_TEST_AGENT_WRITER) {
      TEST_AGENT_WRITER.flush()
      try {
        TEST_AGENT_WRITER.start()
      } catch (IllegalStateException) {
        // do nothing
      }
    }

    TEST_WRITER.start()
    TEST_DATA_STREAMS_WRITER.clear()
    TEST_DATA_STREAMS_MONITORING.clear()

    def util = new MockUtil()
    util.attachMock(STATS_D_CLIENT, this)
    util.attachMock(TEST_CHECKPOINTER, this)

    originalAppSecRuntimeValue = ActiveSubsystems.APPSEC_ACTIVE
    ActiveSubsystems.APPSEC_ACTIVE = true
  }

  void cleanup() {
    if (USE_TEST_AGENT_WRITER) {
      // write ListWriter traces to the AgentWriter at cleanup so trace-processing changes occur after span assertions
      def array = TEST_WRITER.toArray()
      def traces = (Arrays.asList(array) as List<List<DDSpan>>)
      for (trace in traces) {
        TEST_AGENT_WRITER.write(trace)
      }
      TEST_AGENT_WRITER.flush()
    }
    TEST_TRACER.flush()

    def util = new MockUtil()
    util.detachMock(STATS_D_CLIENT)
    util.detachMock(TEST_CHECKPOINTER)

    ActiveSubsystems.APPSEC_ACTIVE = originalAppSecRuntimeValue
  }

  /** Override to clean up things after the agent is removed */
  protected void cleanupAfterAgent() {}

  void cleanupSpec() {
    TEST_TRACER?.close()
    TEST_AGENT_WRITER?.close()

    if (null != activeTransformer) {
      INSTRUMENTATION.removeTransformer(activeTransformer)
      activeTransformer = null
    }

    cleanupAfterAgent()

    // All cleanup should happen before these assertion.  If not, a failing assertion may prevent cleanup
    assert INSTRUMENTATION_ERROR_COUNT.get() == 0: INSTRUMENTATION_ERROR_COUNT.get() + " Instrumentation errors during test"

    assert TRANSFORMED_CLASSES_TYPES.findAll {
      GlobalIgnores.isAdditionallyIgnored(it.getActualName())
    }.isEmpty(): "Transformed classes match global libraries ignore matcher"
  }

  boolean useStrictTraceWrites() {
    return true
  }

  void assertTraces(
    final int size,
    @ClosureParams(
    value = SimpleType,
    options = "datadog.trace.agent.test.asserts.ListWriterAssert")
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {
    assertTraces(size, false, spec)
  }

  void assertTraces(
    final int size,
    final boolean ignoreAdditionalTraces,
    @ClosureParams(
    value = SimpleType,
    options = "datadog.trace.agent.test.asserts.ListWriterAssert")
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {
    ListWriterAssert.assertTraces(TEST_WRITER, size, ignoreAdditionalTraces, spec)
  }

  protected static final Comparator<List<DDSpan>> SORT_TRACES_BY_ID = ListWriterAssert.SORT_TRACES_BY_ID
  protected static final Comparator<List<DDSpan>> SORT_TRACES_BY_START = ListWriterAssert.SORT_TRACES_BY_START
  protected static final Comparator<List<DDSpan>> SORT_TRACES_BY_NAMES = ListWriterAssert.SORT_TRACES_BY_NAMES

  void assertTraces(
    final int size,
    final Comparator<List<DDSpan>> traceSorter,
    @ClosureParams(
    value = SimpleType,
    options = "datadog.trace.agent.test.asserts.ListWriterAssert")
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {
    ListWriterAssert.assertTraces(TEST_WRITER, size, false, traceSorter, spec)
  }

  void blockUntilChildSpansFinished(final int numberOfSpans) {
    final AgentSpan span = TEST_TRACER.activeSpan()

    blockUntilChildSpansFinished(span, numberOfSpans)
  }

  static void blockUntilChildSpansFinished(AgentSpan span, int numberOfSpans) {
    if (span instanceof DDSpan) {
      final PendingTrace pendingTrace = ((DDSpan) span).context().getTrace()

      final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS

      while (pendingTrace.size() < numberOfSpans) {
        if (System.currentTimeMillis() > deadline) {
          throw new TimeoutException(
          "Timed out waiting for child spans.  Received: " + pendingTrace.size())
        }
        Thread.sleep(10)
      }
    }
  }

  @Override
  void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded, DynamicType dynamicType) {
    TRANSFORMED_CLASSES_NAMES.add(typeDescription.getActualName())
    TRANSFORMED_CLASSES_TYPES.add(typeDescription)
  }

  @Override
  void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
    // Intentionally prevented instrumentation
    if (throwable instanceof AbortTransformationException) {
      return
    }

    // Incorrect* classes assert on incorrect api usage. Error expected.
    if (typeName.startsWith('context.FieldInjectionTestInstrumentation$Incorrect')
      && throwable.getMessage().startsWith("Incorrect Context Api Usage detected.")) {
      return
    }

    println "Unexpected instrumentation error when instrumenting ${typeName} on ${classLoader}"
    throwable.printStackTrace()
    INSTRUMENTATION_ERROR_COUNT.incrementAndGet()
  }

  @Override
  void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
  }

  @Override
  void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
  }

  @Override
  void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
  }
}

/** Used to signal that a transformation was intentionally aborted and is not an error. */
@SuppressFBWarnings("RANGE_ARRAY_INDEX")
class AbortTransformationException extends RuntimeException {
  AbortTransformationException(final String message) {
    super(message)
  }
}


