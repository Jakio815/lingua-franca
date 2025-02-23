package org.lflang.generator.c;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.lflang.TargetConfig;
import org.lflang.TargetConfig.ClockSyncOptions;
import org.lflang.TargetProperty.Platform;
import org.lflang.TargetProperty.ClockSyncMode;
import org.lflang.TargetProperty.CoordinationType;
import org.lflang.generator.CodeBuilder;
import org.lflang.generator.GeneratorBase;
import org.lflang.util.StringUtil;

import static org.lflang.util.StringUtil.addDoubleQuotes;

/**
 * Generates code for preambles for the C and CCpp target.
 * This includes #include and #define directives at the top
 * of each generated ".c" file.
 *
 * @author Edward A. Lee
 * @author Marten Lohstroh
 * @author Mehrdad Niknami
 * @author Christian Menard
 * @author Matt Weber
 * @author Soroush Bateni
 * @author Alexander Schulz-Rosengarten
 * @author Hou Seng Wong
 * @author Peter Donovan
 */
public class CPreambleGenerator {
    /** Add necessary source files specific to the target language.  */
    public static String generateIncludeStatements(
        TargetConfig targetConfig,
        boolean cppMode,
        boolean isFederated
    ) {
        var tracing = targetConfig.tracing;
        CodeBuilder code = new CodeBuilder();
        if (cppMode) {
            code.pr("extern \"C\" {");
        }
        if (targetConfig.platformOptions.platform == Platform.ARDUINO) {
            CCoreFilesUtils.getArduinoTargetHeaders().forEach(
                it -> code.pr("#include " + StringUtil.addDoubleQuotes(it))
            );
        }
        CCoreFilesUtils.getCTargetHeader().forEach(
            it -> code.pr("#include " + StringUtil.addDoubleQuotes(it))
        );
        code.pr("#include \"core/reactor.h\"");
        code.pr("#include \"core/reactor_common.h\"");
        if (targetConfig.threading) {
            code.pr("#include \"core/threaded/scheduler.h\"");
        }
        if (isFederated) {
            code.pr("#include \"core/federated/federate.c\"");
        }
        if (tracing != null) {
            code.pr("#include \"core/trace.h\"");
        }
        code.pr("#include \"core/mixed_radix.h\"");
        code.pr("#include \"core/port.h\"");
        code.pr("int lf_reactor_c_main(int argc, const char* argv[]);");
        if (cppMode) {
            code.pr("}");
        }
        return code.toString();
    }

    public static String generateDefineDirectives(
        TargetConfig targetConfig,
        int numFederates,
        boolean isFederated,
        Path srcGenPath,
        boolean clockSyncIsOn,
        boolean hasModalReactors
    ) {
        int logLevel = targetConfig.logLevel.ordinal();
        var coordinationType = targetConfig.coordination;
        var advanceMessageInterval = targetConfig.coordinationOptions.advance_message_interval;
        var tracing = targetConfig.tracing;
        CodeBuilder code = new CodeBuilder();
        // TODO: Get rid of all of these
        code.pr("#define LOG_LEVEL " + logLevel);
        code.pr("#define TARGET_FILES_DIRECTORY " + addDoubleQuotes(srcGenPath.toString()));

        if (isFederated) {
            code.pr("#define NUMBER_OF_FEDERATES " + numFederates);
            code.pr(generateFederatedDefineDirective(coordinationType));
            if (advanceMessageInterval != null) {
                code.pr("#define ADVANCE_MESSAGE_INTERVAL " +
                    GeneratorBase.timeInTargetLanguage(advanceMessageInterval));
            }
        }
        if (tracing != null) {
            targetConfig.compileDefinitions.put("LF_TRACE", tracing.traceFileName);
        }
        if (clockSyncIsOn) {
            code.pr(generateClockSyncDefineDirective(
                targetConfig.clockSync,
                targetConfig.clockSyncOptions
            ));
        }
        if (targetConfig.threading) {
            targetConfig.compileDefinitions.put("LF_THREADED", "1");
        } else {
            targetConfig.compileDefinitions.put("LF_UNTHREADED", "1");
        }
        code.newLine();
        return code.toString();
    }

    /**
     * Returns the #define directive for the given coordination type.
     *
     * NOTE: Instead of checking #ifdef FEDERATED, we could
     *       use #if (NUMBER_OF_FEDERATES > 1).
     *       To Soroush Bateni, the former is more accurate.
     */
    private static String generateFederatedDefineDirective(CoordinationType coordinationType) {
        List<String> directives = new ArrayList<>();
        directives.add("#define FEDERATED");
        if (coordinationType == CoordinationType.CENTRALIZED) {
            directives.add("#define FEDERATED_CENTRALIZED");
        } else if (coordinationType == CoordinationType.DECENTRALIZED) {
            directives.add("#define FEDERATED_DECENTRALIZED");
        }
        return String.join("\n", directives);
    }

    /**
     * Initialize clock synchronization (if enabled) and its related options for a given federate.
     *
     * Clock synchronization can be enabled using the clock-sync target property.
     * @see <a href="https://github.com/icyphy/lingua-franca/wiki/Distributed-Execution#clock-synchronization">Documentation</a>
     */
    private static String generateClockSyncDefineDirective(
        ClockSyncMode mode,
        ClockSyncOptions options
    ) {
        List<String> code = new ArrayList<>(List.of(
            "#define _LF_CLOCK_SYNC_INITIAL",
            "#define _LF_CLOCK_SYNC_PERIOD_NS " + GeneratorBase.timeInTargetLanguage(options.period),
            "#define _LF_CLOCK_SYNC_EXCHANGES_PER_INTERVAL " + options.trials,
            "#define _LF_CLOCK_SYNC_ATTENUATION " + options.attenuation
        ));
        if (mode == ClockSyncMode.ON) {
            code.add("#define _LF_CLOCK_SYNC_ON");
            if (options.collectStats) {
                code.add("#define _LF_CLOCK_SYNC_COLLECT_STATS");
            }
        }
        return String.join("\n", code);
    }
}
