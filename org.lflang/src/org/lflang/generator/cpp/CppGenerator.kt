/* Generator for Cpp target. */

/*************
 * Copyright (c) 2019-2021, TU Dresden.

 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:

 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.

 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.

 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
 * THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ***************/

package org.lflang.generator.cpp

import org.eclipse.emf.ecore.resource.Resource
import org.lflang.ErrorReporter
import org.lflang.Target
import org.lflang.ast.AfterDelayTransformation
import org.lflang.generator.CodeMap
import org.lflang.generator.GeneratorBase
import org.lflang.generator.GeneratorResult
import org.lflang.generator.GeneratorUtils.canGenerate
import org.lflang.generator.IntegratedBuilder
import org.lflang.generator.LFGeneratorContext
import org.lflang.generator.LFGeneratorContext.Mode
import org.lflang.generator.TargetTypes
import org.lflang.isGeneric
import org.lflang.scoping.LFGlobalScopeProvider
import org.lflang.util.FileUtil
import java.nio.file.Files
import java.nio.file.Path

@Suppress("unused")
class CppGenerator(
    val cppFileConfig: CppFileConfig,
    errorReporter: ErrorReporter,
    private val scopeProvider: LFGlobalScopeProvider
) :
    GeneratorBase(cppFileConfig, errorReporter) {

    // keep a list of all source files we generate
    val cppSources = mutableListOf<Path>()
    val codeMaps = mutableMapOf<Path, CodeMap>()

    companion object {
        /** Path to the Cpp lib directory (relative to class path)  */
        const val libDir = "/lib/cpp"

        const val MINIMUM_CMAKE_VERSION = "3.5"

        const val CPP_VERSION = "20"
    }

    override fun doGenerate(resource: Resource, context: LFGeneratorContext) {
        // Register the after delay transformation to be applied by GeneratorBase.
        registerTransformation(AfterDelayTransformation(CppDelayBodyGenerator, CppTypes, resource))
        super.doGenerate(resource, context)

        if (!canGenerate(errorsOccurred(), mainDef, errorReporter, context)) return

        // create a platform specifi generator
        val platformGenerator: CppPlatformGenerator =
            if (targetConfig.ros2) CppRos2Generator(this) else CppStandaloneGenerator(this)

        // generate all core files
        generateFiles(platformGenerator.srcGenPath)

        // generate platform specific files
        platformGenerator.generatePlatformFiles()

        if (targetConfig.noCompile || errorsOccurred()) {
            println("Exiting before invoking target compiler.")
            context.finish(GeneratorResult.GENERATED_NO_EXECUTABLE.apply(codeMaps))
        } else if (context.mode == Mode.LSP_MEDIUM) {
            context.reportProgress(
                "Code generation complete. Validating generated code...", IntegratedBuilder.GENERATED_PERCENT_PROGRESS
            )

            if (platformGenerator.doCompile(context)) {
                CppValidator(cppFileConfig, errorReporter, codeMaps).doValidate(context)
                context.finish(GeneratorResult.GENERATED_NO_EXECUTABLE.apply(codeMaps))
            } else {
                context.unsuccessfulFinish()
            }
        } else {
            context.reportProgress(
                "Code generation complete. Compiling...", IntegratedBuilder.GENERATED_PERCENT_PROGRESS
            )
            if (platformGenerator.doCompile(context)) {
                context.finish(GeneratorResult.Status.COMPILED, fileConfig.name, fileConfig, codeMaps)
            } else {
                context.unsuccessfulFinish()
            }
        }
    }

    private fun fetchReactorCpp() {
        val version = targetConfig.runtimeVersion
        val libPath = fileConfig.srcGenBasePath.resolve("reactor-cpp-$version")
        // abort if the directory already exists
        if (Files.isDirectory(libPath)) {
            return
        }
        // clone the reactor-cpp repo and fetch the specified version
        Files.createDirectories(libPath)
        commandFactory.createCommand(
            "git",
            listOf("clone", "-n", "https://github.com/lf-lang/reactor-cpp.git", "reactor-cpp-$version"),
            fileConfig.srcGenBasePath
        ).run()
        commandFactory.createCommand("git", listOf("checkout", version), libPath).run()
    }

    private fun generateFiles(srcGenPath: Path) {
        // copy static library files over to the src-gen directory
        val genIncludeDir = srcGenPath.resolve("__include__")
        listOf("lfutil.hh", "time_parser.hh").forEach {
            FileUtil.copyFileFromClassPath("$libDir/$it", genIncludeDir.resolve(it), true)
        }
        FileUtil.copyFileFromClassPath(
            "$libDir/3rd-party/cxxopts.hpp",
            genIncludeDir.resolve("CLI").resolve("cxxopts.hpp"),
            true
        )

        // copy or download reactor-cpp
        if (targetConfig.externalRuntimePath == null) {
            if (targetConfig.runtimeVersion != null) {
                fetchReactorCpp()
            } else {
                FileUtil.copyDirectoryFromClassPath(
                    "$libDir/reactor-cpp",
                    fileConfig.srcGenBasePath.resolve("reactor-cpp-default"),
                    true
                )
            }
        }

        // generate header and source files for all reactors
        for (r in reactors) {
            val generator = CppReactorGenerator(r, cppFileConfig, errorReporter)
            val headerFile = cppFileConfig.getReactorHeaderPath(r)
            val sourceFile = if (r.isGeneric) cppFileConfig.getReactorHeaderImplPath(r) else cppFileConfig.getReactorSourcePath(r)
            val reactorCodeMap = CodeMap.fromGeneratedCode(generator.generateSource())
            if (!r.isGeneric)
                cppSources.add(sourceFile)
            codeMaps[srcGenPath.resolve(sourceFile)] = reactorCodeMap
            val headerCodeMap = CodeMap.fromGeneratedCode(generator.generateHeader())
            codeMaps[srcGenPath.resolve(headerFile)] = headerCodeMap

            FileUtil.writeToFile(headerCodeMap.generatedCode, srcGenPath.resolve(headerFile), true)
            FileUtil.writeToFile(reactorCodeMap.generatedCode, srcGenPath.resolve(sourceFile), true)
        }

        // generate file level preambles for all resources
        for (r in resources) {
            val generator = CppPreambleGenerator(r.eResource, cppFileConfig, scopeProvider)
            val sourceFile = cppFileConfig.getPreambleSourcePath(r.eResource)
            val headerFile = cppFileConfig.getPreambleHeaderPath(r.eResource)
            val preambleCodeMap = CodeMap.fromGeneratedCode(generator.generateSource())
            cppSources.add(sourceFile)
            codeMaps[srcGenPath.resolve(sourceFile)] = preambleCodeMap
            val headerCodeMap = CodeMap.fromGeneratedCode(generator.generateHeader())
            codeMaps[srcGenPath.resolve(headerFile)] = headerCodeMap

            FileUtil.writeToFile(headerCodeMap.generatedCode, srcGenPath.resolve(headerFile), true)
            FileUtil.writeToFile(preambleCodeMap.generatedCode, srcGenPath.resolve(sourceFile), true)
        }
    }

    override fun getTarget() = Target.CPP

    override fun getTargetTypes(): TargetTypes = CppTypes
}

