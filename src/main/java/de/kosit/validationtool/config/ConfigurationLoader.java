package de.kosit.validationtool.config;

import static org.apache.commons.lang3.StringUtils.startsWith;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.xml.validation.Schema;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import de.kosit.validationtool.api.Check;
import de.kosit.validationtool.api.Configuration;
import de.kosit.validationtool.api.InputFactory;
import de.kosit.validationtool.api.ResolvingConfigurationStrategy;
import de.kosit.validationtool.impl.CollectingErrorEventHandler;
import de.kosit.validationtool.impl.ContentRepository;
import de.kosit.validationtool.impl.ConversionService;
import de.kosit.validationtool.impl.ResolvingMode;
import de.kosit.validationtool.impl.Scenario;
import de.kosit.validationtool.impl.model.Result;
import de.kosit.validationtool.impl.tasks.DocumentParseAction;
import de.kosit.validationtool.impl.xml.RelativeUriResolver;
import de.kosit.validationtool.model.reportInput.XMLSyntaxError;
import de.kosit.validationtool.model.scenarios.ResourceType;
import de.kosit.validationtool.model.scenarios.ScenarioType;
import de.kosit.validationtool.model.scenarios.Scenarios;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;

/**
 * Configuration class that loads neccessary {@link Check} configuration from an existing scenario.xml specification.
 * This is the recommended option when an official configuration exists as is the case with 'xrechnung'.
 * 
 * @author Andreas Penski
 */
@RequiredArgsConstructor
@Slf4j
public class ConfigurationLoader {

    private static final String SUPPORTED_MAJOR_VERSION = "1";

    private static final String SUPPORTED_MAJOR_VERSION_SCHEMA = "http://www.xoev.de/de/validator/framework/1/scenarios";

    /**
     * URL, die auf die scenerio.xml Datei zeigt.
     */
    @Getter(AccessLevel.PACKAGE)
    private final URI scenarioDefinition;

    /**
     * Root-Ordner mit den von den einzelnen Szenarien benötigten Dateien
     */
    private final URI scenarioRepository;

    protected ResolvingMode resolvingMode = ResolvingMode.STRICT_RELATIVE;

    protected ResolvingConfigurationStrategy resolvingConfigurationStrategy;

    protected final Map<String, Object> parameters = new HashMap<>();

    URI getScenarioRepository() {
        if (this.scenarioRepository == null) {
            log.info("Creating default scenario repository (alongside scenario definition)");
            return RelativeUriResolver.resolve(URI.create("."), this.scenarioDefinition);
        }
        return this.scenarioRepository;
    }

    private static void checkVersion(final URI scenarioDefinition, final Processor processor) {
        try {
            final Result<XdmNode, XMLSyntaxError> result = new DocumentParseAction(processor)
                    .parseDocument(InputFactory.read(scenarioDefinition.toURL()));
            if (result.isValid() && !isSupportedDocument(result.getObject())) {
                throw new IllegalStateException(String.format(
                        "Specified scenario configuration %s is not supported.%nThis version only supports definitions of '%s'",
                        scenarioDefinition, SUPPORTED_MAJOR_VERSION_SCHEMA));

            }
        } catch (final MalformedURLException e) {
            throw new IllegalStateException("Error reading definition file");
        }
    }

    private static XdmNode findRoot(final XdmNode doc) {
        for (final XdmNode node : doc.children()) {
            if (node.getNodeKind() == XdmNodeKind.ELEMENT) {
                return node;
            }
        }
        throw new IllegalArgumentException("Kein root element gefunden");
    }

    private static boolean isSupportedDocument(final XdmNode doc) {
        final XdmNode root = findRoot(doc);
        final String frameworkVersion = root.getAttributeValue(new QName("frameworkVersion"));
        return startsWith(frameworkVersion, SUPPORTED_MAJOR_VERSION)
                && root.getNodeName().getNamespaceURI().equals(SUPPORTED_MAJOR_VERSION_SCHEMA);
    }

    private static Scenario createFallback(final Scenarios scenarios, final ContentRepository repository) {
        final ResourceType noscenarioResource = scenarios.getNoScenarioReport().getResource();
        return new FallbackBuilder().source(noscenarioResource.getLocation()).name(noscenarioResource.getName()).build(repository)
                .getObject();

    }

    public Configuration build() {
        final ResolvingConfigurationStrategy resolving = getResolvingConfigurationStrategy();
        final Processor processor = resolving.createProcessor();
        final ContentRepository contentRepository = new ContentRepository(resolving, getScenarioRepository());

        final Scenarios def = loadScenarios(contentRepository.getScenarioSchema(), processor);
        final List<Scenario> scenarios = initializeScenarios(def, contentRepository);
        final Scenario fallbackScenario = createFallback(def, contentRepository);
        final DefaultConfiguration configuration = new DefaultConfiguration(scenarios, fallbackScenario);
        configuration.setAdditionalParameters(this.parameters);
        configuration.setAuthor(def.getAuthor());
        configuration.setDate(def.getDate().toString());
        configuration.setName(def.getName());
        configuration.setContentRepository(contentRepository);
        return (configuration);
    }

    private static List<Scenario> initializeScenarios(final Scenarios def, final ContentRepository contentRepository) {
        return def.getScenario().stream().map(s -> initialize(s, contentRepository)).collect(Collectors.toList());
    }

    private ResolvingConfigurationStrategy getResolvingConfigurationStrategy() {
        if (this.resolvingConfigurationStrategy != null) {
            log.info("Custom resolving strategy supplied. Please take care of xml security!");
            return this.resolvingConfigurationStrategy;
        }
        log.info("Using resolving strategy {}", this.resolvingMode);
        return this.resolvingMode.getStrategy();
    }

    private Scenarios loadScenarios(final Schema scenarioSchema, final Processor processor) {
        final ConversionService conversionService = new ConversionService();
        checkVersion(this.scenarioDefinition, processor);
        log.info("Loading scenarios from {}", this.scenarioDefinition);
        final CollectingErrorEventHandler handler = new CollectingErrorEventHandler();
        final Scenarios scenarios = conversionService.readXml(this.scenarioDefinition, Scenarios.class, scenarioSchema, handler);
        if (!handler.hasErrors()) {
            log.info("Loading scenario content from {}", this.getScenarioRepository());
        } else {
            throw new IllegalStateException(
                    String.format("Can not load scenarios from %s due to %s", getScenarioDefinition(), handler.getErrorDescription()));
        }
        return scenarios;

    }

    private static Scenario initialize(final ScenarioType def, final ContentRepository repository) {
        final Scenario s = new Scenario(def);
        s.setMatchExecutable(repository.createMatchExecutable(def));
        s.setSchema(repository.createSchema(def));
        s.setSchematronValidations(repository.createSchematronTransformations(def));
        s.setReportTransformation(repository.createReportTransformation(def));
        if (def.getAcceptMatch() != null) {
            s.setAcceptExecutable(repository.createAccepptExecutable(def));
        }
        return s;
    }

    public ConfigurationLoader setResolvingMode(final ResolvingMode mode) {
        this.resolvingMode = mode;
        return this;
    }

    public ConfigurationLoader addParameter(final String name, final Object value) {
        this.parameters.put(name, value);
        return this;
    }
}
