package fr.inria.diversify.dspot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.inria.diversify.automaticbuilder.AutomaticBuilder;
import fr.inria.diversify.automaticbuilder.AutomaticBuilderFactory;
import fr.inria.diversify.dspot.amplifier.*;
import fr.inria.diversify.dspot.selector.BranchCoverageTestSelector;
import fr.inria.diversify.dspot.selector.TestSelector;
import fr.inria.diversify.dspot.support.json.ClassTimeJSON;
import fr.inria.diversify.dspot.support.Counter;
import fr.inria.diversify.dspot.support.DSpotCompiler;
import fr.inria.diversify.dspot.support.json.ProjectTimeJSON;
import fr.inria.diversify.utils.AmplificationChecker;
import fr.inria.diversify.utils.AmplificationHelper;
import fr.inria.diversify.utils.DSpotUtils;
import fr.inria.diversify.utils.Initializer;
import fr.inria.diversify.utils.sosiefier.InputConfiguration;
import fr.inria.diversify.utils.sosiefier.InputProgram;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static fr.inria.diversify.utils.AmplificationHelper.PATH_SEPARATOR;

/**
 * User: Simon
 * Date: 08/06/15
 * Time: 17:36
 */
public class DSpot {

    private static final Logger LOGGER = LoggerFactory.getLogger(DSpot.class);

    private List<Amplifier> amplifiers;

    private int numberOfIterations;

    private TestSelector testSelector;

    public InputProgram inputProgram;

    private InputConfiguration inputConfiguration;

    private DSpotCompiler compiler;

    private ProjectTimeJSON projectTimeJSON;

    public DSpot(InputConfiguration inputConfiguration) throws Exception {
        this(inputConfiguration, 3, Collections.emptyList(), new BranchCoverageTestSelector(10));
    }

    public DSpot(InputConfiguration configuration, int numberOfIterations) throws Exception {
        this(configuration, numberOfIterations, Collections.emptyList());
    }

    public DSpot(InputConfiguration configuration, TestSelector testSelector) throws Exception {
        this(configuration, 3, Collections.emptyList(), testSelector);
    }

    public DSpot(InputConfiguration configuration, int iteration, TestSelector testSelector) throws Exception {
        this(configuration, iteration, Collections.emptyList(), testSelector);
    }

    public DSpot(InputConfiguration configuration, List<Amplifier> amplifiers) throws Exception {
        this(configuration, 3, amplifiers);
    }

    public DSpot(InputConfiguration inputConfiguration, int numberOfIterations, List<Amplifier> amplifiers) throws Exception {
        this(inputConfiguration, numberOfIterations, amplifiers, new BranchCoverageTestSelector(10));
    }

    public DSpot(InputConfiguration inputConfiguration, int numberOfIterations, List<Amplifier> amplifiers, TestSelector testSelector) throws Exception {

        Initializer.initialize(inputConfiguration, testSelector instanceof BranchCoverageTestSelector);
        this.inputConfiguration = inputConfiguration;
        this.inputProgram = inputConfiguration.getInputProgram();

        AutomaticBuilder builder = AutomaticBuilderFactory.getAutomaticBuilder(inputConfiguration);
        String dependencies = builder.buildClasspath(this.inputProgram.getProgramDir());

        if (inputConfiguration.getProperty("additionalClasspathElements") != null) {
            dependencies += PATH_SEPARATOR + inputConfiguration.getInputProgram().getProgramDir()
                    + inputConfiguration.getProperty("additionalClasspathElements");
        }

        this.compiler = DSpotCompiler.createDSpotCompiler(inputProgram, dependencies);
        this.inputProgram.setFactory(compiler.getLauncher().getFactory());
        this.amplifiers = new ArrayList<>(amplifiers);
        this.numberOfIterations = numberOfIterations;
        this.testSelector = testSelector;
        this.testSelector.init(this.inputConfiguration);

        final String[] splittedPath = inputProgram.getProgramDir().split("/");
        final File projectJsonFile = new File(this.inputConfiguration.getOutputDirectory() +
                "/" + splittedPath[splittedPath.length - 1] + ".json");
        if (projectJsonFile.exists()) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            this.projectTimeJSON = gson.fromJson(new FileReader(projectJsonFile), ProjectTimeJSON.class);
        } else {
            this.projectTimeJSON = new ProjectTimeJSON(splittedPath[splittedPath.length - 1]);
        }
    }

    public void addAmplifier(Amplifier amplifier) {
        this.amplifiers.add(amplifier);
    }

    public List<CtType> amplifyAllTests() throws InterruptedException, IOException, ClassNotFoundException {
        final List<CtType> amplifiedTest = inputProgram.getFactory().Class().getAll().stream()
                .filter(ctClass -> !ctClass.getModifiers().contains(ModifierKind.ABSTRACT))
                .filter(ctClass ->
                        ctClass.getMethods().stream()
                                .anyMatch(AmplificationChecker::isTest))
                .map(this::amplifyTest)
                .collect(Collectors.toList());
        writeTimeJson();
        return amplifiedTest;
    }

    private void writeTimeJson() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        final File file = new File(this.inputConfiguration.getOutputDirectory() +
                "/" + this.projectTimeJSON.projectName + ".json");
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(gson.toJson(this.projectTimeJSON));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<CtType> amplifyTest(String targetTestClasses) {
        if (!targetTestClasses.contains("\\")) {
            targetTestClasses = targetTestClasses.replaceAll("\\.", "\\\\\\.").replaceAll("\\*", ".*");
        }
        Pattern pattern = Pattern.compile(targetTestClasses);
        return this.compiler.getFactory().Class().getAll().stream()
                .filter(ctType -> pattern.matcher(ctType.getQualifiedName()).matches())
                .filter(ctClass ->
                        ctClass.getMethods().stream()
                                .anyMatch(AmplificationChecker::isTest))
                .filter(this.isExcluded)
                .map(this::amplifyTest)
                .collect(Collectors.toList());
    }

    private final Predicate<CtType> isExcluded = ctType ->
            this.inputConfiguration.getProperty("excludedClasses") == null ||
            Arrays.stream(this.inputConfiguration.getProperty("excludedClasses").split(","))
                    .map(Pattern::compile)
                    .map(pattern -> pattern.matcher(ctType.getQualifiedName()))
                    .noneMatch(Matcher::matches);


    public CtType amplifyTest(CtType test) {
        return this.amplifyTest(test, AmplificationHelper.getAllTest(test));
    }

    public CtType amplifyTest(String fullQualifiedName, List<String> methods) throws InterruptedException, IOException, ClassNotFoundException {
        CtType<Object> clone = this.compiler.getLauncher().getFactory().Type().get(fullQualifiedName).clone();
        clone.setParent(this.compiler.getLauncher().getFactory().Type().get(fullQualifiedName).getParent());
        return amplifyTest(clone, methods.stream()
                .map(methodName -> clone.getMethodsByName(methodName).get(0))
                .filter(AmplificationChecker::isTest)
                .collect(Collectors.toList()));
    }

    public CtType amplifyTest(CtType test, List<CtMethod<?>> methods) {
        try {
            Counter.reset();
            Amplification testAmplification = new Amplification(this.inputConfiguration, this.amplifiers, this.testSelector, this.compiler);
            final List<CtMethod<?>> filteredTestCases = this.filterTestCases(methods);
            long time = System.currentTimeMillis();
            testAmplification.amplification(test, filteredTestCases, numberOfIterations);
            final long elapsedTime = System.currentTimeMillis() - time;
            LOGGER.info("elapsedTime {}", elapsedTime);
            this.projectTimeJSON.add(new ClassTimeJSON(test.getQualifiedName(), elapsedTime));
            testSelector.report();
            final File outputDirectory = new File(inputConfiguration.getOutputDirectory());
            CtType<?> amplification = AmplificationHelper.createAmplifiedTest(testSelector.getAmplifiedTestCases(), test);
            LOGGER.info("Print {} with {} amplified test cases in {}", amplification.getSimpleName(),
                    testSelector.getAmplifiedTestCases().size(), this.inputConfiguration.getOutputDirectory());
            DSpotUtils.printAmplifiedTestClass(amplification, outputDirectory);
            FileUtils.cleanDirectory(compiler.getSourceOutputDirectory());
            FileUtils.cleanDirectory(compiler.getBinaryOutputDirectory());
            Initializer.compileTest(this.inputConfiguration);
            writeTimeJson();
            return amplification;
        } catch (IOException | InterruptedException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    protected List<CtMethod<?>> filterTestCases(List<CtMethod<?>> testMethods) {
        if (this.inputConfiguration.getProperty("excludedTestCases") == null) {
            return testMethods;
        } else {
            final List<String> excludedTestCases = Arrays.stream(this.inputConfiguration.getProperty("excludedTestCases").split(",")).collect(Collectors.toList());
            return testMethods.stream()
                    .filter(ctMethod ->
                            excludedTestCases.isEmpty() ||
                                    !excludedTestCases.contains(ctMethod.getSimpleName())
                    ).collect(Collectors.toList());
        }
    }

    public InputProgram getInputProgram() {
        return inputProgram;
    }
}